package com.srbmaury.blastradius.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
public class DatabaseConfig {

    @Bean
    @Primary
    public DataSource customerDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password
    ) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setPoolName("customer-evidence-pool");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5000);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    @Primary
    public JdbcTemplate customerJdbcTemplate(
            @Qualifier("customerDataSource") DataSource dataSource
    ) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DataSource metadataDataSource(
            @Value("${metadata.datasource.url}") String url,
            @Value("${metadata.datasource.username}") String username,
            @Value("${metadata.datasource.password}") String password,
            @Value("${metadata.datasource.driver-class-name}") String driverClassName
    ) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setPoolName("product-metadata-pool");
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5000);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    public JdbcTemplate metadataJdbcTemplate(
            @Qualifier("metadataDataSource") DataSource dataSource
    ) {
        return new JdbcTemplate(dataSource);
    }
}
