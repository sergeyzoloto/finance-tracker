package com.example.financetracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Map;

import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.UserDataService;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.rates.EcbRateLoader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The ledger's scoping rules (ADR 0003, topic C), checked on the compiled application classes so that a query that
 * forgets its ledger fails the build instead of relying on review. The triggers catch such a mistake for writes, and
 * the isolation tests for the reads they make.
 */
class ArchitectureTests {

    /**
     * Code that is about the person, or about rows shared by all users, rather than about one ledger: it runs SQL
     * without a LedgerScope. Each takes the user id as a parameter where it touches a user's rows, never reads the
     * security context (the last rule below), and is covered by DataIsolationApiTests, UserDataApiTests or
     * EcbRateLoaderTests. Keep this list short: anything that reads or writes ledger rows takes a LedgerScope.
     */
    static final Map<Class<?>, String> PERSON_LEVEL = Map.of(
            LedgerAccess.class,
            "decides access itself: finds the user's memberships by the sub, before there is a scope",
            UserDataService.class,
            "the person as a whole: provisions the users row, and deletes all of a user's data by the sub",
            UserSettingsRepository.class,
            "the settings are the person's, keyed by the sub (1:1 with the personal ledger)",
            ExchangeRateRepository.class,
            "rates belong to no ledger: the ECB's are shared by all users, a manual one is the person's",
            EcbRateLoader.class,
            "writes the ECB's rates, which belong to no user; runs on a schedule, never for a request");

    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.financetracker");

    @Test
    void sqlRunsOnlyInMethodsThatTakeALedgerScope() {
        codeUnits().that().areDeclaredInClassesThat(notPersonLevel())
                .should(runSqlOnlyWithALedgerScope())
                .because("every query of a ledger table filters by the ledger id of a LedgerScope; the classes of "
                        + "ArchitectureTests.PERSON_LEVEL are the exceptions, each with its reason")
                .check(APPLICATION);
    }

    @Test
    void onlyLedgerAccessMakesALedgerScope() {
        classes().that().belongToAnyOf(LedgerScope.class).should().haveModifier(JavaModifier.FINAL)
                .check(APPLICATION);
        constructors().that().areDeclaredIn(LedgerScope.class).should().notBePublic()
                .andShould().onlyBeCalled().byClassesThat().belongToAnyOf(LedgerAccess.class)
                .because("a scope stands for a membership that LedgerAccess checked")
                .check(APPLICATION);
    }

    @Test
    void servicesAndTheDomainNeverReadTheSecurityContext() {
        noClasses().that().resideInAPackage("com.example.financetracker.ledger..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
                .because("the user comes only from the controllers' CurrentUser and LedgerScope parameters")
                .check(APPLICATION);
        noClasses().that().resideInAPackage("com.example.financetracker.ledger..")
                .and().resideOutsideOfPackage("com.example.financetracker.ledger.api..")
                .should().dependOnClassesThat().resideInAPackage("com.example.financetracker.security..")
                .because("services take the user id or a LedgerScope as a parameter, which the controllers pass")
                .check(APPLICATION);
    }

    private static DescribedPredicate<JavaClass> notPersonLevel() {
        return DescribedPredicate.describe("are not person-level", javaClass -> PERSON_LEVEL.keySet().stream()
                .noneMatch(type -> javaClass.isEquivalentTo(type)
                        || javaClass.getName().startsWith(type.getName() + "$")));
    }

    private static ArchCondition<JavaCodeUnit> runSqlOnlyWithALedgerScope() {
        return new ArchCondition<>("run SQL only if they take a LedgerScope") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                if (unit.getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(LedgerScope.class))) {
                    return;
                }
                if (unit.isAnnotatedWith(Query.class)) {
                    events.add(SimpleConditionEvent.violated(unit,
                            "%s has @Query but no LedgerScope parameter".formatted(unit.getFullName())));
                }
                unit.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isEquivalentTo(JdbcClient.class)
                                || call.getTargetOwner().isAssignableTo(JdbcOperations.class)
                                || call.getTargetOwner().isAssignableTo(NamedParameterJdbcOperations.class))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(unit,
                                "%s runs SQL but takes no LedgerScope: %s".formatted(unit.getFullName(),
                                        call.getDescription()))));
            }
        };
    }
}
