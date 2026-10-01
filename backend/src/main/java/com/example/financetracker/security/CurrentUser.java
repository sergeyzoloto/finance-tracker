package com.example.financetracker.security;

/**
 * The user a request is made by. Controllers take it as a parameter, which {@link CurrentUserResolver} fills in, and
 * pass its id to services explicitly.
 *
 * @param id the Keycloak "sub" claim of the request's access token, which every row the user owns is keyed by
 *        (rule 11)
 * @param name the account's name, for display only, such as prefilling a name the user chooses (F5); may be null
 */
public record CurrentUser(String id, String name) {
}
