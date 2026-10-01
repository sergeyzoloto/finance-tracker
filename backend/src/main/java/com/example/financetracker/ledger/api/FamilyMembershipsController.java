package com.example.financetracker.ledger.api;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyMembershipService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own family memberships as "Delete all my data" would touch them (F6a, D-20), for its
 * confirmation screen. Only with the family switch on (D-25); otherwise the path is unknown and answers 404, and the
 * screen is as before.
 */
@RestController
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class FamilyMembershipsController {

    private final LedgerAccess access;
    private final FamilyMembershipService memberships;

    FamilyMembershipsController(LedgerAccess access, FamilyMembershipService memberships) {
        this.access = access;
        this.memberships = memberships;
    }

    /**
     * Each family budget the user is an ACTIVE member of, by name: their role and balance, and whether it stays,
     * passes to another owner (named) or is deleted, their invites that stop working, and whether the split rule goes
     * back to equal shares; and how many they left, of which nothing else is said.
     */
    @GetMapping("/api/me/family-memberships")
    FamilyMembershipService.Memberships memberships(CurrentUser user, LedgerScope personal) {
        return memberships.deletionPreview(personal, access.families(user.id()));
    }
}
