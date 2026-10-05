package com.movies.review.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two transaction managers over one {@link DataSource}, deliberately. {@code transactionManager}
 * (JPA, primary) shares its {@link DataSource} with {@code OutboxEventRepository}'s plain JDBC
 * insert so the outbox write is atomic with the {@code reviews} write. {@value
 * #OUTBOX_TRANSACTION_MANAGER} (plain JDBC) exists because {@code OutboxRowPublisher} needs real
 * {@code Propagation.NESTED} savepoints, which {@code JpaTransactionManager} cannot provide in
 * this Spring/Hibernate pairing (verified via bytecode, not assumed).
 */
@Configuration
public class TransactionConfig {

    public static final String OUTBOX_TRANSACTION_MANAGER = "outboxTransactionManager";

    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setDataSource(dataSource);
        return transactionManager;
    }

    @Bean(OUTBOX_TRANSACTION_MANAGER)
    public PlatformTransactionManager outboxTransactionManager(DataSource dataSource) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.setNestedTransactionAllowed(true);
        return transactionManager;
    }
}
