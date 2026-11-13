package com.example.financetracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** D-619 (UX-15): the account console's address, from the issuer and the client id. */
class AccountUrlTests {

    @Test
    void theConsoleOfTheIssuersRealmWithThisClientAsReferrer() {
        assertThat(MeController.accountUrl("https://auth.finance-nl.com/realms/myapps", "finance-tracker"))
                .isEqualTo("https://auth.finance-nl.com/realms/myapps/account?referrer=finance-tracker");
        assertThat(MeController.accountUrl("http://localhost:8080/realms/myapps/", "finance-tracker"))
                .isEqualTo("http://localhost:8080/realms/myapps/account?referrer=finance-tracker");
    }

    @Test
    void theClientIdIsEncoded() {
        assertThat(MeController.accountUrl("https://auth.example/realms/r", "an app&x=1/é"))
                .isEqualTo("https://auth.example/realms/r/account?referrer=an+app%26x%3D1%2F%C3%A9");
    }
}
