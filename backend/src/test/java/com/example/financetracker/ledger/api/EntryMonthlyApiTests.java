package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ENT-4 (D-882, D-883): {@code /entries/monthly} gives the filtered entries' My share by calendar month: the 12 months
 * ending with the To month (without a To, the latest matching entry's), whatever the From, less the months before the
 * first matching entry, every other filter applying; and its months add up to {@code /entries/summary}'s In and Out over
 * the same days.
 */
class EntryMonthlyApiTests extends FamilyApiTest {

    private long current;
    private long cash;
    private long savings;
    private long groceries;
    private long eatingOut;
    private long shop;

    @BeforeEach
    void ledger() throws IOException {
        current = accountId(alice, "CURRENT_ACCOUNT");
        cash = accountId(alice, "CASH");
        savings = accountId(alice, "SAVINGS_ACCOUNT");
        groceries = categoryId(alice, "GROCERIES");
        eatingOut = categoryId(alice, "EATING_OUT");
        shop = newCounterparty(alice, "Shop ENT4");
        // 15 months, 2025-08 to 2026-10: an expense each month on the 5th (10.00 plus the month's number, from the
        // current account, at the shop, in groceries), a café expense in cash in odd months, a salary on the 1st of every
        // third month, and in 2026-04 only a transfer besides.
        int n = 0;
        for (int year = 2025, month = 8; !(year == 2026 && month == 11); n++) {
            String day = "%d-%02d-05".formatted(year, month);
            if (!(year == 2026 && month == 4)) {
                expense(day, "%d.00".formatted(10 + n), current, groceries);
            } else {
                newEntry(alice, """
                        {"kind": "TRANSFER", "entryDate": "%s", "fromAccountId": %d, "toAccountId": %d,
                         "currency": "EUR", "amount": "50.00"}""".formatted(day, current, savings));
            }
            if (month % 2 == 1) {
                expense("%d-%02d-09".formatted(year, month), "4.50", cash, eatingOut);
            }
            if (n % 3 == 0) {
                newEntry(alice, """
                        {"kind": "INCOME", "entryDate": "%d-%02d-01", "accountId": %d, "currency": "EUR",
                         "amount": "1000.00", "categoryId": %d}""".formatted(year, month, current, categoryId(alice, "SALARY")));
            }
            if (++month == 13) {
                month = 1;
                year++;
            }
        }
    }

    private void expense(String date, String amount, long account, long category) throws IOException {
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "%s", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "%s", "categoryId": %d}""".formatted(date, shop, account, amount, category));
    }

    private JsonNode monthly(String query) throws IOException {
        return ok(get(alice, "/api/entries/monthly" + (query.isEmpty() ? "" : "?" + query)));
    }

    /** The months as {@code YYYY-MM in out}, EUR, without trailing zeros. */
    private List<String> lines(JsonNode answer) {
        List<String> lines = new ArrayList<>();
        answer.get("months").forEach(m -> lines.add(m.get("month").asText() + " "
                + new BigDecimal(m.get("incoming").asText()).stripTrailingZeros().toPlainString() + " "
                + new BigDecimal(m.get("outgoing").asText()).stripTrailingZeros().toPlainString()));
        return lines;
    }

    /** The monthly figures add up to the summary's In and Out over the span's days. */
    private void addUpToTheSummary(JsonNode answer, String summaryFilter) throws IOException {
        JsonNode summary = ok(get(alice, "/api/entries/summary?" + summaryFilter));
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (JsonNode m : answer.get("months")) {
            in = in.add(new BigDecimal(m.get("incoming").asText()));
            out = out.add(new BigDecimal(m.get("outgoing").asText()));
        }
        BigDecimal sumIn = BigDecimal.ZERO;
        BigDecimal sumOut = BigDecimal.ZERO;
        for (JsonNode c : summary.get("currencies")) {
            sumIn = sumIn.add(new BigDecimal(c.get("incoming").asText()));
            sumOut = sumOut.add(new BigDecimal(c.get("outgoing").asText()));
        }
        assertThat(in).as("In over " + summaryFilter).isEqualByComparingTo(sumIn);
        assertThat(out).as("Out over " + summaryFilter).isEqualByComparingTo(sumOut);
    }

    @Test
    void twelveMonthsEndingWithTheLatestEntrysMonthAddUpToTheSummary() throws IOException {
        JsonNode answer = monthly("");
        assertThat(answer.get("firstMonth").asText()).isEqualTo("2025-11");
        assertThat(answer.get("lastMonth").asText()).isEqualTo("2026-10");
        // 2026-04 has a transfer only: no figure in it, though it is in the span.
        List<String> lines = lines(answer);
        assertThat(lines).noneMatch(l -> l.startsWith("2026-04"));
        assertThat(lines).contains("2025-11 1000 17.5");
        assertThat(lines.stream().map(l -> l.substring(0, 7)).distinct().count()).isEqualTo(11);
        addUpToTheSummary(answer, "from=2025-11-01&to=2026-10-31");
    }

    @Test
    void theToMonthEndsTheSpanWhateverTheFromAndMonthsBeforeTheFirstEntryAreLeftOut() throws IOException {
        JsonNode answer = monthly("to=2026-06-15");
        // 12 months back from 2026-06 would start at 2025-07; the first entry is in 2025-08.
        assertThat(answer.get("firstMonth").asText()).isEqualTo("2025-08");
        assertThat(answer.get("lastMonth").asText()).isEqualTo("2026-06");
        addUpToTheSummary(answer, "from=2025-08-01&to=2026-06-15");
        // The From changes nothing.
        assertThat(monthly("from=2026-05-01&to=2026-06-15")).isEqualTo(answer);
        assertThat(monthly("from=2026-10-01")).isEqualTo(monthly(""));
        // A To month with no entry is still the span's last.
        assertThat(monthly("to=2027-02-28").get("lastMonth").asText()).isEqualTo("2027-02");
        assertThat(monthly("to=2027-02-28").get("firstMonth").asText()).isEqualTo("2026-03");
    }

    @Test
    void everyOtherFilterApplies() throws IOException {
        JsonNode cafe = monthly("accountId=" + cash);
        // Only the café's odd months, all expenses: the span ends with the last, 2026-09, and starts 11 months before it,
        // after the first (2025-09).
        assertThat(cafe.get("lastMonth").asText()).isEqualTo("2026-09");
        assertThat(cafe.get("firstMonth").asText()).isEqualTo("2025-10");
        assertThat(lines(cafe)).allMatch(l -> l.endsWith(" 0 4.5"));
        addUpToTheSummary(cafe, "accountId=" + cash + "&from=2025-10-01&to=2026-09-30");
        JsonNode income = monthly("direction=INCOME");
        assertThat(lines(income)).allMatch(l -> l.endsWith(" 0"));
        addUpToTheSummary(income, "direction=INCOME&from=2025-11-01&to=2026-10-31");
        addUpToTheSummary(monthly("categoryId=" + groceries + "&categoryId=" + eatingOut),
                "categoryId=" + groceries + "&categoryId=" + eatingOut + "&from=2025-11-01&to=2026-10-31");
        addUpToTheSummary(monthly("payee=shop ent4&origin=PERSONAL"),
                "payee=shop ent4&origin=PERSONAL&from=2025-11-01&to=2026-10-31");
    }

    @Test
    void nothingMatchingGivesNoSpanAndBadValuesAre400() throws IOException {
        JsonNode none = monthly("payee=nobody ent4");
        assertThat(none.get("firstMonth").isNull()).isTrue();
        assertThat(none.get("lastMonth").isNull()).isTrue();
        assertThat(none.get("months")).isEmpty();
        for (String query : List.of("direction=SIDEWAYS", "from=2026-05-01&to=2026-04-01", "accountId=x")) {
            assertThat(get(alice, "/api/entries/monthly?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
