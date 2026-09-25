package com.example.financetracker.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JsonConfiguration {

    /**
     * Money is BigDecimal (rule 3), and the API writes it as a JSON string, such as "-12.50", so that no client reads
     * it into a binary floating-point number. Requests send amounts as strings too; Jackson reads them exactly.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer decimalsAsStrings() {
        return builder -> builder.postConfigurer(mapper -> mapper.configOverride(BigDecimal.class)
                .setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
    }
}
