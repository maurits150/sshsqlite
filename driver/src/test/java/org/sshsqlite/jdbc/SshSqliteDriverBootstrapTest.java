package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SshSqliteDriverBootstrapTest {
    @Test
    void driverModuleLoads() {
        assertNotNull(SshSqliteDriver.class);
    }
}
