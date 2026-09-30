package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.ledger.family.ShareSplit.Share;
import com.example.financetracker.ledger.family.ShareSplit.Weight;
import org.junit.jupiter.api.Test;

/**
 * The split of a family record into shares (D-12): each share cut down to the currency's minor unit, the remainder to
 * the member with the largest share, on a tie to the payer, then by join order. The shares always add up.
 */
class ShareSplitTests {

    private static final long PAYER = 1;
    private static final long PARTNER = 2;
    private static final long KID = 3;

    @Test
    void tenOhOneSplit5050GivesThePayer501() {
        List<Share> shares = ShareSplit.percent(amount("10.01"), 2,
                List.of(new Weight(PARTNER, 5000, 5000), new Weight(PAYER, 5000, 5000)), PAYER);

        assertThat(shares).containsExactly(new Share(PARTNER, amount("5.00"), 5000),
                new Share(PAYER, amount("5.01"), 5000));
        assertThat(ShareSplit.equal(amount("10.01"), 2, List.of(PARTNER, PAYER), PAYER))
                .containsExactly(new Share(PARTNER, amount("5.00"), null), new Share(PAYER, amount("5.01"), null));
    }

    @Test
    void oneHundredEquallyAmongThree() {
        // The payer joined second: the tie goes to them all the same.
        assertThat(ShareSplit.equal(amount("100.00"), 2, List.of(PARTNER, PAYER, KID), PAYER)).containsExactly(
                new Share(PARTNER, amount("33.33"), null), new Share(PAYER, amount("33.34"), null),
                new Share(KID, amount("33.33"), null));
        // A payer who isn't among them leaves it to the first by join order.
        assertThat(ShareSplit.equal(amount("100.00"), 2, List.of(PARTNER, KID), PAYER)).containsExactly(
                new Share(PARTNER, amount("50.00"), null), new Share(KID, amount("50.00"), null));
        assertThat(ShareSplit.equal(amount("0.02"), 2, List.of(PARTNER, KID, PAYER), 99)).containsExactly(
                new Share(PARTNER, amount("0.02"), null), new Share(KID, amount("0.00"), null),
                new Share(PAYER, amount("0.00"), null));
    }

    @Test
    void aCurrencyWithoutMinorUnits() {
        assertThat(ShareSplit.minorUnit("JPY")).isZero();
        assertThat(ShareSplit.minorUnit("EUR")).isEqualTo(2);
        assertThat(ShareSplit.minorUnit("KWD")).isEqualTo(3);
        assertThat(ShareSplit.minorUnit("XAU")).isEqualTo(4);

        assertThat(ShareSplit.equal(amount("1001"), 0, List.of(PARTNER, PAYER), PAYER)).containsExactly(
                new Share(PARTNER, amount("500"), null), new Share(PAYER, amount("501"), null));
        assertThat(ShareSplit.percent(amount("100"), 0,
                List.of(new Weight(PAYER, 3333, 3333), new Weight(PARTNER, 3333, 3333), new Weight(KID, 3334, 3334)),
                PAYER)).containsExactly(new Share(PAYER, amount("33"), 3333), new Share(PARTNER, amount("33"), 3333),
                new Share(KID, amount("34"), 3334));
    }

    @Test
    void aPayerWithZeroPercent() {
        assertThat(ShareSplit.percent(amount("10.01"), 2,
                List.of(new Weight(PAYER, 0, 0), new Weight(PARTNER, 10_000, 10_000)), PAYER)).containsExactly(
                new Share(PAYER, amount("0.00"), 0), new Share(PARTNER, amount("10.01"), 10_000));
        // The largest share takes the remainder, not the payer.
        assertThat(ShareSplit.percent(amount("0.05"), 2,
                List.of(new Weight(PAYER, 3000, 3000), new Weight(PARTNER, 7000, 7000)), PAYER)).containsExactly(
                new Share(PAYER, amount("0.01"), 3000), new Share(PARTNER, amount("0.04"), 7000));
    }

    @Test
    void entirelyOnOneMember() {
        assertThat(ShareSplit.oneMember(amount("42.5"), 2, KID)).containsExactly(new Share(KID, amount("42.50"), null));
    }

    @Test
    void customAmountsThatDontAddUpAreRefused() {
        assertThatThrownBy(() -> ShareSplit.amounts(amount("10.01"), 2, List.of(new Share(PAYER, amount("5"), null),
                new Share(PARTNER, amount("5.00"), null)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the shares sum to 10.00, not to the amount 10.01");
        assertThatThrownBy(() -> ShareSplit.amounts(amount("10.01"), 2, List.of(new Share(PAYER, amount("5.01"), null),
                new Share(PARTNER, amount("5.01"), null)))).hasMessage("the shares sum to 10.02, not to the amount 10.01");
        assertThat(ShareSplit.amounts(amount("10.01"), 2, List.of(new Share(PAYER, amount("7"), null),
                new Share(PARTNER, amount("3.01"), null)))).containsExactly(new Share(PAYER, amount("7.00"), null),
                new Share(PARTNER, amount("3.01"), null));
    }

    @Test
    void theSharesAlwaysAddUp() {
        for (int cents = 1; cents <= 2_000; cents += 7) {
            BigDecimal total = BigDecimal.valueOf(cents, 2);
            for (List<Weight> weights : List.of(
                    List.of(new Weight(PAYER, 1, null), new Weight(PARTNER, 1, null), new Weight(KID, 1, null)),
                    List.of(new Weight(PAYER, 3333, 3333), new Weight(PARTNER, 3333, 3333),
                            new Weight(KID, 3334, 3334)),
                    List.of(new Weight(PAYER, 1, 1), new Weight(PARTNER, 9999, 9999)))) {
                List<Share> shares = ShareSplit.byWeights(total, 2, weights, PARTNER);
                assertThat(shares.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .as("%s by %s", total, weights).isEqualByComparingTo(total);
                assertThat(shares).allSatisfy(share -> assertThat(share.amount().scale()).isEqualTo(2));
            }
        }
    }

    @Test
    void aSplitNeedsAWeight() {
        assertThatThrownBy(() -> ShareSplit.percent(amount("1.00"), 2, List.of(new Weight(PAYER, 0, 0)), PAYER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
