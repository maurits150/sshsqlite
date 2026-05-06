package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteCommandTest {
    @Test
    void quotesSpacesQuotesMetacharactersAndNonAscii() {
        String command = RemoteCommand.helperCommand("/opt/ssh sqlite/bin/help'er;$(whoami)-é");

        assertEquals("'/opt/ssh sqlite/bin/help'\"'\"'er;$(whoami)-é' '--stdio'", command);
    }

    @Test
    void rejectsRelativeOrControlPaths() {
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.helperCommand("sshsqlite-helper"));
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.helperCommand("/opt/../tmp/helper"));
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.helperCommand("/opt/helper\nnext"));
    }

    @Test
    void commandDoesNotIncludeDbPathOrSql() {
        String command = RemoteCommand.helperCommand("/opt/sshsqlite/bin/helper");

        assertFalse(command.contains("/srv/db.sqlite"));
        assertFalse(command.contains("select * from users"));
    }

    @Test
    void sqlite3CommandQuotesDatabasePathAndReadonlyFlag() {
        Properties props = new Properties();
        props.setProperty("sqlite3.path", "/usr/bin/sqlite3");
        props.setProperty("readonly", "true");
        props.setProperty("db.path", "/srv/dbs/my db'$(touch hacked).sqlite");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);

        String command = RemoteCommand.sqlite3Command(config);

        assertEquals("'/usr/bin/sqlite3' '-batch' '-readonly' '/srv/dbs/my db'\"'\"'$(touch hacked).sqlite'", command);
        assertFalse(command.contains("SELECT"));
    }

    @Test
    void sqlite3CommandOmitsReadonlyForWriteConnections() {
        Properties props = new Properties();
        props.setProperty("readonly", "false");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/srv/db.sqlite", props);

        assertEquals("'/usr/bin/sqlite3' '-batch' '/srv/db.sqlite'", RemoteCommand.sqlite3Command(config));
    }

    @Test
    void sqlite3PathRejectsRelativePaths() {
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.validateSqlite3Path("relative/sqlite3"));
    }

    @Test
    void sqlite3CommandQuotesExecutableAndDatabaseMetacharacters() {
        Properties props = new Properties();
        props.setProperty("sqlite3.path", "/opt/sqlite bins/sqlite3'$(ignored)");
        props.setProperty("readonly", "false");
        props.setProperty("db.path", "/srv/[::1]/db path';touch ignored.sqlite");
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);

        assertEquals("'/opt/sqlite bins/sqlite3'\"'\"'$(ignored)' '-batch' '/srv/[::1]/db path'\"'\"';touch ignored.sqlite'",
                RemoteCommand.sqlite3Command(config));
    }
}
