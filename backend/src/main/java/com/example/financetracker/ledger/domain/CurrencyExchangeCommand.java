package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.FX_EXCHANGE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Amount A in currency X exchanged into amount B in currency Y (rule 9): the source account −A in X, FX_EXCHANGE
 * +A in X, FX_EXCHANGE −B in Y, the target account +B in Y. Each currency balances on its own.
 */
public record CurrencyExchangeCommand(LocalDate entryDate, Long payeeId, String memo, Long fromAccountId,
        String fromCurrency, BigDecimal fromAmount, Long toAccountId, String toCurrency, BigDecimal toAmount)
        implements EntryCommand {

    public CurrencyExchangeCommand {
        Problems.ofEntry(entryDate)
                .present(fromAccountId, "source account")
                .present(fromCurrency, "source currency")
                .positive(fromAmount, "source amount")
                .present(toAccountId, "target account")
                .present(toCurrency, "target currency")
                .positive(toAmount, "target amount")
                .check(fromCurrency == null || !fromCurrency.equals(toCurrency),
                        "the two currencies must differ; money moved within one currency is a transfer")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.CURRENCY_EXCHANGE;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        long fxExchange = context.account(FX_EXCHANGE);
        return List.of(
                PostingLine.of(fromAccountId, fromCurrency, fromAmount.negate()),
                PostingLine.of(fxExchange, fromCurrency, fromAmount),
                PostingLine.of(fxExchange, toCurrency, toAmount.negate()),
                PostingLine.of(toAccountId, toCurrency, toAmount));
    }
}
