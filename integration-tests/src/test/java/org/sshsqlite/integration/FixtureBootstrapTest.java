package org.sshsqlite.integration;

import org.junit.jupiter.api.Test;
import org.sshsqlite.integration.fixtures.LocalProcessTransportFixture;
import org.sshsqlite.integration.fixtures.OpenSshContainerFixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureBootstrapTest {
    @Test
    void opensshFixtureDocumentsRequiredCapabilities() {
        assertTrue(new OpenSshContainerFixture().requiredCapabilities().contains("sqlite3-cli-exec"));
    }

    @Test
    void localProcessFixtureIsAvailableForProtocolTests() {
        assertTrue(new LocalProcessTransportFixture().transportName().contains("local-process"));
    }
}
