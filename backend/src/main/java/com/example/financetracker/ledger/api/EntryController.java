package com.example.financetracker.ledger.api;

import java.net.URI;
import java.time.LocalDate;

import com.example.financetracker.ledger.EntryFilter;
import com.example.financetracker.ledger.EntryMatchFilter;
import com.example.financetracker.ledger.EntryMatchPage;
import com.example.financetracker.ledger.EntryMatchService;
import com.example.financetracker.ledger.EntryPage;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.EntryView;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryCommand;
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
    private final EntryMatchService matches;

    EntryController(EntryService entries, EntryMatchService matches) {
        this.entries = entries;
        this.matches = matches;
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
    EntryPage list(LedgerScope ledger, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long counterpartyId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return entries.search(ledger, new EntryFilter(from, to, accountId, categoryId, counterpartyId, q), page,
                size);
    }

    /**
     * The user's earlier expenses or incomes like the one being typed on New entry (UX-8, D-463), newest first. Every
     * criterion given must match, and at least one is needed (400 otherwise); a text counts from 2 characters.
     *
     * @param type EXPENSE or INCOME
     * @param payee words that must all appear in the entry's payee's name, in any case
     * @param memo words that must all appear in the entry's memo, in any case
     * @param accountId entries with a line on this account
     * @param categoryId entries whose categorized line, or family record, has this category
     * @param amount a plain decimal at or above 0: an amount whose characters contain it, or within 1 of it in
     *        {@code currency}
     * @param currency the form's currency; without it only the amount's characters count
     */
    @GetMapping("/matches")
    EntryMatchPage matches(LedgerScope ledger, @RequestParam CategoryType type,
            @RequestParam(required = false) @Size(max = 100) String payee,
            @RequestParam(required = false) @Size(max = 100) String memo,
            @RequestParam(required = false) Long accountId, @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @Size(max = 20) String amount,
            @RequestParam(required = false) @Size(max = 3) String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return matches.matches(ledger, EntryMatchFilter.of(type, payee, memo, accountId, categoryId, amount, currency),
                page, size);
    }

    @GetMapping("/{id}")
    EntryView get(LedgerScope ledger, @PathVariable long id) {
        return entries.get(ledger, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<EntryView> create(LedgerScope ledger, @RequestBody EntryCommand command) {
        EntryView entry = entries.create(ledger, command);
        return ResponseEntity.created(URI.create("/api/entries/" + entry.id())).body(entry);
    }

    /**
     * Replaces the entry and all its postings with those the command builds.
     *
     * @param version the version the caller read; if the entry has changed since, it is refused with 409
     */
    @PutMapping("/{id}")
    EntryView update(LedgerScope ledger, @PathVariable long id, @RequestParam int version,
            @RequestBody EntryCommand command) {
        return entries.update(ledger, id, version, command);
    }

    /** @param version the version the caller read; if the entry has changed since, it is refused with 409 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(LedgerScope ledger, @PathVariable long id, @RequestParam int version) {
        entries.delete(ledger, id, version);
    }
}
