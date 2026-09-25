package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Accounts, categories, counterparties and settings. */
class ReferenceDataApiTests extends LedgerApiTest {

    private final String user = newUser();

    @Test
    void createsRenamesArchivesAndRestoresAnAccount() throws IOException {
        JsonNode created = body(post(user, "/api/accounts", """
                {"code": "BROKER", "name": " Broker ", "type": "ASSET", "defaultCurrency": "USD"}"""),
                HttpStatus.CREATED);
        String uri = "/api/accounts/" + created.get("id").asLong();

        assertThat(created.get("code").asText()).isEqualTo("BROKER");
        assertThat(created.get("name").asText()).isEqualTo("Broker");
        assertThat(created.get("type").asText()).isEqualTo("ASSET");
        assertThat(created.get("defaultCurrency").asText()).isEqualTo("USD");
        assertThat(created.get("requiresCounterparty").asBoolean()).isFalse();
        assertThat(created.get("system").asBoolean()).isFalse();
        assertThat(created.get("archived").asBoolean()).isFalse();
        assertThat(created.has("userId")).isFalse();

        JsonNode renamed = ok(patch(user, uri, """
                {"name": "Brokerage", "archived": true}"""));
        assertThat(renamed.get("name").asText()).isEqualTo("Brokerage");
        assertThat(renamed.get("archived").asBoolean()).isTrue();
        // A field left out stays; null clears the default currency.
        assertThat(renamed.get("defaultCurrency").asText()).isEqualTo("USD");
        JsonNode cleared = ok(patch(user, uri, """
                {"archived": false, "defaultCurrency": null}"""));
        assertThat(cleared.get("archived").asBoolean()).isFalse();
        assertThat(cleared.get("defaultCurrency").isNull()).isTrue();
        assertThat(cleared.get("name").asText()).isEqualTo("Brokerage");

        assertThat(find(ok(get(user, "/api/accounts")), "code", "BROKER")).isEqualTo(cleared);
    }

