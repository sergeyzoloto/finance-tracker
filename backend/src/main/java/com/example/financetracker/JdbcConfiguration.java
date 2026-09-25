package com.example.financetracker;

import java.util.List;

import com.example.financetracker.ledger.Json;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Spring Data JDBC with converters for column types it doesn't map on its own. Replaces Spring Boot's own
 * configuration, which backs off when this class exists; entities are found in this package and below.
 */
@Configuration(proxyBeanMethods = false)
class JdbcConfiguration extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(Json.ToPgObject.INSTANCE, Json.FromPgObject.INSTANCE);
    }
}
