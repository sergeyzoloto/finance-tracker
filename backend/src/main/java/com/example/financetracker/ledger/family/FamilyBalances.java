package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.util.List;

/**
 * Every member's balance in a family ledger (D-1, D1; ADR 0003, topic D), in each currency of its records (D-45, ADR
 * 0004): what the member owes the family, positive, or what the family owes them, negative. In each currency they sum to
 * zero. A settlement moves one currency's balances only (D-46); no total across currencies is computed here (D-47 is
 * F8b's, for display only).
 *
 * @param currency the family's main currency, whose balances {@code members} are; deprecated since F8a for
 *        {@code byCurrency}, until F8b's interface reads it
 * @param members by join order, the balances in the main currency; deprecated since F8a for {@code byCurrency}
 * @param byCurrency the balances in each currency, the main currency first, then every other currency of a record that
 *        isn't deleted, alphabetically (F8a, additive)
 */
public record FamilyBalances(@Deprecated String currency, @Deprecated List<MemberBalance> members,
        List<CurrencyBalances> byCurrency) {

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
