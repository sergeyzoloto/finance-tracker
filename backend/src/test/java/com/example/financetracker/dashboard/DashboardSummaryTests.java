package com.example.financetracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.dashboard.DashboardController.CategorySpend;
import com.example.financetracker.dashboard.DashboardController.Summary;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The summary's arithmetic. Amounts are compared as exact decimals including scale, so 0.3 vs 0.30
 * or 0.30000000000000004 fails. Every test runs as a fresh user.
 */
class DashboardSummaryTests extends IntegrationTest {

    private final String user = token(UUID.randomUUID().toString());

    @Test
    void emptyAccountIsAllZerosWithTwoDecimals() throws IOException {
        Summary summary = summary(user, "2026-01-01", "2026-01-31");

        assertThat(summary.balance()).isEqualTo(money("0.00"));
        assertThat(summary.income()).isEqualTo(money("0.00"));
        assertThat(summary.expense()).isEqualTo(money("0.00"));
        assertThat(summary.spendByCategory()).isEmpty();
    }

    @Test
    void rangeIncludesBothEndDatesAndNothingOutside() throws IOException {
        long salary = createCategory(user, "Salary", "INCOME");
        long rent = createCategory(user, "Rent", "EXPENSE");
        // Powers of two: any wrongly included or excluded day yields a distinct, recognisable total.
        createTransaction(user, salary, "1000.00", "2025-12-31");
        createTransaction(user, salary, "1.00", "2026-01-01");
        createTransaction(user, salary, "2.00", "2026-01-31");
        createTransaction(user, salary, "4000.00", "2026-02-01");
        createTransaction(user, rent, "10.00", "2025-12-31");
        createTransaction(user, rent, "20.00", "2026-01-01");
        createTransaction(user, rent, "40.00", "2026-01-31");
        createTransaction(user, rent, "80.00", "2026-02-01");

        Summary summary = summary(user, "2026-01-01", "2026-01-31");

        assertThat(summary.income()).isEqualTo(money("3.00"));
        assertThat(summary.expense()).isEqualTo(money("60.00"));
        assertThat(summary.spendByCategory()).containsExactly(new CategorySpend(rent, "Rent", money("60.00")));
        // Balance is all-time: 5003.00 in, 150.00 out, regardless of the range.
        assertThat(summary.balance()).isEqualTo(money("4853.00"));
    }

    @Test
    void singleDayRangeCountsThatDay() throws IOException {
        long rent = createCategory(user, "Rent", "EXPENSE");
        createTransaction(user, rent, "7.25", "2028-02-29");
        createTransaction(user, rent, "100.00", "2028-02-28");
        createTransaction(user, rent, "100.00", "2028-03-01");

        Summary summary = summary(user, "2028-02-29", "2028-02-29");

        assertThat(summary.expense()).isEqualTo(money("7.25"));
    }

    @Test
    void centsAddUpExactlyAndBalanceCanGoNegative() throws IOException {
        long salary = createCategory(user, "Salary", "INCOME");
        long food = createCategory(user, "Food", "EXPENSE");
        createTransaction(user, salary, "0.10", "2026-03-01");
        createTransaction(user, salary, "0.20", "2026-03-02");
        createTransaction(user, food, "33.33", "2026-03-03");
        createTransaction(user, food, "33.33", "2026-03-04");
        createTransaction(user, food, "33.33", "2026-03-05");
        createTransaction(user, food, "12.5", "2026-03-06");

        Summary summary = summary(user, "2026-03-01", "2026-03-31");

        assertThat(summary.income()).isEqualTo(money("0.30"));
        assertThat(summary.expense()).isEqualTo(money("112.49"));
        assertThat(summary.balance()).isEqualTo(money("-112.19"));
    }

    @Test
    void totalsCanExceedTheColumnPrecisionOfASingleAmount() throws IOException {
        long salary = createCategory(user, "Salary", "INCOME");
        createTransaction(user, salary, "9999999999.99", "2026-04-01");
        createTransaction(user, salary, "9999999999.99", "2026-04-02");

        Summary summary = summary(user, "2026-04-01", "2026-04-30");

        assertThat(summary.income()).isEqualTo(money("19999999999.98"));
        assertThat(summary.balance()).isEqualTo(money("19999999999.98"));
    }

    @Test
    void spendByCategoryGroupsExpensesOnlyOrdersByTotalAndAddsUpToExpense() throws IOException {
        long rent = createCategory(user, "Rent", "EXPENSE");
        long food = createCategory(user, "Food", "EXPENSE");
        long fun = createCategory(user, "Fun", "EXPENSE");
        createCategory(user, "Unused", "EXPENSE");
        long bonus = createCategory(user, "Bonus", "INCOME");
        createTransaction(user, rent, "20.00", "2026-05-01");
        createTransaction(user, food, "12.34", "2026-05-02");
        createTransaction(user, food, "7.66", "2026-05-03");
        createTransaction(user, fun, "5.01", "2026-05-04");
        createTransaction(user, fun, "999.00", "2026-06-01");
        createTransaction(user, bonus, "100.00", "2026-05-05");

        Summary summary = summary(user, "2026-05-01", "2026-05-31");

        // Food and Rent tie at 20.00 and are ordered by name; Unused and the income category are absent.
        assertThat(summary.spendByCategory()).containsExactly(
                new CategorySpend(food, "Food", money("20.00")),
                new CategorySpend(rent, "Rent", money("20.00")),
                new CategorySpend(fun, "Fun", money("5.01")));
        assertThat(summary.spendByCategory().stream().map(CategorySpend::total).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualTo(summary.expense())
                .isEqualTo(money("45.01"));
    }

    @Test
    void anotherUsersTransactionsNeverCount() throws IOException {
        String otherUser = token(UUID.randomUUID().toString());
        long otherSalary = createCategory(otherUser, "Salary", "INCOME");
        long otherRent = createCategory(otherUser, "Rent", "EXPENSE");
        createTransaction(otherUser, otherSalary, "500.00", "2026-01-10");
        createTransaction(otherUser, otherRent, "200.00", "2026-01-11");
        long rent = createCategory(user, "Rent", "EXPENSE");
        createTransaction(user, rent, "1.00", "2026-01-12");

        Summary summary = summary(user, "2026-01-01", "2026-01-31");

        assertThat(summary.income()).isEqualTo(money("0.00"));
        assertThat(summary.expense()).isEqualTo(money("1.00"));
        assertThat(summary.balance()).isEqualTo(money("-1.00"));
        assertThat(summary.spendByCategory()).containsExactly(new CategorySpend(rent, "Rent", money("1.00")));
    }

    @Test
    void amountsWithMoreThanTwoDecimalsAreRejectedNotRounded() throws IOException {
        long rent = createCategory(user, "Rent", "EXPENSE");

        var result = request(HttpMethod.POST, "/api/transactions", user, """
                {"categoryId": %d, "amount": 10.005, "occurredOn": "2026-01-01"}""".formatted(rent));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(summary(user, "2026-01-01", "2026-01-31").expense()).isEqualTo(money("0.00"));
    }

    @Test
    void invalidRangeIsRejected() {
        assertThat(request(HttpMethod.GET, "/api/dashboard/summary?from=2026-02-01&to=2026-01-31", user, null))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(request(HttpMethod.GET, "/api/dashboard/summary?from=2026-02-01", user, null))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private Summary summary(String token, String from, String to) throws IOException {
        var result = request(HttpMethod.GET, "/api/dashboard/summary?from=" + from + "&to=" + to, token, null);
        assertThat(result).hasStatusOk();
        return read(result, Summary.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
