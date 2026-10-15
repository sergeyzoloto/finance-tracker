package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.util.Map;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-182: a claim is atomic. What it posts commits together or not at all, and a claim that fails leaves the place
 * unclaimed, its records the owner's, and nothing in the claimer's ledger.
 */
class ClaimAtomicityApiTests extends MemberImportApiTest {

    @MockitoSpyBean
    private FamilyPostingService posting;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aClaimThatFailsHalfwayLeavesThePlaceUnclaimedAndNothingPosted() throws IOException {
        ownersFirstImport();
        Map<String, Long> partners = rowsOf(partner);
        Map<String, Long> owners = rowsOf(owner);
        String authors = authors();
        // Everything of the claim is written, then it fails before the commit. join() takes part in its caller's
        // transaction only, so it is stubbed inside one.
        new TransactionTemplate(transactions).executeWithoutResult(status -> doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("The connection went away");
        }).when(posting).join(any(LedgerScope.class)));
        String token = claimInvite("2024-02-01");
        Map<String, Long> withInvite = rowsOf(owner);

        assertThat(inviteCall(partner, "accept", token(token, "\"displayName\": \"Sam B\", \"categoryIds\": []")))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(rowsOf(partner)).isEqualTo(partners);
        assertThat(rowsOf(owner)).isEqualTo(withInvite);
        assertThat(owners.get("family ledger_invite") + 1).isEqualTo(withInvite.get("family ledger_invite"));
        assertThat(authors()).isEqualTo(authors);
        assertThat(jdbc.sql("SELECT user_sub IS NULL AND NOT claimed_seat FROM ledger_member WHERE id = ?")
                .param(seat).query(Boolean.class).single()).isTrue();
        // The invite still waits for its holder, who may try again.
        assertThat(inviteCall(partner, "lookup", token(token, null))).hasStatus(HttpStatus.OK);
    }

    private String authors() {
        return jdbc.sql("""
                SELECT string_agg(id || ':' || author_member_id, ',' ORDER BY id) FROM family_record WHERE ledger_id = ?""")
                .param(family).query(String.class).single();
    }
}
