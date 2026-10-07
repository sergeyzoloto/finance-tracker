package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.example.financetracker.hygiene.fixtures.ImplicitClocks;
import com.example.financetracker.hygiene.fixtures.RawAnswerSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Two faults of the tests that cost a red run on a runner we deploy from, each ruled out here by a check of the
 * compiled classes, so that it can't come back by being written again.
 * <ul>
 *   <li>D-119: a number, id, amount or date searched for as a substring of an answer's text. 51.50 was found inside
 *       {@code "updatedAt":"2026-10-07T12:38:51.500757Z"} (Ubuntu 26.04, run #6), and an id of 555 inside
 *       {@code …006555Z} before it (F8c). An answer becomes text only in {@link Answers} (and {@link IntegrationTest},
 *       which parses it); a test reads it as parsed values with {@link Answers#values}, {@link Answers#numbers} and
 *       {@link Answers#mentions}, which compare whole values and refuse a needle that reads as a number or a date.</li>
 *   <li>D-117: a date or a moment from a clock nobody named. {@code LocalDate.now()} is the JVM's zone, which CI runs
 *       in Pacific/Kiritimati; {@code Instant.now()} and {@code Clock.systemDefaultZone()} leave the clock implicit.
 *       Dates come from {@code ledger.Today} or a clock or zone given to {@code now}; a moment from
 *       {@link WallClock}.</li>
 * </ul>
 * The fixtures in {@code hygiene.fixtures} break both on purpose: the rules are tried on them, and left out of the
 * rules' own scan.
 */
class TestHygieneTests {

    private static final String FIXTURES = "/com/example/financetracker/hygiene/fixtures/";

    private static final ImportOption WITHOUT_FIXTURES = location -> !location.contains(FIXTURES);

    /** Every class of the application and of its tests. */
    private static final JavaClasses ALL = new ClassFileImporter().withImportOption(WITHOUT_FIXTURES)
            .importPackages("com.example.financetracker");

    private static final JavaClasses TESTS = new ClassFileImporter().withImportOption(WITHOUT_FIXTURES)
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS).importPackages("com.example.financetracker");

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter()
            .importClasses(RawAnswerSearch.class, ImplicitClocks.class);

    /** The classes that may turn an answer into text: the helper, and the base class that parses it. */
    private static final Set<String> MAY_READ_TEXT = Set.of(Answers.class.getName(), IntegrationTest.class.getName(),
            TestHygieneTests.class.getName());

    private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.ZonedDateTime", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay");

    // ---- D-119

    static ArchRule answersAreNeverText() {
        return classes().that(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("may not read an answer as text") {
            @Override
            public boolean test(JavaClass javaClass) {
                return !MAY_READ_TEXT.contains(javaClass.getName());
            }
        }).should(notTurnAnAnswerIntoText()).because("a number, id, amount or date found as a substring of an "
                + "answer's text can be part of another value (D-119): read parsed values with Answers");
    }

    private static ArchCondition<JavaClass> notTurnAnAnswerIntoText() {
        return new ArchCondition<>("not turn an answer into text") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                    if (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && (access instanceof JavaMethodCall || access instanceof JavaMethodReference)
                            && makesText(target)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean makesText(AccessTarget.CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String name = target.getName();
        if (owner.isAssignableTo(JsonNode.class)) {
            return (name.equals("toString") || name.equals("toPrettyString")) && target.getRawParameterTypes().isEmpty();
        }
        return switch (owner.getName()) {
            case "org.springframework.mock.web.MockHttpServletResponse" ->
                    name.equals("getContentAsString") || name.equals("getContentAsByteArray");
            case "org.springframework.test.web.servlet.assertj.MvcTestResultAssert" ->
                    name.equals("bodyText") || name.equals("hasBodyTextEqualTo");
            case "com.fasterxml.jackson.databind.ObjectMapper" -> name.equals("writeValueAsString");
            default -> false;
        };
    }

    @Test
    void noTestSearchesTheTextOfAnAnswer() {
        answersAreNeverText().check(TESTS);
    }

    @Test
    void theRuleForAnswersFindsEveryWayToText() {
        EvaluationResult result = answersAreNeverText().evaluate(FIXTURE_CLASSES);
        assertThat(result.hasViolation()).isTrue();
        String report = String.join("\n", result.getFailureReport().getDetails());
        for (String way : List.of("JsonNode.toString()", "JsonNode.toPrettyString()", "getContentAsString()",
                "getContentAsByteArray()", "ObjectMapper.writeValueAsString(")) {
            assertThat(report).as(way).contains(way);
        }
        assertThat(result.getFailureReport().getDetails()).hasSize(5);
    }

    // ---- D-117

    static ArchRule noImplicitClock() {
        return classes().should(notAskTheImplicitClock()).because("a date or a moment comes from ledger.Today, or "
                + "from a clock or a zone that the call names (D-117); a moment of no zone from WallClock");
    }

    private static ArchCondition<JavaClass> notAskTheImplicitClock() {
        return new ArchCondition<>("not ask the implicit clock") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                    if (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && (access instanceof JavaMethodCall || access instanceof JavaMethodReference)
                            && isImplicit(target)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean isImplicit(AccessTarget.CodeUnitAccessTarget target) {
        String owner = target.getOwner().getName();
        if (owner.equals("java.time.Clock")) {
            return target.getName().equals("systemDefaultZone");
        }
        return TIME_TYPES.contains(owner) && target.getName().equals("now") && target.getRawParameterTypes().isEmpty();
    }

    @Test
    void noClassAsksTheImplicitClock() {
        noImplicitClock().check(ALL);
    }

    @Test
    void theRuleForClocksFindsEveryImplicitOne() {
        EvaluationResult result = noImplicitClock().evaluate(FIXTURE_CLASSES);
        List<String> details = result.getFailureReport().getDetails().stream()
                .filter(detail -> detail.contains("ImplicitClocks")).toList();
        for (String method : List.of("localDate", "localDateTime", "localTime", "zonedDateTime", "offsetDateTime",
                "instant", "year", "yearMonth", "systemDefaultZone", "methodReference", "lambda")) {
            assertThat(details).as(method).anyMatch(detail -> detail.contains("ImplicitClocks." + method + "("));
        }
        // A call that names its clock or zone is allowed.
        assertThat(details).noneMatch(detail -> detail.contains("withAClockOrAZone"));
    }
}
