package io.github.imcinq.puremetal;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Keeps the macOS user name and home folder out of everything the mod logs or reports. */
public final class Redact {
    private static final String HOME = System.getProperty("user.home");
    private static final Pattern USER_DIR = Pattern.compile(String.join("", "/Us", "ers/") + "[^/\\s:'\"]+");

    private Redact() {
    }

    /** Replaces the home folder (and any other macOS user folder path) with {@code ~}. Null becomes "null". */
    public static String home(final @Nullable String text) {
        if (text == null) {
            return "null";
        }
        String result = text;
        if (HOME != null && HOME.length() > 1 && !HOME.isBlank()) {
            result = result.replace(HOME, "~");
        }
        return USER_DIR.matcher(result).replaceAll("~");
    }

    /** The throwable's class and redacted message, followed by its redacted cause chain; never a stack trace. */
    public static String error(final @Nullable Throwable error) {
        if (error == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) {
                out.append(" <- ");
            }
            out.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                out.append(": ").append(home(message));
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return out.toString();
    }
}
