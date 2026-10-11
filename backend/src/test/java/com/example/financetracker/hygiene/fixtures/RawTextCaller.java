package com.example.financetracker.hygiene.fixtures;

import java.io.IOException;
import java.util.function.Function;

import com.example.financetracker.Answers;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * What {@code TestHygieneTests} must refuse (D-121): {@code Answers.rawText} called by a class that isn't listed in
 * its {@code RAW_TEXT_CALLERS}, directly, as a method reference and from a nested class. It is excluded from the real
 * rules and imported alone by the tests of the rules.
 */
public final class RawTextCaller {

    private RawTextCaller() {
    }

    public static String direct(MvcTestResult result) throws IOException {
        return Answers.rawText(result);
    }

    public static Object reference() {
        Function<MvcTestResult, Object> text = result -> {
            try {
                return Answers.rawText(result);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        return text;
    }

    public static final class Nested {

        private Nested() {
        }

        public static String call(MvcTestResult result) throws IOException {
            return Answers.rawText(result);
        }
    }
}
