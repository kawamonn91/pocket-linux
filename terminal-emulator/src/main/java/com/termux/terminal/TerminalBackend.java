package com.termux.terminal;

/**
 * Where a {@link TerminalSession} connects to instead of a local subprocess on a pty.
 * Pocket Linux uses it to attach a terminal to a process inside its VM.
 * <p>
 * The backend reports through {@link TerminalSession#onBackendOutput(byte[], int)} and
 * {@link TerminalSession#onBackendExit(int)}, from any thread.
 */
public interface TerminalBackend {

    /** Called once on the main thread, when the size of the terminal is known. Must not block. */
    void start(int columns, int rows, TerminalSession session);

    /** Called on a background thread with what the user typed. May block. */
    void write(byte[] data, int offset, int count);

    /** Called on the main thread when the size of the terminal changes. Must not block. */
    void resize(int columns, int rows);

    /** Called on the main thread to stop the process. Must not block. */
    void kill();
}
