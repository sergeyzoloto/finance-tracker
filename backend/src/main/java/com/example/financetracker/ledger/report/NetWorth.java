package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;

/**
 * What the user owns less what they owe, in one currency. Every amount is {@link Money#normalize}d.
 *
 * @param assets the displayed balances of all ASSET accounts (rule 4)
 * @param liabilities the displayed balances of all LIABILITY accounts, positive for money owed
 * @param netWorth {@code assets} minus {@code liabilities}
 */
public record NetWorth(String currency, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {

    public NetWorth {
        assets = Money.normalize(assets);
        liabilities = Money.normalize(liabilities);
        netWorth = Money.normalize(netWorth);
    }
}
