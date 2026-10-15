package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.family.ShareSplit;
import com.example.financetracker.ledger.family.ShareSplit.Share;
import com.example.financetracker.ledger.family.ShareSplit.Weight;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;

/**
 * What each source row becomes (D-82, option C), in plain Java: a personal entry of the owner's, or the family budget's
 * records and settlements, which the owner imports from his seat and the partner's from the partner's seat (D-142).
 * <ul>
 * <li>The owner's row without the family flag and without FAMILY_DEBT, or with the flag and no item (D-72): a
 * personal entry ({@link EntryMapper}).
 * <li>The owner's family row: a family expense, refund (D-79) or income in CUR_FAMILY (D-74), paid or received by the
 * owner from the account of its other side (with the counterparty of an account that requires one, D-80; the payee,
 * D-81; the comment as his private note), split 50/50 between him and the seat.
 * <li>The owner's row on FAMILY_DEBT: against UNALLOCATED, either one of his EUR incomes BENEFIT, OTHERINC or CASHBACK,
 * which the partner's family rows carry and which isn't imported (D-84), or a record paid by the seat, 100 % his
 * (D-73; the one without a category in OTHERINC); against FAMILY_DEBT in another currency, a conversion: two
 * settlements through his FX_EXCHANGE (D-71); against any other account, a settlement between him and the seat on that
 * account (D-148 for one that requires a counterparty).
 * <li>Each month's family lump in the owner's daily change file before the partner's ledger starts: a record paid by
 * the seat of twice the lump, 50/50, in "Без детализации" (D-83).
 * <li>The partner's family row: a record paid or received by the seat, 50/50.
 * </ul>
 * Amounts are rounded half up to the currency's minor unit (D-74), and each record's split is D-12's. What that makes
 * the owner's balances differ from Excel's is each item's {@link Drift}, explained in the reconciliation (D-68). Rows
 * with Sum 0 are skipped (D-72). No message holds a value of a row.
 */
final class ImportPlanner {

    /** D-83's category of the lumps, with the code an agent's choice (D3b). */
    static final String LUMP_CATEGORY_CODE = "NO_DETAIL";
    static final String LUMP_CATEGORY_NAME = "Без детализации";
    /** D-73: the owner's FAMILY_DEBT row against UNALLOCATED without a category is an income in this category. */
    static final String OTHER_INCOME = "OTHERINC";
    /** D-84: the owner's EUR incomes on FAMILY_DEBT that the partner's family rows carry, which aren't imported. */
    static final Set<String> CARRIED_BY_THE_PARTNER = Set.of("BENEFIT", "OTHERINC", "CASHBACK");
    static final String CARRIED_CURRENCY = "EUR";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    enum Source {
        /** The owner's personal rows. */
        OWNER_PERSONAL,
        /** The owner's family rows. */
        OWNER_FAMILY,
        /** The owner's rows on FAMILY_DEBT: settlements and conversions. */
        OWNER_SETTLEMENT,
        /** The owner's rows on FAMILY_DEBT against UNALLOCATED, the seat's records 100 % his (D-73). */
        OWNER_SEAT,
        /** The monthly lumps before the partner's ledger starts (D-83). */
        OWNER_LUMP,
        /** The partner's family rows. */
        PARTNER,
        /** A member's own personal rows, once he took his seat (D-175). */
        MEMBER_PERSONAL,
        /** A member's own family rows: his records, his payment side on them (D-181). */
        MEMBER_FAMILY,
        /** A member's own settlement rows and conversions, matched to existing settlements (D-178). */
        MEMBER_SETTLEMENT,
        /** A member's own benefit rows, checked and not posted (D-179). */
        MEMBER_BENEFIT
    }

    /** A record or a settlement of the family budget, by codes and names, which the import resolves to ids. */
    sealed interface FamilyItem permits RecordSpec, SettlementSpec {

        Source source();

        /** The row in its file, where the header is row 1; 0 for a lump. */
        int row();

        /** What names the row: its Excel ID, or for a lump {@code PL:<month>:<currency>}. */
        String excelId();

