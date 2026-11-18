package com.example.financetracker.security;

import java.util.Optional;

import com.example.financetracker.ledger.history.ActorSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in user of the running request, for the history's versions (ADR 0006): the token's subject. */
@Component
class SecurityActorSource implements ActorSource {

    @Override
    public Optional<String> currentSub() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getName())) {
            return Optional.empty();
        }
        return Optional.of(authentication.getName());
    }
}
