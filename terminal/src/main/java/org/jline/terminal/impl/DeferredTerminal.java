/*
 * Copyright (c) the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.terminal.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Sized;
import org.jline.terminal.Terminal;
import org.jline.utils.ColorPalette;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * A {@link Terminal} proxy that defers construction to a background thread.
 *
 * <p>All methods block on an internal {@link CompletableFuture} until the real terminal is
 * ready, then delegate transparently. {@link #writer()} and {@link #getType()} return dumb
 * fallbacks when called from the build thread itself (reentrancy guard), since FFM class
 * initialisation may call back through the terminal during init.</p>
 *
 * <p>This class is package-private; callers obtain it via
 * {@link org.jline.terminal.TerminalBuilder#buildDeferred()}.</p>
 */
public class DeferredTerminal implements Terminal {

    private final CompletableFuture<Terminal> future = new CompletableFuture<>();
    private volatile Thread buildThread;

    // Dumb fallback writer for reentrancy (build thread calling writer() during init)
    private final PrintWriter dumbWriter = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8));

    public DeferredTerminal() {}

    public void setBuildThread(Thread thread) {
        this.buildThread = thread;
    }

    public void complete(Terminal terminal) {
        future.complete(terminal);
    }

    public void completeExceptionally(Throwable t) {
        future.completeExceptionally(t);
    }

    private boolean isBuildThread() {
        return Thread.currentThread() == buildThread;
    }

    private Terminal terminal() {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Terminal build failed", cause);
        }
    }

    // -- Reentrancy-safe methods (return dumb fallbacks on build thread) --

    @Override
    public PrintWriter writer() {
        if (isBuildThread()) {
            return dumbWriter;
        }
        return terminal().writer();
    }

    @Override
    public String getType() {
        if (isBuildThread()) {
            return TYPE_DUMB;
        }
        return terminal().getType();
    }

    // -- All remaining Terminal methods delegate, blocking until ready --

    @Override
    public String getName() {
        return terminal().getName();
    }

    @Override
    public SignalHandler handle(Signal signal, SignalHandler handler) {
        return terminal().handle(signal, handler);
    }

    @Override
    public void raise(Signal signal) {
        terminal().raise(signal);
    }

    @Override
    public NonBlockingReader reader() {
        return terminal().reader();
    }

    @Override
    public Charset encoding() {
        return terminal().encoding();
    }

    @Override
    public Charset inputEncoding() {
        return terminal().inputEncoding();
    }

    @Override
    public Charset outputEncoding() {
        return terminal().outputEncoding();
    }

    @Override
    public InputStream input() {
        return terminal().input();
    }

    @Override
    public OutputStream output() {
        return terminal().output();
    }

    @Override
    public boolean canPauseResume() {
        return terminal().canPauseResume();
    }

    @Override
    public void pause() {
        terminal().pause();
    }

    @Override
    public void pause(boolean wait) throws InterruptedException {
        terminal().pause(wait);
    }

    @Override
    public void resume() {
        terminal().resume();
    }

    @Override
    public boolean paused() {
        return terminal().paused();
    }

    @Override
    public Attributes enterRawMode() {
        return terminal().enterRawMode();
    }

    @Override
    public boolean echo() {
        return terminal().echo();
    }

    @Override
    public boolean echo(boolean echo) {
        return terminal().echo(echo);
    }

    @Override
    public Attributes getAttributes() {
        return terminal().getAttributes();
    }

    @Override
    public void setAttributes(Attributes attr) {
        terminal().setAttributes(attr);
    }

    @Override
    public Size getSize() {
        return terminal().getSize();
    }

    @Override
    public void setSize(Sized size) {
        terminal().setSize(size);
    }

    @Override
    public int getColumns() {
        return terminal().getColumns();
    }

    @Override
    public int getRows() {
        return terminal().getRows();
    }

    @Override
    public Size getBufferSize() {
        return terminal().getBufferSize();
    }

    @Override
    public void flush() {
        if (isBuildThread()) {
            dumbWriter.flush();
            return;
        }
        terminal().flush();
    }

    @Override
    public boolean puts(Capability capability, Object... params) {
        return terminal().puts(capability, params);
    }

    @Override
    public boolean getBooleanCapability(Capability capability) {
        return terminal().getBooleanCapability(capability);
    }

    @Override
    public Integer getNumericCapability(Capability capability) {
        return terminal().getNumericCapability(capability);
    }

    @Override
    public String getStringCapability(Capability capability) {
        return terminal().getStringCapability(capability);
    }

    @Override
    public Cursor getCursorPosition(IntConsumer discarded) {
        return terminal().getCursorPosition(discarded);
    }

    @Override
    public boolean hasMouseSupport() {
        return terminal().hasMouseSupport();
    }

    @Override
    public boolean trackMouse(MouseTracking tracking) {
        return terminal().trackMouse(tracking);
    }

    @Override
    public MouseTracking getCurrentMouseTracking() {
        return terminal().getCurrentMouseTracking();
    }

    @Override
    public MouseEvent readMouseEvent() {
        return terminal().readMouseEvent();
    }

    @Override
    public MouseEvent readMouseEvent(IntSupplier reader) {
        return terminal().readMouseEvent(reader);
    }

    @Override
    public MouseEvent readMouseEvent(String prefix) {
        return terminal().readMouseEvent(prefix);
    }

    @Override
    public MouseEvent readMouseEvent(IntSupplier reader, String prefix) {
        return terminal().readMouseEvent(reader, prefix);
    }

    @Override
    public boolean hasFocusSupport() {
        return terminal().hasFocusSupport();
    }

    @Override
    public boolean trackFocus(boolean tracking) {
        return terminal().trackFocus(tracking);
    }

    @Override
    public ColorPalette getPalette() {
        return terminal().getPalette();
    }

    @Override
    public void close() throws IOException {
        if (future.isDone()) {
            terminal().close();
        } else {
            future.cancel(true);
            if (buildThread != null) {
                buildThread.interrupt();
            }
        }
    }
}
