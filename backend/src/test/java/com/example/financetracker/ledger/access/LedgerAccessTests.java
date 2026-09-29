package com.example.financetracker.ledger.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link LedgerAccess} against the V5 schema: a scope comes only from an ACTIVE membership, and anything else answers
 * like a ledger that doesn't exist. Family ledgers and LEFT or FORMER memberships are written here with plain SQL,
 * since no code creates them yet.
 */
class LedgerAccessTests extends IntegrationTest {

    @Autowired
    private LedgerAccess access;
    @Autowired
    private JdbcClient jdbc;

    private final String alice = UUID.randomUUID().toString();
    private final String bob = UUID.randomUUID().toString();

    @Test
    void aUsersScopeIsTheirPersonalLedger() {
        assertThat(mvc.get().uri("/api/accounts").with(member(alice)).exchange()).hasStatus(HttpStatus.OK);

        LedgerScope scope = access.personal(alice);

        long ledgerId = jdbc.sql("SELECT DISTINCT ledger_id FROM account WHERE user_id = ?").param(alice)
                .query(Long.class).single();
        long memberId = jdbc.sql("SELECT id FROM ledger_member WHERE user_sub = ?").param(alice).query(Long.class)
                .single();
        assertThat(scope.ledgerId()).isEqualTo(ledgerId);
        assertThat(scope.type()).isEqualTo(LedgerType.PERSONAL);
        assertThat(scope.memberId()).isEqualTo(memberId);
        assertThat(scope.role()).isEqualTo(MemberRole.OWNER);
        assertThat(scope.userId()).isEqualTo(alice);
        assertThat(access.provisionPersonal(alice)).isEqualTo(scope);
        assertThat(access.member(alice, ledgerId)).isEqualTo(scope);
        assertThat(access.provisionPersonal(bob).ledgerId()).isNotEqualTo(ledgerId);
    }

    @Test
    void aUserWithoutAPersonalLedgerHasNoScope() {
        assertThatThrownBy(() -> access.personal(alice)).isInstanceOf(NotFoundException.class)
                .hasMessage("Personal ledger not found");
    }

    /** Another user's ledger answers exactly like one that doesn't exist: NotFoundException, which is a 404. */
    @Test
    void anotherUsersLedgerIsRefusedAsIfItDidNotExist() {
        long alicesLedger = access.provisionPersonal(alice).ledgerId();
        access.provisionPersonal(bob);
        long missing = jdbc.sql("SELECT max(id) + 1000 FROM ledger").query(Long.class).single();

        assertThatThrownBy(() -> access.member(bob, alicesLedger)).isInstanceOf(NotFoundException.class)
                .hasMessage("Ledger %d not found", alicesLedger);
        assertThatThrownBy(() -> access.member(bob, missing)).isInstanceOf(NotFoundException.class)
                .hasMessage("Ledger %d not found", missing);
    }

    @Test
    void aFamilyLedgerIsReachedByAnActiveMembershipOnly() {
        long family = jdbc.sql("INSERT INTO ledger (type, name, base_currency) VALUES ('SHARED', 'Family', 'EUR') "
                + "RETURNING id").query(Long.class).single();
        long alicesMembership = join(family, alice, "OWNER");
        long bobsMembership = join(family, bob, "MEMBER");

        LedgerScope alicesScope = access.member(alice, family);
        assertThat(alicesScope.type()).isEqualTo(LedgerType.SHARED);
        assertThat(alicesScope.role()).isEqualTo(MemberRole.OWNER);
        assertThat(alicesScope.memberId()).isEqualTo(alicesMembership);
        LedgerScope bobsScope = access.member(bob, family);
        assertThat(bobsScope.role()).isEqualTo(MemberRole.MEMBER);
        assertThat(bobsScope.memberId()).isEqualTo(bobsMembership);
        // A family membership is not a personal ledger.
        assertThatThrownBy(() -> access.personal(bob)).isInstanceOf(NotFoundException.class);

        // Bob leaves (D-19).
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = ? WHERE id = ?")
                .params(LocalDate.of(2026, 9, 29), bobsMembership).update();
        assertThatThrownBy(() -> access.member(bob, family)).isInstanceOf(NotFoundException.class)
                .hasMessage("Ledger %d not found", family);

        // Alice deletes all her data, and her membership becomes FORMER, without her sub (D-20).
        jdbc.sql("UPDATE ledger_member SET status = 'FORMER', role = 'MEMBER', user_sub = NULL, left_date = ?, "
                + "display_name = 'Former member' WHERE id = ?").params(LocalDate.of(2026, 9, 29), alicesMembership)
                .update();
        assertThatThrownBy(() -> access.member(alice, family)).isInstanceOf(NotFoundException.class)
                .hasMessage("Ledger %d not found", family);
    }

    private long join(long ledgerId, String sub, String role) {
        return jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date)
                VALUES (?, 'SHARED', ?, 'Member', ?, 'ACTIVE', ?) RETURNING id""")
                .params(ledgerId, sub, role, LocalDate.of(2026, 9, 1)).query(Long.class).single();
    }
}
