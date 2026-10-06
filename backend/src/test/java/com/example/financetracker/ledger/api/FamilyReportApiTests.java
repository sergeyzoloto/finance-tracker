package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The family report (E1, F6c; ADR 0003 topic J, "F6c plan"): a worked example with numbers, in euros, with an income,
 * a settlement, a deleted record and a member who left; its bounds; who may read it; and E3's
 * check against the balances and the members' personal cash flow ({@link #checkFamilyReport}).
 */
class FamilyReportApiTests extends FamilyApiTest {

    /**
     * Mum, Dad and Kid in September and October, then Dad leaves:
     * <ul>
     * <li>09-05, groceries 90.00 paid by Mum, equal shares: 30.00 each;
     * <li>09-12, rent of 50.00 paid by Dad, half each for Mum and Dad: 25.00;
     * <li>09-15, groceries 40.00 paid by Mum, deleted: not in the report;
     * <li>09-20, salary 300.00 received by Mum, equal shares: 100.00 each;
     * <li>09-25, Dad pays Mum 20.00;
     * <li>10-01, groceries 10.01 paid by Kid, equal shares: 3.33, 3.33 and Kid, the payer, 3.35 (D-12).
     * </ul>
     * The nets: Mum 58.33 − 90.00 − 100.00 + 300.00 + 20.00 = 188.33; Dad 58.33 − 50.00 − 100.00 − 20.00 = −111.67; Kid
     * 33.35 − 10.01 − 100.00 = −76.66; together zero, and each one the member's balance.
     */
    @Test
    void theWorkedExample() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, "\"paymentLater\": true,")));
        created(post(bob, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "50.00", "payerMemberId": %d, "paymentLater": true, "split": {"method": "PERCENT", "shares": [
                   {"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000},
                   {"memberId": %d, "basisPoints": 0}]}}""".formatted(rent, dad, mum, dad, kid)));
        JsonNode deleted = created(post(alice, uri + "/records",
                expense("2026-09-15", groceries, "40.00", mum, "\"paymentLater\": true,")));
        assertThat(delete(alice, uri + "/records/" + deleted.get("id").asLong() + "?version="
                + deleted.get("version").asInt())).hasStatus(HttpStatus.NO_CONTENT);
        created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-20", "categoryId": %d, "amount": "300.00", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(salary, mum)));
        created(post(bob, uri + "/settlements", """
                {"date": "2026-09-25", "amount": "20.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(dad, mum)));
        created(post(alice, uri + "/records", expense("2026-10-01", groceries, "10.01", kid, "")));
        // Before Dad leaves, E3's check for both members with an account, from their join date.
        checkFamilyReport(alice, family, Map.of(alice, LocalDate.of(2026, 9, 1), bob, LocalDate.of(2026, 9, 1)));

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode report = checkFamilyReport(alice, family, Map.of(alice, LocalDate.of(2026, 9, 1)));
        assertThat(report.get("byCurrency").findValuesAsText("currency")).containsExactly("EUR");
        assertThat(report.get("total").get("rates")).isEmpty();
        assertThat(report.get("from").isNull()).isTrue();
        assertThat(members(report)).containsExactly("Mum ACTIVE account you", "Dad LEFT account", "Kid ACTIVE");
        assertThat(rows(report)).containsExactly(
                "2026-09 EXPENSE Groceries 90.00: Mum 30.00/90.00, Dad 30.00/0.00, Kid 30.00/0.00",
                "2026-09 EXPENSE Rent 50.00: Mum 25.00/0.00, Dad 25.00/50.00",
                "2026-09 INCOME Salary 300.00: Mum 100.00/300.00, Dad 100.00/0.00, Kid 100.00/0.00",
                "2026-10 EXPENSE Groceries 10.01: Mum 3.33/0.00, Dad 3.33/0.00, Kid 3.35/10.01");
        assertThat(totals(report)).containsExactly(
                "Mum 58.33 90.00 100.00 300.00 0.00 20.00 = 188.33",
                "Dad 58.33 50.00 100.00 0.00 20.00 0.00 = -111.67",
                "Kid 33.35 10.01 100.00 0.00 0.00 0.00 = -76.66");
        assertThat(balances(alice)).containsExactly("Mum 188.33 you", "Dad -111.67", "Kid -76.66");
    }

    /** {@code from} and {@code to} take the records of those days, both included; reversed bounds are a 400. */
    @Test
    void theBoundsTakeTheRecordsOfTheirDays() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-30", groceries, "30.00", mum, "\"paymentLater\": true,")));
        created(post(alice, uri + "/records", expense("2026-10-01", rent, "60.00", kid, "")));

        assertThat(rows(ok(get(alice, uri + "/report?to=2026-09-30"))))
                .containsExactly("2026-09 EXPENSE Groceries 30.00: Mum 10.00/30.00, Dad 10.00/0.00, Kid 10.00/0.00");
        JsonNode october = ok(get(alice, uri + "/report?from=2026-10-01&to=2026-10-01"));
        assertThat(october.get("from").asText()).isEqualTo("2026-10-01");
        assertThat(rows(october))
                .containsExactly("2026-10 EXPENSE Rent 60.00: Mum 20.00/0.00, Dad 20.00/0.00, Kid 20.00/60.00");
        assertThat(totals(october)).containsExactly("Mum 20.00 0.00 0.00 0.00 0.00 0.00 = 20.00",
                "Dad 20.00 0.00 0.00 0.00 0.00 0.00 = 20.00", "Kid 20.00 60.00 0.00 0.00 0.00 0.00 = -40.00");
        assertThat(rows(ok(get(alice, uri + "/report?from=2026-11-01")))).isEmpty();
        assertThat(get(alice, uri + "/report?from=2026-10-02&to=2026-10-01")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    /**
     * Only ACTIVE members read the report, owners and members alike; a member who left, one who deleted their data
     * (FORMER), anyone else, and any personal ledger get the answer for a family ledger that doesn't exist.
     */
    @Test
    void onlyActiveMembersReadIt() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, "\"paymentLater\": true,")));
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        join(family, carol, "Gran", "MEMBER", LocalDate.of(2026, 9, 1));
        String dave = newUser();
        ok(get(dave, "/api/accounts"));
        JsonNode missing = body(get(dave, "/api/family-ledgers/" + Long.MAX_VALUE + "/report"), HttpStatus.NOT_FOUND);

        assertThat(rows(ok(get(alice, uri + "/report")))).hasSize(1);
        assertThat(rows(ok(get(bob, uri + "/report")))).hasSize(1);
        assertThat(rows(ok(get(carol, uri + "/report")))).hasSize(1);

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(carol, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        for (String outsider : List.of(bob, carol, dave)) {
            JsonNode answer = body(get(outsider, uri + "/report"), HttpStatus.NOT_FOUND);
            assertThat(answer.get("detail").asText()).isEqualTo(missing.get("detail").asText()
                    .replace(String.valueOf(Long.MAX_VALUE), String.valueOf(family)));
        }
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(alice).query(Long.class).single();
        assertThat(get(alice, "/api/family-ledgers/" + personal + "/report")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(members(ok(get(alice, uri + "/report"))))
                .containsExactly("Mum ACTIVE account you", "Dad LEFT account", "Former member FORMER", "Kid ACTIVE");
    }

    /** The members as "name STATUS[ account][ you]". */
    private static List<String> members(JsonNode report) {
        List<String> members = new ArrayList<>();
        report.get("members").forEach(m -> members.add(m.get("displayName").asText() + " " + m.get("status").asText()
                + (m.get("hasAccount").asBoolean() ? " account" : "") + (m.get("you").asBoolean() ? " you" : "")));
        return members;
    }

    /** The rows as "month TYPE category total: name share/paid, …", the members named as the report names them. */
    private static List<String> rows(JsonNode report) {
        Map<Long, String> names = names(report);
        List<String> rows = new ArrayList<>();
        report.get("byCurrency").get(0).get("rows").forEach(row -> {
            List<String> members = new ArrayList<>();
            row.get("members").forEach(c -> members.add(names.get(c.get("memberId").asLong()) + " "
                    + c.get("share").asText() + "/" + c.get("paid").asText()));
            rows.add(row.get("month").asText() + " " + row.get("categoryType").asText() + " "
                    + row.get("categoryName").asText() + " " + row.get("total").asText() + ": "
                    + String.join(", ", members));
        });
        return rows;
    }

    /** The totals as "name expenseShares expensesPaid incomeShares incomesReceived settlementsPaid received = net". */
    private static List<String> totals(JsonNode report) {
        Map<Long, String> names = names(report);
        List<String> totals = new ArrayList<>();
        report.get("byCurrency").get(0).get("totals").forEach(t -> totals.add(names.get(t.get("memberId").asLong()) + " "
                + t.get("expenseShares").asText() + " " + t.get("expensesPaid").asText() + " "
                + t.get("incomeShares").asText() + " " + t.get("incomesReceived").asText() + " "
                + t.get("settlementsPaid").asText() + " " + t.get("settlementsReceived").asText() + " = "
                + t.get("net").asText()));
        return totals;
    }

    private static Map<Long, String> names(JsonNode report) {
        Map<Long, String> names = new java.util.HashMap<>();
        report.get("members").forEach(m -> names.put(m.get("memberId").asLong(), m.get("displayName").asText()));
        return names;
    }
}