        /** Its reference in the family budget (D-142): {@code xls:<seat's member id>:<excelId>}. */
        String ref();

        String hash();

        LocalDate date();

        String currency();

        List<Drift> drift();

        /** For the report: EXPENSE, REFUND, INCOME, SETTLEMENT or CONVERSION. */
        String kind();
    }

    /**
     * A family expense or income.
     *
     * @param ownerPays the owner paid or received it, from the account {@code accountCode}; else the seat did
     * @param accountCurrency the paying currency, always named, so that no account's default decides it (D-89)
     * @param accountAmount what went from or into the account in its currency, when that isn't the record's; else null
     * @param ownerOnly the whole amount is the owner's share (D-73); else 50/50 between the owner and the seat
     */
    record RecordSpec(Source source, int row, String excelId, String ref, String hash, String type, boolean refund,
            LocalDate date, String categoryCode, String categoryName, CategoryType categoryType, BigDecimal amount,
            String currency, boolean ownerPays, String accountCode, String accountCurrency, BigDecimal accountAmount,
            String counterparty, String payee, String note, boolean ownerOnly, List<Drift> drift)
            implements FamilyItem {

        @Override
        public String kind() {
            return refund ? "REFUND" : type;
        }
    }

    /**
     * A settlement between the owner and the seat, the owner's side on {@code accountCode}, or on his FX_EXCHANGE for a
     * conversion (D-71).
     *
     * @param ownerPays the owner paid the seat, which lowers his debt; else the seat paid him
     */
    record SettlementSpec(Source source, int row, String excelId, String ref, String hash, LocalDate date,
            BigDecimal amount, String currency, boolean ownerPays, String accountCode, boolean throughExchange,
            String accountCurrency, BigDecimal accountAmount, String counterparty, List<Drift> drift)
            implements FamilyItem {

        @Override
        public String kind() {
            return throughExchange ? "CONVERSION" : "SETTLEMENT";
        }
    }

    /** What an owner's row is to the import. */
    sealed interface Planned permits Personal, Family, NotImported, Skipped, Failed {

        List<String> warnings();
    }

    record Personal(TransactionRow row, List<String> warnings) implements Planned {
    }

    record Family(List<FamilyItem> items, List<String> warnings) implements Planned {
    }

    /**
     * A row the decisions leave out (D-84).
     *
     * @param carried the row's date, currency and amount, for D-169's reconciliation
     */
    record NotImported(String reason, Reconciler.Carried carried, List<String> warnings) implements Planned {
    }

    record Skipped(String reason, List<String> warnings) implements Planned {
    }

    record Failed(List<String> errors, List<String> warnings) implements Planned {
    }

    private final Map<String, AccountRef> accountsByName;
    private final Map<String, CategoryRow> categoriesByName;
    private final long ownerId;
    private final long seatId;

    /**
     * @param accountsByName the owner's accounts by their names in lower case ({@link Chart#accountsByName})
     * @param categoriesByName the categories file's rows by name
     * @param ownerId the owner's member id in the family budget, his seat
     * @param seatId the partner's seat
     */
    ImportPlanner(Map<String, AccountRef> accountsByName, Map<String, CategoryRow> categoriesByName, long ownerId,
            long seatId) {
        this.accountsByName = accountsByName;
        this.categoriesByName = categoriesByName;
        this.ownerId = ownerId;
        this.seatId = seatId;
    }

    /** Whether the owner's row goes to the family budget: a family row with an item, or one on FAMILY_DEBT. */
    static boolean isFamily(TransactionRow row, Map<String, AccountRef> accountsByName) {
        if (row.family()) {
            return !EntryMapper.NO_CATEGORY.equals(row.item());
        }
        AccountRef debit = accountsByName.get(row.debit().toLowerCase(Locale.ROOT));
        AccountRef credit = accountsByName.get(row.credit().toLowerCase(Locale.ROOT));
        return debit != null && debit.familyDebt() || credit != null && credit.familyDebt();
    }

