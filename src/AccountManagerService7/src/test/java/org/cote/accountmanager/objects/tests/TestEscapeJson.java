package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.rest.services.PictureBookService;
import org.junit.Test;

/**
 * Unit tests for the private static {@code escapeJson(String)} helper in
 * {@code PictureBookService}.
 *
 * <p>This method builds hand-crafted JSON error-response bodies embedded in
 * strings like {@code "{\"error\":" + escapeJson(message) + "}"}, so it must
 * correctly escape the four JSON-significant characters (backslash, double-quote,
 * newline, carriage-return) and handle null input safely.
 *
 * <p>Tests use reflection to reach the private method — no Tomcat, no DB, no
 * HTTP stack is involved. The method is pure string transformation.
 */
public class TestEscapeJson {
    private static final Logger logger = LogManager.getLogger(TestEscapeJson.class);

    /** Reflective accessor — keeps the method private in production code. */
    private static String callEscapeJson(String s) throws Exception {
        Method m = PictureBookService.class.getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    /** null → bare JSON literal "null" (no surrounding quotes). */
    @Test
    public void TestNullReturnsBareLiteral() throws Exception {
        String result = callEscapeJson(null);
        logger.info("TestNullReturnsBareLiteral result: " + result);
        assertEquals("null", result);
    }

    /** Empty string → empty JSON string literal "". */
    @Test
    public void TestEmptyStringReturnsEmptyJsonString() throws Exception {
        String result = callEscapeJson("");
        logger.info("TestEmptyStringReturnsEmptyJsonString result: " + result);
        assertEquals("\"\"", result);
    }

    /** Plain string (no special chars) is just wrapped in double quotes. */
    @Test
    public void TestPlainStringIsWrappedInQuotes() throws Exception {
        String result = callEscapeJson("hello world");
        logger.info("TestPlainStringIsWrappedInQuotes result: " + result);
        assertEquals("\"hello world\"", result);
    }

    /** Double-quote inside the string must be escaped as \". */
    @Test
    public void TestDoubleQuoteIsEscaped() throws Exception {
        // Input:    he said "hi"
        // Expected: "he said \"hi\""  (with actual backslash-quote)
        String result = callEscapeJson("he said \"hi\"");
        logger.info("TestDoubleQuoteIsEscaped result: " + result);
        assertEquals("\"he said \\\"hi\\\"\"", result);
    }

    /** Backslash must be doubled (each \ becomes \\). */
    @Test
    public void TestBackslashIsDoubled() throws Exception {
        // Input:    a\b
        // Expected: "a\\b"  (with two backslashes)
        String result = callEscapeJson("a\\b");
        logger.info("TestBackslashIsDoubled result: " + result);
        assertEquals("\"a\\\\b\"", result);
    }

    /** Newline character (LF) must be escaped as literal backslash-n. */
    @Test
    public void TestNewlineIsEscaped() throws Exception {
        // Input:    a + LF + b
        // Expected: "a\nb"  (literal backslash + n between quotes)
        String result = callEscapeJson("a\nb");
        logger.info("TestNewlineIsEscaped result: " + result);
        assertEquals("\"a\\nb\"", result);
    }

    /** Carriage-return character (CR) must be escaped as literal backslash-r. */
    @Test
    public void TestCarriageReturnIsEscaped() throws Exception {
        // Input:    a + CR + b
        // Expected: "a\rb"  (literal backslash + r between quotes)
        String result = callEscapeJson("a\rb");
        logger.info("TestCarriageReturnIsEscaped result: " + result);
        assertEquals("\"a\\rb\"", result);
    }

    /**
     * Backslash-before-quote: a\"b (backslash, then double-quote).
     * Escaping must happen in order: backslash first, then quote.
     * a\"b → a\\"b (backslash doubled) → a\\\"b (quote escaped) → "a\\\"b"
     */
    @Test
    public void TestBackslashBeforeQuoteEscapingOrder() throws Exception {
        // Input:    a\"b  (3 special chars: backslash + double-quote)
        // Expected: "a\\\"b"
        String result = callEscapeJson("a\\\"b");
        logger.info("TestBackslashBeforeQuoteEscapingOrder result: " + result);
        assertEquals("\"a\\\\\\\"b\"", result);
    }

    /** Multiple escape types in a single string all apply correctly. */
    @Test
    public void TestCombinedEscapingAllSpecialChars() throws Exception {
        // Input:    line1\nline2 (real newline between line1 and line2, no backslashes)
        // Expected: "line1\nline2"  (literal \n inside quotes)
        String result = callEscapeJson("line1\nline2");
        logger.info("TestCombinedEscapingAllSpecialChars result: " + result);
        assertEquals("\"line1\\nline2\"", result);
    }
}
