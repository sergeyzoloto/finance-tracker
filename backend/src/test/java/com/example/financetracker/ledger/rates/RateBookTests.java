package com.example.financetracker.ledger.rates;

import static com.example.financetracker.ledger.rates.RateSource.ECB;
import static com.example.financetracker.ledger.rates.RateSource.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The conversion rules of {@link RateBook}, without a database. */
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

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
