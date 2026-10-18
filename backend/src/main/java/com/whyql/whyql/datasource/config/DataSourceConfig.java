package com.whyql.whyql.datasource.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link DataSourceProperties} from whyql.datasource.*. */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {
}