    Planned plan(TransactionRow row) {
        if (row.family() && EntryMapper.NO_CATEGORY.equals(row.item())) {
            return new Personal(row.withoutFamily(), List.of());
        }
        if (!isFamily(row, accountsByName)) {
            return new Personal(row, List.of());
        }
        if (zero(row)) {
            return new Skipped("Sum is 0 (D-72)", List.of());
        }
        AccountRef debit = accountsByName.get(row.debit().toLowerCase(Locale.ROOT));
        AccountRef credit = accountsByName.get(row.credit().toLowerCase(Locale.ROOT));
        List<String> errors = new ArrayList<>();
        if (debit == null) {
            errors.add("Debit is not in the accounts file");
        }
        if (credit == null) {
            errors.add("Credit is not in the accounts file");
        }
        if (!errors.isEmpty()) {
            return new Failed(errors, List.of());
        }
        return row.family() ? familyRow(row, debit, credit) : debtRow(row, debit, credit);
    }

    private static boolean zero(TransactionRow row) {
        return row.sum().signum() == 0 && row.debitAmount().signum() == 0 && row.creditAmount().signum() == 0;
    }

    // --- The owner's family rows -------------------------------------------------------------------------------------

    private Planned familyRow(TransactionRow row, AccountRef debit, AccountRef credit) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean expense = debit.is(com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED);
        boolean income = credit.is(com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED);
        if (expense == income) {
            return new Failed(List.of("a family row with an item has to have UNALLOCATED on exactly one side"),
                    warnings);
        }
        CategoryRow category = categoriesByName.get(row.item());
        if (category == null) {
            errors.add("Exp. Item is not in the categories file");
        } else if (category.type() != (expense ? CategoryType.EXPENSE : CategoryType.INCOME)) {
            errors.add(expense ? "a family expense needs an expense category" : "a family income needs an income category");
        }
        AccountRef paying = expense ? credit : debit;
        String currency = expense ? row.debitCurrencyOrRow() : row.creditCurrencyOrRow();
        BigDecimal unallocated = expense ? row.debitAmount() : row.creditAmount();
        String payingCurrency = expense ? row.creditCurrencyOrRow() : row.debitCurrencyOrRow();
        BigDecimal paid = expense ? row.creditAmount() : row.debitAmount();
        if (!currency.equals(row.familyCurrency())) {
            errors.add("CUR_FAMILY isn't the currency of the UNALLOCATED side");
        }
        if (paying.familyDebt()) {
            errors.add("a family row is paid from FAMILY_DEBT");
        }
        if (income && unallocated.signum() < 0) {
            errors.add("a family income with a negative amount");
        }
        if (unallocated.signum() != paid.signum() && paid.signum() != 0) {
            errors.add("the two sides of a family row have different signs");
        }
        if (payingCurrency.equals(currency) && paid.compareTo(unallocated) != 0
                && minorUnits(paid.abs(), currency).compareTo(minorUnits(unallocated.abs(), currency)) != 0) {
            errors.add("the two sides of a family row differ, in one currency");
        }
        BigDecimal amount = minorUnits(unallocated.abs(), currency);
        if (amount.signum() == 0) {
            errors.add("the family part rounds to 0 in the currency's minor unit");
        }
        if (!errors.isEmpty()) {
            return new Failed(errors, warnings);
        }
        boolean refund = expense && unallocated.signum() < 0;
        int sign = refund ? -1 : 1;
        BigDecimal accountAmount = payingCurrency.equals(currency) ? null : minorUnits(paid.abs(), payingCurrency);
        String counterparty = null;
        if (paying.requiresCounterparty()) {
            counterparty = row.loanCounterparty();
            if (counterparty == null) {
                counterparty = EntryMapper.UNASSIGNED;
                warnings.add("LOAN_CA is empty on a family row paid from an account that requires a counterparty: "
                        + "'%s' is used".formatted(EntryMapper.UNASSIGNED));
            }
        }
        String note = row.comment() != null && !row.comment().equals(row.counteragent()) ? row.comment() : null;

