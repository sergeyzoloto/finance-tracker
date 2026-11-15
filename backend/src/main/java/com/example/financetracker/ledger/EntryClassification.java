package com.example.financetracker.ledger;

/**
 * The one place that classifies entries as the Entries summary does (D-893; UX-18, D-796 to D-800, D-880): the net of
 * an entry's categorized lines says income (below 0), expenses (above 0) or a transfer (0); a family record counts once,
 * through the user's matched share entry (a live SHARE link), and the paying side of a counted record goes with it. The
 * summary, the Direction filter, the breakdown and the monthly chart all take their CTEs from here, so that they agree
 * by construction.
 *
 * <p>The fragment is a list of CTEs, each starting with a comma, named {@code <prefix>direction} and so on, to put after a CTE named {@code base} with the columns
 * {@code id} and {@code kind} (the matching entries). It needs the parameter {@code rpOn} (the family switch, as
 * {@link RecordPayees#params} puts it). It defines:
 * <ul>
 * <li>{@code direction (id, net)}: per entry with postings, the sign of the net of its categorized lines;</li>
 * <li>{@code counted (id, record_id, currency, full_amount)}: the share entry that counts each live expense or income
 * record once, with the record's full amount;</li>
 * <li>{@code shown_once (id, share_id)}: the paying side (a live PAYMENT link of a FAMILY_PAYMENT entry) of a counted
 * record, with the share entry that counts it;</li>
 * <li>{@code classified (id, net)}: {@code direction}, a paying side taking its counted share's.</li>
 * </ul>
 */
final class EntryClassification {

    private EntryClassification() {
    }

    /** The CTEs after {@code base}, each starting with a comma, their names starting with {@code prefix}. */
    static String ctes(String base, String prefix) {
        return """
                , %2$sdirection AS (
                    SELECT %1$s.id, sign(coalesce(sum(p.amount) FILTER (WHERE p.category_id IS NOT NULL), 0)) AS net
                    FROM %1$s JOIN posting p ON p.entry_id = %1$s.id GROUP BY %1$s.id
                ), %2$scounted AS (
                    SELECT DISTINCT ON (r.id) %1$s.id, r.id AS record_id, r.currency, abs(r.base_amount) AS full_amount
                    FROM %1$s
                    JOIN family_entry_link l ON l.entry_id = %1$s.id AND l.link_type = 'SHARE' AND l.detached_at IS NULL
                    JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                    WHERE :rpOn AND %1$s.kind = 'FAMILY_SHARE' AND r.type IN ('EXPENSE', 'INCOME')
                      AND r.deleted_at IS NULL
                    ORDER BY r.id, %1$s.id
                ), %2$sshown_once AS (
                    SELECT %1$s.id, c.id AS share_id FROM %1$s
                    JOIN family_entry_link l ON l.entry_id = %1$s.id AND l.link_type = 'PAYMENT'
                      AND l.detached_at IS NULL
                    JOIN %2$scounted c ON c.record_id = l.record_id
                    WHERE %1$s.kind = 'FAMILY_PAYMENT'
                ), %2$sclassified AS (
                    SELECT d.id, coalesce(paid.net, d.net) AS net FROM %2$sdirection d
                    LEFT JOIN (SELECT s.id, n.net FROM %2$sshown_once s JOIN %2$sdirection n ON n.id = s.share_id) paid
                      ON paid.id = d.id
                )""".formatted(base, prefix);
    }
}
