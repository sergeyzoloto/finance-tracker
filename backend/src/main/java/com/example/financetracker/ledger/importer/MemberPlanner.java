package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.ImportPlanner.RecordSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.SettlementSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.Source;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;

/**
 * What each row of a member's own file becomes once he took his seat (D-175 to D-181), in plain Java. His file has the
 * owner's layout (D-177), and he imports it himself, so his rows are read from his side:
 * <ul>
 * <li>A row with FAMILY_DEBT on both sides, in two currencies: a conversion of his family debt, matched to the
 * owner's conversion by both legs (D-178), never created.
 * <li>A row between FAMILY_DEBT and UNALLOCATED: an income is a benefit row, his share of a family income another
 * member received, which isn't posted but checked per month (D-179); an expense paid straight from FAMILY_DEBT is an
 * error.
 * <li>A row between FAMILY_DEBT and another account of his: a settlement, matched to an existing one (D-178), never
 * created. The Family flag is ignored on it.
 * <li>A family row with an item: a family record of his, which an import of his rows wrote before for his seat
 * (D-142), with his payment side on the account of its other side (D-181); one that no import wrote yet is created,
 * paid by him and split 50/50 with the other member, as the owner's import made his rows.
 * <li>Every other row, the Family flag on a row without an item included (D-72): a personal entry.
 * </ul>
 * The family records' content hash is the one D-151's upload gave his rows: ID, date, item, the side on UNALLOCATED
 * with its currency and amount, so that the records the owner's import wrote from his rows are recognised as they were
 * (D-181); his payment side has a hash of its own ({@link #sideHash}). No message holds a value of a row.
 */
final class MemberPlanner {

    /** D-179: an expense paid straight from FAMILY_DEBT. */
    static final String EXPENSE_FROM_FAMILY_DEBT = "an expense paid straight from FAMILY_DEBT: split it in Excel into "
            + "a settlement into an account of yours and the expense from that account (D-179)";

    /** What a row of his is to the import. */
    sealed interface Plan permits Personal, FamilyRow, SettlementRow, ConversionRow, Benefit, Refused, Skipped {

        TransactionRow row();
    }

    record Personal(TransactionRow row) implements Plan {
    }

    /**
     * One of his family rows.
     *
     * @param spec the record as his row makes it, with D-151's hash and his reference (D-142)
     * @param sideHash his payment side's hash (D-181)
     */
    record FamilyRow(TransactionRow row, RecordSpec spec, String sideHash, List<String> warnings) implements Plan {
    }

    /** One of his settlement rows, as a settlement between him and the other member, his side on {@code accountCode}. */
    record SettlementRow(TransactionRow row, SettlementSpec spec, String sideHash, List<String> warnings)
            implements Plan {
    }

    /** One of his conversions: its two legs, each a settlement through his FX_EXCHANGE (D-71). */
    record ConversionRow(TransactionRow row, List<SettlementSpec> legs, String sideHash) implements Plan {
    }

    /**
     * A benefit row (D-179): his share of a family income another member received, in {@code currency}, as the amount
     * his FAMILY_DEBT went down by.
     */
    record Benefit(TransactionRow row, String currency, BigDecimal amount) implements Plan {
    }

    record Refused(TransactionRow row, List<String> errors) implements Plan {
    }

    record Skipped(TransactionRow row, String reason) implements Plan {
    }

    private final Map<String, AccountRef> accountsByName;
    private final ImportPlanner planner;

    /**
     * @param accountsByName his accounts by their names in lower case
     * @param categoriesByName the categories file's rows by name
     * @param memberId his member id in the family budget: his seat
     * @param otherId the other member, with whom his new family records are split 50/50
     */
    MemberPlanner(Map<String, AccountRef> accountsByName, Map<String, CategoryRow> categoriesByName, long memberId,
            long otherId) {
        this.accountsByName = accountsByName;
        // The owner's rules, with him in the owner's place: he pays his records and is a side of his settlements.
        this.planner = new ImportPlanner(accountsByName, categoriesByName, memberId, otherId);
    }