        Share[] halves = halves(amount, currency, ownerId);
        BigDecimal own = halves[0].amount();
        BigDecimal seat = halves[1].amount();
        Drift.Reason reason = amount.compareTo(unallocated.abs()) == 0 ? Drift.Reason.SHARES : Drift.Reason.MINOR_UNIT;
        List<Drift> drift = new ArrayList<>();
        BigDecimal part = row.familyPart();
        // The displayed changes: the debt account is the seat's share, UNALLOCATED the owner's, as the posting service
        // posts them (D-7); Excel moves FAMILY_DEBT by the family part and UNALLOCATED by the rest.
        BigDecimal appDebt = expense ? seat.multiply(BigDecimal.valueOf(-sign)) : seat;
        BigDecimal appEquity = expense ? own.multiply(BigDecimal.valueOf(-sign)) : own;
        BigDecimal excelEquity = (expense ? unallocated.negate() : unallocated).subtract(part);
        add(drift, "FAMILY_DEBT", currency, row.date(), appDebt.subtract(part), reason);
        add(drift, "UNALLOCATED", currency, row.date(), appEquity.subtract(excelEquity), reason);
        // The paying account: the record's amount, or what went from or into it in its own currency.
        BigDecimal appPaid = (accountAmount == null ? amount : accountAmount).multiply(BigDecimal.valueOf(sign));
        BigDecimal appPosting = expense ? appPaid.negate() : appPaid;
        BigDecimal excelPosting = expense ? paid.negate() : paid;
        int displayed = paying.type() == AccountType.ASSET ? 1 : -1;
        add(drift, paying.code(), payingCurrency, row.date(),
                appPosting.subtract(excelPosting).multiply(BigDecimal.valueOf(displayed)), Drift.Reason.MINOR_UNIT);

