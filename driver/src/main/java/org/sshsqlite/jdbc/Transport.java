package org.sshsqlite.jdbc;

import java.io.Closeable;
import java.io.IOException;

interface Transport extends Closeable {
    SqlClient protocol();

    String diagnostics();

    @Override
    void close() throws IOException;
}
