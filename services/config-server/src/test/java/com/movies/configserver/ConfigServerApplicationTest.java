package com.movies.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-verifies config-server actually serves the seed config-repo content over its REST API —
 * not just that the app context starts. No client service consumes this yet (see config-repo's
 * own README and docs/adr/0009); this proves the server side of that future integration works.
 *
 * <p>The server normally clones this project's GitHub repo. Here it clones a throwaway local git
 * repo instead, holding this checkout's config-repo/ files at the same search path, so the test
 * runs offline and exercises the real git backend against the files on the branch under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@DisplayName("config-server Integration Test")
class ConfigServerApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void serveFromLocalGitRepo(DynamicPropertyRegistry registry) throws Exception {
        Path repo = Files.createTempDirectory("config-repo-test");
        Path target = Files.createDirectories(repo.resolve("services/config-server/config-repo"));
        // Maven runs tests with services/config-server as the working directory.
        try (Stream<Path> files = Files.list(Path.of("config-repo"))) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("seed config-repo").setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com").setSign(false).call();
        }
        registry.add("spring.cloud.config.server.git.uri", () -> repo.toUri().toString());
    }

    @Test
    @DisplayName("serves catalog-service's own config merged with shared application.yml defaults")
    void servesCatalogServiceConfigMergedWithSharedDefaults() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/catalog-service/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> merged = flattenPropertySources(response.getBody());

        // From catalog-service.yml
        assertThat(merged.get("server.port")).isEqualTo(8081);
        assertThat(merged.get("spring.mongodb.database")).isEqualTo("sample_mflix");
        // From the shared application.yml, proving profile-wide defaults are merged in too
        assertThat(merged.get("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus,info");
    }

    @Test
    @DisplayName("serves a different database name for search-service than catalog-service")
    void servesDistinctConfigPerApplication() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/search-service/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> merged = flattenPropertySources(response.getBody());

        assertThat(merged.get("server.port")).isEqualTo(8082);
        assertThat(merged.get("spring.mongodb.database")).isEqualTo("sample_mflix_search");
    }

    @Test
    @DisplayName("falls back to only the shared application.yml defaults for an application with no dedicated config file")
    void fallsBackToSharedDefaultsForUnknownApplication() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/no-such-service/default", String.class);

        // Spring Cloud Config always resolves at least the shared application.yml source, even
        // for an application name with no dedicated file of its own — it does not 404.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> merged = flattenPropertySources(response.getBody());

        assertThat(merged.get("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus,info");
        assertThat(merged).doesNotContainKey("spring.mongodb.database");
    }

    /** Flattens every propertySource's "source" map into one, later sources losing to earlier
     * ones — mirrors Spring Cloud Config's own precedence (most-specific file first). */
    private Map<String, Object> flattenPropertySources(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        Map<String, Object> merged = new LinkedHashMap<>();

        // propertySources are ordered most-specific-first; iterate in reverse so earlier
        // (higher-precedence) entries overwrite later ones in the merged map.
        java.util.List<JsonNode> ordered = new java.util.ArrayList<>();
        root.get("propertySources").iterator().forEachRemaining(ordered::add);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            JsonNode source = ordered.get(i).get("source");
            for (Map.Entry<String, JsonNode> entry : source.properties()) {
                merged.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
            }
        }
        return merged;
    }
}
