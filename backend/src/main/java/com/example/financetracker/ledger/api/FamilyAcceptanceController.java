package com.example.financetracker.ledger.api;

import java.util.List;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.family.FamilyAcceptance;
import com.example.financetracker.ledger.family.FamilyAcceptance.AcceptView;
import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Family acceptance (HIST-3; ADR 0006, 7.3): the caller's pending changes of a family budget and their catch-up
 * accept, for its ACTIVE members only, anyone else getting 404. Only while both the family budget's switch and
 * acceptance's are on: otherwise these paths are unknown and answer 404.
 */
@RestController
@RequestMapping("/api/family-ledgers/{ledgerId}")
@ConditionalOnProperty(name = {FamilySwitch.PROPERTY, FamilyAcceptanceSwitch.PROPERTY}, havingValue = "true")
class FamilyAcceptanceController {

    /**
     * The caller's pending changes.
     *
     * @param count how many records have changes they have not accepted
     * @param recordIds those records, by id
     */
    record PendingView(int count, List<Long> recordIds) {
    }

    private final FamilyAcceptance acceptance;
    private final LedgerAccess access;

    FamilyAcceptanceController(FamilyAcceptance acceptance, LedgerAccess access) {
        this.acceptance = acceptance;
        this.access = access;
    }

    /** The records whose changes the caller has not accepted yet. */
    @GetMapping("/pending")
    PendingView pending(CurrentUser user, @PathVariable long ledgerId) {
        List<Long> records = acceptance.pendingForMe(access.member(user.id(), ledgerId));
        return new PendingView(records.size(), records);
    }

    /** Accepts every pending change at once, bringing them into the caller's books; 0 when nothing was pending. */
    @PostMapping("/accept")
    AcceptView accept(CurrentUser user, @PathVariable long ledgerId) {
        return acceptance.accept(access.member(user.id(), ledgerId));
    }
}
