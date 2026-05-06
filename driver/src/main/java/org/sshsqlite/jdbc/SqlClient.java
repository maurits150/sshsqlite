package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

interface SqlClient {
    JsonNode ping() throws IOException;
    JsonNode query(String sql, int maxRows, int fetchSize, int timeoutMs) throws IOException;
    JsonNode query(String sql, List<Map<String, Object>> params, int maxRows, int fetchSize, int timeoutMs) throws IOException;
    JsonNode fetch(String cursorId, int maxRows, int timeoutMs) throws IOException;
    JsonNode closeCursor(String cursorId) throws IOException;
    JsonNode exec(String sql, List<Map<String, Object>> params, int timeoutMs) throws IOException;
    JsonNode begin(String mode, int timeoutMs) throws IOException;
    JsonNode commit(int timeoutMs) throws IOException;
    JsonNode rollback(int timeoutMs) throws IOException;
    void cancelActive() throws Exception;
    boolean isBroken();
    long lastRequestId();
    String sqliteVersion();
    ProtocolClient.HelperMetadata helperMetadata();
    SQLException toSqlException(Exception e);
    void closeReader();
}
