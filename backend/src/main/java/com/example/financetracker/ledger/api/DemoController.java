package com.example.financetracker.ledger.api;

import java.time.LocalDate;

import com.example.financetracker.ledger.demo.DemoLedgerService;
import com.example.financetracker.ledger.demo.DemoLedgerView;
import com.example.financetracker.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The demo ledger, for trying the app without entering anything. */
@RestController
class DemoController {

    private final DemoLedgerService demo;

    DemoController(DemoLedgerService demo) {
        this.demo = demo;
    }

    /**
     * Fills the user's empty ledger with six months of invented entries in euros and US dollars, ending today, in one
     * transaction. A ledger with entries, counterparties, or accounts or categories other than the starter ledger's is
     * refused with 409.
     */
    @PostMapping("/api/demo-data")
    DemoLedgerView load(CurrentUser user) {
        return demo.load(user.id(), LocalDate.now());
    }
}
