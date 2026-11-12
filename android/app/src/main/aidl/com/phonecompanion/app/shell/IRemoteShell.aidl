package com.phonecompanion.app.shell;

/**
 * Runs one shell command as the Shizuku peer (shell/root).
 *
 * Used by the operator "adb link" channel: logs, settings, screenshots
 * (`screencap -p | base64`), input simulation — anything `adb shell`
 * accepts. The service process is not a normal app process: no Context
 * APIs, plain ProcessBuilder only.
 */
interface IRemoteShell {
    /**
     * Runs [command] with `/system/bin/sh -c`, capped at [timeoutMs], and
     * returns the packed result:
     *   `<exitCode>\n<truncated 0|1>\n<base64 stdout>\n<base64 stderr>`.
     */
    String run(String command, int timeoutMs) = 1;

    /** Reserved Shizuku destroy (exits the shell process). */
    void destroy() = 16777114;
}
