package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.rates.RateBook;

/**
 * Every member's balance in a family ledger (D-1, D1; ADR 0003, topic D), in each currency of its records (D-45, ADR
 * 0004): what the member owes the family, positive, or what the family owes them, negative. In each currency they sum to
 * zero. A settlement moves one currency's balances only (D-46). {@code total} is D-47's total in the main currency,
 * for display only (F8b).
 *
 * @param byCurrency the balances in each currency, the main currency first, then every other currency of a record that
 *        isn't deleted, alphabetically (F8a); the main currency's always, all zero without a record in it
 * @param total each member's balances together in the main currency, approximately (D-47; F8b, additive)
 */
public record FamilyBalances(List<CurrencyBalances> byCurrency, Total total) {

    /**
     * D-47's total: each member's balances in every currency converted to the main currency at the rates that apply
     * today for the member who reads (D-49, D-90, D-91), added up and rounded once to its minor unit. For display
     * only: never posted, never settled (D-46).
     *
     * @param currency the main currency
     * @param asOf the day of the rates: today
     * @param members by join order; empty if a currency has no rate
     * @param rates the rates it used, each with its date, source and stale mark; empty if all is in the main currency
     * @param missingCurrencies the currencies without a rate, which leave no total; empty otherwise
     */
    public record Total(String currency, LocalDate asOf, List<MemberTotal> members, List<RateBook.Rate> rates,
            List<String> missingCurrencies) {
    }

    /** A member's total, in the main currency. */
    public record MemberTotal(long memberId, BigDecimal balance) {
    }

    /** @param you whether this is the member who reads */
    public record MemberBalance(long memberId, String displayName, MemberStatus status, boolean hasAccount,
            BigDecimal balance, boolean you) {
    }

    /**
     * Every member's balance in one currency, by join order.
     */
    public record CurrencyBalances(String currency, List<MemberBalance> members) {
    }

    /** The balances in the currency; every member at zero if the family has no record in it. */
    public CurrencyBalances in(String currency) {
        return byCurrency.stream().filter(balances -> balances.currency().equals(currency)).findFirst()
                .orElse(new CurrencyBalances(currency, byCurrency.getFirst().members().stream()
                        .map(m -> new MemberBalance(m.memberId(), m.displayName(), m.status(), m.hasAccount(),
                                BigDecimal.ZERO.setScale(ShareSplit.minorUnit(currency)), m.you()))
                        .toList()));
    }
}
