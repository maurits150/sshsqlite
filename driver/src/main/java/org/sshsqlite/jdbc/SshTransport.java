package org.sshsqlite.jdbc;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.io.BuiltinIoServiceFactoryFactories;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class SshTransport implements Transport {
    private static final String IO_SERVICE_FACTORY_PROPERTY = "org.apache.sshd.common.io.IoServiceFactoryFactory";

    private final SshClient client;
    private final ClientSession session;
    private final ClientChannel channel;
    private final SqlClient protocol;
    private final BoundedCapture stderr;
    private final ExecutorService executor;

    private SshTransport(SshClient client, ClientSession session, ClientChannel channel, SqlClient protocol,
                         BoundedCapture stderr, ExecutorService executor) {
        this.client = client;
        this.session = session;
        this.channel = channel;
        this.protocol = protocol;
        this.stderr = stderr;
        this.executor = executor;
    }

    static SshTransport connect(ConnectionConfig config) throws IOException {
        return connect(config, null);
    }

    static SshTransport connect(ConnectionConfig config, RemoteFileAccess remoteFiles) throws IOException {
        if (config.helperPath == null || config.helperPath.isBlank()) {
            RemoteCommand.validateSqlite3Path(config.sqlite3Path);
        } else {
            RemoteCommand.validateHelperPath(config.helperPath);
        }
        forcePureJavaIoProvider();
        SshClient client = SshClient.setUpDefaultClient();
        configurePureJavaIo(client);
        client.setServerKeyVerifier(MinaKnownHosts.verifier(config.knownHosts));
        boolean agentConfigured = configureAgent(client, config);
        requireConfiguredAuthentication(config, agentConfigured);
        configureAuthPolicy(client, config, agentConfigured);
        configurePrivateKey(client, config);
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "sshsqlite-ssh-transport");
            thread.setDaemon(true);
            return thread;
        });
        client.start();
        ClientSession session = null;
        try {
            ConnectFuture connect = client.connect(config.sshUser, config.sshHost, config.sshPort).verify(Duration.ofMillis(config.connectTimeoutMs));
            session = connect.getSession();
            if (config.password != null) {
                session.addPasswordIdentity(config.password);
            }
            session.auth().verify(Duration.ofMillis(config.connectTimeoutMs));
            boolean helperMode = config.helperPath != null && !config.helperPath.isBlank();
            if (helperMode) {
                RemoteFileAccess verifierFiles = remoteFiles == null ? new MinaSftpRemoteFileAccess(session) : remoteFiles;
                new HelperIntegrityVerifier().verify(config, verifierFiles);
            }
            PipedInputStream stdout = new PipedInputStream();
            PipedOutputStream stdoutSink = new PipedOutputStream(stdout);
            PipedOutputStream stdin = new PipedOutputStream();
            PipedInputStream stdinSource = new PipedInputStream(stdin);
            ClientChannel channel = session.createExecChannel(helperMode ? RemoteCommand.helperCommand(config.helperPath) : RemoteCommand.sqlite3Command(config));
            channel.setIn(stdinSource);
            channel.setOut(stdoutSink);
            PipedInputStream stderrInput = new PipedInputStream();
            PipedOutputStream stderrSink = new PipedOutputStream(stderrInput);
            BoundedCapture stderr = new BoundedCapture(stderrInput, config.stderrMaxBufferedBytes);
            executor.submit(stderr);
            channel.setErr(stderrSink);
            channel.open().verify(helperMode ? config.helperStartupTimeoutMs : config.sqlite3StartupTimeoutMs);
            ClientSession activeSession = session;
            SqlClient protocol = helperMode
                    ? new ProtocolClient(stdout, stdin, config.maxFrameBytes)
                    : new CliProtocolClient(stdout, stdin, config, stderr, () -> {
                        channel.close(true);
                        activeSession.close(true);
                        client.stop();
                        executor.shutdownNow();
                    });
            SshTransport transport = new SshTransport(client, session, channel, protocol, stderr, executor);
            if (helperMode) {
                ProtocolClient helperProtocol = (ProtocolClient) protocol;
                transport.withStartupTimeout(config, () -> {
                    helperProtocol.hello();
                    helperProtocol.open(config);
                    return null;
                });
            }
            return transport;
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.close(false).await(2, TimeUnit.SECONDS);
            }
            client.stop();
            executor.shutdownNow();
            throw e;
        } catch (Exception e) {
            if (session != null) {
                session.close(false).await(2, TimeUnit.SECONDS);
            }
            client.stop();
            executor.shutdownNow();
            throw new IOException("SSHSQLite startup failed", e);
        }
    }

    static void forcePureJavaIoProvider() {
        System.setProperty(IO_SERVICE_FACTORY_PROPERTY, BuiltinIoServiceFactoryFactories.NIO2.getName());
    }

    static void configurePureJavaIo(SshClient client) {
        client.setIoServiceFactoryFactory(BuiltinIoServiceFactoryFactories.NIO2.create());
    }

    static boolean configureAgent(SshClient client, ConnectionConfig config) throws IOException {
        if (!config.sshAgent) {
            return false;
        }
        if (hasConfiguredPrivateKey(config) || config.password != null) {
            return false;
        }
        throw new IOException("SSH agent authentication is not supported by the self-contained SSHSQLite driver; configure ssh.privateKey or ssh.password instead");
    }

    static void requireConfiguredAuthentication(ConnectionConfig config, boolean agentConfigured) throws IOException {
        if (!agentConfigured && !hasConfiguredPrivateKey(config) && config.password == null) {
            throw new IOException("No SSH authentication configured; set ssh.privateKey or ssh.password (DBeaver password field is accepted as password). Checked default private keys: "
                    + ConnectionConfig.defaultPrivateKeyCandidates());
        }
    }

    static void configureAuthPolicy(SshClient client, ConnectionConfig config, boolean agentConfigured) {
        List<UserAuthFactory> factories = new ArrayList<>();
        if (agentConfigured || hasConfiguredPrivateKey(config)) {
            factories.add(UserAuthPublicKeyFactory.INSTANCE);
        }
        if (config.password != null) {
            factories.add(UserAuthPasswordFactory.INSTANCE);
        }
        if (!hasConfiguredPrivateKey(config)) {
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        }
        client.setUserAuthFactories(factories);
    }

    private static void configurePrivateKey(SshClient client, ConnectionConfig config) throws IOException {
        if (!hasConfiguredPrivateKey(config)) {
            return;
        }
        try {
            Path keyPath = Path.of(config.privateKey);
            try (InputStream input = Files.newInputStream(keyPath)) {
                FilePasswordProvider passwordProvider = config.privateKeyPassphrase == null
                        ? FilePasswordProvider.EMPTY
                        : FilePasswordProvider.of(config.privateKeyPassphrase);
                Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(keyPath.toString()),
                        input, passwordProvider);
                client.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keys));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to load configured SSH private key", e);
        }
    }

    private static boolean hasConfiguredPrivateKey(ConnectionConfig config) {
        return config.privateKey != null && !config.privateKey.isBlank();
    }

    @Override
    public SqlClient protocol() {
        return protocol;
    }

    @Override
    public String diagnostics() {
        return stderr.text();
    }

    @Override
    public void close() throws IOException {
        protocol.closeReader();
        if (!channel.close(false).await(2, TimeUnit.SECONDS)) {
            channel.close(true).await(2, TimeUnit.SECONDS);
        }
        if (!session.close(false).await(2, TimeUnit.SECONDS)) {
            session.close(true).await(2, TimeUnit.SECONDS);
        }
        client.stop();
        executor.shutdownNow();
    }

    private <T> T withStartupTimeout(ConnectionConfig config, java.util.concurrent.Callable<T> callable) throws Exception {
        Future<T> future = executor.submit(callable);
        try {
            return future.get(config.helperStartupTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out waiting for helper startup", e);
        } catch (Exception e) {
            throw new IOException("Helper startup failed: " + diagnostics(), e);
        }
    }
}
