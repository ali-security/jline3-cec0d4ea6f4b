/*
 * Copyright (c) 2002-2017, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NanoTest {

    /**
     * Catastrophic backtracking pattern: exponential on "aaaa...!".
     * The outer quantifier is bounded because, since JDK 9, java.util.regex
     * memoizes failed positions of unbounded greedy group loops such as (a+)+.
     */
    private static final String EVIL_PATTERN = "(a+){1,100}$";

    @Test
    @Timeout(1)
    public void nanoBufferLineOverflow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        for (int i = 0; i < 100; i++) {
            terminal.processInputByte(' ');
        }
        terminal.processInputByte(KeyMap.ctrl('X').getBytes()[0]);
        terminal.processInputByte('n');
        String[] argv = {"--ignorercfiles"};
        Nano nano = new Nano(
                terminal,
                Paths.get("target/test.txt"),
                Options.compile(Nano.usage()).parse(argv));
        nano.run();
    }

    private static Options options() {
        return Options.compile(Nano.usage()).parse(new String[] {"--ignorercfiles"});
    }

    private static String evilLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        return sb.append('!').toString();
    }

    private static Nano nanoWithLines(String... lines) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        Nano nano = new Nano(terminal, Paths.get("target"), options());
        nano.size.copy(terminal.getSize());
        nano.shortcuts = new LinkedHashMap<>();
        nano.shortcuts.put("^X", "Exit");
        nano.shortcuts.put("^W", "Where Is");
        nano.buffer = nano.new Buffer(null);
        nano.buffer.open();
        nano.buffer.lines = new ArrayList<>(Arrays.asList(lines));
        nano.buffer.computeAllOffsets();
        return nano;
    }

    private static LineDisciplineTerminal terminal() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        // Deliver '\r' as is, so that it validates the prompts
        Attributes attrs = terminal.getAttributes();
        attrs.setInputFlag(InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);
        return terminal;
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws IOException {
        terminal.processInputBytes(keys.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeLines(Path file, String... lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Nano that records every status message it shows.
     */
    static class RecordingNano extends Nano {
        final List<String> messages = new ArrayList<>();

        RecordingNano(LineDisciplineTerminal terminal, Path root) {
            super(terminal, root, options());
        }

        @Override
        void setMessage(String message) {
            super.setMessage(message);
            messages.add(message);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testSearchReDoS() throws Exception {
        String evil = evilLine();
        List<String> lines = new ArrayList<>();
        lines.add("first");
        for (int i = 0; i < 10; i++) {
            lines.add(evil);
        }
        lines.add("last");
        Nano nano = nanoWithLines(lines.toArray(new String[0]));
        nano.searchTerm = EVIL_PATTERN;
        nano.searchRegexp = true;

        long start = System.nanoTime();
        boolean found = nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertFalse(found);
        assertTrue(
                nano.message != null && nano.message.contains("timed out"),
                "Search timeout should be reported, got " + nano.message);
        assertEquals(0, nano.buffer.line);
        assertEquals(0, nano.buffer.column);
        assertTrue(elapsedMs < 10000, "search took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testBackwardSearchReDoS() throws Exception {
        String evil = evilLine();
        Nano nano = nanoWithLines("first", evil, evil, evil, evil, evil, evil, evil, evil, "last");
        nano.searchTerm = EVIL_PATTERN;
        nano.searchRegexp = true;
        nano.searchBackwards = true;

        long start = System.nanoTime();
        boolean found = nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertFalse(found);
        assertTrue(
                nano.message != null && nano.message.contains("timed out"),
                "Search timeout should be reported, got " + nano.message);
        assertEquals(0, nano.buffer.line);
        assertTrue(elapsedMs < 10000, "search took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testSearch() throws Exception {
        Nano nano = nanoWithLines("foo", "bar baz", "qux");
        nano.searchTerm = "ba[rz]";
        nano.searchRegexp = true;

        assertTrue(nano.buffer.nextSearch());
        assertEquals(1, nano.buffer.line);
        assertEquals(0, nano.buffer.offsetInLine + nano.buffer.column);
        assertEquals(3, nano.matchedLength);

        assertTrue(nano.buffer.nextSearch());
        assertEquals(1, nano.buffer.line);
        assertEquals(4, nano.buffer.offsetInLine + nano.buffer.column);

        nano.searchTerm = "nothere";
        assertFalse(nano.buffer.nextSearch());
        assertEquals("\"nothere\" not found", nano.message);
    }

    @Test
    @Timeout(10)
    public void testLiteralSearch() throws Exception {
        String evil = evilLine();
        Nano nano = nanoWithLines("first", evil, "x" + EVIL_PATTERN + "y");
        // Without the regexp flag the term is matched literally
        nano.searchTerm = EVIL_PATTERN;
        nano.searchRegexp = false;

        assertTrue(nano.buffer.nextSearch());
        assertEquals(2, nano.buffer.line);
        assertEquals(1, nano.buffer.offsetInLine + nano.buffer.column);
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testSearchPromptReDoS(@TempDir Path dir) throws Exception {
        String evil = evilLine();
        writeLines(dir.resolve("evil.txt"), "first", evil, evil, evil, evil, evil, evil, evil, evil, "last");
        LineDisciplineTerminal terminal = terminal();
        // ^W, type the pattern, M-R to switch to regexp mode, Enter, then ^X to exit
        type(terminal, KeyMap.ctrl('W') + EVIL_PATTERN + KeyMap.alt('r') + "\r" + KeyMap.ctrl('X'));

        RecordingNano nano = new RecordingNano(terminal, dir);
        nano.open("evil.txt");
        long start = System.nanoTime();
        nano.run();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(nano.searchRegexp);
        assertEquals(EVIL_PATTERN, nano.searchTerm);
        assertTrue(
                nano.messages.contains("Search for \"" + EVIL_PATTERN + "\" timed out"),
                "Search timeout should be reported, got " + nano.messages);
        assertTrue(elapsedMs < 10000, "nano took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testReplacePromptReDoS(@TempDir Path dir) throws Exception {
        String evil = evilLine();
        writeLines(dir.resolve("evil.txt"), "first", evil, evil, evil, evil, evil, evil, evil, evil, "last");
        LineDisciplineTerminal terminal = terminal();
        // M-R (replace), type the pattern, M-R to switch to regexp mode, Enter,
        // type the replacement, Enter, then ^X to exit
        type(terminal, KeyMap.alt('r') + EVIL_PATTERN + KeyMap.alt('r') + "\r" + "x\r" + KeyMap.ctrl('X'));

        RecordingNano nano = new RecordingNano(terminal, dir);
        nano.open("evil.txt");
        Nano.Buffer buffer = nano.buffers.get(0);
        long start = System.nanoTime();
        nano.run();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(nano.searchRegexp);
        assertTrue(
                nano.messages.contains("Search for \"" + EVIL_PATTERN + "\" timed out"),
                "Search timeout should be reported, got " + nano.messages);
        assertEquals("Replaced 0 occurrences", nano.message);
        assertFalse(buffer.dirty);
        assertTrue(elapsedMs < 10000, "nano took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testReplaceAllRegexp(@TempDir Path dir) throws Exception {
        writeLines(dir.resolve("test.txt"), "foo bar baz", "qux");
        LineDisciplineTerminal terminal = terminal();
        // M-R (replace), type the pattern, M-R to switch to regexp mode, Enter,
        // type the replacement, Enter, A to replace all, then ^X and N to exit without saving
        type(terminal, KeyMap.alt('r') + "ba[rz]" + KeyMap.alt('r') + "\r" + "X\r" + "a" + KeyMap.ctrl('X') + "n");

        RecordingNano nano = new RecordingNano(terminal, dir);
        nano.open("test.txt");
        Nano.Buffer buffer = nano.buffers.get(0);
        nano.run();

        assertEquals("Replaced 2 occurrences", nano.message);
        assertEquals(Arrays.asList("foo X X", "qux"), buffer.lines);
    }
}
