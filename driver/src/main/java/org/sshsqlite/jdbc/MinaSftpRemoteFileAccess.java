package org.sshsqlite.jdbc;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class MinaSftpRemoteFileAccess implements RemoteFileAccess {
    private final ClientSession session;

    MinaSftpRemoteFileAccess(ClientSession session) {
        this.session = session;
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            SftpClient.Attributes attrs = sftp.stat(path);
            return new RemoteFile(path, attrs.getUserId(), attrs.getGroupId(), attrs.getPermissions(), attrs.getSize(),
                    attrs.isRegularFile(), attrs.isDirectory());
        } catch (IOException e) {
            throw new IOException("SFTP stat failed for helper", e);
        }
    }

    @Override
    public byte[] readAll(String path, int maxBytes) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             InputStream input = sftp.read(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read == -1) {
                    return out.toByteArray();
                }
                total += read;
                if (total > maxBytes) {
                    throw new IOException("helper file exceeds maximum verification size");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IOException("SFTP read failed for helper", e);
        }
    }
}
