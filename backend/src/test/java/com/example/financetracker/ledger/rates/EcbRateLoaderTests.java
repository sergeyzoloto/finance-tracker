package com.example.financetracker.ledger.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.ExchangeRate;
import com.example.financetracker.ledger.ExchangeRateRepository;
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
 * {@link EcbRateLoader} against a stand-in for the ECB's two files on a local port. The ECB's rates are shared by all
 * users, so each test starts and ends without any; other tests use manual rates, which are their users' own.
 */
class EcbRateLoaderTests extends IntegrationTest {

    private static final LocalDate FRI = LocalDate.of(2026, 9, 25);
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 29);
    private static final LocalDate WED = LocalDate.of(2026, 9, 30);

    @Autowired
    private ExchangeRateRepository rates;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private HttpServer ecb;
    private volatile byte[] daily;
    private volatile byte[] history;
    private final AtomicInteger historyDownloads = new AtomicInteger();
    private EcbRateLoader loader;

    @BeforeEach
    void startEcb() throws IOException {
        deleteEcbRates();
        ecb = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ecb.createContext("/eurofxref-daily.xml", exchange -> respond(exchange, daily));
        ecb.createContext("/eurofxref-hist.zip", exchange -> {
            historyDownloads.incrementAndGet();
            respond(exchange, history);
        });
        ecb.start();
        String url = "http://localhost:" + ecb.getAddress().getPort();
        EcbProperties properties = new EcbProperties(true, URI.create(url + "/eurofxref-daily.xml"),
                URI.create(url + "/eurofxref-hist.zip"), "-");
        loader = new EcbRateLoader(new EcbClient(RestClient.builder(), properties), rates, jdbc, transactions);

        daily = daily(FRI, "USD", "1.1403");
        history = EcbClientTests.zip("eurofxref-hist.csv", EcbClientTests.HISTORY);
    }

    @AfterEach
    void stopEcb() {
        ecb.stop(0);
        deleteEcbRates();
    }

    @Test
    void theFirstLoadReadsTheWholeHistory() {
        EcbRateLoader.Result result = loader.load();

        assertThat(result.fromHistory()).isTrue();
        assertThat(result.days()).containsExactly(LocalDate.of(2004, 12, 31), LocalDate.of(2022, 3, 1), FRI);
        // The history's three days, with the daily file's day as the daily file has it.
        assertThat(result.rates()).isEqualTo(3 + 3 + 1);
        assertThat(ecbRate(LocalDate.of(2022, 3, 1), "RUB")).isEqualByComparingTo("117.201");
        assertThat(ecbRate(FRI, "USD")).isEqualByComparingTo("1.1403");
        assertThat(rates.latestSharedDate()).contains(FRI);
    }

    @Test
    void theNextWorkingDayAfterAWeekendNeedsOnlyTheDailyFile() {
        loader.load();
        daily = daily(MON, "USD", "1.1500");

        EcbRateLoader.Result result = loader.load();

        assertThat(result.fromHistory()).isFalse();
        assertThat(result.days()).containsExactly(MON);
        assertThat(historyDownloads).hasValue(1);
        assertThat(ecbRate(MON, "USD")).isEqualByComparingTo("1.15");
    }

    @Test
    void aWorkingDayThatWasNotLoadedIsFilledFromTheHistory() {
        loader.load();
        daily = daily(WED, "USD", "1.1600");
        history = EcbClientTests.zip("eurofxref-hist.csv", """
                Date,USD,
                2026-09-30,1.16,
                2026-09-29,1.155,
                2026-09-28,1.15,
                2026-09-25,1.1403,
                """);

        EcbRateLoader.Result result = loader.load();

        assertThat(result.fromHistory()).isTrue();
        assertThat(result.days()).containsExactly(FRI, MON, TUE, WED);
        assertThat(ecbRate(TUE, "USD")).isEqualByComparingTo("1.155");
    }

    @Test
    void aFileThatIsNotTheEcbsWritesNothing() {
        daily = "<html>Maintenance</html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(loader::load).isInstanceOf(EcbClient.EcbFormatException.class);
        assertThat(rates.latestSharedDate()).isEmpty();
    }

    @Test
    void aManualRateOfTheSameDayIsKept() {
        String user = UUID.randomUUID().toString();
        rates.save(new ExchangeRate(FRI, "EUR", "USD", new BigDecimal("1.20"), "MANUAL", user));

        loader.load();

        assertThat(rates.find(FRI, "EUR", "USD", user).orElseThrow().rate()).isEqualByComparingTo("1.20");
        assertThat(ecbRate(FRI, "USD")).isEqualByComparingTo("1.1403");
        jdbc.sql("DELETE FROM exchange_rate WHERE user_id = ?").param(user).update();
    }

    private BigDecimal ecbRate(LocalDate day, String currency) {
        return rates.find(day, "EUR", currency, null).map(ExchangeRate::rate).orElseThrow();
    }

    private void deleteEcbRates() {
        jdbc.sql("DELETE FROM exchange_rate WHERE user_id IS NULL").update();
    }

    private static byte[] daily(LocalDate day, String currency, String rate) {
        return EcbClientTests.DAILY.replaceAll("<Cube time='[^']*'>", "<Cube time='%s'>".formatted(day))
                .replaceAll("\\s*<Cube currency='(?!USD)[^']*' rate='[^']*'/>", "")
                .replace("<Cube currency='USD' rate='1.1403'/>",
                        "<Cube currency='%s' rate='%s'/>".formatted(currency, rate))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, byte[] body) {
        try (exchange) {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
