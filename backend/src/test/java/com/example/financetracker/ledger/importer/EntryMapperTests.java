package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import org.junit.jupiter.api.Test;

/** The owner's personal rows as entries, without a database (D3b). */
class EntryMapperTests {

    private final Map<String, AccountRef> byCode = new HashMap<>();
    private final EntryMapper mapper;
    private final Map<Long, TransactionRow> rows;

    EntryMapperTests() {
        Map<String, AccountRef> byName = new HashMap<>();
        long id = 1;
        for (var row : Workbook.accounts(ImportFixtures.file("accounts.csv")).rows()) {
            AccountRef ref = new AccountRef(id++, row.code(), row.type(), row.requiresCounterparty());
            byName.put(row.name().toLowerCase(Locale.ROOT), ref);
            byCode.put(row.code(), ref);
        }
        byCode.put("FX_EXCHANGE", new AccountRef(90, "FX_EXCHANGE", AccountType.EQUITY, false));
        byCode.put("OPENING_BALANCE", new AccountRef(91, "OPENING_BALANCE", AccountType.EQUITY, false));
        Map<String, CategoryRef> categories = new HashMap<>();
        for (var row : Workbook.categories(ImportFixtures.file("categories.csv")).rows()) {
            categories.put(row.name(), new CategoryRef(id++, row.code(), row.type()));
        }
        mapper = new EntryMapper(new Chart(byName, byCode, categories));
        rows = Workbook.transactions(ImportFixtures.file("transactions.csv")).rows().stream()
                .collect(Collectors.toMap(TransactionRow::id, Function.identity()));
    }

    private EntryPlan plan(long id) {
        RowResult result = mapper.map(rows.get(id));
        assertThat(result).isInstanceOf(RowResult.Entry.class);
        return ((RowResult.Entry) result).plan();
    }

    private List<String> lines(EntryPlan plan) {
        Map<Long, String> codes = byCode.values().stream().collect(Collectors.toMap(AccountRef::id, AccountRef::code));
        return plan.postings().stream().map(l -> codes.get(l.accountId()) + " " + l.currency() + " "
                + l.amount().stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void aRowIsAnEntryReferencedByItsId() {
        EntryPlan salary = plan(1);
        assertThat(salary.externalRef()).isEqualTo("xls:1");
        assertThat(salary.contentHash()).isEqualTo(rows.get(1L).contentHash());
        assertThat(salary.kind()).isEqualTo(EntryKind.INCOME);
        assertThat(lines(salary)).containsExactly("UNALLOCATED EUR -1000", "EURO_ACCOUNT EUR 1000");
    }

    /** D-145: a negative Sum with its sides swapped is a valid transfer. */
    @Test
    void aNegativeSumWithSwappedSidesIsATransfer() {
        EntryPlan transfer = plan(17);
        assertThat(transfer.kind()).isEqualTo(EntryKind.TRANSFER);
        assertThat(lines(transfer)).containsExactly("EURO_ACCOUNT EUR 20", "CASH EUR -20");
    }

    /** An agent's choice (D3b): amounts with more than 4 decimals rounded half up to 4, explained in the drift. */
    @Test
    void moreThanFourDecimalsAreRoundedAndExplained() {
        EntryPlan food = plan(19);
        assertThat(lines(food)).containsExactly("NORTH_CARD RUB -123.4568", "UNALLOCATED RUB 123.4568");
        assertThat(food.drift()).extracting(d -> d.code() + " " + d.amount().toPlainString() + " " + d.reason())
                .containsExactlyInAnyOrder("NORTH_CARD -0.000011 FOUR_DECIMALS", "UNALLOCATED -0.000011 FOUR_DECIMALS");
    }

    /** An agent's choice (D3b): the same account on both sides in two currencies is an exchange within it. */
    @Test
    void anExchangeWithinOneAccountAndTheSameAccountInOneCurrency() {
        EntryPlan exchange = plan(24);
        assertThat(exchange.kind()).isEqualTo(EntryKind.CURRENCY_EXCHANGE);
        assertThat(lines(exchange)).containsExactly("EURO_ACCOUNT EUR -10", "FX_EXCHANGE EUR 10",
                "FX_EXCHANGE RUB -900", "EURO_ACCOUNT RUB 900");
        TransactionRow row = rows.get(24L);
        // D-192: without the Family flag, both sides alike, it moves nothing: skipped and named.
        TransactionRow oneCurrency = new TransactionRow(row.row(), row.id(), row.contentHash(), row.date(), row.item(),
                row.sum(), row.currency(), null, null, row.debit(), row.credit(), false, null, null, null, null, null,
                null, null, null, null);
        assertThat(mapper.map(oneCurrency)).isInstanceOf(RowResult.Skipped.class).satisfies(r -> {
            assertThat(((RowResult.Skipped) r).reason()).isEqualTo(EntryMapper.SAME_ACCOUNT_NOOP);
            assertThat(r.warnings()).containsExactly(EntryMapper.SAME_ACCOUNT_NOOP);
        });
        // With the Family flag set (and no item: D-72 clears it on the way), it stays an error (D-156).
        TransactionRow flagged = new TransactionRow(row.row(), row.id(), row.contentHash(), row.date(), row.item(),
                row.sum(), row.currency(), null, null, row.debit(), row.credit(), false, null, null, null, null, null,
                null, null, null, null, true);
        assertThat(mapper.map(flagged)).isInstanceOf(RowResult.Failed.class)
                .extracting(r -> ((RowResult.Failed) r).errors())
                .isEqualTo(List.of("Debit and Credit are the same account, in one currency"));
    }

    @Test
    void zeroRowsAreSkipped() {
        assertThat(mapper.map(rows.get(18L))).isInstanceOf(RowResult.Skipped.class);
    }

    /** D-62: the first daily row's balances, assets against RESERVE; Excel's residues and zeros are left out. */
    @Test
    void theOpeningBalancesComeFromTheFirstRowOfTheDailyBalanceFile() {
        Map<DailyFile.Column, BigDecimal> balances = new LinkedHashMap<>();
        balances.put(new DailyFile.Column("CASH", "EUR"), new BigDecimal("100"));
        balances.put(new DailyFile.Column("RESERVE", "EUR"), new BigDecimal("100"));
        balances.put(new DailyFile.Column("FAMILY_DEBT", "USD"), new BigDecimal("-1.42109E-14"));
        RowResult result = mapper.openingEntry(LocalDate.of(2023, 12, 31), balances);
        EntryPlan opening = ((RowResult.Entry) result).plan();
        assertThat(opening.externalRef()).isEqualTo("xls-opening:2023-12-31");
        assertThat(lines(opening)).containsExactly("CASH EUR 100", "RESERVE EUR -100");
        balances.put(new DailyFile.Column("FAMILY_DEBT", "EUR"), new BigDecimal("5"));
        assertThat(mapper.openingEntry(LocalDate.of(2023, 12, 31), balances)).isInstanceOf(RowResult.Failed.class);
    }
}
