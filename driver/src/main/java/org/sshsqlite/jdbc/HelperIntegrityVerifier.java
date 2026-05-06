package org.sshsqlite.jdbc;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class HelperIntegrityVerifier {
    private static final int MAX_HELPER_BYTES = 128 * 1024 * 1024;

    void verify(ConnectionConfig config, RemoteFileAccess files) throws IOException {
        if (!config.productionMode) {
            return;
        }
        if (config.helperPath == null || config.helperPath.isBlank()) {
            throw new IOException("helper.path is required in production");
        }
        if (config.helperExpectedSha256 == null || config.helperExpectedSha256.isBlank()) {
            throw new IOException("helper.expectedSha256 is required in production");
        }
        if (files == null) {
            throw new IOException("SFTP/stat/read verification is unavailable");
        }
        RemoteFile file = files.stat(config.helperPath);
        if (!file.regularFile()) {
            throw new IOException("helper.path is not a regular file");
        }
        if (file.uid() != config.helperExpectedUid) {
            throw new IOException("helper.path is owned by unexpected uid");
        }
        if (file.groupWritable() || file.otherWritable()) {
            throw new IOException("helper.path must not be group- or other-writable");
        }
        if (file.size() < 1 || file.size() > MAX_HELPER_BYTES) {
            throw new IOException("helper file size is outside verification bounds");
        }
        byte[] contents = files.readAll(config.helperPath, MAX_HELPER_BYTES);
        String actual = sha256(contents);
        String expected = normalizeHash(config.helperExpectedSha256);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("helper hash mismatch");
        }
    }

    static String sha256(byte[] contents) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(contents);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeHash(String value) {
        if (value.startsWith("sha256:")) {
            return value.substring("sha256:".length());
        }
        return value;
    }
}
