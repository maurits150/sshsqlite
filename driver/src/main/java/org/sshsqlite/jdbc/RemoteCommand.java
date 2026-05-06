package org.sshsqlite.jdbc;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class RemoteCommand {
    private static final List<String> FIXED_ARGS = List.of("--stdio");
    private static final Pattern CONTROL = Pattern.compile(".*[\\x00-\\x1F\\x7F].*");

    private RemoteCommand() {
    }

    static String helperCommand(String helperPath) {
        validateHelperPath(helperPath);
        StringBuilder command = new StringBuilder(posixSingleQuote(helperPath));
        for (String arg : FIXED_ARGS) {
            command.append(' ').append(posixSingleQuote(arg));
        }
        return command.toString();
    }

    static String sqlite3Command(ConnectionConfig config) {
        validateSqlite3Path(config.sqlite3Path);
        StringBuilder command = new StringBuilder(posixSingleQuote(config.sqlite3Path));
        command.append(' ').append(posixSingleQuote("-batch"));
        if (config.readonly) {
            command.append(' ').append(posixSingleQuote("-readonly"));
        }
        command.append(' ').append(posixSingleQuote(config.dbPath));
        return command.toString();
    }

    static void validateSqlite3Path(String sqlite3Path) {
        if (sqlite3Path == null || sqlite3Path.isBlank()) {
            throw new IllegalArgumentException("sqlite3.path is required");
        }
        if (sqlite3Path.indexOf('\u0000') >= 0 || CONTROL.matcher(sqlite3Path).matches()) {
            throw new IllegalArgumentException("sqlite3.path contains unsupported characters");
        }
        if ("sqlite3".equals(sqlite3Path)) {
            return;
        }
        Path path = Path.of(sqlite3Path);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("sqlite3.path must be absolute or exactly sqlite3");
        }
        for (Path element : path) {
            String part = element.toString();
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("sqlite3.path must not contain . or .. elements");
            }
        }
    }

    static void validateHelperPath(String helperPath) {
        if (helperPath == null || helperPath.isBlank()) {
            throw new IllegalArgumentException("helper.path is required");
        }
        if (!helperPath.startsWith("/")) {
            throw new IllegalArgumentException("helper.path must be absolute");
        }
        if (helperPath.startsWith("~/") || helperPath.contains("\u0000") || CONTROL.matcher(helperPath).matches()) {
            throw new IllegalArgumentException("helper.path contains unsupported characters");
        }
        Path path = Path.of(helperPath);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("helper.path must be absolute");
        }
        for (Path element : path) {
            String part = element.toString();
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("helper.path must not contain . or .. elements");
            }
        }
    }

    static String posixSingleQuote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
