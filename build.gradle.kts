import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
}

allprojects {
    group = "org.sshsqlite"
    version = "0.1.0-SNAPSHOT"
}

val jdbcMatrixFile = layout.projectDirectory.file("docs/jdbc-method-support.generated.md")
val jdbcMatrixCheckFile = layout.buildDirectory.file("generated/jdbc-method-support.generated.md")
val jdbcInterfaces = listOf(
    java.sql.Driver::class.java,
    java.sql.Connection::class.java,
    java.sql.Statement::class.java,
    java.sql.PreparedStatement::class.java,
    java.sql.ResultSet::class.java,
    java.sql.ResultSetMetaData::class.java,
    java.sql.DatabaseMetaData::class.java,
    java.sql.Wrapper::class.java,
)

fun Method.matrixSignature(): String {
    val parameters = parameterTypes.joinToString(", ") { it.typeName.replace('$', '.') }
    return "${name}($parameters): ${returnType.typeName.replace('$', '.')}"
}

data class JdbcMatrixClassification(
    val status: String,
    val behavior: String,
    val failurePolicy: String,
    val phase: String,
    val testOwner: String,
)

fun Method.hasParameters(vararg types: Class<*>): Boolean = parameterTypes.contentEquals(types)

fun Method.hasLeadingSqlString(): Boolean = parameterTypes.firstOrNull() == String::class.java

fun unsupportedClassification(phase: String = "current driver") = JdbcMatrixClassification(
    "unsupported-feature",
    "Optional JDBC feature is not implemented by this driver.",
    "Open objects throw `SQLFeatureNotSupportedException` with SQLState `0A000`; closed objects throw `SQLException` with SQLState `08003` for connections or `HY010` for statements/result sets.",
    phase,
    "JDBC reflection tests",
)

fun wrapperClassification() = JdbcMatrixClassification(
    "supported",
    "Implements the JDBC `Wrapper` contract for this object and returns/casts this instance when compatible.",
    "Null or incompatible wrapper targets throw `SQLException` with SQLState `HY000`.",
    "current driver",
    "JDBC contract tests",
)

fun localStateClassification(behavior: String) = JdbcMatrixClassification(
    "local-advisory-state",
    behavior,
    "Closed objects throw the interface-specific closed-state `SQLException`; invalid arguments throw `SQLException` with SQLState `HY000`; unsupported alternate modes throw `SQLFeatureNotSupportedException` with SQLState `0A000`.",
    "current driver",
    "JDBC contract tests",
)

fun supportedClassification(behavior: String, testOwner: String = "JDBC contract/integration tests") = JdbcMatrixClassification(
    "supported",
    behavior,
    "SQLException from local validation or remote sqlite3/protocol failures is propagated with the driver SQLState; closed objects use SQLState `08003` or `HY010`.",
    "current driver",
    testOwner,
)

fun constantClassification(behavior: String) = JdbcMatrixClassification(
    "conservative-constant",
    behavior,
    "Closed objects throw the interface-specific closed-state `SQLException` when the implementation checks object state.",
    "current driver",
    "JDBC contract tests",
)

fun metadataClassification(behavior: String, populated: Boolean = true) = JdbcMatrixClassification(
    if (populated) "populated-metadata-result" else "empty-metadata-result",
    behavior,
    "SQLException from metadata SQL or protocol failures is propagated; closed connections throw SQLState `08003`.",
    "current driver",
    "metadata integration tests",
)

