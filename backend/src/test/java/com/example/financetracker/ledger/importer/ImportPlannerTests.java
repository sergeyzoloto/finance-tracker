package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.ImportPlanner.Family;
import com.example.financetracker.ledger.importer.ImportPlanner.FamilyItem;
import com.example.financetracker.ledger.importer.ImportPlanner.Planned;
import com.example.financetracker.ledger.importer.ImportPlanner.RecordSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.SettlementSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.Source;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;
import org.junit.jupiter.api.Test;

/**
 * What each row of the synthetic export becomes (D-82, option C), without a database: the owner's family rows
 * (D-74, D-79, D-80, D-81), his rows on FAMILY_DEBT (D-71, D-73, D-84, D-148), the lumps (D-83), the partner's rows,
 * the references (D-142) and the drift (D-68).
 */
class ImportPlannerTests {

    private static final long OWNER = 10;
    private static final long SEAT = 20;

    private final Map<String, AccountRef> accounts = new HashMap<>();
    private final ImportPlanner planner;
    private final Map<Long, TransactionRow> rows;

    ImportPlannerTests() {
        long id = 1;
        for (var row : Workbook.accounts(ImportFixtures.file("accounts.csv")).rows()) {
            accounts.put(row.name().toLowerCase(Locale.ROOT), new AccountRef(id++, row.code(), row.type(),
                    row.requiresCounterparty()));
        }
        Map<String, CategoryRow> categories = Workbook.categories(ImportFixtures.file("categories.csv")).rows().stream()
                .collect(Collectors.toMap(CategoryRow::name, Function.identity()));
        planner = new ImportPlanner(accounts, categories, OWNER, SEAT);
        rows = Workbook.transactions(ImportFixtures.file("transactions.csv")).rows().stream()
                .collect(Collectors.toMap(TransactionRow::id, Function.identity()));
    }

    private Planned plan(long id) {
        return planner.plan(rows.get(id));
    }

    private List<FamilyItem> items(long id) {
        assertThat(plan(id)).isInstanceOf(Family.class);
        return ((Family) plan(id)).items();
    }

    private RecordSpec record(long id) {
        return (RecordSpec) items(id).getFirst();
    }

    private static List<String> drift(FamilyItem item) {
        return item.drift().stream().map(d -> d.code() + " " + d.currency() + " " + d.amount().stripTrailingZeros()
                .toPlainString() + " " + d.reason()).toList();
    }

    @Test
    void personalRowsAndFamilyRowsWithoutAnItemAreTheEntryMappersOnes() {
        assertThat(plan(1)).isInstanceOf(ImportPlanner.Personal.class);
        // D-72: a family row without an item is a transfer, the flag ignored.
        Planned transfer = plan(7);
        assertThat(transfer).isInstanceOf(ImportPlanner.Personal.class);
        assertThat(((ImportPlanner.Personal) transfer).row().family()).isFalse();
        assertThat(plan(8)).isInstanceOf(ImportPlanner.Skipped.class);
    }

    /** D-12's halves, the owner paying: 10.01 gives him 5.01, the seat 5.00; Excel's exact half is the drift. */
    @Test
    void theOwnersFamilyExpenseIsHisRecordPaidFromTheOtherSide() {
        RecordSpec expense = record(2);
        assertThat(expense.source()).isEqualTo(Source.OWNER_FAMILY);
        assertThat(expense.ref()).isEqualTo("xls:10:2");
        assertThat(expense.type()).isEqualTo("EXPENSE");
        assertThat(expense.refund()).isFalse();
        assertThat(expense.amount()).isEqualByComparingTo("10.01");
        assertThat(expense.ownerPays()).isTrue();
        assertThat(expense.accountCode()).isEqualTo("EURO_ACCOUNT");
        assertThat(expense.accountCurrency()).isEqualTo("EUR");
        assertThat(expense.accountAmount()).isNull();
        assertThat(expense.categoryCode()).isEqualTo("FOOD");
        // D-81: the payee; the comment as his private note.
        assertThat(expense.payee()).isEqualTo("Лавка");
        assertThat(expense.note()).isEqualTo("на неделю");
        assertThat(drift(expense)).containsExactlyInAnyOrder("FAMILY_DEBT EUR 0.005 SHARES",
                "UNALLOCATED EUR -0.005 SHARES");
    }

