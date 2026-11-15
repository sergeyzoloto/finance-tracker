package com.example.financetracker.ledger.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.ExchangeRate;
import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.Today;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * {@link CbrRateLoader} against a stand-in for the CBR's XML_dynamic.asp on a local port (D-852 to D-858). The shared
 * rates are every user's, so each test starts and ends without any, and removes the manual rates of its own users.
 */
class CbrRateLoaderTests extends IntegrationTest {

    private static final LocalDate NOV_3 = LocalDate.of(2026, 11, 3);
    private static final LocalDate NOV_4 = LocalDate.of(2026, 11, 4);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Autowired
    private ExchangeRateRepository rates;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private Today today;

    private HttpServer cbr;
    private volatile byte[] answer;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> users = new CopyOnWriteArrayList<>();
    private CbrRateLoader loader;

    @BeforeEach
    void startCbr() throws IOException {
        deleteSharedRates();
        cbr = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        cbr.createContext("/scripts/XML_dynamic.asp", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            respond(exchange, answer);
        });
        cbr.start();
        CbrProperties properties = new CbrProperties(true,
                URI.create("http://localhost:" + cbr.getAddress().getPort() + "/scripts/XML_dynamic.asp"), "-");
        loader = new CbrRateLoader(new CbrClient(RestClient.builder(), properties), rates, jdbc, transactions, today);
        answer = CbrClientTests.answer(CbrClientTests.ANSWER);
    }

    @AfterEach
    void stopCbr() {
        cbr.stop(0);
        deleteSharedRates();
        users.forEach(user -> jdbc.sql("DELETE FROM exchange_rate WHERE user_id = ?").param(user).update());
    }

    @Test
    void theFirstLoadAsksFromMarch2022AndLaterOnesFromTenDaysBeforeTheLatest() {
        String tomorrow = today.utc().plusDays(1).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));

        CbrRateLoader.Result first = loader.load();

        assertThat(first.first()).isTrue();
        assertThat(first.rates()).isEqualTo(2);
        assertThat(first.firstDay()).isEqualTo(NOV_3);
        assertThat(first.lastDay()).isEqualTo(NOV_4);
        assertThat(cbrRate(NOV_3)).isEqualByComparingTo("90.4635");
        assertThat(rates.latestCbrDate()).contains(NOV_4);
        assertThat(rates.latestEcbDate()).isEmpty();

        answer = CbrClientTests.answer(CbrClientTests.ANSWER.replace("90,4635", "90,5000")
                .replace("<Record Date=\"04.11.2026\"",
                        "<Record Date=\"05.11.2026\" Id=\"R01239\"><Nominal>1</Nominal><Value>91,0000</Value></Record>"
                                + "\n<Record Date=\"04.11.2026\""));
        CbrRateLoader.Result later = loader.load();

        assertThat(later.first()).isFalse();
        assertThat(cbrRate(NOV_3)).isEqualByComparingTo("90.5");
        assertThat(cbrRate(NOV_5)).isEqualByComparingTo("91");
        assertThat(queries).containsExactly(
                "date_req1=02/03/2022&date_req2=%s&VAL_NM_RQ=R01239".formatted(tomorrow),
                "date_req1=25/10/2026&date_req2=%s&VAL_NM_RQ=R01239".formatted(tomorrow));
    }

    /** D-853: an ECB rate and a CBR rate of the same day and currency are two rows; neither loader overwrites the other. */
    @Test
    void theEcbsAndTheCbrsRatesOfTheSameDayStandSideBySide() {
        rates.save(new ExchangeRate(NOV_3, "EUR", "RUB", new BigDecimal("99.5"), "ECB", null));

        loader.load();
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source)
                VALUES ('2026-11-03', 'EUR', 'RUB', 99.6, 'ECB')
                ON CONFLICT (base_currency, quote_currency, rate_date, user_id, source) DO UPDATE SET rate = EXCLUDED.rate""")
                .update();
        loader.load();

        assertThat(rates.find(NOV_3, "EUR", "RUB", "ECB", null).orElseThrow().rate()).isEqualByComparingTo("99.6");
        assertThat(cbrRate(NOV_3)).isEqualByComparingTo("90.4635");
    }

    /** D-858: the first load deletes the manual RUB rates equal to the CBR's of their date, and nothing else. */
    @Test
    void theFirstLoadDeletesManualRublesEqualToTheCbrsOnceOnly() {
        String alice = user();
        String bob = user();
        manual(alice, NOV_3, "RUB", "90.46350000");
        manual(alice, NOV_4, "RUB", "90.8120");
        manual(bob, NOV_3, "RUB", "90.4635");
        // Differs from the CBR's, has no CBR rate on its date, or isn't RUB: kept.
        manual(bob, NOV_4, "RUB", "90.8121");
        manual(bob, NOV_5, "RUB", "91");
        manual(alice, NOV_3, "USD", "90.4635");

        CbrRateLoader.Result first = loader.load();

        assertThat(first.manualDeleted()).isEqualTo(3);
        assertThat(manualOf(alice)).containsExactly(NOV_3 + " USD");
        assertThat(manualOf(bob)).containsExactly(NOV_5 + " RUB", NOV_4 + " RUB");

        // A rate entered later, equal to the CBR's, stays: later loads never delete anything.
        manual(alice, NOV_4, "RUB", "90.812");
        CbrRateLoader.Result second = loader.load();

        assertThat(second.first()).isFalse();
        assertThat(second.manualDeleted()).isZero();
        assertThat(manualOf(alice)).containsExactly(NOV_4 + " RUB", NOV_3 + " USD");
    }

    @Test
    void anAnswerThatIsNotTheCbrsWritesAndDeletesNothing() {
        String alice = user();
        manual(alice, NOV_3, "RUB", "90.4635");
        answer = CbrClientTests.answer(CbrClientTests.ANSWER.replace("Id=\"R01239\"><Nominal>1</Nominal><Value>90,4635",
                "Id=\"R01235\"><Nominal>1</Nominal><Value>90,4635"));

        assertThatThrownBy(loader::load).isInstanceOf(CbrClient.CbrFormatException.class);
        assertThat(rates.latestCbrDate()).isEmpty();
        assertThat(manualOf(alice)).containsExactly(NOV_3 + " RUB");
    }

    private String user() {
        String user = UUID.randomUUID().toString();
        users.add(user);
        return user;
    }

    private void manual(String user, LocalDate day, String currency, String rate) {
        rates.save(new ExchangeRate(day, "EUR", currency, new BigDecimal(rate), "MANUAL", user));
    }

    private List<String> manualOf(String user) {
        return rates.findManual(user).stream().map(rate -> rate.rateDate() + " " + rate.quoteCurrency()).toList();
    }

    private BigDecimal cbrRate(LocalDate day) {
        return rates.find(day, "EUR", "RUB", "CBR", null).map(ExchangeRate::rate).orElseThrow();
    }

    private void deleteSharedRates() {
        jdbc.sql("DELETE FROM exchange_rate WHERE user_id IS NULL").update();
    }

    private static void respond(HttpExchange exchange, byte[] body) {
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=windows-1251");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