fun classifyJdbcMethod(iface: Class<*>, method: Method): JdbcMatrixClassification {
    val name = method.name
    val signature = method.matrixSignature()
    if (name == "unwrap" || name == "isWrapperFor") return wrapperClassification()

    return when (iface) {
        java.sql.Driver::class.java -> when (name) {
            "connect" -> supportedClassification("Accepts `jdbc:sshsqlite:` URLs, opens the SSH/local sqlite3 transport, and returns an `SshSqliteConnection`; returns null for non-matching URLs.", "URL/config tests and integration tests")
            "acceptsURL" -> supportedClassification("Returns true for non-null URLs starting with `jdbc:sshsqlite:`.", "URL/config tests")
            "getPropertyInfo" -> constantClassification("Returns an empty `DriverPropertyInfo` array.")
            "getMajorVersion" -> constantClassification("Returns driver major version `0`.")
            "getMinorVersion" -> constantClassification("Returns driver minor version `1`.")
            "jdbcCompliant" -> constantClassification("Returns false.")
            "getParentLogger" -> unsupportedClassification()
            else -> unsupportedClassification()
        }
        java.sql.Connection::class.java -> when {
            name in setOf("close", "isClosed", "isValid", "abort") -> supportedClassification("Manages connection lifecycle and defensive cleanup of registered statements and the underlying transport.", "JDBC contract and pooling tests")
            name in setOf("createStatement", "prepareStatement") && (method.parameterCount == 0 || method.hasParameters(String::class.java) || signature == "prepareStatement(java.lang.String, int): java.sql.PreparedStatement") -> supportedClassification("Creates forward-only statement objects; supported prepared-statement overloads bind indexed values and may request generated keys.", "query/write integration tests")
            name == "getMetaData" -> supportedClassification("Returns `SshSqliteDatabaseMetaData` backed by sqlite3 metadata queries.", "metadata integration tests")
            name in setOf("setAutoCommit", "getAutoCommit", "commit", "rollback") && !signature.contains("Savepoint") -> supportedClassification("Implements sqlite3 transaction state for read/write connections; commit/rollback are invalid while `autoCommit=true`.", "transaction integration tests")
            name in setOf("setReadOnly", "isReadOnly", "nativeSQL", "setCatalog", "getCatalog", "setSchema", "getSchema", "setTransactionIsolation", "getTransactionIsolation", "setHoldability", "getHoldability", "getWarnings", "clearWarnings", "setClientInfo", "getClientInfo", "setNetworkTimeout", "getNetworkTimeout") -> localStateClassification("Stores or returns local advisory JDBC state without rewriting user SQL; read-only mode is fixed by connection configuration.")
            else -> unsupportedClassification()
        }
        java.sql.Statement::class.java -> when {
            name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate") && !signature.contains("int[]") && !signature.contains("java.lang.String[]") -> supportedClassification("Executes SQL through remote sqlite3 stdin, preserving SQL text; query/update methods validate whether rows are expected.", "query/write integration tests")
            name in setOf("getResultSet", "getUpdateCount", "getLargeUpdateCount", "getMoreResults", "getGeneratedKeys") -> supportedClassification("Returns current result state, generated-key result sets, or no-more-results state for the last execution.", "query/write integration tests")
            name in setOf("close", "cancel", "isClosed", "getConnection", "getWarnings", "clearWarnings") -> supportedClassification("Manages statement lifecycle, warnings, connection access, and best-effort cancellation for remote statements.", "JDBC contract tests")
            name in setOf("setMaxFieldSize", "getMaxFieldSize", "setMaxRows", "getMaxRows", "setLargeMaxRows", "getLargeMaxRows", "setQueryTimeout", "getQueryTimeout", "setFetchDirection", "getFetchDirection", "setFetchSize", "getFetchSize", "setEscapeProcessing", "setPoolable", "isPoolable", "closeOnCompletion", "isCloseOnCompletion") -> localStateClassification("Stores local JDBC statement options; only forward fetch direction is accepted.")
            name in setOf("getResultSetConcurrency", "getResultSetType", "getResultSetHoldability") -> constantClassification("Returns forward-only/read-only/close-cursors-at-commit capabilities for real SSHSQLite statements.")
            else -> unsupportedClassification()
        }
        java.sql.PreparedStatement::class.java -> when {
            name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate") && method.parameterCount == 0 -> supportedClassification("Executes the fixed prepared SQL with bound positional values through remote sqlite3.", "prepared-statement integration tests")
            name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch") && method.hasLeadingSqlString() -> JdbcMatrixClassification("invalid-state-error", "PreparedStatement SQL is fixed at construction; inherited Statement SQL overloads reject supplied SQL.", "Throws `SQLException` with SQLState `HY010`.", "current driver", "prepared-statement integration tests")
            name in setOf("setNull", "setString", "setInt", "setLong", "setDouble", "setFloat", "setBoolean", "setBytes", "setBigDecimal", "setDate", "setTime", "setTimestamp", "setObject", "clearParameters") -> supportedClassification("Binds supported scalar values as sqlite3 parameters; calendars are accepted but not timezone-adjusted.", "prepared-statement integration tests")
            name in setOf("getResultSet", "getUpdateCount", "getLargeUpdateCount", "getMoreResults", "getGeneratedKeys", "close") -> supportedClassification("Returns prepared-statement result/update state and generated-key result sets.", "prepared-statement integration tests")
            name in setOf("getMetaData") -> constantClassification("Returns null because result-set metadata is only known after execution.")
            name in setOf("getParameterMetaData") -> unsupportedClassification()
            name in setOf("getConnection", "getWarnings", "clearWarnings", "setMaxFieldSize", "getMaxFieldSize", "setMaxRows", "getMaxRows", "setLargeMaxRows", "getLargeMaxRows", "setQueryTimeout", "getQueryTimeout", "setFetchDirection", "getFetchDirection", "setFetchSize", "getFetchSize", "setEscapeProcessing", "setPoolable", "isPoolable", "closeOnCompletion", "isCloseOnCompletion") -> localStateClassification("Uses the same local advisory state as `Statement`.")
            else -> unsupportedClassification()
        }
        java.sql.ResultSet::class.java -> when {
            name in setOf("next", "close", "isClosed", "getMetaData", "wasNull", "getStatement", "isBeforeFirst", "isAfterLast", "isFirst", "isLast", "findColumn", "getObject", "getString", "getLong", "getInt", "getDouble", "getBytes", "getBoolean") -> supportedClassification("Supports forward-only remote cursor fetching and basic SQLite value getters by index or label.", "query integration tests")
            name in setOf("getType", "getConcurrency", "getHoldability", "isClosed") -> constantClassification("Returns conservative forward-only/read-only cursor state where implemented by the base result set.")
            else -> unsupportedClassification()
        }
        java.sql.ResultSetMetaData::class.java -> when {
            name in setOf("getColumnCount", "getColumnLabel", "getColumnName", "getColumnType", "getColumnTypeName", "getColumnClassName", "isNullable", "getCatalogName", "getSchemaName", "getTableName") -> supportedClassification("Returns column metadata captured from sqlite3 output or metadata queries, including declared SQLite type names.", "metadata integration tests")
            else -> unsupportedClassification()
        }
        java.sql.DatabaseMetaData::class.java -> when {
            name in setOf("getTables", "getColumns", "getPrimaryKeys", "getIndexInfo", "getImportedKeys", "getSchemas", "getTableTypes", "getTypeInfo") -> metadataClassification("Returns JDBC-shaped metadata result sets populated from SQLite-compatible `sqlite_master` and schema-qualified PRAGMAs.")
            name in setOf("getCatalogs", "getProcedures", "getProcedureColumns", "getFunctions", "getFunctionColumns", "getUDTs") -> metadataClassification("Returns a correctly shaped empty metadata result set for unsupported SQLite object categories.", populated = false)
            name in setOf("getURL", "getUserName", "getDatabaseProductVersion", "getDatabaseMajorVersion", "getDatabaseMinorVersion", "getJDBCMajorVersion", "getJDBCMinorVersion", "getSQLStateType", "getIdentifierQuoteString", "getSearchStringEscape", "getSQLKeywords", "getNumericFunctions", "getStringFunctions", "getSystemFunctions", "getTimeDateFunctions", "getExtraNameCharacters", "allProceduresAreCallable", "allTablesAreSelectable", "nullsAreSortedHigh", "nullsAreSortedLow", "nullsAreSortedAtStart", "nullsAreSortedAtEnd", "usesLocalFiles", "usesLocalFilePerTable", "supportsMixedCaseIdentifiers", "storesUpperCaseIdentifiers", "storesLowerCaseIdentifiers", "storesMixedCaseIdentifiers", "supportsMixedCaseQuotedIdentifiers", "storesUpperCaseQuotedIdentifiers", "storesLowerCaseQuotedIdentifiers", "storesMixedCaseQuotedIdentifiers", "supportsLikeEscapeClause", "supportsColumnAliasing", "nullPlusNonNullIsNull", "supportsMultipleResultSets", "supportsMultipleTransactions", "supportsNonNullableColumns", "supportsStoredProcedures", "supportsTransactions", "getDefaultTransactionIsolation", "supportsTransactionIsolationLevel", "supportsDataDefinitionAndDataManipulationTransactions", "supportsDataManipulationTransactionsOnly", "dataDefinitionCausesTransactionCommit", "dataDefinitionIgnoredInTransactions", "getResultSetHoldability", "supportsResultSetType", "supportsResultSetConcurrency", "supportsResultSetHoldability", "supportsBatchUpdates", "supportsMultipleOpenResults", "supportsGetGeneratedKeys", "generatedKeyAlwaysReturned", "supportsSavepoints", "supportsNamedParameters", "supportsStatementPooling", "getRowIdLifetime", "autoCommitFailureClosesAllResultSets", "isReadOnly", "getConnection", "getDriverName", "getDriverVersion", "getDriverMajorVersion", "getDriverMinorVersion", "getDatabaseProductName") -> constantClassification("Returns conservative SQLite/driver capability constants or connection-derived values.")
            else -> unsupportedClassification()
        }
        java.sql.Wrapper::class.java -> wrapperClassification()
        else -> unsupportedClassification()
    }
}

