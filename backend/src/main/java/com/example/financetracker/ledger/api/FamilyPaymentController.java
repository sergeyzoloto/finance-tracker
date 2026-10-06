package com.example.financetracker.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.EntryView;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyPaymentEntries;
import com.example.financetracker.ledger.family.FamilyPaymentEntries.PaymentEdit;
import com.example.financetracker.ledger.family.FamilySwitch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payer's own payment for a family expense, as an entry of their personal ledger (F4c; D-14): its date, amount,
 * account and private note change here, as the payer's change of the expense, which splits the amount again and posts
 * the other members' shares in the same transaction. {@code DELETE /api/entries/{id}} of it deletes the expense. Only
 * the payer reaches it: anyone else's entry is missing (404).
 * <p>
 * Only while the feature switch is on (D-25, {@link FamilySwitch}): otherwise this path is unknown and answers 404.
 */
@RestController
@RequestMapping("/api/entries")
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class FamilyPaymentController {

    /**
     * A change of a payment; fields left out stay as they are. A class rather than a record, because a memo sent as
     * null removes it, while one left out keeps it.
     */
    static final class PaymentPatch {

        private LocalDate date;
        private BigDecimal amount;
        private Long accountId;
        private Boolean later;
        @Size(max = 500)
        private String memo;
        private boolean changesMemo;
        @CurrencyCode
        private String currency;
        private BigDecimal accountAmount;
        @CurrencyCode
        private String accountCurrency;

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        /** The record's amount, in its currency (D-45); above 0. */
        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        /** The account of your personal ledger it is paid from. */
        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        /** True for "Specify later": "Payments without a specified account". */
        public Boolean getLater() {
            return later;
        }

        public void setLater(Boolean later) {
            this.later = later;
        }

        /** Your private note, which no family answer holds; null removes it. */
        public String getMemo() {
            return memo;
        }

        public void setMemo(String memo) {
            this.memo = memo;
            this.changesMemo = true;
        }

        /** The record's new currency (D-45), with its amount. */
        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        /**
         * What went from or into the account, in its currency, when that isn't the record's (D-87); for the other side
         * of a settlement, only this entry holds it (F4e).
         */
        public BigDecimal getAccountAmount() {
            return accountAmount;
        }

        public void setAccountAmount(BigDecimal accountAmount) {
            this.accountAmount = accountAmount;
        }

        /**
         * The paying currency (D-89): what the account paid or received in; left out, the side's as it is, or for a
         * newly named account its default currency, else the record's.
         */
        public String getAccountCurrency() {
            return accountCurrency;
        }

        public void setAccountCurrency(String accountCurrency) {
            this.accountCurrency = accountCurrency;
        }

        PaymentEdit edit() {
            return new PaymentEdit(date, amount, accountId, Boolean.TRUE.equals(later), changesMemo,
                    memo == null || memo.isBlank() ? null : memo.strip(), currency, accountAmount, accountCurrency);
        }
    }

    private final FamilyPaymentEntries payments;
    private final EntryService entries;

    FamilyPaymentController(FamilyPaymentEntries payments, EntryService entries) {
        this.payments = payments;
        this.entries = entries;
    }

    /**
     * Changes your payment, and with it the family expense it pays.
     *
     * @param version the entry's version the caller read; if it has changed since, it is refused with 409
     * @return the entry as it is now
     */
    @PatchMapping("/{id}/family-payment")
    EntryView update(LedgerScope ledger, @PathVariable long id, @RequestParam int version,
            @Valid @RequestBody PaymentPatch patch) {
        payments.update(ledger, id, version, patch.edit());
        return entries.get(ledger, id);
    }
}
