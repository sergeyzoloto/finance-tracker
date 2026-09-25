package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.api.AccountController.NOT_BLANK;

import java.util.List;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.CounterpartyChanges;
import com.example.financetracker.ledger.CounterpartyService;
import com.example.financetracker.ledger.CounterpartyView;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/counterparties")
class CounterpartyController {

    /**
     * @param name unique among the user's counterparties, regardless of case
     * @param kind null for unclassified
     */
    record NewCounterparty(@NotBlank @Size(max = 100) String name, Counterparty.Kind kind) {
    }

    /**
     * Rename, classify, archive or restore a counterparty; fields left out stay as they are. A class rather than a
     * record, because a kind sent as null clears it, while one left out keeps it.
     */
    static final class CounterpartyPatch {

        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK, message = "must not be blank")
        private String name;
        private Boolean archived;
        private Counterparty.Kind kind;
        private boolean changesKind;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** True archives the counterparty, false restores it. */
        public Boolean getArchived() {
            return archived;
        }

        public void setArchived(Boolean archived) {
            this.archived = archived;
        }

        /** Null for unclassified. */
        public Counterparty.Kind getKind() {
            return kind;
        }

        public void setKind(Counterparty.Kind kind) {
            this.kind = kind;
            this.changesKind = true;
        }

        CounterpartyChanges changes() {
            return new CounterpartyChanges(name == null ? null : name.strip(), archived, changesKind, kind);
        }
    }

    private final CounterpartyService counterparties;

    CounterpartyController(CounterpartyService counterparties) {
        this.counterparties = counterparties;
    }

    /**
     * All the user's counterparties, archived ones included, by name. Each comes with the category of its most recent
     * categorized entry as payee, to preselect in the next one.
     */
    @GetMapping
    List<CounterpartyView> list(CurrentUser user) {
        return counterparties.list(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CounterpartyView create(CurrentUser user, @Valid @RequestBody NewCounterparty counterparty) {
        return counterparties.create(user.id(), counterparty.name().strip(), counterparty.kind());
    }

    @PatchMapping("/{id}")
    CounterpartyView update(CurrentUser user, @PathVariable long id, @Valid @RequestBody CounterpartyPatch patch) {
        return counterparties.update(user.id(), id, patch.changes());
    }
}
