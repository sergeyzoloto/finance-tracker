package com.example.financetracker.ledger.rates;

import static com.example.financetracker.ledger.rates.RateSource.ECB;
import static com.example.financetracker.ledger.rates.RateSource.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The conversion rules of {@link RateBook} (D-49, D-90, D-91), without a database. */
class RateBookTests {

    private static final LocalDate THU = LocalDate.of(2026, 9, 24);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 25);
    private static final LocalDate SAT = LocalDate.of(2026, 9, 26);
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);

    @Test
    void anAmountUsesTheLatestRateOnOrBeforeItsDay() {
        RateBook rates = RateBook.builder()
                .add("USD", THU, money("1.1367"), ECB)
                .add("USD", FRI, money("1.1403"), ECB)
                .add("USD", MON, money("1.1500"), ECB)
                .build();

        assertThat(rates.convert(money("100.00"), "EUR", "USD", FRI)).hasValueSatisfying(
                usd -> assertThat(usd).isEqualByComparingTo("114.03"));
        // Saturday has no rate of its own: Friday's applies, not Monday's.
        assertThat(rates.convert(money("100.00"), "EUR", "USD", SAT)).hasValueSatisfying(
                usd -> assertThat(usd).isEqualByComparingTo("114.03"));
        assertThat(rates.rate("USD", SAT).orElseThrow().date()).isEqualTo(FRI);
    }

    @Test
    void anAmountBeforeTheFirstRateIsMissingAndNotConvertedAtALaterRate() {
        RateBook rates = RateBook.builder().add("RUB", FRI, money("95.50"), MANUAL).build();

        assertThat(rates.convert(money("9550.00"), "RUB", "EUR", THU)).isEmpty();
        assertThat(rates.missing("RUB", "EUR", THU)).contains("RUB");
        // A currency without any rate.
        assertThat(rates.convert(money("1.00"), "KZT", "EUR", FRI)).isEmpty();
        assertThat(rates.missing("KZT", "EUR", FRI)).contains("KZT");
        // The currency converted to can be the one without a rate.
        assertThat(rates.convert(money("1.00"), "RUB", "KZT", FRI)).isEmpty();
        assertThat(rates.missing("RUB", "KZT", FRI)).contains("KZT");
        assertThat(rates.missing("RUB", "EUR", FRI)).isEmpty();
    }

    /** 1 EUR = 1.25 USD = 100 RUB, so 1 USD = 80 RUB. */
    @Test
    void aCrossRateIsComputedThroughTheEuro() {
        RateBook rates = RateBook.builder()
                .add("USD", FRI, money("1.25"), ECB)
                .add("RUB", FRI, money("100"), MANUAL)
                .build();

        assertThat(rates.convert(money("10.00"), "USD", "RUB", FRI)).hasValueSatisfying(
                rub -> assertThat(rub).isEqualByComparingTo("800.00"));
        assertThat(rates.convert(money("800.00"), "RUB", "USD", FRI)).hasValueSatisfying(
                usd -> assertThat(usd).isEqualByComparingTo("10.00"));
    }

    @Test
    void euroIsOneAndAnAmountInItsOwnCurrencyNeedsNoRate() {
        RateBook rates = RateBook.builder().build();

        assertThat(rates.convert(money("12.34"), "EUR", "EUR", FRI)).contains(money("12.34"));
        assertThat(rates.convert(money("12.34"), "KZT", "KZT", FRI)).contains(money("12.34"));
        assertThat(rates.rate("EUR", FRI).orElseThrow().perEuro()).isEqualByComparingTo("1");
    }

    @Test
    void theUsersManualRateTakesPrecedenceOverTheEcbsOnTheSameDayButNotOverALaterOne() {
        RateBook rates = RateBook.builder()
                .add("USD", THU, money("1.30"), MANUAL)
                .add("USD", THU, money("1.1367"), ECB)
                .add("USD", FRI, money("1.1403"), ECB)
                .build();

        assertThat(rates.rate("USD", THU).orElseThrow().source()).isEqualTo(MANUAL);
        assertThat(rates.rate("USD", FRI).orElseThrow().source()).isEqualTo(ECB);
    }

    /**
     * D-49: an ECB rate applies up to 7 days after its date and never later. The ECB's last rouble rate was of
     * 2022-03-01: a rouble amount on 2022-03-08 still converts at it, one on 2022-03-09 or in 2026 has no rate, and is
     * never given the stale one.
     */
    @Test
    void anEcbRateOlderThanSevenDaysIsNeverUsed() {
        LocalDate last = LocalDate.of(2022, 3, 1);
        RateBook rates = RateBook.builder()
                .add("RUB", LocalDate.of(2022, 2, 28), money("117.2010"), ECB)
                .add("RUB", last, money("115.8000"), ECB)
                .build();

        assertThat(rates.rate("RUB", last.plusDays(7)).orElseThrow().date()).isEqualTo(last);
        assertThat(rates.convert(money("1158.00"), "RUB", "EUR", last.plusDays(7))).hasValueSatisfying(
                eur -> assertThat(eur).isEqualByComparingTo("10.00"));
        for (LocalDate day : new LocalDate[] {last.plusDays(8), LocalDate.of(2026, 9, 25)}) {
            assertThat(rates.rate("RUB", day)).as("%s", day).isEmpty();
            assertThat(rates.convert(money("1158.00"), "RUB", "EUR", day)).as("%s", day).isEmpty();
            assertThat(rates.missing("RUB", "EUR", day)).as("%s", day).contains("RUB");
            assertThat(rates.used("RUB", "EUR", day)).as("%s", day).isEmpty();
        }
    }

    /**
     * D-49: a manual rate applies from its date until the user's next one, at any age; past 31 days it is marked stale
     * on the day it converts on, not before.
     */
    @Test
    void aManualRateAppliesUntilTheNextOneAndIsStalePastThirtyOneDays() {
        LocalDate entered = LocalDate.of(2026, 8, 1);
        RateBook rates = RateBook.builder()
                .add("RUB", entered, money("95.50"), MANUAL)
                .add("RUB", LocalDate.of(2026, 9, 20), money("97.00"), MANUAL)
                .build();

        assertThat(rates.rate("RUB", entered.plusDays(31)).orElseThrow().stale()).isFalse();
        RateBook.Rate old = rates.rate("RUB", entered.plusDays(32)).orElseThrow();
        assertThat(old.perEuro()).isEqualByComparingTo("95.50");
        assertThat(old.stale()).isTrue();
        assertThat(old.manual()).isTrue();
        assertThat(rates.rate("RUB", LocalDate.of(2026, 9, 20)).orElseThrow().perEuro()).isEqualByComparingTo("97");
        RateBook.Rate years = rates.rate("RUB", LocalDate.of(2029, 1, 1)).orElseThrow();
        assertThat(years.perEuro() + " " + years.stale()).isEqualTo("97.00 true");
        assertThat(rates.used("RUB", "EUR", entered.plusDays(40))).containsExactly(old);
    }

    /**
     * D-91: among the rates that apply, the most recent date wins, whatever its source; on the same date the manual
     * one. An ECB rate too old to apply loses to an older manual one, which applies.
     */
    @Test
    void theMostRecentApplicableRateWinsAndTheManualOneOnTheSameDate() {
        RateBook rates = RateBook.builder()
                .add("USD", LocalDate.of(2026, 9, 1), money("1.20"), MANUAL)
                .add("USD", LocalDate.of(2026, 9, 10), money("1.15"), ECB)
                .add("USD", LocalDate.of(2026, 9, 15), money("1.25"), MANUAL)
                .add("USD", LocalDate.of(2026, 9, 15), money("1.16"), ECB)
                .add("USD", LocalDate.of(2026, 9, 16), money("1.17"), ECB)
                .build();

        assertThat(rates.rate("USD", LocalDate.of(2026, 9, 12)).orElseThrow().source()).isEqualTo(ECB);
        assertThat(rates.rate("USD", LocalDate.of(2026, 9, 15)).orElseThrow().perEuro()).isEqualByComparingTo("1.25");
        assertThat(rates.rate("USD", LocalDate.of(2026, 9, 16)).orElseThrow().perEuro()).isEqualByComparingTo("1.17");
        // The ECB's of 16 September is too old on the 24th; the manual one of the 15th still applies.
        RateBook.Rate later = rates.rate("USD", LocalDate.of(2026, 9, 24)).orElseThrow();
        assertThat(later.source() + " " + later.date()).isEqualTo("MANUAL 2026-09-15");
    }

    /** A conversion names the rates it uses: none in one currency, one through the euro, two for a cross rate. */
    @Test
    void aConversionNamesTheRatesItUses() {
        RateBook rates = RateBook.builder()
                .add("USD", FRI, money("1.25"), ECB)
                .add("RUB", THU, money("100"), MANUAL)
                .build();

        assertThat(rates.used("USD", "USD", FRI)).isEmpty();
        assertThat(rates.used("EUR", "USD", FRI)).extracting(RateBook.Rate::currency).containsExactly("USD");
        assertThat(rates.used("RUB", "USD", FRI)).extracting(rate -> rate.currency() + " " + rate.date() + " "
                + rate.source()).containsExactly("RUB 2026-09-24 MANUAL", "USD 2026-09-25 ECB");
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
