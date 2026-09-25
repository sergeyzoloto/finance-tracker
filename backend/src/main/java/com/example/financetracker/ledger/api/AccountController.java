package com.example.financetracker.ledger.api;

import java.util.List;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.AccountChanges;
import com.example.financetracker.ledger.AccountService;
import com.example.financetracker.ledger.AccountView;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
class AccountController {

    /**
     * @param code the account's identifier, unique per user and fixed once created, such as SAVINGS_ACCOUNT
     * @param requiresCounterparty whether every posting to it needs a counterparty, and its balance is kept per
     *        counterparty (rule 8); fixed once created
     */
    record NewAccount(@NotNull @Size(max = 50) @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 100) String name, @NotNull AccountType type, @CurrencyCode String defaultCurrency,
            boolean requiresCounterparty) {
    }

    /**
     * Rename, archive or restore an account, or change its default currency; fields left out stay as they are. A class
     * rather than a record, because a default currency sent as null clears it, while one left out keeps it.
     */
    static final class AccountPatch {

        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK, message = "must not be blank")
        private String name;
        private Boolean archived;
        @CurrencyCode
        private String defaultCurrency;
        private boolean changesDefaultCurrency;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** True archives the account, false restores it. System accounts can't be archived. */
        public Boolean getArchived() {
            return archived;
        }

        public void setArchived(Boolean archived) {
            this.archived = archived;
        }

        /** Null for none. */
        public String getDefaultCurrency() {
            return defaultCurrency;
        }

        public void setDefaultCurrency(String defaultCurrency) {
            this.defaultCurrency = defaultCurrency;
            this.changesDefaultCurrency = true;
        }

        AccountChanges changes() {
            return new AccountChanges(name == null ? null : name.strip(), archived, changesDefaultCurrency,
                    defaultCurrency);
        }
    }

    /** A code: capital letters, digits and underscores, starting with a letter. */
    static final String CODE = "[A-Z][A-Z0-9_]*";
    static final String CODE_MESSAGE = "must be capital letters, digits and underscores, starting with a letter";
    static final String NOT_BLANK = "(?s).*\\S.*";

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** All the user's accounts, archived ones included, by code. */
    @GetMapping
    List<AccountView> list(CurrentUser user) {
        return accounts.list(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountView create(CurrentUser user, @Valid @RequestBody NewAccount account) {
        return accounts.create(user.id(), account.code(), account.name().strip(), account.type(),
                account.defaultCurrency(), account.requiresCounterparty());
    }

    /** Renaming or archiving a system account, such as OPENING_BALANCE, is refused with 409. */
    @PatchMapping("/{id}")
    AccountView update(CurrentUser user, @PathVariable long id, @Valid @RequestBody AccountPatch patch) {
        return accounts.update(user.id(), id, patch.changes());
    }
}
