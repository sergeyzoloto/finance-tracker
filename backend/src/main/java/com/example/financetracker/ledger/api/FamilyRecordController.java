package com.example.financetracker.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyBalances;
import com.example.financetracker.ledger.family.FamilyJournalPage;
import com.example.financetracker.ledger.family.FamilyRecordChanges;
import com.example.financetracker.ledger.family.FamilyRecordPage;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilyRecordView;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.NewFamilyRecord;
import com.example.financetracker.ledger.family.NewSettlement;
import com.example.financetracker.ledger.family.RecordSplit;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A family ledger's records, balances and change journal (F4a; ADR 0003, topics D, H and I), for its ACTIVE members
 * only: anyone else gets 404, the answer for a family ledger that doesn't exist, for reads and writes alike. Records are
 * family expenses and settlements (F4d) in the base currency; incomes come with F4d too, other currencies with F4e. A
 * settlement is recorded through {@code /settlements}, the resource ADR 0003 names for it, and is read, changed and
 * deleted as a record. No answer holds a member's accounts, personal categories or personal entries (C4), except the
 * caller's own payment or side of a settlement, for their eyes only ({@code yourPayment}, F4c).
 * <p>
 * Only while the feature switch is on (D-25, {@link FamilySwitch}): otherwise these paths are unknown and answer 404.
 */
