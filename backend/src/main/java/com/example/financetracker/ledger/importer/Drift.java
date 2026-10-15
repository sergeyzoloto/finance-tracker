package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a rule of the import makes an account's displayed balance differ from Excel's on a date (D-68): the app's
 * change less Excel's, by the account's code and currency, with the rule. The reconciliation adds them up to each
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
         * D-169: the owner's D-84 rows of a month, typed by hand as half of the partner's family incomes rounded to a
         * cent, less that exact half; within half a cent per income.
         */
        OWNER_HALF,
        /**
         * D-180: a member's family debt is compared as movement since his first day: his daily balance file's first
         * balance, which the app doesn't have, and the opening balance his claim posted on the join date (D-31).
         */
        CLAIM_OPENING,
        /**
         * D-188: a member's settlement row on its own date in Excel, and his side of the settlement it matches on the
         * settlement's date in the app (D-178).
         */
        SETTLEMENT_DATE
    }
}