fun generateJdbcMatrix(): String {
    val rows = jdbcInterfaces.flatMap { iface ->
        iface.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .distinctBy { it.matrixSignature() }
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.matrixSignature() })
            .map { method -> iface.simpleName to method }
    }
    return buildString {
        appendLine("# JDBC Method Support Matrix")
        appendLine()
        appendLine("Generated by `./gradlew generateJdbcMethodSupport` for Java release 11.")
        appendLine()
        appendLine("Target interfaces: Driver, Connection, Statement, PreparedStatement, ResultSet, ResultSetMetaData, DatabaseMetaData, Wrapper.")
        appendLine()
        appendLine("| Interface | Method | MVP status | Behavior | Failure policy | Phase | Test owner |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        rows.forEach { (iface, method) ->
            val classification = classifyJdbcMethod(jdbcInterfaces.first { it.simpleName == iface }, method)
            appendLine("| `$iface` | `${method.matrixSignature()}` | ${classification.status} | ${classification.behavior} | ${classification.failurePolicy} | ${classification.phase} | ${classification.testOwner} |")
        }
    }
}

tasks.register("generateJdbcMethodSupport") {
    group = "verification"
    description = "Generates the committed Java 11 JDBC method support matrix."
    outputs.file(jdbcMatrixFile)
    doLast {
        require(JavaVersion.current().majorVersion.toInt() >= 11) { "JDBC matrix generation must run on Java 11 or newer." }
        jdbcMatrixFile.asFile.writeText(generateJdbcMatrix())
    }
}

tasks.register("checkJdbcMethodSupport") {
    group = "verification"
    description = "Checks the committed JDBC method support matrix is fresh and complete for Java 11."
    mustRunAfter("generateJdbcMethodSupport")
    inputs.file(jdbcMatrixFile)
    outputs.file(jdbcMatrixCheckFile)
    doLast {
        require(JavaVersion.current().majorVersion.toInt() >= 11) { "JDBC matrix check must run on Java 11 or newer." }
        val expected = generateJdbcMatrix()
        val actualFile = jdbcMatrixFile.asFile
        require(actualFile.isFile) { "Missing ${actualFile.path}; run ./gradlew generateJdbcMethodSupport" }
        val actual = actualFile.readText()
        val generatedTarget = jdbcMatrixCheckFile.get().asFile
        Files.createDirectories(generatedTarget.parentFile.toPath())
        generatedTarget.writeText(expected)
        require(!actual.contains("TODO", ignoreCase = true) && !actual.contains("TBD", ignoreCase = true) && !actual.contains("placeholder", ignoreCase = true)) {
            "JDBC method support matrix contains placeholder content."
        }
        require(actual == expected) {
            "JDBC method support matrix is stale; run ./gradlew generateJdbcMethodSupport"
        }
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(11))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(11)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

val helperTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs Go helper unit tests through the Gradle command surface."
    workingDir = layout.projectDirectory.dir("helper").asFile
    commandLine("go", "test", "./...")
}

val helperBinary = layout.buildDirectory.file("helper/sshsqlite-helper")
val releaseDistributionDir = layout.buildDirectory.dir("distributions")
val writeReleaseEvidenceFile = layout.buildDirectory.file("reports/write-release/backup-restore.json")
val supportedHelperTargets = listOf("linux-amd64", "linux-arm64")
val helperSources = fileTree(layout.projectDirectory.dir("helper")) {
    include("**/*.go", "go.mod", "go.sum")
    exclude("**/build/**")
}

fun currentGoOs(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("linux") -> "linux"
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("windows") -> "windows"
        else -> osName.substringBefore(' ')
    }
}

fun currentGoArch(): String {
    val archName = System.getProperty("os.arch").lowercase()
    return when (archName) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> archName
    }
}

