package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * POST /api/import over the synthetic workbook of ImportServiceTests, in {@code src/test/resources/import/}. Its row
 * 10 names an account that the accounts file doesn't have; ImportServiceTests covers the import itself.
 */
class ImportApiTests extends LedgerApiTest {

    private static final String ROW_10_ERROR = "Credit 'Карта «Несуществующая»' is not in the accounts file";

    private final String user = newUser();

    @Test
    void aDryRunIsTheDefaultAndSavesNothing() throws IOException {
        JsonNode report = ok(upload(fixture("transactions.csv"), null));

        assertThat(report.get("outcome").asText()).isEqualTo("DRY_RUN");
        assertThat(report.get("commit").asBoolean()).isFalse();
        assertThat(report.get("userId").asText()).isEqualTo(user);
        assertThat(report.get("files").findValuesAsText("name"))
                .containsExactly("accounts.csv", "categories.csv", "transactions.csv");
        assertThat(report.get("entriesByKind").toString()).isEqualTo("""
                {"EXPENSE":8,"INCOME":4,"TRANSFER":5,"SHARED_EXPENSE":2,"LOAN_GIVEN":2,"LOAN_REPAID":2,\
                "CURRENCY_EXCHANGE":1}""");
        assertThat(report.get("errors")).hasSize(1);
        assertThat(report.get("errors").get(0).get("file").asText()).isEqualTo("transactions.csv");
        assertThat(report.get("errors").get(0).get("row").asInt()).isEqualTo(10);
        assertThat(report.get("errors").get(0).get("message").asText()).isEqualTo(ROW_10_ERROR);
        JsonNode cashInRubles = StreamSupport.stream(report.get("balances").spliterator(), false)
                .filter(balance -> balance.get("accountCode").asText().equals("CASH"))
                .filter(balance -> balance.get("currency").asText().equals("RUB"))
                .findFirst()
                .orElseThrow();
        assertThat(cashInRubles.get("balance").isTextual()).isTrue();
        assertThat(cashInRubles.get("balance").asText()).isEqualTo("-3349.00");

        assertThat(ok(get(user, "/api/entries")).get("totalElements").asLong()).isZero();
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code")).doesNotContain("NORTH_CARD");
    }

    @Test
    void aCommitWithARowErrorSavesNothing() throws IOException {
        JsonNode report = ok(upload(fixture("transactions.csv"), "false"));

        assertThat(report.get("outcome").asText()).isEqualTo("ABORTED");
        assertThat(report.get("errors").findValuesAsText("message")).containsExactly(ROW_10_ERROR);
        assertThat(ok(get(user, "/api/entries")).get("totalElements").asLong()).isZero();
    }

    @Test
    void aCommitWithoutErrorsSavesTheLedgerOnce() throws IOException {
        byte[] transactions = withoutRow(fixture("transactions.csv"), 10);

        JsonNode committed = ok(upload(transactions, "false"));
        JsonNode again = ok(upload(transactions, "false"));

        assertThat(committed.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("errors")).isEmpty();
        assertThat(ok(get(user, "/api/entries")).get("totalElements").asLong()).isEqualTo(24);
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code")).contains("NORTH_CARD");
        // The starter ledger's accounts of the same code take the workbook's names.
        assertThat(find(ok(get(user, "/api/accounts")), "code", "CASH").get("name").asText()).isEqualTo("Кошелёк");
        assertThat(again.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(again.get("entriesByKind")).isEmpty();
        assertThat(ok(get(user, "/api/entries")).get("totalElements").asLong()).isEqualTo(24);
    }

    @Test
    void accountsCategoriesAndTransactionsAreRequired() throws IOException {
        MvcTestResult result = mvc.post().uri("/api/import").multipart()
                .file(new MockMultipartFile("transactions", "transactions.csv", "text/csv", fixture("transactions.csv")))
                .with(member(user))
                .exchange();

        assertThat(body(result, HttpStatus.BAD_REQUEST).get("detail").asText()).contains("'accounts'");
    }

    /** @param dryRun the parameter's value, or null to leave it out */
    private MvcTestResult upload(byte[] transactions, String dryRun) throws IOException {
        var request = mvc.post().uri("/api/import").multipart()
                .file(new MockMultipartFile("accounts", "accounts.csv", "text/csv", fixture("accounts.csv")))
                .file(new MockMultipartFile("categories", "categories.csv", "text/csv", fixture("categories.csv")))
                .file(new MockMultipartFile("transactions", "transactions.csv", "text/csv", transactions))
                .with(member(user));
        if (dryRun != null) {
            request.param("dryRun", dryRun);
        }
        return request.exchange();
    }

    private static byte[] fixture(String name) throws IOException {
        return new ClassPathResource("import/" + name).getContentAsByteArray();
    }

    /** The file without one spreadsheet row; the header is row 1. No field of the fixture spans lines. */
    private static byte[] withoutRow(byte[] csv, int row) {
        List<String> lines = new ArrayList<>(List.of(new String(csv, StandardCharsets.UTF_8).split("\r\n")));
        lines.remove(row - 1);
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
