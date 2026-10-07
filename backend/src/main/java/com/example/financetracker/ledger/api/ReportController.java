package com.example.financetracker.ledger.api;

import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.report.AccountBalance;
import com.example.financetracker.ledger.report.CashFlowRow;
import com.example.financetracker.ledger.report.ConvertedBalance;
import com.example.financetracker.ledger.report.ConvertedCashFlow;
import com.example.financetracker.ledger.report.ConvertedNetWorth;
import com.example.financetracker.ledger.report.CounterpartyBalance;
import com.example.financetracker.ledger.report.IntegrityViolation;
import com.example.financetracker.ledger.report.NetWorth;
import com.example.financetracker.ledger.report.ReportService;
import com.example.financetracker.ledger.report.SharedSettlement;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reports of {@link ReportService}, computed from the user's postings on every request. Dates are inclusive; a
 * balance as of a day includes the entries of that day. {@code asOf} defaults to today in the server's time zone.
 * <p>
 * Balances, net worth and cash flow are per currency, or with {@code currency=BASE} converted to the user's base
 * currency: an amount on a day at the latest rate on or before that day, and null with {@code missingRates} where no
 * such rate exists. Each is two handlers on one path, told apart by the parameter; any other value is a 400.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    /** The {@code currency} parameter, as the OpenAPI description shows it. */
    private static final String BASE = "BASE for the report in the user's base currency; left out for each currency "
            + "on its own";

    private final ReportService reports;
    private final Today today;

    ReportController(ReportService reports, Today today) {
        this.reports = reports;
        this.today = today;
    }

    /** The displayed balance of every account that isn't archived, per currency (rule 4). */
    @GetMapping(path = "/balances", params = "!currency")
    List<AccountBalance> balances(LedgerScope ledger, @RequestParam(required = false) LocalDate asOf) {
        return reports.balances(ledger, asOfOrToday(ledger, asOf));
    }

    /** The displayed balance of every account that isn't archived, in the base currency. */
    @GetMapping(path = "/balances", params = "currency=BASE")
    List<ConvertedBalance> balancesInBase(LedgerScope ledger, @RequestParam(required = false) LocalDate asOf,
            @Parameter(description = BASE, schema = @Schema(allowableValues = "BASE")) @RequestParam(required = false)
            String currency) {
        return reports.balancesInBase(ledger, asOfOrToday(ledger, asOf));
    }

    /** Income and expenses per month, category and currency, from entries dated {@code from} to {@code to}. */
    @GetMapping(path = "/cash-flow", params = "!currency")
    List<CashFlowRow> cashFlow(LedgerScope ledger, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.cashFlow(ledger, from, to);
    }

    /**
     * Income and expenses per month and category in the base currency, and per month the realized and unrealized
     * results of exchange rates.
     */
    @GetMapping(path = "/cash-flow", params = "currency=BASE")
    ConvertedCashFlow cashFlowInBase(LedgerScope ledger, @RequestParam LocalDate from, @RequestParam LocalDate to,
            @Parameter(description = BASE, schema = @Schema(allowableValues = "BASE")) @RequestParam(required = false)
            String currency) {
        return reports.cashFlowInBase(ledger, from, to);
    }

    /**
     * The balance of an account that requires a counterparty, such as LOANS_ASSET, per counterparty and currency
     * (rule 8).
     *
     * @param accountCode the account's code; 404 if the user has no such account, 400 if it doesn't require a
     *        counterparty
     */
    @GetMapping("/counterparty-balances")
    List<CounterpartyBalance> counterpartyBalances(LedgerScope ledger, @RequestParam String accountCode,
            @RequestParam(required = false) LocalDate asOf) {
        return reports.counterpartyBalances(ledger, accountCode, asOfOrToday(ledger, asOf));
    }

    /** Assets minus liabilities, per currency. */
    @GetMapping(path = "/net-worth", params = "!currency")
    List<NetWorth> netWorth(LedgerScope ledger, @RequestParam(required = false) LocalDate asOf) {
        return reports.netWorth(ledger, asOfOrToday(ledger, asOf));
    }

    /** Assets minus liabilities in the base currency, with the realized and unrealized results of exchange rates. */
    @GetMapping(path = "/net-worth", params = "currency=BASE")
    ConvertedNetWorth netWorthInBase(LedgerScope ledger, @RequestParam(required = false) LocalDate asOf,
            @Parameter(description = BASE, schema = @Schema(allowableValues = "BASE")) @RequestParam(required = false)
            String currency) {
        return reports.netWorthInBase(ledger, asOfOrToday(ledger, asOf));
    }

    /** What is open between the user and the shared budget, per currency (rule 7). */
    @GetMapping("/shared-settlement")
    List<SharedSettlement> sharedSettlement(LedgerScope ledger, @RequestParam(required = false) LocalDate asOf) {
        return reports.sharedSettlement(ledger, asOfOrToday(ledger, asOf));
    }

    /** The currencies in which the user's ledger doesn't add up; empty for a sound ledger. */
    @GetMapping("/integrity")
    List<IntegrityViolation> integrity(LedgerScope ledger) {
        return reports.integrityCheck(ledger);
    }

    /** The day asked about, by default the reader's own today ({@link Today}, D-53, D-100). */
    private LocalDate asOfOrToday(LedgerScope ledger, LocalDate asOf) {
        return asOf != null ? asOf : today.date(ledger);
    }
}
