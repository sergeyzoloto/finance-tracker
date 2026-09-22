package com.example.financetracker.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This app's client in the shared Keycloak realm "myapps", from the KEYCLOAK_* environment variables (docs/auth.md).
 *
 * @param issuerUrl the realm URL exactly as it appears in the tokens' {@code iss} claim
 * @param clientId the client ID; access tokens must name it in {@code aud}, and its client roles grant access
 * @param clientSecret the confidential client's secret, used for the code exchange and token refreshes
 */
@ConfigurationProperties("app.keycloak")
record KeycloakProperties(String issuerUrl, String clientId, String clientSecret) {
}
