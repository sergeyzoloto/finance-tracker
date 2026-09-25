package com.example.financetracker.ledger.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * A request to write one journal entry. Each kind of entry has its own command, and its {@link #postings} method is the
 * pure builder that turns the command into postings by the domain rules. A command can't be constructed with fields
 * its builder can't work with: the constructor throws {@link InvalidEntryException} naming every such field.
 */
public sealed interface EntryCommand permits ExpenseCommand, IncomeCommand, TransferCommand, SharedExpenseCommand,
        LoanGivenCommand, LoanRepaidCommand, CurrencyExchangeCommand, OpeningBalanceCommand, ManualCommand {

    LocalDate entryDate();

    /** The counterparty the entry is with, if any. */
    default Long payeeId() {
        return null;
    }

    String memo();

    EntryKind kind();

    /**
     * @throws InvalidEntryException if the user lacks an account the command posts to on its own, such as
     *         UNALLOCATED
     */
    List<PostingLine> postings(LedgerContext context);

    default EntryDraft draft(LedgerContext context) {
        return new EntryDraft(entryDate(), kind(), payeeId(), memo(), postings(context));
    }
}
