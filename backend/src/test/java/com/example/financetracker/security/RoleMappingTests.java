package com.example.financetracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleMappingTests {

    private static final String CLIENT_ID = "finance-tracker";

    @Test
    void mapsThisClientsRoles() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of(CLIENT_ID, Map.of("roles", List.of("user", "admin")))));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void ignoresRealmRolesAndOtherClientsRoles() {
        // The realm role "user" is held by every myapps user; shop-api's admin is an admin of shop-api only.
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("user", "admin", "offline_access")),
                "resource_access", Map.of(
                        CLIENT_ID, Map.of("roles", List.of("user")),
                        "shop-api", Map.of("roles", List.of("admin", "user")))));

        assertThat(authorities(jwt)).containsExactly("ROLE_USER");
    }

    @Test
    void grantsNothingWithoutThisClientsRoles() {
        assertThat(authorities(jwt(Map.of("realm_access", Map.of("roles", List.of("user")))))).isEmpty();
        assertThat(authorities(jwt(Map.of("resource_access", Map.of("shop-api", Map.of("roles", List.of("admin"))))))).isEmpty();
        assertThat(authorities(jwt(Map.of("resource_access", Map.of(CLIENT_ID, Map.of()))))).isEmpty();
        assertThat(authorities(jwt(Map.of("resource_access", Map.of(CLIENT_ID, Map.of("roles", "user")))))).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("subject").claims(c -> c.putAll(claims)).build();
    }

    private static List<String> authorities(Jwt jwt) {
        return ClientRoles.authorities(jwt, CLIENT_ID).stream().map(GrantedAuthority::getAuthority).toList();
    }
}
