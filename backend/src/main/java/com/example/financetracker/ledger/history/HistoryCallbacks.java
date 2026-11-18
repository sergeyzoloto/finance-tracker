package com.example.financetracker.ledger.history;

import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.JournalEntry;
import com.example.financetracker.ledger.LedgerCategory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

/**
 * Every save of a personal entry, account, category or payee through its repository writes the record's version
 * (ADR 0006, 2.2): one place for the services, the importer and the family code that save through the repositories. A
 * save that changes nothing writes nothing (D-995). Deletes and the raw SQL writes call {@link HistoryService} where
 * they happen.
 */
@Configuration
class HistoryCallbacks {

    /** Whether the save in progress on this thread inserts a new row: known before it, lost after it. */
    private static final ThreadLocal<Boolean> INSERTING = new ThreadLocal<>();

    private static void saving(Object id) {
        INSERTING.set(id == null);
    }

    private static boolean inserted() {
        boolean inserted = Boolean.TRUE.equals(INSERTING.get());
        INSERTING.remove();
        return inserted;
    }

    @Bean
    BeforeConvertCallback<JournalEntry> entrySaving() {
        return new BeforeConvertCallback<>() {
            @Override
            public JournalEntry onBeforeConvert(JournalEntry entry) {
                saving(entry.id());
                return entry;
            }
        };
    }

    @Bean
    BeforeConvertCallback<Account> accountSaving() {
        return new BeforeConvertCallback<>() {
            @Override
            public Account onBeforeConvert(Account account) {
                saving(account.id());
                return account;
            }
        };
    }

    @Bean
    BeforeConvertCallback<LedgerCategory> categorySaving() {
        return new BeforeConvertCallback<>() {
            @Override
            public LedgerCategory onBeforeConvert(LedgerCategory category) {
                saving(category.id());
                return category;
            }
        };
    }

    @Bean
    BeforeConvertCallback<Counterparty> counterpartySaving() {
        return new BeforeConvertCallback<>() {
            @Override
            public Counterparty onBeforeConvert(Counterparty counterparty) {
                saving(counterparty.id());
                return counterparty;
            }
        };
    }

    @Bean
    AfterSaveCallback<JournalEntry> entryHistory(HistoryService history) {
        return new AfterSaveCallback<>() {
            @Override
            public JournalEntry onAfterSave(JournalEntry entry) {
                if (inserted()) {
                    history.created(entry.ledgerId(), RecordType.ENTRY, entry.id(), RecordContents.of(entry));
                } else {
                    history.changed(entry.ledgerId(), RecordType.ENTRY, entry.id());
                }
                return entry;
            }
        };
    }

    @Bean
    AfterSaveCallback<Account> accountHistory(HistoryService history) {
        return new AfterSaveCallback<>() {
            @Override
            public Account onAfterSave(Account account) {
                history.changed(account.ledgerId(), RecordType.ACCOUNT, account.id(), inserted());
                return account;
            }
        };
    }

    @Bean
    AfterSaveCallback<LedgerCategory> categoryHistory(HistoryService history) {
        return new AfterSaveCallback<>() {
            @Override
            public LedgerCategory onAfterSave(LedgerCategory category) {
                history.changed(category.ledgerId(),
                        category.userId() == null ? RecordType.FAMILY_CATEGORY : RecordType.CATEGORY, category.id(),
                        inserted());
                return category;
            }
        };
    }

    @Bean
    AfterSaveCallback<Counterparty> counterpartyHistory(HistoryService history) {
        return new AfterSaveCallback<>() {
            @Override
            public Counterparty onAfterSave(Counterparty counterparty) {
                history.changed(counterparty.ledgerId(), RecordType.COUNTERPARTY, counterparty.id(), inserted());
                return counterparty;
            }
        };
    }
}
