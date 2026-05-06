package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.common.io.BuiltinIoServiceFactoryFactories;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlAndConfigTest {
    @Test
    void parsesUserHostPortPathAndQuery() {
        ConnectionConfig config = ConnectionConfig.parse(
                "jdbc:sshsqlite://alice@example.com:2200/srv/app/my%20db.sqlite?readonly=false&writeBackupAcknowledged=true",
                new Properties());

        assertEquals("alice", config.sshUser);
        assertEquals("example.com", config.sshHost);
        assertEquals(2200, config.sshPort);
        assertEquals("/srv/app/my db.sqlite", config.dbPath);
        assertFalse(config.readonly);
    }

    @Test
    void dbeaverStyleUrlParsesWithoutHelperProperties() {
        ConnectionConfig config = ConnectionConfig.parse(
                "jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db",
                new Properties());

        assertEquals("root", config.sshUser);
        assertEquals("db.example.org", config.sshHost);
        assertEquals(22, config.sshPort);
        assertEquals("/srv/app/app.db", config.dbPath);
        assertEquals("/usr/bin/sqlite3", config.sqlite3Path);
        assertFalse(config.readonly);
        assertEquals(null, config.helperPath);
    }

    @Test
    void defaultsKnownHostsAndMayDiscoverStandardPrivateKey() {
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://alice@db.example.org/srv/app/app.db", new Properties());

        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"), config.knownHosts);
        if (config.privateKey != null) {
            assertTrue(config.privateKey.endsWith("id_ed25519") || config.privateKey.endsWith("id_ecdsa") || config.privateKey.endsWith("id_rsa")
                    || config.privateKey.endsWith("id_dsa") || config.privateKey.endsWith("identity"));
        }
    }

    @Test
    void parsesDocumentedSqliteStartupTimeout() {
        Properties props = new Properties();
        props.setProperty("sqlite3.startupTimeoutMs", "15000");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);

        assertEquals(15000, config.sqlite3StartupTimeoutMs);
        assertEquals(10000, config.helperStartupTimeoutMs);
    }

    @Test
    void parsesIpv6Literal() {
        ConnectionConfig config = ConnectionConfig.parse(
                "jdbc:sshsqlite://user@[2001:db8::10]:2222/srv/db.sqlite", new Properties());

        assertEquals("2001:db8::10", config.sshHost);
        assertEquals(2222, config.sshPort);
    }

    @Test
    void propertiesOverrideQueryAndDbPathOverridesUrlPath() {
        Properties props = new Properties();
        props.setProperty("readonly", "true");
        props.setProperty("db.path", "/override.sqlite");
        ConnectionConfig config = ConnectionConfig.parse(
                "jdbc:sshsqlite://user@example.com/url.sqlite?readonly=false", props);

        assertEquals("/override.sqlite", config.dbPath);
        assertEquals(true, config.readonly);
    }

    @Test
    void rejectsUnknownQueryAndUrlSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db?unknown=true", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u:pw@example.com/db", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db?ssh.password=pw", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db?ssh.privateKey=/tmp/id", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db?ssh.privateKeyPassphrase=pw", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db?ssh.privateKeyMaterial=key", new Properties()));
    }

    @Test
    void redactsSecretsAndPaths() {
        Properties props = new Properties();
        props.setProperty("ssh.password", "secret");
        props.setProperty("ssh.privateKeyPassphrase", "key-secret");
        props.setProperty("helper.expectedSha256", "abc");
        props.setProperty("helper.path", "/opt/sshsqlite/bin/helper");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);

        String text = config.toString();
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("key-secret"));
        assertFalse(text.contains("/opt/sshsqlite"));
        assertFalse(text.contains("/db"));
    }

    @Test
    void authPolicyOrderIsPublicKeyBeforePassword() {
        Properties props = new Properties();
        props.setProperty("ssh.agent", "false");
        props.setProperty("ssh.privateKey", "/tmp/id_ed25519");
        props.setProperty("ssh.password", "secret");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);
        SshClient client = SshClient.setUpDefaultClient();

        SshTransport.configureAuthPolicy(client, config, false);

        assertEquals(UserAuthPublicKeyFactory.INSTANCE, client.getUserAuthFactories().get(0));
        assertEquals(UserAuthPasswordFactory.INSTANCE, client.getUserAuthFactories().get(1));
    }

    @Test
    void acceptsDesktopToolUserAndPasswordAliases() {
        Properties props = new Properties();
        props.setProperty("user", "desktop-root");
        props.setProperty("password", "desktop-secret");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://host.example/srv/db.sqlite", props);

        assertEquals("desktop-root", config.sshUser);
        assertEquals("desktop-secret", config.password);
        assertFalse(config.toString().contains("desktop-secret"));
    }

    @Test
    void acceptsCommonDesktopToolCredentialAliases() {
        Properties props = new Properties();
        props.setProperty("username", "desktop-root");
        props.setProperty("pass", "desktop-secret");
        props.setProperty("knownHosts", "/tmp/known_hosts");
        props.setProperty("identityFile", "/tmp/id_ed25519");
        props.setProperty("passphrase", "key-secret");
        props.setProperty("sqlite3Path", "/custom/sqlite3");
        props.setProperty("database", "/override.db");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://host.example/from-url.db", props);

        assertEquals("desktop-root", config.sshUser);
        assertEquals("desktop-secret", config.password);
        assertEquals("/tmp/known_hosts", config.knownHosts.toString());
        assertEquals("/tmp/id_ed25519", config.privateKey);
        assertEquals("key-secret", config.privateKeyPassphrase);
        assertEquals("/custom/sqlite3", config.sqlite3Path);
        assertEquals("/override.db", config.dbPath);
        assertFalse(config.toString().contains("desktop-secret"));
        assertFalse(config.toString().contains("key-secret"));
        assertFalse(config.toString().contains("/tmp/id_ed25519"));
    }

    @Test
    void acceptsUppercaseJdbcCredentialAliases() {
        Properties props = new Properties();
        props.setProperty("UID", "desktop-root");
        props.setProperty("PWD", "desktop-secret");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://host.example/srv/db.sqlite", props);

        assertEquals("desktop-root", config.sshUser);
        assertEquals("desktop-secret", config.password);
    }

    @Test
    void explicitSshPropertiesWinOverDesktopToolAliases() {
        Properties props = new Properties();
        props.setProperty("user", "desktop-root");
        props.setProperty("password", "desktop-secret");
        props.setProperty("ssh.user", "ssh-root");
        props.setProperty("ssh.password", "ssh-secret");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://host.example/srv/db.sqlite", props);

        assertEquals("ssh-root", config.sshUser);
        assertEquals("ssh-secret", config.password);
    }

    @Test
    void noAuthenticationFailsBeforeSshdNoFactoriesError() throws Exception {
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", new Properties());

        if (config.privateKey == null) {
            assertTrue(assertThrows(java.io.IOException.class, () -> SshTransport.requireConfiguredAuthentication(config, false))
                    .getMessage().contains("Checked default private keys"));
        } else {
            SshTransport.requireConfiguredAuthentication(config, false);
        }
    }

    @Test
    void sshTransportForcesPureJavaNio2Io() {
        SshClient client = SshClient.setUpDefaultClient();

        SshTransport.configurePureJavaIo(client);

        assertEquals(BuiltinIoServiceFactoryFactories.NIO2.create().getClass(), client.getIoServiceFactoryFactory().getClass());
    }

    @Test
    void driverForcesPureJavaNio2ProviderBeforeSshdDiscovery() {
        SshTransport.forcePureJavaIoProvider();

        assertEquals("nio2", System.getProperty("org.apache.sshd.common.io.IoServiceFactoryFactory"));
    }

    @Test
    void agentOnlyWithoutSocketFailsClearly() throws Exception {
        Properties props = new Properties();
        props.setProperty("ssh.agent", "true");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);
        SshClient client = SshClient.setUpDefaultClient();

        if (config.privateKey == null) {
            assertTrue(assertThrows(java.io.IOException.class, () -> SshTransport.configureAgent(client, config))
                    .getMessage().contains("not supported"));
        } else {
            assertFalse(SshTransport.configureAgent(client, config));
        }
    }

    @Test
    void agentRequestStillAllowsPasswordFallbackWithoutNativeAgentLibraries() throws Exception {
        Properties props = new Properties();
        props.setProperty("ssh.agent", "true");
        props.setProperty("ssh.password", "secret");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);
        SshClient client = SshClient.setUpDefaultClient();

        assertFalse(SshTransport.configureAgent(client, config));
    }
}
