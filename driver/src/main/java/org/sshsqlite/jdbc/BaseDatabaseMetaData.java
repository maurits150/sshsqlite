package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BaseDatabaseMetaData implements java.sql.DatabaseMetaData {
    protected final BaseConnection connection;
    protected void checkOpen() throws SQLException { connection.checkOpen(); }
    private ResultSet emptyResultSet() { return new BaseResultSet(null, new BaseResultSetMetaData()); }
    public BaseDatabaseMetaData(BaseConnection connection) { this.connection = java.util.Objects.requireNonNull(connection, "connection"); }
    @Override
    public java.sql.ResultSet getAttributes(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        checkOpen();
        return true;
    }

    @Override
    public String getURL() throws java.sql.SQLException {
        checkOpen();
        return null;
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public String getUserName() throws java.sql.SQLException {
        checkOpen();
        return "";
    }

    @Override
    public String getDriverName() throws java.sql.SQLException {
        checkOpen();
        return "SSHSQLite JDBC Driver";
    }

    @Override
    public String getDriverVersion() throws java.sql.SQLException {
        checkOpen();
        return "0.1";
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 1;
    }

    @Override
    public String getDatabaseProductName() throws java.sql.SQLException {
        checkOpen();
        return "SQLite";
    }

    @Override
    public String getDatabaseProductVersion() throws java.sql.SQLException {
        checkOpen();
        return "unknown";
    }

    @Override
    public boolean supportsTransactions() throws java.sql.SQLException {
        checkOpen();
        return false;
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean allProceduresAreCallable() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean allTablesAreSelectable() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean nullsAreSortedHigh() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean nullsAreSortedLow() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean usesLocalFiles() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean usesLocalFilePerTable() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getIdentifierQuoteString() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getSQLKeywords() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getNumericFunctions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getStringFunctions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getSystemFunctions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getTimeDateFunctions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getSearchStringEscape() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getExtraNameCharacters() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsColumnAliasing() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsConvert(int arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsConvert() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsGroupBy() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMultipleResultSets() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMultipleTransactions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsNonNullableColumns() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOuterJoins() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsFullOuterJoins() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getSchemaTerm() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getProcedureTerm() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getCatalogTerm() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isCatalogAtStart() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String getCatalogSeparator() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsPositionedDelete() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsPositionedUpdate() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSelectForUpdate() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsStoredProcedures() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsUnion() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsUnionAll() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxCharLiteralLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnsInIndex() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnsInSelect() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxColumnsInTable() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxConnections() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxCursorNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxIndexLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxSchemaNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxProcedureNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxCatalogNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxRowSize() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxStatementLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxStatements() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxTableNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxTablesInSelect() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getMaxUserNameLength() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getDefaultTransactionIsolation() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getProcedures(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getProcedureColumns(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getTables(String arg0, String arg1, String arg2, String[] arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getSchemas() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getSchemas(String arg0, String arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getCatalogs() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getTableTypes() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getColumns(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getColumnPrivileges(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getTablePrivileges(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getBestRowIdentifier(String arg0, String arg1, String arg2, int arg3, boolean arg4) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getVersionColumns(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getPrimaryKeys(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getImportedKeys(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getExportedKeys(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getCrossReference(String arg0, String arg1, String arg2, String arg3, String arg4, String arg5) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getTypeInfo() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getIndexInfo(String arg0, String arg1, String arg2, boolean arg3, boolean arg4) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsResultSetType(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsResultSetConcurrency(int arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean ownUpdatesAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean ownDeletesAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean ownInsertsAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean othersUpdatesAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean othersDeletesAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean othersInsertsAreVisible(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean updatesAreDetected(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean deletesAreDetected(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean insertsAreDetected(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsBatchUpdates() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getUDTs(String arg0, String arg1, String arg2, int[] arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSavepoints() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsNamedParameters() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getSuperTypes(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getSuperTables(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsResultSetHoldability(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getDatabaseMajorVersion() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getDatabaseMinorVersion() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getJDBCMajorVersion() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getJDBCMinorVersion() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getSQLStateType() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean locatorsUpdateCopy() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsStatementPooling() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getClientInfoProperties() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getFunctions(String arg0, String arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getFunctionColumns(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getPseudoColumns(String arg0, String arg1, String arg2, String arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long getMaxLogicalLobSize() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsRefCursors() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean supportsSharding() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public <T> T unwrap(Class<T> arg0) throws java.sql.SQLException {
        return JdbcSupport.unwrap(this, arg0);
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws java.sql.SQLException {
        return JdbcSupport.isWrapperFor(this, arg0);
    }

}
