package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.StarterLedger.Seed;
import com.example.financetracker.ledger.StarterLedger.SeedAccount;
import com.example.financetracker.ledger.StarterLedger.SeedCategory;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The seed in {@code seed/starter-ledger.json} against the ledger's rules. */
class StarterLedgerTests {

    private final Seed seed = new StarterLedger(null, new ObjectMapper()).seed();
    private final Map<String, SeedAccount> accounts = seed.accounts().stream()
            .collect(Collectors.toMap(SeedAccount::code, Function.identity()));

    @Test
    void hasEveryAccountThatCommandsPostToOnTheirOwn() {
        assertThat(accounts).containsKeys(Arrays.stream(AccountRole.values()).map(AccountRole::defaultCode)
                .toArray(String[]::new));
    }

    @Test
    void followsTheRulesForAccounts() {
        assertThat(accounts).hasSameSizeAs(seed.accounts());
        // Rule 4.
        assertThat(seed.accounts()).filteredOn(SeedAccount::system).extracting(SeedAccount::code)
                .containsExactlyInAnyOrder("OPENING_BALANCE", "FX_EXCHANGE");
        assertThat(List.of("UNALLOCATED", "RESERVE", "OPENING_BALANCE", "FX_EXCHANGE"))
                .allMatch(code -> accounts.get(code).type() == AccountType.EQUITY);
        assertThat(accounts.get("FAMILY_DEBT").type()).isEqualTo(AccountType.LIABILITY);
        // Rule 8.
        assertThat(seed.accounts()).filteredOn(SeedAccount::requiresCounterparty).extracting(SeedAccount::code)
                .containsExactlyInAnyOrder("LOANS_ASSET", "CREDITOR_DEBT");
        assertThat(seed.accounts()).allMatch(account -> account.name().length() <= 100 && account.code().length() <= 50);
    }

    @Test
    void hasCategoriesOfBothTypes() {
        assertThat(seed.categories()).extracting(SeedCategory::code).doesNotHaveDuplicates();
        assertThat(seed.categories()).extracting(SeedCategory::type)
                .contains(CategoryType.INCOME, CategoryType.EXPENSE)
                .doesNotContainNull();
    }
}