    @Test
    void systemAccountsCanNeitherBeRenamedNorArchived() throws IOException {
        JsonNode openingBalance = find(ok(get(user, "/api/accounts")), "code", "OPENING_BALANCE");
        String uri = "/api/accounts/" + openingBalance.get("id").asLong();
        assertThat(openingBalance.get("system").asBoolean()).isTrue();

        assertThat(body(patch(user, uri, """
                {"name": "Start"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("OPENING_BALANCE is a system account and can't be renamed.");
        assertThat(body(patch(user, uri, """
                {"archived": true}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("OPENING_BALANCE is a system account and can't be archived.");
        // Sending its own name back, or setting a default currency, is no rename.
        assertThat(ok(patch(user, uri, """
                {"name": "Opening balance", "archived": false, "defaultCurrency": "EUR"}""")).get("defaultCurrency")
                .asText()).isEqualTo("EUR");
        assertThat(ok(patch(user, "/api/accounts/" + accountId(user, "UNALLOCATED"), """
                {"name": "Free money"}""")).get("name").asText()).isEqualTo("Free money");
    }

    @Test
    void invalidFieldsAreListed() throws IOException {
        JsonNode problem = body(post(user, "/api/accounts", """
                {"code": "my account", "name": " ", "defaultCurrency": "EURO"}"""), HttpStatus.BAD_REQUEST);

        assertThat(problem.get("title").asText()).isEqualTo("Invalid request");
        assertThat(problem.get("errors").findValuesAsText("field"))
                .containsExactly("code", "defaultCurrency", "name", "type");
        assertThat(problem.get("errors").findValuesAsText("message")).containsExactly(
                "must be capital letters, digits and underscores, starting with a letter",
                "must be an ISO 4217 currency code, such as EUR", "must not be blank", "must not be null");
        assertThat(problem.get("detail").asText()).startsWith("Invalid request: code must be capital letters");

        assertThat(body(patch(user, "/api/accounts/" + accountId(user, "CASH"), """
                {"name": "", "defaultCurrency": "XYZ"}"""), HttpStatus.BAD_REQUEST).get("errors")
                .findValuesAsText("field")).containsExactly("defaultCurrency", "name");
    }

    @Test
    void codesAreUniquePerUser() throws IOException {
        assertThat(body(post(user, "/api/accounts", """
                {"code": "CASH", "name": "Wallet", "type": "ASSET"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("You have an account with the code CASH already.");
        assertThat(post(user, "/api/categories", """
                {"code": "GROCERIES", "name": "Food", "type": "EXPENSE"}""")).hasStatus(HttpStatus.CONFLICT);
        // Another user has codes of their own.
        assertThat(post(newUser(), "/api/accounts", """
                {"code": "WALLET", "name": "Wallet", "type": "ASSET"}""")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aCategoryCanBeRenamedAndArchivedButKeepsItsType() throws IOException {
        JsonNode created = body(post(user, "/api/categories", """
                {"code": "PETS", "name": "Pets", "type": "EXPENSE"}"""), HttpStatus.CREATED);
        String uri = "/api/categories/" + created.get("id").asLong();

        assertThat(body(patch(user, uri, """
                {"type": "INCOME"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("The category PETS is EXPENSE, and a category's type can't be changed.");
        JsonNode changed = ok(patch(user, uri, """
                {"name": "Cats", "archived": true, "type": "EXPENSE"}"""));

        assertThat(changed.get("name").asText()).isEqualTo("Cats");
        assertThat(changed.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(changed.get("archived").asBoolean()).isTrue();
        assertThat(find(ok(get(user, "/api/categories")), "code", "PETS")).isEqualTo(changed);
    }

    @Test
    void aCounterpartyKnowsTheCategoryOfItsLatestCategorizedEntry() throws IOException {
        long shop = newCounterparty(user, "Corner shop");
        long other = newCounterparty(user, "Other shop");
        long groceries = categoryId(user, "GROCERIES");
        long eatingOut = categoryId(user, "EATING_OUT");
        long cash = accountId(user, "CASH");

        assertThat(find(ok(get(user, "/api/counterparties")), "name", "Corner shop").get("lastCategoryId").isNull())
                .isTrue();
        newEntry(user, expense("2026-05-10", shop, groceries, cash));
        // Earlier by date, though written later.
        newEntry(user, expense("2026-05-01", shop, eatingOut, cash));
        // Later, but without a category.
        newEntry(user, """
                {"kind": "TRANSFER", "entryDate": "2026-05-20", "payeeId": %d, "fromAccountId": %d,
                 "toAccountId": %d, "currency": "EUR", "amount": "5"}""".formatted(shop, cash,
                accountId(user, "CURRENT_ACCOUNT")));
        newEntry(user, expense("2026-05-30", other, eatingOut, cash));

        JsonNode list = ok(get(user, "/api/counterparties"));
        assertThat(list.findValuesAsText("name")).containsExactly("Corner shop", "Other shop");
        assertThat(find(list, "name", "Corner shop").get("lastCategoryId").asLong()).isEqualTo(groceries);
        assertThat(find(list, "name", "Other shop").get("lastCategoryId").asLong()).isEqualTo(eatingOut);

        // On the same date, the later entry wins.
        newEntry(user, expense("2026-05-10", shop, eatingOut, cash));
        assertThat(ok(patch(user, "/api/counterparties/" + shop, "{}")).get("lastCategoryId").asLong())
                .isEqualTo(eatingOut);
    }

    @Test
    void aCounterpartyCanBeRenamedClassifiedAndArchived() throws IOException {
        long shop = newCounterparty(user, "Corner shop");
        String uri = "/api/counterparties/" + shop;

        JsonNode classified = ok(patch(user, uri, """
                {"name": "Corner Shop", "kind": "MERCHANT"}"""));
        assertThat(classified.get("name").asText()).isEqualTo("Corner Shop");
        assertThat(classified.get("kind").asText()).isEqualTo("MERCHANT");
        JsonNode archived = ok(patch(user, uri, """
                {"archived": true}"""));
        assertThat(archived.get("kind").asText()).isEqualTo("MERCHANT");
        assertThat(archived.get("archived").asBoolean()).isTrue();
        assertThat(ok(patch(user, uri, """
                {"kind": null}""")).get("kind").isNull()).isTrue();

        // Names are unique regardless of case.
        assertThat(body(post(user, "/api/counterparties", """
                {"name": "CORNER SHOP"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("You have a counterparty named CORNER SHOP already.");
        assertThat(body(patch(user, uri, """
                {"kind": "SHOP"}"""), HttpStatus.BAD_REQUEST).get("errors").get(0).get("message").asText())
                .isEqualTo("must be one of MERCHANT, PERSON, ORGANIZATION");
    }

    @Test
    void settingsStartWithEuroAndTheDefaultShareRatio() throws IOException {
        JsonNode settings = ok(get(user, "/api/settings"));

        assertThat(settings.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(settings.get("sharedAccountId").isNull()).isTrue();
        assertThat(settings.get("defaultShareRatio").asText()).isEqualTo("0.50");
    }

    @Test
    void settingsAreReplacedAndApplyToSharedExpenses() throws IOException {
        long reserve = accountId(user, "RESERVE");

        JsonNode settings = ok(put(user, "/api/settings", """
                {"baseCurrency": "USD", "sharedAccountId": %d, "defaultShareRatio": "0.25"}""".formatted(reserve)));
        assertThat(settings.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(settings.get("sharedAccountId").asLong()).isEqualTo(reserve);
        assertThat(settings.get("defaultShareRatio").asText()).isEqualTo("0.25");
        assertThat(ok(get(user, "/api/settings"))).isEqualTo(settings);

        JsonNode entry = newEntry(user, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-06-01", "accountId": %d, "currency": "EUR",
                 "total": "100", "categoryId": %d}""".formatted(accountId(user, "CASH"), categoryId(user, "GROCERIES")));
        assertThat(entry.get("postings").findValuesAsText("amount")).containsExactly("-100.00", "75.00", "25.00");
        assertThat(entry.get("postings").get(2).get("accountId").asLong()).isEqualTo(reserve);
    }

    @Test
    void invalidSettingsAreRefused() throws IOException {
        JsonNode invalid = body(put(user, "/api/settings", """
                {"baseCurrency": "EURO", "defaultShareRatio": "12.5"}"""), HttpStatus.BAD_REQUEST);
        assertThat(invalid.get("errors").findValuesAsText("field")).containsExactly("baseCurrency", "defaultShareRatio",
                "defaultShareRatio");

        long othersAccount = accountId(newUser(), "FAMILY_DEBT");
        JsonNode problem = body(put(user, "/api/settings", """
                {"baseCurrency": "EUR", "sharedAccountId": %d, "defaultShareRatio": "0.5"}""".formatted(othersAccount)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(problem.get("detail").asText()).isEqualTo("Account %d does not exist.".formatted(othersAccount));
    }

    private static String expense(String date, long payee, long category, long account) {
        return """
                {"kind": "EXPENSE", "entryDate": "%s", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "10", "categoryId": %d}""".formatted(date, payee, account, category);
    }
}
