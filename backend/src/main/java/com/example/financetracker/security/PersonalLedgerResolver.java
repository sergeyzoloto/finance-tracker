package com.example.financetracker.security;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Fills in the {@link LedgerScope} parameter of controller methods with the request's user's personal ledger, which
 * {@link LedgerAccess} resolves by their membership (ADR 0003, topic C). The personal endpoints always mean that
 * ledger; family ledgers will come in by their id in the path, through {@link LedgerAccess#member}.
 */
@Component
@ConditionalOnWebApplication
class PersonalLedgerResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserResolver currentUser;
    private final LedgerAccess ledgers;

    PersonalLedgerResolver(CurrentUserResolver currentUser, LedgerAccess ledgers) {
        this.currentUser = currentUser;
        this.ledgers = ledgers;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == LedgerScope.class;
    }

    @Override
    public LedgerScope resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        // The user is provisioned first, and with them their personal ledger.
        return ledgers.personal(currentUser.currentUser().id());
    }
}