fun helperTaskName(target: String): String = "buildHelper" + target.split('-').joinToString("") { part ->
    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val toolEvidenceSchema = "sshsqlite-tool-evidence-v1"
val toolEvidenceWorkflowIds = listOf(
    "add-driver-artifact",
    "configure-readonly-known-host-sqlite3-path",
    "connect-and-diagnostics",
    "browse-metadata",
    "open-data-grids",
    "run-bounded-select",
    "verify-fetch-batching",
    "verify-readonly-rejections",
    "capture-unexpected-jdbc-methods",
)
val toolEvidenceNames = setOf("DBeaver")
val sha256Regex = Regex("[0-9a-f]{64}")

fun Any?.asJsonObject(path: String): Map<*, *> = this as? Map<*, *> ?: error("$path must be a JSON object")

fun Any?.asJsonArray(path: String): List<*> = this as? List<*> ?: error("$path must be a JSON array")

fun Map<*, *>.requiredString(key: String, path: String): String {
    val value = this[key] as? String ?: error("$path.$key must be a string")
    require(value.isNotBlank()) { "$path.$key must not be blank" }
    return value
}

fun Map<*, *>.requiredSha256(key: String, path: String): String {
    val value = requiredString(key, path)
    require(value.matches(sha256Regex)) { "$path.$key must be a lowercase SHA-256 hex hash" }
    return value
}

fun requireReferenceFile(evidenceDir: File, reference: String, path: String): File {
    require(reference.isNotBlank()) { "$path must not be blank" }
    require(!reference.startsWith("/")) { "$path must be relative to ${evidenceDir.path}" }
    val relative = java.nio.file.Path.of(reference).normalize()
    require(!relative.startsWith("..")) { "$path must not escape ${evidenceDir.path}" }
    val file = evidenceDir.toPath().resolve(relative).normalize().toFile()
    require(file.isFile && file.length() > 0) { "$path references missing or empty evidence file: $reference" }
    return file
}

fun parseJsonFile(file: File, path: String): Map<*, *> = JsonSlurper().parse(file).asJsonObject(path)

fun validatePassFailJson(file: File, path: String) {
    val json = parseJsonFile(file, path)
    require(json.requiredString("status", path) == "pass") { "$path.status must be pass" }
    val workflowIds = json["workflows"].asJsonArray("$path.workflows").mapIndexed { index, item ->
        val workflow = item.asJsonObject("$path.workflows[$index]")
        require(workflow.requiredString("status", "$path.workflows[$index]") == "pass") { "$path.workflows[$index].status must be pass" }
        workflow.requiredString("id", "$path.workflows[$index]")
    }
    require(workflowIds == toolEvidenceWorkflowIds) { "$path.workflows must list the documented read-only workflows in order: ${toolEvidenceWorkflowIds.joinToString()}" }
}

fun validateUnexpectedJdbcTrace(file: File, path: String) {
    val json = parseJsonFile(file, path)
    json["unexpectedMethods"].asJsonArray("$path.unexpectedMethods")
}

fun validateToolEvidence(evidenceDir: File, allowMissingToolEvidence: Boolean) {
    if (!evidenceDir.exists()) {
        require(allowMissingToolEvidence) {
            "Missing ${evidenceDir.path}. Production verification requires DBeaver evidence; pass -PallowMissingToolEvidence=true only for development/preview."
        }
        return
    }
    val bundleFile = evidenceDir.resolve("workflow.json")
    require(bundleFile.isFile && bundleFile.length() > 0) { "${bundleFile.path} is required and must be non-empty." }
    val bundle = parseJsonFile(bundleFile, "workflow.json")
    require(bundle.requiredString("schema", "workflow.json") == toolEvidenceSchema) { "workflow.json.schema must be $toolEvidenceSchema" }
    require(bundle.requiredString("releaseScope", "workflow.json") == "production-readonly-mvp") { "workflow.json.releaseScope must be production-readonly-mvp" }
    val workflowIds = bundle["workflows"].asJsonArray("workflow.json.workflows").mapIndexed { index, item ->
        item.asJsonObject("workflow.json.workflows[$index]").requiredString("id", "workflow.json.workflows[$index]")
    }
    require(workflowIds == toolEvidenceWorkflowIds) { "workflow.json.workflows must list the documented read-only workflows in order: ${toolEvidenceWorkflowIds.joinToString()}" }
    val toolEntries = bundle["tools"].asJsonArray("workflow.json.tools").mapIndexed { index, item ->
        item.asJsonObject("workflow.json.tools[$index]")
    }
    val names = toolEntries.map { it.requiredString("name", "workflow.json.tools[]") }.toSet()
    require(names == toolEvidenceNames) { "workflow.json.tools must contain exactly DBeaver evidence" }
    toolEntries.forEachIndexed { index, tool ->
        val path = "workflow.json.tools[$index]"
        val name = tool.requiredString("name", path)
        require(name in toolEvidenceNames) { "$path.name must be DBeaver" }
        tool.requiredString("version", path)
        tool.requiredString("build", path)
        tool.requiredSha256("driverArtifactHash", path)
        tool.requiredString("remoteSqlite3Version", path)
        tool.requiredSha256("disposableFixtureHash", path)
        tool["redactedLogs"].asJsonArray("$path.redactedLogs").also { logs ->
            require(logs.isNotEmpty()) { "$path.redactedLogs must contain at least one reference" }
            logs.forEachIndexed { refIndex, ref -> requireReferenceFile(evidenceDir, ref as? String ?: error("$path.redactedLogs[$refIndex] must be a string"), "$path.redactedLogs[$refIndex]") }
        }
        tool["screenshotsOrDiagnostics"].asJsonArray("$path.screenshotsOrDiagnostics").also { refs ->
            require(refs.isNotEmpty()) { "$path.screenshotsOrDiagnostics must contain at least one reference" }
            refs.forEachIndexed { refIndex, ref -> requireReferenceFile(evidenceDir, ref as? String ?: error("$path.screenshotsOrDiagnostics[$refIndex] must be a string"), "$path.screenshotsOrDiagnostics[$refIndex]") }
        }
        val passFail = requireReferenceFile(evidenceDir, tool.requiredString("passFailJson", path), "$path.passFailJson")
        validatePassFailJson(passFail, "$path.passFailJson")
        val trace = requireReferenceFile(evidenceDir, tool.requiredString("unexpectedJdbcMethodTrace", path), "$path.unexpectedJdbcMethodTrace")
        validateUnexpectedJdbcTrace(trace, "$path.unexpectedJdbcMethodTrace")
        val toolWorkflowIds = tool["workflows"].asJsonArray("$path.workflows").mapIndexed { workflowIndex, item ->
            item.asJsonObject("$path.workflows[$workflowIndex]").requiredString("id", "$path.workflows[$workflowIndex]")
        }
        require(toolWorkflowIds == toolEvidenceWorkflowIds) { "$path.workflows must list every documented read-only workflow in order" }
    }
}

fun File.releaseRelativePath(baseDir: File): String = baseDir.toPath().relativize(toPath()).toString().replace(File.separatorChar, '/')

fun distributionFiles(distDir: File): List<File> = distDir.walkTopDown()
    .filter { it.isFile }
    .filterNot { file ->
        val name = file.name
        name.endsWith(".sig") || name.endsWith(".asc") || name.endsWith(".bundle") ||
            name == "sshsqlite_SHA256SUMS" || name == "sshsqlite-helper_SHA256SUMS"
    }
    .sortedBy { it.releaseRelativePath(distDir) }
    .toList()

fun verifySha256Manifest(distDir: File, manifest: File) {
    require(manifest.isFile && manifest.length() > 0) { "Missing or empty SHA-256 manifest: ${manifest.path}" }
    val entries = manifest.readLines().filter { it.isNotBlank() }
    require(entries.isNotEmpty()) { "SHA-256 manifest has no entries: ${manifest.path}" }
    entries.forEach { line ->
        val parts = line.split(Regex("\\s+"), limit = 2)
        require(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 manifest line in ${manifest.name}: $line" }
        val relativePath = parts[1].trim().removePrefix("*")
        require(!relativePath.startsWith("/") && !relativePath.contains("..")) { "Unsafe manifest path in ${manifest.name}: $relativePath" }
        val artifact = distDir.resolve(relativePath)
        require(artifact.isFile && artifact.length() > 0) { "Manifest entry is missing or empty: $relativePath" }
        require(sha256(artifact) == parts[0]) { "SHA-256 mismatch for $relativePath" }
    }
}

fun verifyReleaseDistributionContents(distDir: File, projectVersion: String) {
    require(distDir.isDirectory) { "Missing release distribution directory: ${distDir.path}. Run ./gradlew packageRelease first." }
    val files = distributionFiles(distDir)
    val relativePaths = files.map { it.releaseRelativePath(distDir) }
    val driverJars = files.filter { it.name.matches(Regex("sshsqlite-driver-.*-all\\.jar")) }
    require(driverJars.size == 1) { "Expected exactly one driver distribution jar, found ${driverJars.size}" }
    require("sshsqlite-release-metadata.json" in relativePaths) { "Missing release metadata JSON" }
    require("sshsqlite-gradle-runtime-dependencies.json" in relativePaths) { "Missing Gradle dependency inventory JSON" }
    files.forEach { file -> require(file.length() > 0) { "Release artifact is empty: ${file.releaseRelativePath(distDir)}" } }

    val manifest = distDir.resolve("sshsqlite_SHA256SUMS")
    verifySha256Manifest(distDir, manifest)
    val manifestPaths = manifest.readLines()
        .filter { it.isNotBlank() }
        .map { it.split(Regex("\\s+"), limit = 2)[1].trim().removePrefix("*") }
        .sorted()
    require(manifestPaths == relativePaths.sorted()) {
        "SHA-256 manifest entries do not exactly match release artifacts. manifest=$manifestPaths artifacts=${relativePaths.sorted()}"
    }

    val metadataText = distDir.resolve("sshsqlite-release-metadata.json").readText()
    require(metadataText.contains("\"driverVersion\": \"$projectVersion\"")) { "Release metadata driverVersion does not match project version $projectVersion" }
    require(metadataText.contains("\"protocolVersions\"")) { "Release metadata is missing protocolVersions" }
    require(metadataText.contains("\"sqlite3PathDefault\"")) { "Release metadata is missing sqlite3PathDefault" }
}

fun verifyReleaseSecurity(distDir: File, allowUnsignedRelease: Boolean, projectVersion: String) {
    require(distDir.isDirectory) { "Missing release distribution directory: ${distDir.path}. Run ./gradlew packageRelease first." }
    val files = distributionFiles(distDir)
    require(files.isNotEmpty()) { "No release artifacts found in ${distDir.path}. Run ./gradlew packageRelease first." }
    files.forEach { file -> require(file.length() > 0) { "Release artifact is empty: ${file.releaseRelativePath(distDir)}" } }

    val driverJars = files.filter { it.name.matches(Regex("sshsqlite-driver-.*-all\\.jar")) }
    require(driverJars.size == 1) { "Expected exactly one driver distribution jar, found ${driverJars.size}" }
    ZipFile(driverJars.single()).use { zip ->
        require(zip.getEntry("META-INF/services/java.sql.Driver") != null) { "Missing META-INF/services/java.sql.Driver in ${driverJars.single().name}" }
        require(zip.getEntry("org/sshsqlite/jdbc/SshSqliteDriver.class") != null) { "Missing SshSqliteDriver.class in ${driverJars.single().name}" }
    }

    val metadata = distDir.resolve("sshsqlite-release-metadata.json")
    require(metadata.isFile && metadata.length() > 0) { "Missing or empty release metadata: ${metadata.path}" }
    val metadataText = metadata.readText()
    require(metadataText.contains("\"driverVersion\": \"$projectVersion\"")) { "Release metadata driverVersion does not match project version $projectVersion" }
    require(metadataText.contains("\"protocolVersions\"")) { "Release metadata is missing protocolVersions" }
    require(metadataText.contains("\"sqlite3PathDefault\"")) { "Release metadata is missing sqlite3PathDefault" }

    verifyReleaseDistributionContents(distDir, projectVersion)
    distDir.resolve("sshsqlite-helper_SHA256SUMS").takeIf { it.isFile }?.let { verifySha256Manifest(distDir, it) }

    require(distDir.resolve("sshsqlite-gradle-runtime-dependencies.json").isFile) { "Missing Gradle dependency inventory" }

    val signedManifest = listOf("sshsqlite_SHA256SUMS.sig", "sshsqlite_SHA256SUMS.asc", "sshsqlite_SHA256SUMS.bundle").any { distDir.resolve(it).isFile }
    val provenance = distDir.resolve("sshsqlite-provenance.json")
    val signedProvenance = provenance.isFile && listOf("sshsqlite-provenance.json.sig", "sshsqlite-provenance.json.asc", "sshsqlite-provenance.json.bundle").any { distDir.resolve(it).isFile }
    if (!allowUnsignedRelease) {
        require(signedManifest) { "Production verifyRelease requires signed checksum manifest; pass -PallowUnsignedRelease=true only for development." }
        require(signedProvenance) { "Production verifyRelease requires signed provenance/attestation; pass -PallowUnsignedRelease=true only for development." }
    }
}

fun runConfiguredHook(hookName: String, command: String, workingDirectory: File) {
    val result = ProcessBuilder("bash", "-lc", command)
        .directory(workingDirectory)
        .inheritIO()
        .start()
        .waitFor()
    require(result == 0) { "$hookName failed with exit code $result" }
}

fun verifyWriteReleaseBackupEvidence(evidenceFile: File) {
    require(evidenceFile.isFile && evidenceFile.length() > 0) {
        "Missing write-capable backup/restore rehearsal evidence: ${evidenceFile.path}. Run ./gradlew verifyWriteReleaseBackupRestore or ./gradlew verifyRelease -PverifyWriteRelease=true."
    }
    val evidence = parseJsonFile(evidenceFile, "backup-restore.json")
    require(evidence.requiredString("schema", "backup-restore.json") == "sshsqlite-write-release-backup-restore-v1") { "backup-restore.json.schema is invalid" }
    require(evidence.requiredString("status", "backup-restore.json") == "pass") { "backup-restore.json.status must be pass" }
    require(evidence.requiredString("databaseScope", "backup-restore.json") == "disposable") { "backup-restore.json.databaseScope must be disposable" }
    require(evidence.requiredString("backupMethod", "backup-restore.json") == "VACUUM INTO") { "backup-restore.json.backupMethod must document the supported backup method" }
    require(evidence.requiredString("integrityCheck", "backup-restore.json") == "ok") { "backup-restore.json.integrityCheck must be ok" }
    require(evidence.requiredString("sshsqliteReadonlyBrowse", "backup-restore.json") == "pass") { "backup-restore.json.sshsqliteReadonlyBrowse must be pass" }
}

val buildHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Go SSHSQLite helper binary."
    workingDir = layout.projectDirectory.dir("helper").asFile
    inputs.files(helperSources)
    outputs.file(helperBinary)
    commandLine("go", "build", "-o", helperBinary.get().asFile.absolutePath, "./cmd/sshsqlite-helper")
}

val helperTargetTasks = supportedHelperTargets.associateWith { target ->
    val goOs = target.substringBefore('-')
    val goArch = target.substringAfter('-')
    val output = releaseDistributionDir.map { it.file("sshsqlite-helper-$target") }
    tasks.register(helperTaskName(target), Exec::class) {
        group = "distribution"
        description = "Builds the SSHSQLite helper for $target when running on a matching cgo-capable host."
        workingDir = layout.projectDirectory.dir("helper").asFile
        inputs.files(helperSources)
        outputs.file(output)
        environment("GOOS", goOs)
        environment("GOARCH", goArch)
        environment("CGO_ENABLED", "1")
        commandLine("go", "build", "-trimpath", "-o", output.get().asFile.absolutePath, "./cmd/sshsqlite-helper")
        doFirst {
            val hostTarget = "${currentGoOs()}-${currentGoArch()}"
            if (target != hostTarget) {
                throw GradleException("$target helper cross-build is a skeleton only: github.com/mattn/go-sqlite3 requires cgo and no cross C toolchain is configured. Run this task on a $target host or add an explicit cross compiler/toolchain.")
            }
        }
    }
}

val buildHelperCurrentHost by tasks.registering {
    group = "distribution"
    description = "Builds the helper distribution binary for the current supported host target."
    val hostTarget = "${currentGoOs()}-${currentGoArch()}"
    val hostTask = helperTargetTasks[hostTarget]
    doFirst {
        require(hostTask != null) { "Current host target $hostTarget is not in the supported helper target list: ${supportedHelperTargets.joinToString()}" }
    }
    if (hostTask != null) {
        dependsOn(hostTask)
    }
}

val cleanReleaseDistribution by tasks.registering(Delete::class) {
    group = "distribution"
    description = "Removes stale release distribution outputs before packaging a CLI driver candidate."
    delete(releaseDistributionDir)
}

val packageDriverDistribution by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the desktop-tool-ready JDBC driver jar into build/distributions."
    dependsOn(cleanReleaseDistribution)
    dependsOn(":driver:driverDistributionJar")
    from(project(":driver").tasks.named("driverDistributionJar"))
    into(releaseDistributionDir)
}

