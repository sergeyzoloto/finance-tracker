package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * UX-11, D-524: what every entry the entry list's filters match adds up to, on every page, as the list's cells show
 * them. Read only, in one snapshot.
 *
 * @param count how many entries match
 * @param firstDate the earliest entry's date, or null for none
 * @param lastDate the latest entry's date, or null for none
 * @param payees the distinct payees as the Payee cells show them (the entry's payee, else its lines' counterparties,
 *        else its family record's payee, D-523), names compared ignoring case
 * @param withoutPayee the entries whose Payee cell shows none
 * @param categories the distinct categories as the Category cells show them (the categorized lines', else the family
 *        record's, D-477)
 * @param withoutCategory the entries whose Category cell shows none
 * @param accounts the distinct accounts the Accounts cells name: the lines without a category, FX_EXCHANGE left out of
 *        an entry that has any other line
 * @param currencies per currency, the Amount cells' figures by their sign
 */
public record EntrySummary(long count, LocalDate firstDate, LocalDate lastDate, long payees, long withoutPayee,
        long categories, long withoutCategory, long accounts, List<CurrencyTotals> currencies) {

    /**
     * One currency's Amount cells, each the sum of the entry's positive lines in it (rule 2), by the sign the row shows,
     * which the net of its categorized lines decides (rule 5): {@code incoming} shown with +, income and refunds;
     * {@code outgoing} shown with −, expenses; {@code transfers} shown without a sign. Each at or above 0.
     */
    public record CurrencyTotals(String currency, BigDecimal incoming, BigDecimal outgoing, BigDecimal transfers) {
    }
}
