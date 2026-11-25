package com.example.financetracker.ledger.api;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.SnapshotService;
import com.example.financetracker.ledger.history.SnapshotService.SnapshotPage;
import com.example.financetracker.ledger.history.SnapshotService.SnapshotView;
import com.example.financetracker.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Snapshots of the caller's personal ledger (ADR 0006, HIST-4 part 1), for its owner only. A family budget's snapshots
 * are refused with the code {@code FAMILY_SNAPSHOTS_UNSUPPORTED} until HIST-5.
 */
@RestController
@RequestMapping("/api")
class SnapshotController {

    /** @param name optional; trimmed, 1 to 80 characters; absent or blank is the date and time */
    record NewSnapshot(String name) {
    }

    private final SnapshotService snapshots;
    private final LedgerAccess access;
    private final FamilySwitch family;

    SnapshotController(SnapshotService snapshots, LedgerAccess access, FamilySwitch family) {
        this.snapshots = snapshots;
        this.access = access;
        this.family = family;
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    SnapshotView create(LedgerScope personal, @RequestBody(required = false) NewSnapshot body) {
        return snapshots.create(personal, body == null ? null : body.name());
    }

    /** Newest first, offset paging. */
    @GetMapping("/snapshots")
    SnapshotPage list(LedgerScope personal, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return snapshots.list(personal, page, size);
    }

    @GetMapping("/snapshots/{id}")
    SnapshotView get(LedgerScope personal, @PathVariable long id) {
        return snapshots.get(personal, id);
    }

    /** A family budget's snapshots: refused for its members (409 with a code), unknown for anyone else. */
    @PostMapping("/family-ledgers/{ledgerId}/snapshots")
    SnapshotView createFamily(CurrentUser user, HttpServletRequest request, @PathVariable long ledgerId)
            throws NoResourceFoundException {
        return snapshots.create(familyScope(user, request, ledgerId), null);
    }

    /** No paging: every family request is refused until HIST-5, which must add paging here as the personal list has. */
    @GetMapping("/family-ledgers/{ledgerId}/snapshots")
    SnapshotPage listFamily(CurrentUser user, HttpServletRequest request, @PathVariable long ledgerId)
            throws NoResourceFoundException {
        return snapshots.list(familyScope(user, request, ledgerId), 0, 50);
    }

    private LedgerScope familyScope(CurrentUser user, HttpServletRequest request, long ledgerId)
            throws NoResourceFoundException {
        if (!family.enabled()) {
            throw new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()),
                    request.getRequestURI().substring(1));
        }
        return access.member(user.id(), ledgerId);
    }
}