val generateHelperHashManifest by tasks.registering {
    group = "distribution"
    description = "Generates SHA-256 hashes for built helper distribution binaries."
    dependsOn(buildHelperCurrentHost)
    val manifest = releaseDistributionDir.map { it.file("sshsqlite-helper_SHA256SUMS") }
    outputs.file(manifest)
    outputs.upToDateWhen { false }
    doLast {
        val distDir = releaseDistributionDir.get().asFile
        val helpers = supportedHelperTargets
            .map { distDir.resolve("sshsqlite-helper-$it") }
            .filter { it.isFile }
            .sortedBy { it.name }
        require(helpers.isNotEmpty()) { "No helper binaries found in ${distDir.path}" }
        manifest.get().asFile.writeText(helpers.joinToString(System.lineSeparator(), postfix = System.lineSeparator()) { helper ->
            "${sha256(helper)}  ${helper.name}"
        })
    }
}

val generateGradleDependencyInventory by tasks.registering {
    group = "distribution"
    description = "Writes a JSON inventory of Gradle runtime dependencies used by release artifacts."
    dependsOn(cleanReleaseDistribution)
    val inventory = releaseDistributionDir.map { it.file("sshsqlite-gradle-runtime-dependencies.json") }
    inputs.files(project(":driver").configurations.named("runtimeClasspath"))
    outputs.file(inventory)
    doLast {
        val file = inventory.get().asFile
        Files.createDirectories(file.parentFile.toPath())
        val dependencies = project(":driver").configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedWith(compareBy({ it.moduleVersion.id.group }, { it.name }, { it.moduleVersion.id.version }))
        file.writeText(buildString {
            appendLine("{")
            appendLine("  \"schema\": \"sshsqlite-gradle-runtime-dependencies-v1\",")
            appendLine("  \"project\": \"sshsqlite\",")
            appendLine("  \"version\": \"${project.version}\",")
            appendLine("  \"dependencies\": [")
            dependencies.forEachIndexed { index, artifact ->
                val id = artifact.moduleVersion.id
                val suffix = if (index == dependencies.lastIndex) "" else ","
                appendLine("    { \"group\": \"${id.group}\", \"name\": \"${artifact.name}\", \"version\": \"${id.version}\", \"type\": \"${artifact.type}\" }$suffix")
            }
            appendLine("  ]")
            appendLine("}")
        })
    }
}

