package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilyInvariants;

/**
 * The accepting mode of the random family classes (HIST-3, D-1168): with acceptance on, after every step each ACTIVE
 * member with an account accepts through {@code POST /accept}; then nothing is pending and the invariants hold, those
 * of acceptance ({@link FamilyInvariants#checkAccepting}) and the classic ones.
 */
final class AcceptingMode {

    private AcceptingMode() {
    }

    static Map<Long, BigDecimal> everybodyAccepts(LedgerApiTest test, long family) throws IOException {
        for (String user : test.jdbc.sql("""
                SELECT user_sub FROM ledger_member
                WHERE ledger_id = ? AND status = 'ACTIVE' AND user_sub IS NOT NULL ORDER BY id""")
                .param(family).query(String.class).list()) {
            test.ok(test.post(user, "/api/family-ledgers/" + family + "/accept", "{}"));
            assertThat(test.ok(test.get(user, "/api/family-ledgers/" + family + "/pending")).get("count").asInt())
                    .as("pending for %s after accepting", user).isZero();
        }
        FamilyInvariants.checkAccepting(test.jdbc, family);
        return FamilyInvariants.check(test.jdbc, family);
    }
}
