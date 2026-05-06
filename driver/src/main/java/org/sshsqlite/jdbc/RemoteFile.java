package org.sshsqlite.jdbc;

final class RemoteFile {
    private final String path;
    private final int uid;
    private final int gid;
    private final int permissions;
    private final long size;
    private final boolean regularFile;
    private final boolean directory;

    RemoteFile(String path, int uid, int gid, int permissions, long size, boolean regularFile, boolean directory) {
        this.path = path;
        this.uid = uid;
        this.gid = gid;
        this.permissions = permissions;
        this.size = size;
        this.regularFile = regularFile;
        this.directory = directory;
    }

    String path() { return path; }
    int uid() { return uid; }
    int gid() { return gid; }
    int permissions() { return permissions; }
    long size() { return size; }
    boolean regularFile() { return regularFile; }
    boolean directory() { return directory; }

    boolean groupWritable() {
        return (permissions & 0020) != 0;
    }

    boolean otherWritable() {
        return (permissions & 0002) != 0;
    }
}
