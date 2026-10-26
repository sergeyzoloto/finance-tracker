package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.ImportPlanner.Source;
import com.example.financetracker.ledger.importer.MemberPlanner.Benefit;
import com.example.financetracker.ledger.importer.MemberPlanner.ConversionRow;
import com.example.financetracker.ledger.importer.MemberPlanner.FamilyRow;
import com.example.financetracker.ledger.importer.MemberPlanner.Personal;
import com.example.financetracker.ledger.importer.MemberPlanner.Plan;
import com.example.financetracker.ledger.importer.MemberPlanner.Refused;
import com.example.financetracker.ledger.importer.MemberPlanner.SettlementRow;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** What each row of a member's own file becomes (D-177 to D-181), over D3c's synthetic partner, without a database. */
class MemberPlannerTests {

    private static final long MEMBER = 20;
    private static final long OTHER = 10;

    private final Map<String, AccountRef> accounts = new HashMap<>();
    private final MemberPlanner planner;
    private final Map<Long, TransactionRow> rows;

    MemberPlannerTests() throws IOException {
        long id = 1;
        for (var row : ImportFixtures.chart("d3c/partner-transactions.csv").accounts()) {
            accounts.put(row.name().toLowerCase(Locale.ROOT), new AccountRef(id++, row.code(), row.type(),
                    row.requiresCounterparty()));
        }
        Map<String, CategoryRow> categories = ImportFixtures.chart("d3c/partner-transactions.csv").categories().stream()
                .collect(Collectors.toMap(CategoryRow::name, Function.identity()));
        planner = new MemberPlanner(accounts, categories, MEMBER, OTHER);
        rows = Workbook.transactions(file("partner-transactions.csv")).rows().stream()
                .collect(Collectors.toMap(TransactionRow::id, Function.identity()));
    }

    private static ImportFile file(String name) throws IOException {
        return new ImportFile(name, new ClassPathResource("import/d3c/" + name).getContentAsByteArray());
    }

    private Plan plan(long id) {
        return planner.plan(rows.get(id));
    }

    /** D-181: his family row is the record his place's upload made, by D-151's hash and his reference (D-142). */
    @Test
    void aFamilyRowIsHisRecordByTheUploadsHash() throws IOException {
        FamilyRow row = (FamilyRow) plan(101);
        assertThat(row.spec().ref()).isEqualTo("xls:20:101");
        assertThat(row.spec().source()).isEqualTo(Source.MEMBER_FAMILY);
        assertThat(row.spec().ownerPays()).isTrue();
        assertThat(row.spec().accountCode()).isEqualTo("PARTNER_CARD");
        // The owner's upload of the same row, through the page's filter, hashes the same.
        var uploaded = Workbook.partner(new ImportFile("rows.csv",
                ImportFixtures.seatRows(file("partner-transactions.csv").content()))).rows().stream()
                .filter(r -> r.id() == 101).findFirst().orElseThrow();
        assertThat(row.spec().hash()).isEqualTo(uploaded.contentHash());
        // His side has a hash of its own, which an edit of his account or comment changes (D-181).
        assertThat(row.sideHash()).isNotEqualTo(row.spec().hash());
        // A RUB record paid from his EUR card names what went from the card (D-89).
        FamilyRow rouble = (FamilyRow) plan(103);
        assertThat(rouble.spec().currency()).isEqualTo("RUB");
        assertThat(rouble.spec().accountCurrency()).isEqualTo("EUR");
        assertThat(rouble.spec().accountAmount()).isEqualByComparingTo("5.50");
    }

    /** D-178: settlement rows and conversions, the Family flag on a transfer ignored. */
    @Test
    void settlementRowsAndConversions() {
        SettlementRow receives = (SettlementRow) plan(106);
        assertThat(receives.spec().ownerPays()).isFalse();
        assertThat(receives.spec().amount()).isEqualByComparingTo("40");
        assertThat(receives.spec().accountCode()).isEqualTo("PARTNER_CARD");
        SettlementRow flagged = (SettlementRow) plan(116);
        assertThat(flagged.spec().ownerPays()).isTrue();
        ConversionRow conversion = (ConversionRow) plan(110);
        assertThat(conversion.legs()).extracting(l -> l.currency() + " " + l.amount().stripTrailingZeros().toPlainString() + " "
                + (l.ownerPays() ? "pays" : "receives")).containsExactlyInAnyOrder("USD 10 pays", "EUR 9 receives");
        assertThat(conversion.legs()).allSatisfy(l -> assertThat(l.throughExchange()).isTrue());
    }

    /** D-179: a benefit row is checked, not posted; an expense straight from FAMILY_DEBT is refused. */
    @Test
    void benefitRowsAndExpensesFromTheFamilyDebt() {
        Benefit benefit = (Benefit) plan(105);
        assertThat(benefit.currency()).isEqualTo("EUR");
        assertThat(benefit.amount()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(((Refused) plan(112)).errors()).containsExactly(MemberPlanner.EXPENSE_FROM_FAMILY_DEBT);
    }

    /** D-72: a row with the Family flag and no item is a personal transfer, as are his other personal rows. */
    @Test
    void personalRows() {
        assertThat(plan(114)).isInstanceOf(Personal.class);
        assertThat(((Personal) plan(114)).row().family()).isFalse();
        assertThat(plan(104)).isInstanceOf(Personal.class);
    }
}
