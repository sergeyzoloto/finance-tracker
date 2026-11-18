package com.example.financetracker.ledger.history;

import java.util.Optional;

/**
 * The signed-in user of the current request, which the history module may not read from Spring Security itself
 * (nothing in {@code ledger} but {@code ledger.api} depends on it): the {@code security} package implements it.
 */
public interface ActorSource {

    /** The Keycloak sub of the user whose request is running, if there is one. */
    Optional<String> currentSub();
}
