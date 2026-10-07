package com.example.financetracker.ledger.api;

import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.demo.DemoLedgerService;
import com.example.financetracker.ledger.demo.DemoLedgerView;
import com.example.financetracker.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The demo ledger, for trying the app without entering anything. */
@RestController
class DemoController {

    private final DemoLedgerService demo;
    private final Today today;

    DemoController(DemoLedgerService demo, Today today) {
        this.demo = demo;
        this.today = today;
    }

    /**
     * Fills the user's empty ledger with six months of invented entries in euros and US dollars, ending today (the
     * user's today, {@link Today}, D-53, D-100), in one
     * transaction, and while the family budget is switched on also creates the demo's family budget with an invented
     * partner (H1), in which the user's name is their account's. A ledger with entries, counterparties, or accounts or
     * categories other than the starter ledger's is refused with 409.
     */
    @PostMapping("/api/demo-data")
    DemoLedgerView load(CurrentUser user, LedgerScope ledger) {
        return demo.load(ledger, today.date(ledger), user.name());
    }
}
