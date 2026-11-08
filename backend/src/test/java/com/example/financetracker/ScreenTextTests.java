package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.financetracker.hygiene.fixtures.DecisionNumberMessage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D-384 (UX-4): no decision number in any text a screen can show, the backend's messages included (the import's row
 * errors, warnings, conflicts and refusals, the family budget's). Every string constant of every compiled application
 * class is read from its class file's constant pool, so comments never count and a message built at run time counts
 * by its recipe ({@code "Record \u0001: … (D-8)"}). Only the classes and log lines of {@link #NO_SCREEN} may carry
 * one, each with its reason. {@link ScreenTexts#assertNoDecisionNumber} checks the answers themselves.
 */
class ScreenTextTests {

    /** The application classes (by top-level name) and log lines whose texts no page shows. */
    static final Map<String, String> NO_SCREEN = Map.of(
            "com.example.financetracker.ledger.importer.ImportReportMarkdown",
            "the command line's Markdown report, which only ImportRunner writes to a file",
            "com.example.financetracker.ledger.importer.ImportRunner",
            "the command line (profile import, no web server)",
            "Family ledgers (D-25): {}",
            "FamilySwitch's log line at startup, which the deploy scripts read (D-25)");

    @Test
    void noApplicationTextAPageCanShowHoldsADecisionNumber() throws IOException, URISyntaxException {
        Map<String, List<String>> found = decisionNumbers(classesOf(FinanceTrackerApplication.class));
        found.keySet().removeIf(NO_SCREEN::containsKey);
        found.values().forEach(texts -> texts.removeIf(NO_SCREEN::containsKey));
        found.values().removeIf(List::isEmpty);
        assertThat(found).as("texts with a decision number outside NO_SCREEN (D-384)").isEmpty();
    }

    @Test
    void everyClassOfTheListStillHasOne() throws IOException, URISyntaxException {
        Map<String, List<String>> found = decisionNumbers(classesOf(FinanceTrackerApplication.class));
        List<String> texts = found.values().stream().flatMap(List::stream).toList();
        for (String entry : NO_SCREEN.keySet()) {
            assertThat(found.containsKey(entry) || texts.contains(entry))
                    .as("%s is in NO_SCREEN but holds no decision number any more: remove it", entry).isTrue();
        }
    }

    @Test
    void theScanFindsAMessageWholeAndBuiltAtRunTime() throws IOException, URISyntaxException {
        Path file = Path.of(DecisionNumberMessage.class.getResource("DecisionNumberMessage.class").toURI());
        List<String> texts = stringConstants(Files.readAllBytes(file)).stream()
                .filter(t -> ScreenTexts.DECISION.matcher(t).find()).toList();
        assertThat(texts).hasSize(2).contains("Sum is 0 (D-72)");
        assertThat(texts).anyMatch(t -> t.endsWith(": only they change it (D-8, D-28)"));
    }

    /** The folder the given class was loaded from, such as {@code target/classes}. */
    private static Path classesOf(Class<?> anchor) throws URISyntaxException {
        return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** By top-level class name, the string constants of the classes under root that hold a decision number. */
    private static Map<String, List<String>> decisionNumbers(Path root) throws IOException {
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(root)) {
            classFiles = files.filter(p -> p.toString().endsWith(".class")).toList();
        }
        assertThat(classFiles).as("the application's classes under " + root).isNotEmpty();
        Map<String, List<String>> found = new TreeMap<>();
        for (Path file : classFiles) {
            String name = root.relativize(file).toString().replace(java.io.File.separatorChar, '.')
                    .replaceAll("\\.class$", "");
            String topLevel = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            for (String text : stringConstants(Files.readAllBytes(file))) {
                if (ScreenTexts.DECISION.matcher(text).find()) {
                    found.computeIfAbsent(topLevel, k -> new ArrayList<>()).add(text);
                }
            }
        }
        return found;
    }

    /** The CONSTANT_String entries of a class file's constant pool (JVMS 4.4). */
    static List<String> stringConstants(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        List<Integer> strings = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8.put(i, in.readUTF());
                case 8 -> strings.add(in.readUnsignedShort());
                case 7, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        return strings.stream().map(utf8::get).toList();
    }
}
