/*
 * Copyright (c) 2026, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LessTest {

    /**
     * Catastrophic backtracking pattern: exponential on "aaaa...!".
     * The outer quantifier is bounded because, since JDK 9, java.util.regex
     * memoizes failed positions of unbounded greedy group loops such as (a+)+.
     */
    private static final String EVIL_PATTERN = "(a+){1,100}$";

    private static String evilLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        return sb.append('!').toString();
    }

    private static Options options() {
        return Options.compile(Less.usage()).parse(new String[] {"--ignorercfiles"});
    }

    private static LineDisciplineTerminal terminal() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("less", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 10));
        // Deliver '\r' as is, so that it validates the search pattern
        Attributes attrs = terminal.getAttributes();
        attrs.setInputFlag(InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);
        return terminal;
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws IOException {
        terminal.processInputBytes(keys.getBytes(StandardCharsets.UTF_8));
    }

    private static Source source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new Source.InputStreamSource(new ByteArrayInputStream(bytes), true, "test");
    }

    private static String[] lines(String first, int count, String line, String last) {
        List<String> lines = new ArrayList<>();
        lines.add(first);
        for (int i = 0; i < count; i++) {
            lines.add(line);
        }
        lines.add(last);
        return lines.toArray(new String[0]);
    }

    /**
     * Less that records the status message shown by each redraw.
     */
    static class RecordingLess extends Less {
        final List<String> messages = new ArrayList<>();

        RecordingLess(LineDisciplineTerminal terminal) {
            super(terminal, Paths.get("."), options());
        }

        @Override
        synchronized boolean display(boolean oneScreen, Integer curPos) throws IOException {
            boolean res = super.display(oneScreen, curPos);
            messages.add(message);
            return res;
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testForwardSearchReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "/" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines("first", 7, evilLine(), "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(less.messages.contains("Pattern search timed out"), "got " + less.messages);
        assertEquals(0, less.firstLineToDisplay);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testBackwardSearchReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        // Jump to the last line, then search backward
        type(terminal, "G");
        type(terminal, "?" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines(evilLine(), 11, evilLine(), "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(less.messages.contains("Pattern search timed out"), "got " + less.messages);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testHighlightReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        // The pattern matches "last" so the search succeeds, but highlighting
        // the lines on screen would backtrack catastrophically
        type(terminal, "/" + EVIL_PATTERN + "|last\r");
        type(terminal, "k");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        String evil = evilLine();
        less.run(source("first", "last", evil, evil, evil, evil, evil, evil, evil, evil));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(less.messages.contains("Pattern highlighting timed out"), "got " + less.messages);
        assertFalse(less.messages.contains("Pattern not found"), "got " + less.messages);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testDisplayFilterReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        // Only display the lines matching the pattern
        type(terminal, "&" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines("first", 8, evilLine(), "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(less.messages.contains("Display filter timed out"), "got " + less.messages);
        assertNull(less.displayPattern);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testDisplayFilterSearchReDoS() throws Exception {
        String evil = evilLine();
        List<String> lines = new ArrayList<>();
        lines.add("first");
        for (int i = 0; i < 10; i++) {
            lines.add("ok");
        }
        for (int i = 0; i < 12; i++) {
            lines.add(evil);
        }
        lines.add("last");
        LineDisciplineTerminal terminal = terminal();
        // The display filter only backtracks catastrophically on the lines
        // below the first screen, which are only reached by the search: the
        // filter must be dropped on the first timeout, not paid on every line
        type(terminal, "&" + EVIL_PATTERN + "|ok|first|last\r");
        type(terminal, "/last\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines.toArray(new String[0])));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(less.messages.contains("Display filter timed out"), "got " + less.messages);
        assertNull(less.displayPattern);
        assertEquals(lines.size() - 1, less.firstLineToDisplay);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testSearch() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "/ba[rz]\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "qux", "bar", "baz"));

        assertEquals(2, less.firstLineToDisplay);
        assertFalse(less.messages.contains("Pattern not found"), "got " + less.messages);
        assertFalse(less.messages.contains("Pattern search timed out"), "got " + less.messages);
        assertFalse(less.messages.contains("Pattern highlighting timed out"), "got " + less.messages);
    }

    @Test
    @Timeout(10)
    public void testSearchNotFound() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "/nothere\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "bar", "baz"));

        assertEquals(0, less.firstLineToDisplay);
        assertTrue(less.messages.contains("Pattern not found"), "got " + less.messages);
    }

    @Test
    @Timeout(10)
    public void testDisplayFilter() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        // Only display the lines matching ba[rz], then search for a line hidden by the filter
        type(terminal, "&ba[rz]\r");
        type(terminal, "/qux\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "bar", "qux", "baz"));

        assertEquals("ba[rz]", less.displayPattern);
        assertEquals(0, less.firstLineToDisplay);
        assertTrue(less.messages.contains("Pattern not found"), "got " + less.messages);
        assertFalse(less.messages.contains("Display filter timed out"), "got " + less.messages);
    }

    @Test
    public void testStyleMatchesSameAsAttributedString() throws Exception {
        assertStyleMatchesSame("foo bar baz");
        assertStyleMatchesSame("\u001B[1mbold bar\u001B[0m and \u001B[32mgreen baz\u001B[0m");
        assertStyleMatchesSame("no match here");
        assertStyleMatchesSame("");
        assertStyleMatchesSame("barbarbar");
    }

    private static void assertStyleMatchesSame(String ansi) throws Exception {
        Class<?>[] types = {AttributedString.class, Pattern.class, AttributedStyle.class};
        Method styleMatches = Less.class.getDeclaredMethod("styleMatches", types);
        styleMatches.setAccessible(true);
        AttributedStyle inverse = AttributedStyle.DEFAULT.inverse();
        AttributedString line = AttributedString.fromAnsi(ansi);
        for (String regex : new String[] {"(ba[rz])", "(x*)", "(r b)"}) {
            Pattern p = Pattern.compile(regex);
            AttributedString expected = line.styleMatches(p, inverse);
            AttributedString actual = (AttributedString) styleMatches.invoke(null, line, p, inverse);
            assertEquals(expected, actual, ansi + " / " + regex);
            assertEquals(expected.toAnsi(), actual.toAnsi(), ansi + " / " + regex);
        }
    }
}
