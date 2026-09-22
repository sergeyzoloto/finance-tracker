package com.example.financetracker.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dashboard")
class DashboardController {

    /**
     * {@code balance} is all-time income minus expense; the other figures cover {@code from..to}, both
     * inclusive. Amounts keep two decimals, including zeros ({@code 0.00}).
     */
    record Summary(BigDecimal balance, BigDecimal income, BigDecimal expense, List<CategorySpend> spendByCategory) {
    }

    record CategorySpend(long categoryId, String name, BigDecimal total) {
    }

    record Totals(BigDecimal balance, BigDecimal income, BigDecimal expense) {
    }

    private final JdbcClient jdbc;

    DashboardController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    // One snapshot for both queries, so the breakdown always adds up to the expense total.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Summary summary(@AuthenticationPrincipal CurrentUser user, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'");
        }
        Totals totals = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN c.type = 'INCOME' THEN t.amount ELSE -t.amount END), 0.00) AS balance,
                       COALESCE(SUM(t.amount) FILTER (WHERE c.type = 'INCOME' AND t.occurred_on BETWEEN :from AND :to), 0.00) AS income,
                       COALESCE(SUM(t.amount) FILTER (WHERE c.type = 'EXPENSE' AND t.occurred_on BETWEEN :from AND :to), 0.00) AS expense
                FROM transactions t JOIN categories c ON c.id = t.category_id
                WHERE t.user_id = :userId""")
                .param("userId", user.id()).param("from", from).param("to", to)
                .query(Totals.class)
                .single();
        List<CategorySpend> spendByCategory = jdbc.sql("""
                SELECT c.id AS category_id, c.name, SUM(t.amount) AS total
                FROM transactions t JOIN categories c ON c.id = t.category_id
                WHERE t.user_id = :userId AND c.type = 'EXPENSE' AND t.occurred_on BETWEEN :from AND :to
                GROUP BY c.id, c.name
                ORDER BY total DESC, c.name, c.id""")
                .param("userId", user.id()).param("from", from).param("to", to)
                .query(CategorySpend.class)
                .list();
        return new Summary(totals.balance(), totals.income(), totals.expense(), spendByCategory);
    }
}
