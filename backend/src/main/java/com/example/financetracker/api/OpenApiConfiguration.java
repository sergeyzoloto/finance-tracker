package com.example.financetracker.api;

import java.math.BigDecimal;
import java.util.Set;

import com.example.financetracker.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI description that springdoc serves at {@code /api/openapi} (application.yml). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig()
                // As JsonConfiguration writes them.
                .replaceWithSchema(BigDecimal.class,
                        new StringSchema().types(Set.of("string")).format("decimal").example("-12.50"))
                // Filled in from the access token, not from the request.
                .addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    OpenAPI financeTrackerApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Finance Tracker API")
                        .version("1")
                        .description("""
                                The double-entry ledger of docs/adr/0001-double-entry-ledger.md. Every object \
                                belongs to the signed-in user; another user's object answers 404.

                                Amounts are decimal strings, such as "-12.50": debit positive, credit negative. \
                                Dates are ISO dates, such as "2026-09-25".

                                Errors are RFC 7807 problem details. 400 lists the invalid fields or parameters in \
                                "errors"; 422 lists the ledger rules an entry or setting breaks in "violations"; 409 \
                                is a stale version or a change that the object's state rules out."""))
                .components(new Components()
                        .addSecuritySchemes("session", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("The browser's session after signing in at "
                                        + "/oauth2/authorization/keycloak. Writes also need the XSRF-TOKEN cookie's "
                                        + "value in the X-XSRF-TOKEN header."))
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("An access token of the Keycloak realm myapps for the client "
                                        + "finance-tracker, with that client's role \"user\".")))
                .addSecurityItem(new SecurityRequirement().addList("session"))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /**
     * Describes request and response bodies as the app's own ObjectMapper reads and writes them, mix-ins included:
     * the subtypes of an entry command come from one. For OpenAPI 3.1, springdoc's default version.
     */
    @Bean
    ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper).openapi31(true);
    }
}
