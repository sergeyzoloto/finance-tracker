package com.example.financetracker.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The roles of this app's client in an access token, as {@code ROLE_<ROLE>}. Part of {@link SecurityConfig}.
 * <p>
 * Unlike the auth server's reference SecurityConfig, realm roles are left out: the realm role "user" is held by every
 * user of every myapps project, so mapping it would let them all in. Other clients' roles are ignored too.
 */
final class ClientRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String clientId;

    ClientRoles(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return authorities(jwt, clientId);
    }

    static Set<GrantedAuthority> authorities(Jwt jwt, String clientId) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(clientId) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