val generateGoModuleInventory by tasks.registering {
    group = "distribution"
    description = "Writes a JSON inventory of Go modules used by the helper."
    val inventory = releaseDistributionDir.map { it.file("sshsqlite-go-modules.json") }
    inputs.files(layout.projectDirectory.file("helper/go.mod"), layout.projectDirectory.file("helper/go.sum"))
    outputs.file(inventory)
    doLast {
        val file = inventory.get().asFile
        Files.createDirectories(file.parentFile.toPath())
        val process = ProcessBuilder("go", "list", "-m", "-json", "all")
            .directory(layout.projectDirectory.dir("helper").asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "go list -m -json all failed:\n$output" }
        file.writeText(buildString {
            appendLine("{")
            appendLine("  \"schema\": \"sshsqlite-go-modules-v1\",")
            appendLine("  \"project\": \"sshsqlite-helper\",")
            appendLine("  \"modules\": [")
            val modules = Regex("\\{[\\s\\S]*?\\n}").findAll(output).map { it.value.prependIndent("    ") }.toList()
            modules.forEachIndexed { index, moduleJson ->
                val suffix = if (index == modules.lastIndex) "" else ","
                append(moduleJson)
                appendLine(suffix)
            }
            appendLine("  ]")
            appendLine("}")
        })
    }
}

val generateReleaseHashManifest by tasks.registering {
    group = "distribution"
    description = "Generates SHA-256 hashes for release distribution outputs."
    dependsOn(packageDriverDistribution, generateReleaseMetadata, generateGradleDependencyInventory)
    val manifest = releaseDistributionDir.map { it.file("sshsqlite_SHA256SUMS") }
    outputs.file(manifest)
    outputs.upToDateWhen { false }
    doLast {
        val distDir = releaseDistributionDir.get().asFile
        val artifacts = distributionFiles(distDir)
        require(artifacts.isNotEmpty()) { "No release artifacts found in ${distDir.path}" }
        manifest.get().asFile.writeText(artifacts.joinToString(System.lineSeparator(), postfix = System.lineSeparator()) { artifact ->
            "${sha256(artifact)}  ${artifact.releaseRelativePath(distDir)}"
        })
    }
}

val generateReleaseMetadata by tasks.registering {
    group = "distribution"
    description = "Writes release candidate version and compatibility metadata."
    dependsOn(cleanReleaseDistribution)
    val metadata = releaseDistributionDir.map { it.file("sshsqlite-release-metadata.json") }
    outputs.file(metadata)
    doLast {
        val file = metadata.get().asFile
        Files.createDirectories(file.parentFile.toPath())
        file.writeText(
            """
            {
              "driverVersion": "${project.version}",
              "protocolVersions": { "min": 1, "max": 1 },
              "driverJavaRuntime": "11+",
              "backend": "sqlite3-cli-over-ssh",
              "sqlite3PathDefault": "/usr/bin/sqlite3",
              "sqlite3TrustModel": "trusted remote server binary managed by the operator or OS package manager",
              "supportedServerTargets": [
                { "os": "linux", "arch": "amd64", "requirement": "OpenSSH server with trusted sqlite3 CLI" },
                { "os": "linux", "arch": "arm64", "requirement": "OpenSSH server with trusted sqlite3 CLI" }
              ]
            }
            """.trimIndent() + System.lineSeparator()
        )
    }
}

tasks.register("packageRelease") {
    group = "distribution"
    description = "Packages release candidate driver artifacts under build/distributions."
    dependsOn(packageDriverDistribution, generateReleaseMetadata, generateGradleDependencyInventory, generateReleaseHashManifest, "verifyPackagedReleaseDistribution")
    dependsOn("verifyDriverDistributionJar")
}