@RestController
@RequestMapping("/api/family-ledgers/{ledgerId}")
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class FamilyRecordController {

    /**
     * A family expense (C1).
     *
     * @param amount in the family's base currency, above 0, with at most its minor unit's decimals
     * @param payerMemberId who paid: yourself, if you paid, or a member without an account (D-14)
     * @param paymentAccountId if you paid: the account of your personal ledger you paid with. It stays private: no
     *        answer about the record names it (D-16)
     * @param paymentLater if you paid: true to specify the account later; the payment goes to "Payments without a
     *        specified account" (D-14)
     * @param split how the amount is split; the family budget's rule if left out
     * @param privateNote if you paid: a note that only your payment entry in your personal ledger holds; no family
     *        answer and no journal names it (F4c, C2)
     */
    record NewRecord(@NotNull LocalDate date, @NotNull Long categoryId, @NotNull BigDecimal amount,
            @Size(max = 500) String comment, @NotNull Long payerMemberId, Long paymentAccountId, Boolean paymentLater,
            @Valid Split split, @Size(max = 500) String privateNote) {
    }

    /**
     * How a record's amount is split (D-12).
     *
     * @param method RULE (the family budget's default rule), PERCENT, AMOUNT or ONE_MEMBER
     * @param shares for PERCENT, every member's share in basis points (2500 is 25.00 %), summing to 10000; for AMOUNT,
     *        every member's amount, summing to the record's
     * @param memberId for ONE_MEMBER, the member the whole amount is on
     */
    record Split(@NotNull RecordSplit.Method method, List<@Valid @NotNull Share> shares, Long memberId) {

        RecordSplit toSplit() {
            return new RecordSplit(method, shares == null ? List.of() : shares.stream()
                    .map(share -> new RecordSplit.ShareInput(share.memberId(), share.basisPoints(), share.amount()))
                    .toList(), memberId);
        }
    }

    /**
     * A settlement (D2, D-24): one member pays another.
     *
     * @param amount in the family's base currency, above 0, with at most its minor unit's decimals
     * @param payerMemberId who paid
     * @param payeeMemberId who received. You are one of the two, unless you are an owner recording a settlement between
     *        two members without an account
     * @param paymentAccountId if you pay or receive: the account of your personal ledger it went from or into. It stays
     *        private: no answer but yours names it (D-16)
     * @param paymentLater if you pay or receive: true to specify the account later; your side goes to "Payments without
     *        a specified account". The other side's always does, for them to put on an account (D-24)
     */
    record NewSettlementRequest(@NotNull LocalDate date, @NotNull BigDecimal amount, @NotNull Long payerMemberId,
            @NotNull Long payeeMemberId, @Size(max = 500) String comment, Long paymentAccountId,
            Boolean paymentLater) {
    }

    /** @param basisPoints for PERCENT; @param amount for AMOUNT */
    record Share(@NotNull Long memberId, @Min(0) @Max(10_000) Integer basisPoints, BigDecimal amount) {
    }

    /**
     * A change of a record (D-14); fields left out stay as they are. A class rather than a record, because a comment
     * sent as null removes it, while one left out keeps it.
     * <ul>
     * <li>The family fields, by the record's author or an owner: the category, the comment and the split.
     * <li>The payment fields (F4c), for a record paid by a member with an account by that payer only, else by the author
     * or an owner: the date, the amount and the payer. A new amount, date or payer splits the amount again by the
     * record's split; under AMOUNT a new amount needs the split's new amounts with it. The payer may be yourself, or a
     * member without an account.
     * <li>How you paid, when you are the payer with an account: the account, or "Specify later". It stays private: no
     * answer but yours names it, and the journal doesn't (D-16).
     * </ul>
     */
    static final class RecordPatch {

        private Long categoryId;
        @Size(max = 500)
        private String comment;
        private boolean changesComment;
        @Valid
        private Split split;
        private LocalDate date;
        private BigDecimal amount;
        private Long payerMemberId;
        private Long paymentAccountId;
        private Boolean paymentLater;

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        /** Null removes the comment. */
        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
            this.changesComment = true;
        }

        public Split getSplit() {
            return split;
        }

        public void setSplit(Split split) {
            this.split = split;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        /** In the family's base currency, above 0. */
        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public Long getPayerMemberId() {
            return payerMemberId;
        }

        public void setPayerMemberId(Long payerMemberId) {
            this.payerMemberId = payerMemberId;
        }

        /** The account of your personal ledger you paid with, if you paid. */
        public Long getPaymentAccountId() {
            return paymentAccountId;
        }

        public void setPaymentAccountId(Long paymentAccountId) {
            this.paymentAccountId = paymentAccountId;
        }

        /** True for "Specify later", if you paid. */
        public Boolean getPaymentLater() {
            return paymentLater;
        }

        public void setPaymentLater(Boolean paymentLater) {
            this.paymentLater = paymentLater;
        }

        FamilyRecordChanges changes() {
            return new FamilyRecordChanges(categoryId, changesComment,
                    comment == null || comment.isBlank() ? null : comment.strip(),
                    split == null ? null : split.toSplit(), date, amount, payerMemberId, paymentAccountId,
                    Boolean.TRUE.equals(paymentLater), false, null);
        }
    }

    private final LedgerAccess access;
    private final FamilyRecordService records;

    FamilyRecordController(LedgerAccess access, FamilyRecordService records) {
        this.access = access;
        this.records = records;
    }

    /** The records, newest first: by date, then by id. */
    @GetMapping("/records")
    FamilyRecordPage records(CurrentUser user, @PathVariable long ledgerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return records.page(access.member(user.id(), ledgerId), page, size);
    }

    /** Any member. The shares are posted into the personal ledgers of the members with an account (D-7). */
    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    FamilyRecordView create(CurrentUser user, LedgerScope personal, @PathVariable long ledgerId,
            @Valid @RequestBody NewRecord record) {
        return records.create(access.member(user.id(), ledgerId), personal, new NewFamilyRecord(record.date(),
                record.categoryId(), record.amount(),
                record.comment() == null || record.comment().isBlank() ? null : record.comment().strip(),
                record.payerMemberId(), record.paymentAccountId(), Boolean.TRUE.equals(record.paymentLater()),
                record.split() == null ? null : record.split().toSplit(),
                record.privateNote() == null || record.privateNote().isBlank() ? null : record.privateNote().strip()));
    }

    /**
     * A settlement between two members (D2, D-24), by one of them with an account, or by an owner between two members
     * without an account. Each side with an account gets its part in their personal ledger.
     */
    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    FamilyRecordView settle(CurrentUser user, LedgerScope personal, @PathVariable long ledgerId,
            @Valid @RequestBody NewSettlementRequest settlement) {
        return records.settle(access.member(user.id(), ledgerId), personal, new NewSettlement(settlement.date(),
                settlement.amount(), settlement.payerMemberId(), settlement.payeeMemberId(),
                settlement.comment() == null || settlement.comment().isBlank() ? null : settlement.comment().strip(),
                settlement.paymentAccountId(), Boolean.TRUE.equals(settlement.paymentLater())));
    }

    @GetMapping("/records/{recordId}")
    FamilyRecordView record(CurrentUser user, @PathVariable long ledgerId, @PathVariable long recordId) {
        return records.get(access.member(user.id(), ledgerId), recordId);
    }

    /**
     * The record's author and the owners change its family fields; its payer with an account, or for a payer without
     * one its author and the owners, change its payment fields (D-14). A settlement's date, amount and comment change by
     * the side who recorded it (between two members without an account, its author or an owner), and each side with an
     * account names the account of its own side (F4d).
     *
     * @param version the version the caller read; if the record has changed since, it is refused with 409
     */
    @PatchMapping("/records/{recordId}")
    FamilyRecordView update(CurrentUser user, LedgerScope personal, @PathVariable long ledgerId,
            @PathVariable long recordId, @RequestParam int version, @Valid @RequestBody RecordPatch patch) {
        return records.update(access.member(user.id(), ledgerId), personal, recordId, version, patch.changes());
    }

    /**
     * The payer deletes a record they paid; a record paid by a member without an account, its author or an owner; a
     * settlement, the side who recorded it, or between two members without an account its author or an owner. Its
     * posted entries go with it.
     *
     * @param version the version the caller read; if the record has changed since, it is refused with 409
     */
    @DeleteMapping("/records/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable long ledgerId, @PathVariable long recordId,
            @RequestParam int version) {
        records.delete(access.member(user.id(), ledgerId), recordId, version);
    }

    /** Every member's balance in the base currency; they sum to zero, and "you" marks the caller's (D1). */
    @GetMapping("/balances")
    FamilyBalances balances(CurrentUser user, @PathVariable long ledgerId) {
        return records.balances(access.member(user.id(), ledgerId));
    }

    /**
     * The change journal, newest first (D-16).
     *
     * @param recordId only this record's changes
     */
    @GetMapping("/journal")
    FamilyJournalPage journal(CurrentUser user, @PathVariable long ledgerId,
            @RequestParam(required = false) Long recordId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return records.journal(access.member(user.id(), ledgerId), recordId, page, size);
    }
}