        String excelId = String.valueOf(row.id());
        return new Family(List.of(new RecordSpec(Source.OWNER_FAMILY, row.row(), excelId, ref(ownerId, excelId),
                row.contentHash(), expense ? "EXPENSE" : "INCOME", refund, row.date(), category.code(),
                category.name(), category.type(), amount, currency, true, paying.code(), payingCurrency, accountAmount,
                counterparty, row.counteragent(), note, false, drift)), warnings);
    }

    // --- The owner's rows on FAMILY_DEBT -----------------------------------------------------------------------------

    private Planned debtRow(TransactionRow row, AccountRef debit, AccountRef credit) {
        if (debit.familyDebt() && credit.familyDebt()) {
            return conversion(row);
        }
        boolean debtIsDebit = debit.familyDebt();
        AccountRef other = debtIsDebit ? credit : debit;
        String currency = debtIsDebit ? row.debitCurrencyOrRow() : row.creditCurrencyOrRow();
        // FAMILY_DEBT is a liability: a debit lowers it, a credit raises it (Excel's displayed sign).
        BigDecimal debt = debtIsDebit ? row.debitAmount().negate() : row.creditAmount();
        String otherCurrency = debtIsDebit ? row.creditCurrencyOrRow() : row.debitCurrencyOrRow();
        BigDecimal otherAmount = debtIsDebit ? row.creditAmount() : row.debitAmount();
        if (other.is(com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED)) {
            return seatRecord(row, currency, debt, otherCurrency);
        }
        return settlement(row, other, currency, debt, otherCurrency, otherAmount, debtIsDebit);
    }

    /** D-84 and D-73: the owner's row on FAMILY_DEBT against UNALLOCATED. */
    private Planned seatRecord(TransactionRow row, String currency, BigDecimal debt, String otherCurrency) {
        List<String> warnings = new ArrayList<>();
        boolean noCategory = EntryMapper.NO_CATEGORY.equals(row.item());
        CategoryRow category = noCategory ? categoryByCode(OTHER_INCOME) : categoriesByName.get(row.item());
        if (category == null) {
            return new Failed(List.of(noCategory ? "the categories file has no %s, for a row on FAMILY_DEBT without a "
                    .formatted(OTHER_INCOME) + "category (D-73)" : "Exp. Item is not in the categories file"),
                    warnings);
        }
        if (!currency.equals(otherCurrency)) {
            return new Failed(List.of("a row on FAMILY_DEBT and UNALLOCATED has two currencies"), warnings);
        }
        boolean lowersTheDebt = debt.signum() < 0;
        if (currency.equals(CARRIED_CURRENCY) && lowersTheDebt && category.type() == CategoryType.INCOME
                && CARRIED_BY_THE_PARTNER.contains(category.code())) {
            return new NotImported("an income on FAMILY_DEBT that the partner's family rows carry (D-84)",
                    new Reconciler.Carried(row.date(), currency, debt.negate()), warnings);
        }
        String type;
        boolean refund = false;
        if (category.type() == CategoryType.EXPENSE) {
            type = "EXPENSE";
            refund = lowersTheDebt;
        } else if (lowersTheDebt) {
            type = "INCOME";
        } else {
            return new Failed(List.of("an income category on a row that raises FAMILY_DEBT"), warnings);
        }
        BigDecimal amount = minorUnits(debt.abs(), currency);
        BigDecimal signed = lowersTheDebt ? amount.negate() : amount;
        List<Drift> drift = new ArrayList<>();
        // The whole amount is the owner's share: his debt moves by it, and UNALLOCATED the other way.
        add(drift, "FAMILY_DEBT", currency, row.date(), signed.subtract(debt), Drift.Reason.MINOR_UNIT);
        add(drift, "UNALLOCATED", currency, row.date(), debt.subtract(signed), Drift.Reason.MINOR_UNIT);
        String excelId = String.valueOf(row.id());
        return new Family(List.of(new RecordSpec(Source.OWNER_SEAT, row.row(), excelId, ref(ownerId, excelId),
                row.contentHash(), type, refund, row.date(), category.code(), category.name(), category.type(), amount,
                currency, false, null, null, null, null, null, null, true, drift)), warnings);
    }

    /** A settlement between the owner and the seat, his side on the row's other account (D-148 too). */
    private Planned settlement(TransactionRow row, AccountRef other, String currency, BigDecimal debt,
            String otherCurrency, BigDecimal otherAmount, boolean debtIsDebit) {
        List<String> warnings = new ArrayList<>();
        BigDecimal amount = minorUnits(debt.abs(), currency);
        if (amount.signum() == 0) {
            return new Failed(List.of("the settlement rounds to 0 in the currency's minor unit"), warnings);
        }
        BigDecimal accountAmount = otherCurrency.equals(currency) ? null : minorUnits(otherAmount.abs(), otherCurrency);
        String counterparty = null;
        if (other.requiresCounterparty()) {
            counterparty = row.loanCounterparty();
            if (counterparty == null) {
                counterparty = EntryMapper.UNASSIGNED;
                warnings.add("LOAN_CA is empty on a settlement with an account that requires a counterparty: '%s' is used"
                        .formatted(EntryMapper.UNASSIGNED));
            }
        }
        boolean ownerPays = debt.signum() < 0;
        List<Drift> drift = new ArrayList<>();
        BigDecimal signed = ownerPays ? amount.negate() : amount;
        add(drift, "FAMILY_DEBT", currency, row.date(), signed.subtract(debt), Drift.Reason.MINOR_UNIT);
        // The other account: the posting of its side, the account's amount or the settlement's.
        BigDecimal appSide = accountAmount == null ? amount : accountAmount;
        BigDecimal excelPosting = debtIsDebit ? otherAmount.negate() : otherAmount;
        BigDecimal appPosting = excelPosting.signum() < 0 ? appSide.negate() : appSide;
        int displayed = other.type() == AccountType.ASSET ? 1 : -1;
        add(drift, other.code(), otherCurrency, row.date(),
                appPosting.subtract(excelPosting).multiply(BigDecimal.valueOf(displayed)), Drift.Reason.MINOR_UNIT);
        String excelId = String.valueOf(row.id());
        return new Family(List.of(new SettlementSpec(Source.OWNER_SETTLEMENT, row.row(), excelId, ref(ownerId, excelId),
                row.contentHash(), row.date(), amount, currency, ownerPays, other.code(), false, otherCurrency,
                accountAmount, counterparty, drift)), warnings);
    }

    /** D-71: a conversion of the family debt into another currency, as two settlements through FX_EXCHANGE. */
    private Planned conversion(TransactionRow row) {
        String from = row.debitCurrencyOrRow();
        String to = row.creditCurrencyOrRow();
        if (from.equals(to)) {
            return new Failed(List.of("a row with FAMILY_DEBT on both sides in one currency"), List.of());
        }
        List<FamilyItem> items = new ArrayList<>();
        for (boolean debitSide : List.of(true, false)) {
            String currency = debitSide ? from : to;
            BigDecimal debt = debitSide ? row.debitAmount().negate() : row.creditAmount();
            BigDecimal amount = minorUnits(debt.abs(), currency);
            BigDecimal signed = debt.signum() < 0 ? amount.negate() : amount;
            List<Drift> drift = new ArrayList<>();
            add(drift, "FAMILY_DEBT", currency, row.date(), signed.subtract(debt), Drift.Reason.MINOR_UNIT);
            String excelId = row.id() + ":" + currency;
            items.add(new SettlementSpec(Source.OWNER_SETTLEMENT, row.row(), excelId, ref(ownerId, excelId),
                    ImportSync.contentHash(row.contentHash(), currency), row.date(), amount, currency,
                    debt.signum() < 0, "FX_EXCHANGE", true, currency, null, null, drift));
        }
        return new Family(items, List.of());
    }

    // --- D-83's lumps ------------------------------------------------------------------------------------------------

    /**
     * The seat's records for the owner's monthly family lumps before the partner's ledger starts (D-83): each non-zero
     * lump of a month before {@code partnerStart}'s, twice its amount, 50/50, on its date.
     */
    List<RecordSpec> lumps(DailyFile change, LocalDate partnerStart) {
        List<RecordSpec> records = new ArrayList<>();
        LocalDate before = partnerStart.withDayOfMonth(1);
        change.lumpColumns().forEach((column, currency) -> change.values().headMap(before, false).forEach((date, cells) -> {
            BigDecimal lump = cells.getOrDefault(column, BigDecimal.ZERO);
            BigDecimal amount = minorUnits(lump.abs().multiply(BigDecimal.TWO), currency);
            // A lump that is 0 at the minor unit, such as Excel's floating-point residue, is none.
            if (amount.signum() == 0) {
                return;
            }
            boolean refund = lump.signum() < 0;
            int sign = refund ? -1 : 1;
            BigDecimal own = halves(amount, currency, seatId)[0].amount().multiply(BigDecimal.valueOf(sign));
            List<Drift> drift = new ArrayList<>();
            add(drift, "FAMILY_DEBT", currency, date, own.subtract(lump), Drift.Reason.SHARES);
            add(drift, "UNALLOCATED", currency, date, lump.subtract(own), Drift.Reason.SHARES);
            String excelId = "PL:" + MONTH.format(date) + ":" + currency;
            records.add(new RecordSpec(Source.OWNER_LUMP, 0, excelId, ref(ownerId, excelId),
                    ImportSync.contentHash("PL", date.toString(), currency, TransactionRow.plain(lump)), "EXPENSE",
                    refund, date, LUMP_CATEGORY_CODE, LUMP_CATEGORY_NAME, CategoryType.EXPENSE, amount, currency, false,
                    null, null, null, null, null, null, false, drift));
        }));
        return records;
    }

    // --- The partner's family rows -----------------------------------------------------------------------------------

    /**
     * D-169: a partner's family income that the owner's D-84 rows carry the other half of (BENEFIT, OTHERINC, CASHBACK
     * in EUR), as its date, currency and amount; null for any other row.
     */
    Reconciler.Carried carried(PartnerRow row) {
        CategoryRow category = categoriesByName.get(row.item());
        if (row.expense() || row.amount().signum() <= 0 || category == null
                || !CARRIED_BY_THE_PARTNER.contains(category.code()) || !row.currency().equals(CARRIED_CURRENCY)) {
            return null;
        }
        return new Reconciler.Carried(row.date(), row.currency(), row.amount());
    }

    Planned partner(PartnerRow row) {
        if (row.amount().signum() == 0) {
            return new Skipped("Sum is 0 (D-72)", List.of());
        }
        CategoryRow category = categoriesByName.get(row.item());
        if (category == null) {
            return new Failed(List.of("Exp. Item is not in the categories file"), List.of());
        }
        if (category.type() != (row.expense() ? CategoryType.EXPENSE : CategoryType.INCOME)) {
            return new Failed(List.of(row.expense() ? "a family expense needs an expense category"
                    : "a family income needs an income category"), List.of());
        }
        if (!row.expense() && row.amount().signum() < 0) {
            return new Failed(List.of("a family income with a negative amount"), List.of());
        }
        BigDecimal amount = minorUnits(row.amount().abs(), row.currency());
        if (amount.signum() == 0) {
            return new Failed(List.of("the family part rounds to 0 in the currency's minor unit"), List.of());
        }
        boolean refund = row.expense() && row.amount().signum() < 0;
        int sign = refund ? -1 : 1;
        BigDecimal own = halves(amount, row.currency(), seatId)[0].amount().multiply(BigDecimal.valueOf(sign));
        // Excel's owner side: half of the row, in the monthly lump (or, for BENEFIT, CASHBACK and OTHERINC, in his own
        // rows on FAMILY_DEBT, D-84).
        BigDecimal half = row.amount().divide(BigDecimal.TWO);
        BigDecimal appDebt = row.expense() ? own : own.negate();
        BigDecimal excelDebt = row.expense() ? half : half.negate();
        Drift.Reason reason = amount.compareTo(row.amount().abs()) == 0 ? Drift.Reason.SHARES : Drift.Reason.MINOR_UNIT;
        List<Drift> drift = new ArrayList<>();
        add(drift, "FAMILY_DEBT", row.currency(), row.date(), appDebt.subtract(excelDebt), reason);
        add(drift, "UNALLOCATED", row.currency(), row.date(), excelDebt.subtract(appDebt), reason);
        String excelId = String.valueOf(row.id());
        return new Family(List.of(new RecordSpec(Source.PARTNER, row.row(), excelId, ref(seatId, excelId),
                row.contentHash(), row.expense() ? "EXPENSE" : "INCOME", refund, row.date(), category.code(),
                category.name(), category.type(), amount, row.currency(), false, null, null, null, null, null, null,
                false, drift)), List.of());
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------

    /** D-142's reference of a family row: the seat whose file it comes from, and the row's Excel ID. */
    static String ref(long seat, String excelId) {
        return "xls:" + seat + ":" + excelId;
    }

    /** D-12's halves, the owner's first: each cut down to the minor unit, the rest to the payer. */
    private Share[] halves(BigDecimal amount, String currency, long payerId) {
        List<Share> shares = ShareSplit.percent(amount, ShareSplit.minorUnit(currency),
                List.of(new Weight(ownerId, 5000, 5000), new Weight(seatId, 5000, 5000)), payerId);
        return new Share[] {shares.get(0), shares.get(1)};
    }

    /** D-74: an amount rounded half up to the currency's minor unit. */
    static BigDecimal minorUnits(BigDecimal amount, String currency) {
        return amount.setScale(ShareSplit.minorUnit(currency), RoundingMode.HALF_UP);
    }

    private CategoryRow categoryByCode(String code) {
        return categoriesByName.values().stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    private static void add(List<Drift> drift, String code, String currency, LocalDate date, BigDecimal amount,
            Drift.Reason reason) {
        if (amount.signum() != 0) {
            drift.add(new Drift(code, currency, date, amount, reason));
        }
    }
}
