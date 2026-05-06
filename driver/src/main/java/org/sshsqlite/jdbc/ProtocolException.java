package org.sshsqlite.jdbc;

import java.io.IOException;

final class ProtocolException extends IOException {
    private final String sqlState;
    private final boolean connectionBroken;

    ProtocolException(String message) {
        this(message, null, false, null);
    }

    ProtocolException(String message, String sqlState, boolean connectionBroken) {
        this(message, sqlState, connectionBroken, null);
    }

    private ProtocolException(String message, String sqlState, boolean connectionBroken, Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
        this.sqlState = sqlState;
        this.connectionBroken = connectionBroken;
    }

    ProtocolException(String message, Throwable cause) {
        this(message, null, false, cause);
    }

    String sqlState() { return sqlState; }

    boolean connectionBroken() { return connectionBroken; }
}