    @Test
    void aRefundAndAnIncome() {
        RecordSpec refund = record(3);
        assertThat(refund.kind()).isEqualTo("REFUND");
        assertThat(refund.amount()).isEqualByComparingTo("4.00");
        assertThat(drift(refund)).isEmpty();
        RecordSpec income = record(4);
        assertThat(income.type()).isEqualTo("INCOME");
        assertThat(income.categoryCode()).isEqualTo("OTHERINC");
        assertThat(income.accountCode()).isEqualTo("EURO_ACCOUNT");
    }

    /** D-74: CUR_FAMILY's record, rounded half up to the minor unit, paid from the euro account with its euros (D-87). */
    @Test
    void aRowInAnotherCurrencyThanItsPayingAccount() {
        RecordSpec roubles = record(5);
        assertThat(roubles.currency()).isEqualTo("RUB");
        assertThat(roubles.amount()).isEqualByComparingTo("300.12");
        assertThat(roubles.accountCurrency()).isEqualTo("EUR");
        assertThat(roubles.accountAmount()).isEqualByComparingTo("3.30");
        assertThat(drift(roubles)).containsExactlyInAnyOrder("FAMILY_DEBT RUB 0.001725 MINOR_UNIT",
                "UNALLOCATED RUB 0.001725 MINOR_UNIT");
    }

    /** D-80: paid from a creditor debt, its counterparty LOAN_CA. */
    @Test
    void anExpensePaidOnCredit() {
        RecordSpec credit = record(6);
        assertThat(credit.accountCode()).isEqualTo("CREDITOR_DEBT");
        assertThat(credit.counterparty()).isEqualTo("Банк");
    }

    @Test
    void settlementsGoTheWayFamilyDebtMoves() {
        SettlementSpec pays = (SettlementSpec) items(9).getFirst();
        assertThat(pays.ownerPays()).isTrue();
        assertThat(pays.accountCode()).isEqualTo("EURO_ACCOUNT");
        assertThat(pays.amount()).isEqualByComparingTo("30");
        SettlementSpec receives = (SettlementSpec) items(10).getFirst();
        assertThat(receives.ownerPays()).isFalse();
        assertThat(receives.accountCode()).isEqualTo("CASH");
        // D-148: against a loan given, with its counterparty.
        SettlementSpec loan = (SettlementSpec) items(11).getFirst();
        assertThat(loan.ownerPays()).isFalse();
        assertThat(loan.accountCode()).isEqualTo("LOANS_ASSET");
        assertThat(loan.counterparty()).isEqualTo("Друг");
        assertThat(loan.ref()).isEqualTo("xls:10:11");
        // In dollars, back to 0.
        SettlementSpec dollars = (SettlementSpec) items(16).getFirst();
        assertThat(dollars.currency()).isEqualTo("USD");
        assertThat(dollars.ownerPays()).isTrue();
    }

    /** D-73: the seat's record, 100 % the owner's; the one without a category an income in OTHERINC. */
    @Test
    void theOwnersRowsOnFamilyDebtAgainstUnallocated() {
        RecordSpec gift = record(12);
        assertThat(gift.source()).isEqualTo(Source.OWNER_SEAT);
        assertThat(gift.ownerPays()).isFalse();
        assertThat(gift.ownerOnly()).isTrue();
        assertThat(gift.kind()).isEqualTo("EXPENSE");
        assertThat(gift.categoryCode()).isEqualTo("GIFTS");
        RecordSpec other = record(13);
        assertThat(other.kind()).isEqualTo("INCOME");
        assertThat(other.categoryCode()).isEqualTo("OTHERINC");
        // D-84: his EUR benefit on the family debt is the partner's family row's half: not imported.
        assertThat(plan(23)).isInstanceOf(ImportPlanner.NotImported.class);
    }

    /** D-71: a conversion of the family debt, two settlements through FX_EXCHANGE, one per currency. */
    @Test
    void aConversionOfTheFamilyDebt() {
        List<FamilyItem> parts = items(14);
        assertThat(parts).hasSize(2);
        SettlementSpec roubles = (SettlementSpec) parts.get(0);
        SettlementSpec euros = (SettlementSpec) parts.get(1);
        assertThat(roubles.throughExchange()).isTrue();
        assertThat(roubles.currency()).isEqualTo("RUB");
        assertThat(roubles.ownerPays()).isTrue();
        assertThat(roubles.amount()).isEqualByComparingTo("1000");
        assertThat(roubles.ref()).isEqualTo("xls:10:14:RUB");
        assertThat(euros.currency()).isEqualTo("EUR");
        assertThat(euros.ownerPays()).isFalse();
        assertThat(euros.ref()).isEqualTo("xls:10:14:EUR");
        assertThat(roubles.hash()).isNotEqualTo(euros.hash());
    }

