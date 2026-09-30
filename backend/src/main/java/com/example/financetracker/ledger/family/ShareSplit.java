package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Splits a family record's amount into its members' shares (D-12; ADR 0003, topic D). Plain Java, with no Spring and
 * no database.
 * <p>
 * Every share is the amount times its weight, cut down to the currency's minor unit, so that the shares never add up
 * to more than the amount. What that leaves, the remainder, goes to one member: the one with the largest share; on a
 * tie the payer, and otherwise the first of them in join order. So the shares always add up to the amount, and 10.01
 * split 50/50 gives the payer 5.01 and the other member 5.00.
 */
public final class ShareSplit {

    /**
     * A member's weight in a split: a share in basis points out of 10000, or 1 for each of equal shares.
     *
     * @param basisPoints kept with the share when the split was in percentages; null for equal shares
     */
    public record Weight(long memberId, long weight, Integer basisPoints) {
    }

    /**
     * A member's share of the amount, in the currency's minor unit.
     *
     * @param basisPoints the percentage it was split by, in basis points, or null
     */
    public record Share(long memberId, BigDecimal amount, Integer basisPoints) {
    }

    private ShareSplit() {
    }

    /**
     * The currency's minor unit as decimal places: 2 for EUR, 0 for JPY, 3 for KWD. A currency without one, such as
     * gold (XAU), keeps the four places that amounts are stored with.
     */
    public static int minorUnit(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return digits < 0 ? 4 : digits;
    }

    /** Equal shares of the members, given in join order. */
    public static List<Share> equal(BigDecimal amount, int scale, List<Long> members, long payerId) {
        return byWeights(amount, scale, members.stream().map(id -> new Weight(id, 1, null)).toList(), payerId);
    }

    /** Shares by percentage, in basis points that sum to 10000, of the members given in join order. */
    public static List<Share> percent(BigDecimal amount, int scale, List<Weight> basisPoints, long payerId) {
        return byWeights(amount, scale, basisPoints, payerId);
    }

    /**
     * Shares by amount, as entered: they add up to the amount exactly, each with at most the currency's decimals.
     *
     * @throws IllegalArgumentException naming the sum, if they don't add up
     */
    public static List<Share> amounts(BigDecimal amount, int scale, List<Share> shares) {
        BigDecimal sum = shares.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(amount) != 0) {
            throw new IllegalArgumentException("the shares sum to %s, not to the amount %s".formatted(
                    sum.setScale(scale, RoundingMode.DOWN).toPlainString(),
                    amount.setScale(scale, RoundingMode.DOWN).toPlainString()));
        }
        return shares.stream().map(share -> new Share(share.memberId(),
                share.amount().setScale(scale, RoundingMode.UNNECESSARY), null)).toList();
    }

    /** The whole amount on one member. */
    public static List<Share> oneMember(BigDecimal amount, int scale, long memberId) {
        return List.of(new Share(memberId, amount.setScale(scale, RoundingMode.UNNECESSARY), null));
    }

    /**
     * @param weights in join order, which breaks a tie that the payer doesn't; their sum is above 0
     */
    static List<Share> byWeights(BigDecimal amount, int scale, List<Weight> weights, long payerId) {
        long total = weights.stream().mapToLong(Weight::weight).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("A split needs a weight above 0");
        }
        List<Share> shares = new ArrayList<>();
        BigDecimal rest = amount;
        for (Weight weight : weights) {
            BigDecimal share = amount.multiply(BigDecimal.valueOf(weight.weight()))
                    .divide(BigDecimal.valueOf(total), scale, RoundingMode.DOWN);
            shares.add(new Share(weight.memberId(), share, weight.basisPoints()));
            rest = rest.subtract(share);
        }
        int recipient = recipient(weights, payerId);
        Share share = shares.get(recipient);
        shares.set(recipient, new Share(share.memberId(), share.amount().add(rest).setScale(scale,
                RoundingMode.UNNECESSARY), share.basisPoints()));
        return List.copyOf(shares);
    }

    /** The index of the member with the largest weight; on a tie the payer, else the first of them. */
    private static int recipient(List<Weight> weights, long payerId) {
        long largest = weights.stream().mapToLong(Weight::weight).max().orElseThrow();
        int first = -1;
        for (int i = 0; i < weights.size(); i++) {
            if (weights.get(i).weight() == largest) {
                if (weights.get(i).memberId() == payerId) {
                    return i;
                }
                if (first < 0) {
                    first = i;
                }
            }
        }
        return first;
    }
}
