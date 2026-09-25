package com.example.financetracker.ledger.api;

import java.net.URI;
import java.time.LocalDate;

import com.example.financetracker.ledger.EntryFilter;
import com.example.financetracker.ledger.EntryPage;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.EntryView;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journal entries. A request body is an entry command whose "kind" picks how its postings are built
 * ({@link EntryCommandJson}); a response is the entry with the postings that were built. An entry that breaks the
 * ledger's rules is refused with 422, listing every rule it breaks.
 */
@RestController
@RequestMapping("/api/entries")
class EntryController {

    private final EntryService entries;

    EntryController(EntryService entries) {
        this.entries = entries;
    }

    /**
     * The user's entries, newest first: by date, then by id, both descending. Every filter is optional.
     *
     * @param from the earliest entry date, inclusive
     * @param to the latest entry date, inclusive
     * @param accountId entries with a posting to this account
     * @param categoryId entries with a posting in this category
     * @param counterpartyId entries with this payee, or with a posting with this counterparty
     * @param q entries whose memo or payee's name contains this text, in any case
     * @param page the page, from 0
     * @param size entries per page
     */
    @GetMapping
    EntryPage list(CurrentUser user, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long counterpartyId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return entries.search(user.id(), new EntryFilter(from, to, accountId, categoryId, counterpartyId, q), page,
                size);
    }

    @GetMapping("/{id}")
    EntryView get(CurrentUser user, @PathVariable long id) {
        return entries.get(user.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<EntryView> create(CurrentUser user, @RequestBody EntryCommand command) {
        EntryView entry = entries.create(user.id(), command);
        return ResponseEntity.created(URI.create("/api/entries/" + entry.id())).body(entry);
    }

    /**
     * Replaces the entry and all its postings with those the command builds.
     *
     * @param version the version the caller read; if the entry has changed since, it is refused with 409
     */
    @PutMapping("/{id}")
    EntryView update(CurrentUser user, @PathVariable long id, @RequestParam int version,
            @RequestBody EntryCommand command) {
        return entries.update(user.id(), id, version, command);
    }

    /** @param version the version the caller read; if the entry has changed since, it is refused with 409 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable long id, @RequestParam int version) {
        entries.delete(user.id(), id, version);
    }
}