    /** D-83: twice the lump, 50/50, the seat paying; only the months before the partner's ledger starts. */
    @Test
    void lumpsBeforeThePartnersLedger() {
        DailyFile change = DailyFile.read(ImportFixtures.file("daily-change.csv"), LocalDate.of(2024, 3, 8),
                new java.util.ArrayList<>());
        List<RecordSpec> lumps = planner.lumps(change, LocalDate.of(2024, 2, 1));
        // The USD lump column is 0 (D-85): no USD lump.
        assertThat(lumps).extracting(RecordSpec::excelId).containsExactly("PL:2024-01:RUB", "PL:2024-01:EUR");
        RecordSpec roubles = lumps.getFirst();
        assertThat(roubles.amount()).isEqualByComparingTo("666.67");
        assertThat(roubles.categoryCode()).isEqualTo(ImportPlanner.LUMP_CATEGORY_CODE);
        assertThat(roubles.categoryName()).isEqualTo("Без детализации");
        assertThat(roubles.ref()).isEqualTo("xls:10:PL:2024-01:RUB");
        // The seat paid: the extra kopeck is his, so the owner's share is 333.33 against Excel's 333.335.
        assertThat(drift(roubles)).containsExactlyInAnyOrder("FAMILY_DEBT RUB -0.005 SHARES",
                "UNALLOCATED RUB 0.005 SHARES");
        assertThat(planner.lumps(change, LocalDate.of(2024, 1, 15))).isEmpty();
    }

    /** The partner's rows, keyed by his seat (D-142). */
    @Test
    void thePartnersRows() {
        Map<Long, PartnerRow> partner = Workbook.partner(new ImportFile("p.csv", ImportFixtures.partnerRows())).rows()
                .stream().collect(Collectors.toMap(PartnerRow::id, Function.identity()));
        RecordSpec expense = (RecordSpec) ((Family) planner.partner(partner.get(101L))).items().getFirst();
        assertThat(expense.ref()).isEqualTo("xls:20:101");
        assertThat(expense.source()).isEqualTo(Source.PARTNER);
        assertThat(expense.ownerPays()).isFalse();
        assertThat(expense.payee()).isNull();
        assertThat(expense.note()).isNull();
        // 40.02 halves exactly: no drift.
        assertThat(drift(expense)).isEmpty();
        RecordSpec refund = (RecordSpec) ((Family) planner.partner(partner.get(102L))).items().getFirst();
        assertThat(refund.kind()).isEqualTo("REFUND");
        RecordSpec benefit = (RecordSpec) ((Family) planner.partner(partner.get(103L))).items().getFirst();
        assertThat(benefit.kind()).isEqualTo("INCOME");
        assertThat(benefit.categoryCode()).isEqualTo("BENEFIT");
        RecordSpec odd = (RecordSpec) ((Family) planner.partner(partner.get(104L))).items().getFirst();
        // 500.5 roubles: the seat pays, so the owner's half is 250.25 exactly.
        assertThat(odd.amount()).isEqualByComparingTo(new BigDecimal("500.50"));
        assertThat(planner.partner(partner.get(105L))).isInstanceOf(ImportPlanner.Skipped.class);
        RecordSpec dollars = (RecordSpec) ((Family) planner.partner(partner.get(108L))).items().getFirst();
        assertThat(dollars.currency()).isEqualTo("RUB");
        assertThat(dollars.amount()).isEqualByComparingTo("230.75");
        // 230.75 split 50/50, the seat paying: 115.37 is the owner's, against Excel's 115.375.
        assertThat(drift(dollars)).containsExactlyInAnyOrder("FAMILY_DEBT RUB -0.005 SHARES",
                "UNALLOCATED RUB 0.005 SHARES");
    }

    @Test
    void aFamilyRowThatCantBeMappedIsAnErrorWithoutItsValues() {
        TransactionRow row = rows.get(2L);
        TransactionRow wrongCurrency = new TransactionRow(row.row(), row.id(), row.contentHash(), row.date(), row.item(),
                row.sum(), row.currency(), row.counteragent(), row.comment(), row.debit(), row.credit(), true,
                row.debitCurrency(), row.debitSum(), row.creditCurrency(), row.creditSum(), row.loanCounterparty(), "RUB",
                row.familyExpense(), row.familyIncome(), row.itemCode());
        assertThat(planner.plan(wrongCurrency)).isInstanceOf(ImportPlanner.Failed.class)
                .extracting(p -> ((ImportPlanner.Failed) p).errors())
                .isEqualTo(List.of("CUR_FAMILY isn't the currency of the UNALLOCATED side"));
    }
}
