package org.sshsqlite.jdbc;

import java.io.IOException;

interface RemoteFileAccess {
    RemoteFile stat(String path) throws IOException;

    byte[] readAll(String path, int maxBytes) throws IOException;
}