tasks.register("verifyPackagedReleaseDistribution") {
    group = "verification"
    description = "Asserts packaged release files and SHA-256 manifest are complete and consistent."
    dependsOn(generateReleaseHashManifest)
    doLast {
        verifyReleaseDistributionContents(releaseDistributionDir.get().asFile, project.version.toString())
    }
}

tasks.register("buildReleaseArtifacts") {
    group = "distribution"
    description = "Builds mutable release candidate artifacts; verifyRelease consumes these after they exist."
    dependsOn("packageRelease")
}

tasks.register("verifyDriverDistributionJar") {
    group = "verification"
    description = "Asserts the JDBC distribution jar contains service-loader and driver classes."
    dependsOn(packageDriverDistribution)
    doLast {
        val jars = releaseDistributionDir.get().asFile.listFiles { file -> file.name.matches(Regex("sshsqlite-driver-.*-all\\.jar")) }?.toList().orEmpty()
        require(jars.size == 1) { "Expected exactly one sshsqlite-driver all jar in ${releaseDistributionDir.get().asFile.path}, found ${jars.size}" }
        val jar = jars.single()
        require(jar.length() > 0) { "${jar.name} is empty" }
        ZipFile(jar).use { zip ->
            require(zip.getEntry("META-INF/services/java.sql.Driver") != null) { "Missing META-INF/services/java.sql.Driver in ${jar.name}" }
            require(zip.getEntry("org/sshsqlite/jdbc/SshSqliteDriver.class") != null) { "Missing SshSqliteDriver.class in ${jar.name}" }
            require(zip.getEntry("org/sshsqlite/jdbc/SshSqliteConnection.class") != null) { "Missing SshSqliteConnection.class in ${jar.name}" }
            val entries = sequence {
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) yield(enumeration.nextElement().name)
            }.toList()
            val signatureFiles = entries.filter { name ->
                name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC"))
            }
            require(signatureFiles.isEmpty()) { "Driver jar contains dependency signature files: ${signatureFiles.joinToString()}" }
            val excludedNativePrefixes = listOf(
                "org/apache/sshd/agent/unix/",
                "org/apache/sshd/common/io/apr/",
                "org/apache/tomcat/jni/",
                "io/netty/"
            )
            val nativeEntries = entries.filter { name -> excludedNativePrefixes.any { prefix -> name.startsWith(prefix) } }
            require(nativeEntries.isEmpty()) { "Driver jar contains excluded native/APR packages: ${nativeEntries.take(10).joinToString()}" }
        }
    }
}

tasks.register("verifyHelperDistribution") {
    group = "verification"
    description = "Asserts helper distribution binary and hash manifest exist and are non-empty."
    dependsOn(generateHelperHashManifest, generateReleaseMetadata)
    doLast {
        val hostTarget = "${currentGoOs()}-${currentGoArch()}"
        val helper = releaseDistributionDir.get().asFile.resolve("sshsqlite-helper-$hostTarget")
        val manifest = releaseDistributionDir.get().asFile.resolve("sshsqlite-helper_SHA256SUMS")
        val metadata = releaseDistributionDir.get().asFile.resolve("sshsqlite-release-metadata.json")
        require(helper.isFile && helper.length() > 0) { "Missing or empty helper binary: ${helper.path}" }
        require(manifest.isFile && manifest.length() > 0) { "Missing or empty helper hash manifest: ${manifest.path}" }
        require(metadata.isFile && metadata.length() > 0) { "Missing or empty release metadata: ${metadata.path}" }
        require(manifest.readText().contains(helper.name)) { "Helper hash manifest does not mention ${helper.name}" }
    }
}

val verifyReleaseSecurityChecks by tasks.registering {
    group = "verification"
    description = "Checks release security verification fails closed and allows explicit unsigned development mode."
    dependsOn("packageRelease")
    doLast {
        val sourceDistDir = releaseDistributionDir.get().asFile
        val distDir = layout.buildDirectory.dir("release-security-selftest/unsigned-distributions").get().asFile
        if (distDir.exists()) {
            distDir.deleteRecursively()
        }
        sourceDistDir.copyRecursively(distDir, overwrite = true)
        distDir.listFiles { file -> file.name.endsWith(".sig") || file.name.endsWith(".asc") || file.name.endsWith(".bundle") || file.name == "sshsqlite-provenance.json" }
            ?.forEach { it.delete() }

        verifyReleaseSecurity(distDir, allowUnsignedRelease = true, projectVersion = project.version.toString())

        var failedClosed = false
        try {
            verifyReleaseSecurity(distDir, allowUnsignedRelease = false, projectVersion = project.version.toString())
        } catch (expected: IllegalArgumentException) {
            require(expected.message?.contains("signed") == true) { "Production failure should be about missing signed release evidence: ${expected.message}" }
            failedClosed = true
        }
        require(failedClosed) { "Production release verification unexpectedly passed without signed manifest/provenance evidence." }
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Runs fast local verification for normal development."
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("check", "checkJdbcMethodSupport", "verifyDriverDistributionJar", verifyReleaseSecurityChecks)
}

tasks.register("verifyIntegration") {
    group = "verification"
    description = "Runs SSH/sqlite3 CLI/JDBC integration tests."
    dependsOn(":integration-tests:test")
}

tasks.register("verifyWriteReleaseBackupRestore") {
    group = "verification"
    description = "Rehearses write-capable release backup/restore against disposable WAL databases only."
    dependsOn(":integration-tests:writeReleaseTest")
    doLast {
        verifyWriteReleaseBackupEvidence(writeReleaseEvidenceFile.get().asFile)
    }
}

tasks.register("verifyRelease") {
    group = "verification"
    description = "Validates existing release artifacts, manifests, inventories, and production signing evidence without building mutable artifacts. Add -PverifyWriteRelease=true for write-capable backup/restore gates."
    dependsOn("checkJdbcMethodSupport")
    val verifyWriteRelease = providers.gradleProperty("verifyWriteRelease").map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
    if (verifyWriteRelease) {
        dependsOn("verifyWriteReleaseBackupRestore")
    }
    doLast {
        val distDir = releaseDistributionDir.get().asFile
        val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease").map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
        val allowMissingToolEvidence = providers.gradleProperty("allowMissingToolEvidence").map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
        verifyReleaseSecurity(distDir, allowUnsignedRelease, project.version.toString())
        validateToolEvidence(layout.projectDirectory.dir("tool-evidence").asFile, allowMissingToolEvidence)
        if (verifyWriteRelease) {
            verifyWriteReleaseBackupEvidence(writeReleaseEvidenceFile.get().asFile)
        }

        listOf(
            "cosignVerifyManifestCommand",
            "gpgVerifyManifestCommand",
            "cosignVerifyProvenanceCommand",
            "gpgVerifyProvenanceCommand",
        ).forEach { propertyName ->
            providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }?.let { command ->
                runConfiguredHook(propertyName, command, distDir)
            }
        }
    }
}