    Plan plan(TransactionRow row) {
        AccountRef debit = accountsByName.get(row.debit().toLowerCase(Locale.ROOT));
        AccountRef credit = accountsByName.get(row.credit().toLowerCase(Locale.ROOT));
        boolean debt = debit != null && debit.familyDebt() || credit != null && credit.familyDebt();
        if (!debt) {
            return familyOrPersonal(row);
        }
        if (debit == null || credit == null) {
            return new Refused(row, List.of((debit == null ? "Debit" : "Credit") + " is not in the accounts file"));
        }
        if (zero(row)) {
            return new Skipped(row, "Sum is 0 (D-72)");
        }
        // D-178: the Family flag on a transfer is ignored.
        TransactionRow transfer = row.withoutFamily();
        if (debit.familyDebt() && credit.familyDebt()) {
            return switch (planner.plan(transfer)) {
                case ImportPlanner.Family family -> new ConversionRow(row, family.items().stream()
                        .map(SettlementSpec.class::cast).map(MemberPlanner::member).toList(), sideHash(row));
                case ImportPlanner.Failed failed -> new Refused(row, failed.errors());
                default -> throw new IllegalStateException("A conversion is two settlements or an error");
            };
        }
        AccountRef other = debit.familyDebt() ? credit : debit;
        if (other.is(AccountRole.UNALLOCATED)) {
            if (debit.familyDebt()) {
                if (!row.debitCurrencyOrRow().equals(row.creditCurrencyOrRow())) {
                    return new Refused(row, List.of("a row on FAMILY_DEBT and UNALLOCATED has two currencies"));
                }
                return new Benefit(row, row.creditCurrencyOrRow(), row.creditAmount());
            }
            return new Refused(row, List.of(EXPENSE_FROM_FAMILY_DEBT));
        }
        return switch (planner.plan(transfer)) {
            case ImportPlanner.Family family -> new SettlementRow(row, member((SettlementSpec) family.items().getFirst()),
                    sideHash(row), family.warnings());
            case ImportPlanner.Failed failed -> new Refused(row, failed.errors());
            default -> throw new IllegalStateException("A settlement row is a settlement or an error");
        };
    }

    private Plan familyOrPersonal(TransactionRow row) {
        if (!row.family() || EntryMapper.NO_CATEGORY.equals(row.item())) {
            return new Personal(row.family() ? row.withoutFamily() : row);
        }
        return switch (planner.plan(row)) {
            case ImportPlanner.Family family -> {
                RecordSpec spec = (RecordSpec) family.items().getFirst();
                yield new FamilyRow(row, new RecordSpec(Source.MEMBER_FAMILY, spec.row(), spec.excelId(), spec.ref(),
                        uploadHash(row, accountsByName), spec.type(), spec.refund(), spec.date(), spec.categoryCode(),
                        spec.categoryName(), spec.categoryType(), spec.amount(), spec.currency(), true,
                        spec.accountCode(), spec.accountCurrency(), spec.accountAmount(), spec.counterparty(),
                        spec.payee(), spec.note(), false, spec.drift()), sideHash(row), family.warnings());
            }
            case ImportPlanner.Skipped skipped -> new Skipped(row, skipped.reason());
            case ImportPlanner.Failed failed -> new Refused(row, failed.errors());
            default -> throw new IllegalStateException("A family row is a record, skipped or an error");
        };
    }

    private static SettlementSpec member(SettlementSpec spec) {
        return new SettlementSpec(Source.MEMBER_SETTLEMENT, spec.row(), spec.excelId(), spec.ref(), spec.hash(),
                spec.date(), spec.amount(), spec.currency(), spec.ownerPays(), spec.accountCode(),
                spec.throughExchange(), spec.accountCurrency(), spec.accountAmount(), spec.counterparty(), spec.drift());
    }

    private static boolean zero(TransactionRow row) {
        return row.sum().signum() == 0 && row.debitAmount().signum() == 0 && row.creditAmount().signum() == 0;
    }

    /**
     * D-151's columns of a family row, as the import page uploaded them for his seat (PartnerFilter): the date, the
     * item, the side on UNALLOCATED ("Debit" or "Credit") with that side's currency and amount, hashed as
     * {@link PartnerRow} hashes them. So a record the owner's import wrote from his row is recognised unchanged as long
     * as those columns are (D-181), whatever else of the row changed.
     */
    static String uploadHash(TransactionRow row, Map<String, AccountRef> accountsByName) {
        AccountRef debit = accountsByName.get(row.debit().toLowerCase(Locale.ROOT));
        AccountRef credit = accountsByName.get(row.credit().toLowerCase(Locale.ROOT));
        String side = "";
        String currency = row.currency();
        BigDecimal amount = row.sum();
        if (debit != null && debit.is(AccountRole.UNALLOCATED)) {
            side = PartnerRow.DEBIT;
            if (row.debitCurrency() != null) {
                currency = row.debitCurrency();
                amount = row.debitAmount();
            }
        } else if (credit != null && credit.is(AccountRole.UNALLOCATED)) {
            side = PartnerRow.CREDIT;
            if (row.creditCurrency() != null) {
                currency = row.creditCurrency();
                amount = row.creditAmount();
            }
        }
        return ImportSync.contentHash(row.date().toString(), row.item(), side, currency, TransactionRow.plain(amount));
    }

    /**
     * His side of a family row or a settlement (D-181): both accounts, their currencies and amounts, LOAN_CA, and the
     * payee and note his payment entry takes (Counteragent, Comment), so that an edit of any of them in Excel shows.
     * The date and the family part are the record's, whose hash is {@link #uploadHash}.
     */
    static String sideHash(TransactionRow row) {
        return ImportSync.contentHash("side", row.debit(), row.debitCurrencyOrRow(),
                TransactionRow.plain(row.debitAmount()), row.credit(), row.creditCurrencyOrRow(),
                TransactionRow.plain(row.creditAmount()), row.loanCounterparty(), row.counteragent(), row.comment());
    }
}
