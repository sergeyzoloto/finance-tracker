package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a rule of the import makes an account's displayed balance differ from the file's on a date (D-68): the app's
 * change less the file's, by the account's code and currency, with the rule. The reconciliation adds them up to each
 * month-end and reports the sum as explained.
 *
 * @param code the account's code as the ledger has it; UNALLOCATED for the equity side, FAMILY_DEBT for the family
 *        budget's debt account
 */
record Drift(String code, String currency, LocalDate date, BigDecimal amount, Reason reason) {

    enum Reason {
        /** D-12: each share cut down to the minor unit, the rest to the payer, where Excel keeps exact halves. */
        SHARES,
        /** D-74, D-83: an amount rounded half up to the currency's minor unit, where Excel keeps more decimals. */
        MINOR_UNIT,
        /** An amount rounded half up to the 4 decimals a posting holds (an agent's choice, D3b). */
        FOUR_DECIMALS,
        /**
         * D-188: a member's settlement row on its own date in Excel, and his side of the settlement it matches on the
         * settlement's date in the app (D-178).
         */
        SETTLEMENT_DATE,
        /**
         * D-223: a personal entry changed in the app while its row stayed as imported (D-219's "changed in the app,
         * the file unchanged"): what the ledger holds less what the row maps to, on each one's date.
         */
        APP_EDITED,
        /**
         * D4b: an entry of the ledger that no row of the file stands for, as no single row can (D-248: the opening
         * balances, a claim's opening balance, a return's correction, an entry of another shape): what it moves.
         */
        NO_ROW,
        /**
         * D-263: a typed amount the transactions file shows rounded (its cell formatted to fewer decimals), whose whole
         * value the row's computed columns keep: what the import builds of the shown amount less what they move.
         */
        SHOWN_ROUNDED
    }
}
