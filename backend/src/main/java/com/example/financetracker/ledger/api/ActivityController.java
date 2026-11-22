package com.example.financetracker.ledger.api;

import java.util.List;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.ActivityLog;
import com.example.financetracker.ledger.history.ActivityLog.ActivityPage;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The activity list (ADR 0006, section 13, D-1098): the caller's own events, such as sign-ins and changes of their
 * settings, and with the family switch on the events of every family budget they are an ACTIVE member of.
 */
@RestController
class ActivityController {

    private final ActivityLog activity;
    private final LedgerAccess access;
    private final FamilySwitch family;

    ActivityController(ActivityLog activity, LedgerAccess access, FamilySwitch family) {
        this.activity = activity;
        this.access = access;
        this.family = family;
    }

    @GetMapping("/api/activity")
    ActivityPage activity(CurrentUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        boolean familyOn = family.enabled();
        return activity.page(user.id(), familyOn ? access.families(user.id()) : List.of(), familyOn, page, size);
    }
}
