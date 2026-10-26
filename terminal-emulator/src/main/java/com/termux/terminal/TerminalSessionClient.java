package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    void onPasteTextFromClipboard(@Nullable TerminalSession session);

    default void onClipboardRequest(@NonNull TerminalSession session, String request) {
        // A detached/background session cannot access the Android clipboard.
        String id = "";
        String metadata = request.split(";", 2)[0];
        for (String field : metadata.split(":"))
            if (field.startsWith("id=")) id = ":id=" + field.substring(3).replaceAll("[^a-zA-Z0-9_+.-]", "");
        String type = metadata.startsWith("type=read") ? "read" : "write";
        session.write("\033]5522;type=" + type + ":status=EPERM" + id + "\033\\");
    }

    void onBell(@NonNull TerminalSession session);

    void onColorsChanged(@NonNull TerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    void setTerminalShellPid(@NonNull TerminalSession session, int pid);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