tasks.register("generateToolEvidenceTemplate") {
    group = "help"
    description = "Writes a guided DBeaver read-only evidence checklist and JSON templates."
    doLast {
        val outputDir = layout.buildDirectory.dir("reports/tool-evidence-template").get().asFile
        Files.createDirectories(outputDir.toPath())
        outputDir.resolve("README.md").writeText(
            buildString {
                appendLine("# SSHSQLite Tool Evidence Template")
                appendLine()
                appendLine("Copy this structure to `tool-evidence/` for DBeaver release validation. Do not include secrets; logs must be redacted.")
                appendLine()
                appendLine("Required workflow IDs, in order:")
                toolEvidenceWorkflowIds.forEach { appendLine("- `$it`") }
                appendLine()
                appendLine("Run `./gradlew verifyTools` after replacing placeholder hashes, versions, builds, and artifact references with captured evidence.")
            }
        )
        val workflowObjects = toolEvidenceWorkflowIds.map { mapOf("id" to it) }
        val template = mapOf(
            "schema" to toolEvidenceSchema,
            "releaseScope" to "production-readonly-mvp",
            "workflows" to workflowObjects,
            "tools" to toolEvidenceNames.sorted().map { name ->
                mapOf(
                    "name" to name,
                    "version" to "replace-with-pinned-version",
                    "build" to "replace-with-pinned-build",
                    "driverArtifactHash" to "replace-with-64-char-sha256",
                    "remoteSqlite3Version" to "replace-with-sqlite3-version",
                    "disposableFixtureHash" to "replace-with-64-char-sha256",
                    "redactedLogs" to listOf("${name.lowercase()}/redacted.log"),
                    "screenshotsOrDiagnostics" to listOf("${name.lowercase()}/diagnostics.txt"),
                    "passFailJson" to "${name.lowercase()}/pass-fail.json",
                    "unexpectedJdbcMethodTrace" to "${name.lowercase()}/unexpected-jdbc-methods.json",
                    "workflows" to workflowObjects,
                )
            },
        )
        outputDir.resolve("workflow.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(template)) + System.lineSeparator())
        outputDir.resolve("pass-fail.template.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("status" to "pass", "workflows" to toolEvidenceWorkflowIds.map { mapOf("id" to it, "status" to "pass") }))) + System.lineSeparator())
        outputDir.resolve("unexpected-jdbc-methods.template.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("unexpectedMethods" to emptyList<String>()))) + System.lineSeparator())
    }
}

tasks.register("verifySoak") {
    group = "verification"
    description = "Runs soak verification and writes build/reports/soak/verifySoak.json. Use -PsoakProfile=full for production criteria."
    dependsOn(":integration-tests:soakTest")
}

tasks.register("verifyToolEvidenceValidatorFixtures") {
    group = "verification"
    description = "Runs minimal valid and invalid fixture checks for the tool evidence validator."
    doLast {
        val fixtureRoot = layout.buildDirectory.dir("tool-evidence-validator-fixtures").get().asFile
        if (fixtureRoot.exists()) {
            fixtureRoot.deleteRecursively()
        }
        val valid = fixtureRoot.resolve("valid")
        fun writeEvidenceFile(relative: String, text: String = "redacted fixture evidence\n") {
            val file = valid.resolve(relative)
            Files.createDirectories(file.parentFile.toPath())
            file.writeText(text)
        }
        fun passFailJson() = JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("status" to "pass", "workflows" to toolEvidenceWorkflowIds.map { mapOf("id" to it, "status" to "pass") })))
        fun traceJson() = JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("unexpectedMethods" to emptyList<String>())))
        listOf("dbeaver").forEach { toolDir ->
            writeEvidenceFile("$toolDir/redacted.log")
            writeEvidenceFile("$toolDir/diagnostics.txt")
            writeEvidenceFile("$toolDir/pass-fail.json", passFailJson())
            writeEvidenceFile("$toolDir/unexpected-jdbc-methods.json", traceJson())
        }
        val workflowObjects = toolEvidenceWorkflowIds.map { mapOf("id" to it) }
        val validBundle = mapOf(
            "schema" to toolEvidenceSchema,
            "releaseScope" to "production-readonly-mvp",
            "workflows" to workflowObjects,
            "tools" to listOf(
                "DBeaver" to "dbeaver",
            ).map { (toolName, dirName) ->
                mapOf(
                    "name" to toolName,
                    "version" to "2026.1",
                    "build" to "fixture-build",
                    "driverArtifactHash" to "a".repeat(64),
                    "remoteSqlite3Version" to "3.45.1 fixture",
                    "disposableFixtureHash" to "c".repeat(64),
                    "redactedLogs" to listOf("$dirName/redacted.log"),
                    "screenshotsOrDiagnostics" to listOf("$dirName/diagnostics.txt"),
                    "passFailJson" to "$dirName/pass-fail.json",
                    "unexpectedJdbcMethodTrace" to "$dirName/unexpected-jdbc-methods.json",
                    "workflows" to workflowObjects,
                )
            },
        )
        valid.resolve("workflow.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(validBundle)) + System.lineSeparator())
        validateToolEvidence(valid, allowMissingToolEvidence = false)

        val invalid = fixtureRoot.resolve("invalid")
        valid.copyRecursively(invalid, overwrite = true)
        invalid.resolve("dbeaver/pass-fail.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("status" to "pass", "workflows" to toolEvidenceWorkflowIds.dropLast(1).map { mapOf("id" to it, "status" to "pass") }))) + System.lineSeparator())
        var failed = false
        try {
            validateToolEvidence(invalid, allowMissingToolEvidence = false)
        } catch (expected: IllegalStateException) {
            require(expected.message?.contains("workflows") == true) { "Invalid fixture failed for the wrong reason: ${expected.message}" }
            failed = true
        } catch (expected: IllegalArgumentException) {
            require(expected.message?.contains("workflows") == true) { "Invalid fixture failed for the wrong reason: ${expected.message}" }
            failed = true
        }
        require(failed) { "Invalid tool evidence fixture unexpectedly passed" }
    }
}

tasks.register("verifyTools") {
    group = "verification"
    description = "Validates DBeaver read-only GUI tool evidence. Use -PallowMissingToolEvidence=true only for development/preview."
    dependsOn("verifyToolEvidenceValidatorFixtures")
    doLast {
        val allowMissingToolEvidence = providers.gradleProperty("allowMissingToolEvidence").map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
        validateToolEvidence(layout.projectDirectory.dir("tool-evidence").asFile, allowMissingToolEvidence)
    }
}
