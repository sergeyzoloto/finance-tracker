package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.example.financetracker.hygiene.fixtures.ImplicitClocks;
import com.example.financetracker.hygiene.fixtures.RawAnswerSearch;
import com.example.financetracker.hygiene.fixtures.RawTextCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaConstructorReference;
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
 *       {@link WallClock}. Since F8f the rule also refuses {@code System.currentTimeMillis()}, {@code new Date()}
 *       without an argument and {@code ZoneId.systemDefault()}, and since F8f-fix (D-130) {@code TimeZone.getDefault()}
 *       outside the classes of {@link #DEFAULT_ZONE_READERS}.</li>
 *   <li>D-121 (F8f): {@link Answers#rawText} only in the classes of {@link #RAW_TEXT_CALLERS}, each with its reason:
 *       whole answers compared with each other, or an answer in a message.</li>
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
            .importClasses(RawAnswerSearch.class, ImplicitClocks.class, RawTextCaller.class,
                    RawTextCaller.Nested.class);

    /** The classes that may turn an answer into text: the helper, and the base class that parses it. */
    private static final Set<String> MAY_READ_TEXT = Set.of(Answers.class.getName(), IntegrationTest.class.getName(),
            TestHygieneTests.class.getName());

    private static final String API = "com.example.financetracker.ledger.api.";

    /**
     * D-121: the classes that may call {@link Answers#rawText}, each with its reason; never to search for a number, an
     * id, an amount or a date (D-119). A nested class or a lambda counts as its top-level class.
     */
    static final Map<String, String> RAW_TEXT_CALLERS = Map.of(
            API + "DataIsolationApiTests",
            "compares Bob's whole answer for Alice's object with the answer for a missing one, digits masked, and "
                    + "collects whole answers for invalid tokens to compare them with each other",
            API + "FamilyInviteApiTests",
            "compares the whole answers for every kind of invalid token with each other, and finds the token, a "
                    + "random base64url string, only in the creation's answer",
            API + "FamilySwitchOffApiTests",
            "compares a family endpoint's whole answer with an unknown path's, the path replaced",
            API + "FamilyClaimRandomTests", "names the answer in an assertion's message",
            API + "FamilyLifecycleRandomTests", "names the answer in an assertion's message");

    /**
     * D-130: the classes that may call {@link TimeZone#getDefault()}, each with its reason. A nested class or a lambda
     * counts as its top-level class. Every other implicit clock of D-117 has no exemption.
     */
    static final Map<String, String> DEFAULT_ZONE_READERS = Map.of(
            JvmDefaultsGuard.class.getName(),
            "reads the JVM's default zone before and after each test class to fail a class that leaves it changed "
                    + "(OPS-2b); it compares the zone itself and makes no date or moment from it");

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
                boolean mayReadTheDefaultZone = DEFAULT_ZONE_READERS.containsKey(topLevel(javaClass));
                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                    if (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && (access instanceof JavaMethodCall || access instanceof JavaMethodReference
                                    || access instanceof JavaConstructorCall
                                    || access instanceof JavaConstructorReference)
                            && isImplicit(target)
                            && !(mayReadTheDefaultZone && readsTheDefaultZone(target))) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean isImplicit(AccessTarget.CodeUnitAccessTarget target) {
        String owner = target.getOwner().getName();
        String name = target.getName();
        boolean noArguments = target.getRawParameterTypes().isEmpty();
        switch (owner) {
            case "java.time.Clock":
                return name.equals("systemDefaultZone");
            case "java.time.ZoneId":
                return name.equals("systemDefault");
            case "java.lang.System":
                return name.equals("currentTimeMillis");
            case "java.util.Date":
                return name.equals("<init>") && noArguments;
            case "java.util.TimeZone":
                return readsTheDefaultZone(target);
            default:
                break;
        }
        return TIME_TYPES.contains(owner) && target.getName().equals("now") && target.getRawParameterTypes().isEmpty();
    }

    /** A nested class's or a lambda's top-level class. */
    private static String topLevel(JavaClass javaClass) {
        return javaClass.getName().split("\\$", 2)[0];
    }

    private static boolean readsTheDefaultZone(AccessTarget.CodeUnitAccessTarget target) {
        return target.getOwner().getName().equals("java.util.TimeZone") && target.getName().equals("getDefault");
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
                "instant", "year", "yearMonth", "systemDefaultZone", "methodReference", "lambda", "currentTimeMillis",
                "newDate", "dateConstructorReference", "systemDefaultZoneId", "timeZoneDefault",
                "timeZoneDefaultReference")) {
            assertThat(details).as(method).anyMatch(detail -> detail.contains("ImplicitClocks." + method + "("));
        }
        // A call that names its clock or zone is allowed.
        assertThat(details).noneMatch(detail -> detail.contains("withAClockOrAZone"));
    }

    @Test
    void everyListedReaderOfTheDefaultZoneReadsIt() {
        // D-130: each listed class exists and does read it, so that the list can't outlive its reason.
        for (String name : DEFAULT_ZONE_READERS.keySet()) {
            assertThat(ALL.stream()
                    .filter(javaClass -> topLevel(javaClass).equals(name))
                    .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                    .anyMatch(access -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && readsTheDefaultZone(target)))
                    .as(name + " reads TimeZone.getDefault()").isTrue();
        }
    }

    // ---- D-121

    static ArchRule rawTextOnlyInTheListedClasses() {
        return classes().should(new ArchCondition<JavaClass>("call Answers.rawText only if listed in RAW_TEXT_CALLERS") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String topLevel = topLevel(javaClass);
                boolean listed = topLevel.equals(Answers.class.getName())
                        || RAW_TEXT_CALLERS.containsKey(topLevel);
                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                    if (!listed && access.getTarget().getOwner().isEquivalentTo(Answers.class)
                            && access.getTarget().getName().equals("rawText")) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        }).because("Answers.rawText is for whole answers and messages, never for a search for a number, an id, an "
                + "amount or a date (D-119, D-121): a new caller is added to RAW_TEXT_CALLERS with its reason");
    }

    @Test
    void onlyTheListedClassesCallRawText() {
        rawTextOnlyInTheListedClasses().check(TESTS);
    }

    @Test
    void theRuleForRawTextFindsAnUnlistedCaller() {
        EvaluationResult result = rawTextOnlyInTheListedClasses().evaluate(FIXTURE_CLASSES);
        List<String> details = result.getFailureReport().getDetails();
        assertThat(details).anyMatch(detail -> detail.contains("RawTextCaller.direct("));
        assertThat(details).anyMatch(detail -> detail.contains("RawTextCaller.reference("));
        assertThat(details).anyMatch(detail -> detail.contains("RawTextCaller$Nested"));
        assertThat(details).allMatch(detail -> detail.contains("RawTextCaller"));
    }
}
