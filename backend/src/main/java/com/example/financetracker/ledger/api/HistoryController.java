package com.example.financetracker.ledger.api;

import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.InvalidRequestException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.RecordHistory;
import com.example.financetracker.ledger.history.RecordHistory.HistoryPage;
import com.example.financetracker.ledger.history.RecordType;
import com.example.financetracker.ledger.history.RestorePlanner;
import com.example.financetracker.ledger.history.SnapshotService;
import com.example.financetracker.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * A record's history, its versions newest first (ADR 0006, section 13, D-1086): an entry, account, category or
 * counterparty of the caller's personal ledger, for its owner only; a family record for the ACTIVE members of its
 * family budget only, and only with the family switch on.
 */
@RestController
class HistoryController {

    private static final List<RecordType> PERSONAL =
            List.of(RecordType.ENTRY, RecordType.ACCOUNT, RecordType.CATEGORY, RecordType.COUNTERPARTY);

    private final RecordHistory history;
    private final FamilyRecordService records;
    private final LedgerAccess access;
    private final FamilySwitch family;
    private final RestorePlanner restores;

    HistoryController(RecordHistory history, FamilyRecordService records, LedgerAccess access, FamilySwitch family,
            RestorePlanner restores) {
        this.restores = restores;
        this.history = history;
        this.records = records;
        this.access = access;
        this.family = family;
    }

    /**
     * @param recordType ENTRY, ACCOUNT, CATEGORY, COUNTERPARTY or FAMILY_RECORD
     */
    @GetMapping("/api/history/{recordType}/{recordId}")
    HistoryPage history(CurrentUser user, LedgerScope personal, HttpServletRequest request,
            @PathVariable String recordType, @PathVariable long recordId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) throws NoResourceFoundException {
        Optional<RecordType> type = PERSONAL.stream().filter(t -> t.name().equals(recordType)).findFirst();
        if (type.isPresent()) {
            return history.personal(personal, type.get(), recordId, page, size);
        }
        if (!RecordType.FAMILY_RECORD.name().equals(recordType) || !family.enabled()) {
            // As an unknown path: there is no such history.
            throw new NoResourceFoundException(HttpMethod.GET, request.getRequestURI().substring(1));
        }
        // The record's family budget is one the caller is an ACTIVE member of, or the record answers as missing.
        for (LedgerScope scope : access.families(user.id())) {
            if (history.exists(scope, RecordType.FAMILY_RECORD, recordId)) {
                return records.history(scope, recordId, page, size);
            }
        }
        throw new NotFoundException("Family record " + recordId + " not found");
    }

    /**
     * The read-only plan of restoring one record to its version {@code seq} (HIST-4 part 2a): nothing is written. A
     * family record's versions have no restore yet (409); an unknown record type is refused (400).
     */
    @PostMapping("/api/history/{recordType}/{recordId}/versions/{seq}/restore-preview")
    RestorePlanner.Preview restorePreview(LedgerScope personal, @PathVariable String recordType,
            @PathVariable long recordId, @PathVariable int seq) {
        Optional<RecordType> type = PERSONAL.stream().filter(t -> t.name().equals(recordType)).findFirst();
        if (type.isPresent()) {
            return restores.ofVersion(personal, type.get(), recordId, seq).preview();
        }
        if (recordType.startsWith("FAMILY_")) {
            throw new ConflictException("A family budget has no restores yet", SnapshotService.FAMILY_UNSUPPORTED);
        }
        throw new InvalidRequestException("Unknown record type " + recordType, "RECORD_TYPE_UNKNOWN");
    }
}
