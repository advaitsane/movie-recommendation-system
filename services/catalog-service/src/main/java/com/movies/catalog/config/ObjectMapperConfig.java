package com.movies.catalog.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds a custom ObjectMapper so dates serialize as ISO-8601 and MongoDB's ObjectId
 * serializes as a hex string. Built by hand rather than via Boot's auto-configured
 * builder, so it also registers every other Module bean in the context explicitly —
 * including Spring Data's PageModule, needed for Page<MovieResponse> to serialize
 * correctly.
 */

@Configuration
public class ObjectMapperConfig {

    @Bean
    public ObjectMapper objectMapper(ObjectProvider<Module> jacksonModules) {
        ObjectMapper mapper =
                new ObjectMapper(new JsonFactory())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .registerModule(new JavaTimeModule());
        jacksonModules.orderedStream().forEach(mapper::registerModule);

        SimpleModule module = new SimpleModule();
        module.addSerializer(ObjectId.class, new ObjectIdSerializer());
        mapper.registerModule(module);
        return mapper;
    }
}
