/*
 * Copyright (c) 2026, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SafeRegexTest {

    /**
     * Catastrophic backtracking pattern: exponential on "aaaa...!".
     * The outer quantifier is bounded because, since JDK 9, java.util.regex
     * memoizes failed positions of unbounded greedy group loops such as (a+)+.
     */
    static final String EVIL_PATTERN = "(a+){1,100}$";

    static String evilInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        return sb.append('!').toString();
    }

    @Test
    public void testFindSameAsPlainMatcher() {
        Pattern p = Pattern.compile("b[a-z]+");
        String input = "foo bar baz qux bzz";
        List<String> expected = new ArrayList<>();
        Matcher plain = p.matcher(input);
        while (plain.find()) {
            expected.add(plain.start() + "-" + plain.end() + ":" + plain.group());
        }
        List<String> actual = new ArrayList<>();
        Matcher safe = SafeRegex.matcher(p, input);
        while (safe.find()) {
            actual.add(safe.start() + "-" + safe.end() + ":" + safe.group());
        }
        assertEquals(3, expected.size());
        assertEquals(expected, actual);
    }

    @Test
    public void testMatchesAndGroups() {
        Matcher m = SafeRegex.matcher(Pattern.compile("(\\w+)=(\\d+)"), "key=42");
        assertTrue(m.matches());
        assertEquals("key", m.group(1));
        assertEquals("42", m.group(2));
        assertFalse(SafeRegex.matcher(Pattern.compile("\\d+"), "abc").matches());
    }

    @Test
    public void testAttributedStringInput() {
        AttributedString line = AttributedString.fromAnsi("\u001B[1mhello\u001B[0m world");
        Matcher m = SafeRegex.matcher(Pattern.compile("wor"), line);
        assertTrue(m.find());
        assertEquals(6, m.start());
        assertEquals(9, m.end());
    }

    @Test
    public void testLongInputDoesNotTimeOut() {
        // Linear matching over a large input must not trip the deadline
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200000; i++) {
            sb.append('x');
        }
        sb.append("needle");
        Matcher m = SafeRegex.matcher(Pattern.compile("needle"), sb);
        assertTrue(m.find());
        assertEquals(200000, m.start());
    }

    @Test
    public void testDeadlineStartsLazily() throws Exception {
        Matcher m = SafeRegex.matcher(Pattern.compile("x+y"), longString('x', 10000) + "y", 100);
        // The timeout measures matching time, not the time since the matcher was created
        Thread.sleep(300);
        assertTrue(m.find());
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testCatastrophicBacktrackingTimesOut() {
        Matcher m = SafeRegex.matcher(Pattern.compile(EVIL_PATTERN), evilInput(), 200);
        long start = System.nanoTime();
        assertThrows(RegexTimeoutException.class, m::find);
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(elapsedMs < 5000, "Timed out after " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testCatastrophicBacktrackingTimesOutWithDefaultTimeout() {
        Matcher m = SafeRegex.matcher(Pattern.compile(EVIL_PATTERN), evilInput());
        long start = System.nanoTime();
        assertThrows(RegexTimeoutException.class, m::matches);
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(elapsedMs >= SafeRegex.DEFAULT_TIMEOUT_MS - 100, "Timed out after " + elapsedMs + " ms");
        assertTrue(elapsedMs < SafeRegex.DEFAULT_TIMEOUT_MS + 3000, "Timed out after " + elapsedMs + " ms");
    }

    @Test
    public void testSubSequenceDelegates() {
        Matcher m = SafeRegex.matcher(Pattern.compile("(b+)"), "aabbbcc");
        assertTrue(m.find());
        assertEquals("bbb", m.group(1));
        CharSequence seq = m.group(1);
        assertEquals(3, seq.length());
        assertEquals("bbb", seq.toString());
    }

    private static String longString(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
