package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("soak")
class ReadOnlySoakTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private long peakHeapBytes;
    private long peakOpenFds;
    private long peakSqlite3RssBytes;
    private LargeResultMetrics largeResultMetrics;

    @Test
    void readOnlyReliabilityAndLeakSoak() throws Exception {
        SoakProfile profile = SoakProfile.from(System.getProperty("sshsqlite.soakProfile", "short"));
        Path report = Path.of(System.getProperty("sshsqlite.soakReport", "build/reports/soak/verifySoak.json"));
        Files.createDirectories(report.getParent());

        System.gc();
        sleepQuietly(Duration.ofMillis(100));
        long baselineHeap = usedHeapBytes();
        long baselineFds = openFileDescriptorCount();
        peakHeapBytes = baselineHeap;
        peakOpenFds = baselineFds;
        peakSqlite3RssBytes = sqlite3RssBytes();

        Path db = createDatabase();
        ObjectNode root = JSON.createObjectNode();
        ArrayNode scenarios = root.putArray("scenarios");
        Instant started = Instant.now();
        boolean pass = true;

        try {
            pass &= runScenario(scenarios, "repeated-connect-query-close", () -> repeatedConnectQueryClose(db, profile.sequentialCycles));
            pass &= runScenario(scenarios, "concurrent-non-pooled-read-only", () -> concurrentNonPooled(db, profile.concurrentConnections, profile.concurrentDuration));
            pass &= runScenario(scenarios, "idle-ping-validation", () -> idlePingValidation(db, profile.idleDuration));
            pass &= runScenario(scenarios, "bounded-large-result", () -> boundedLargeResult(db, profile));
            pass &= runScenario(scenarios, "sqlite3-crash-eof-behavior", () -> sqlite3CrashEofBehavior(db));
            pass &= runScenario(scenarios, "malformed-cli-output-failure", () -> malformedCliOutputFailure(db));
        } finally {
            System.gc();
            sleepQuietly(Duration.ofMillis(200));
            long finalHeap = usedHeapBytes();
            long finalFds = openFileDescriptorCount();
            long finalSqlite3Rss = sqlite3RssBytes();
            long finalSqlite3 = sqlite3ProcessCount();
            pass &= finalSqlite3 == 0;
            pass &= withinGrowthCeiling(baselineFds, finalFds, 0.10d, 3);
            pass &= withinGrowthCeiling(baselineHeap, finalHeap, 0.20d, 4 * 1024 * 1024L);
            pass &= withinGrowthCeiling(0, finalSqlite3Rss, 0.20d, 0);

            root.put("schema", "sshsqlite-verify-soak-v1");
            root.put("commandVersion", System.getProperty("sshsqlite.projectVersion", "unknown"));
            root.put("gitRevision", commandOutput(List.of("git", "rev-parse", "--short", "HEAD"), Path.of(".")));
            root.put("profile", profile.name);
            root.put("pass", pass);
            root.put("durationMs", Duration.between(started, Instant.now()).toMillis());
            root.put("os", System.getProperty("os.name"));
            root.put("arch", System.getProperty("os.arch"));
            root.put("javaVersion", System.getProperty("java.version"));
            root.put("sqliteVersion", cliSqliteVersion(db));
            ObjectNode memory = root.putObject("memory");
            memory.put("baselineHeapBytes", baselineHeap);
            memory.put("peakHeapBytes", peakHeapBytes);
            memory.put("finalHeapBytes", finalHeap);
            memory.put("sqlite3RssPeakBytes", peakSqlite3RssBytes);
            memory.put("sqlite3RssFinalBytes", finalSqlite3Rss);
            memory.put("sqlite3RssLimitations", "RSS is sampled from direct child sqlite3 processes visible through ProcessHandle and /proc; short-lived processes and non-Linux platforms may report 0.");
            ObjectNode fds = root.putObject("fds");
            fds.put("baseline", baselineFds);
            fds.put("peak", peakOpenFds);
            fds.put("final", finalFds);
            root.put("sqlite3ProcessCount", finalSqlite3);
            root.put("sshChannelCount", 0);
            ArrayNode diagnostics = root.putArray("diagnostics");
            if (finalSqlite3 != 0) {
                diagnostics.add("sqlite3 process leak detected: " + finalSqlite3);
            }
            if (!withinGrowthCeiling(baselineFds, finalFds, 0.10d, 3)) {
                diagnostics.add("open file descriptors exceeded 10% growth ceiling");
            }
            if (!withinGrowthCeiling(baselineHeap, finalHeap, 0.20d, 4 * 1024 * 1024L)) {
                diagnostics.add("JVM heap exceeded 20% growth ceiling after forced GC");
            }
            if (!withinGrowthCeiling(0, finalSqlite3Rss, 0.20d, 0)) {
                diagnostics.add("sqlite3 RSS remained non-zero after teardown");
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), root);
        }

        if (!pass) {
            fail("verifySoak failed; see " + report);
        }
    }

    private boolean runScenario(ArrayNode scenarios, String name, Callable<Void> action) {
        ObjectNode scenario = scenarios.addObject();
        Instant started = Instant.now();
        scenario.put("name", name);
        try {
            action.call();
            assertEquals(0, sqlite3ProcessCount(), "sqlite3 processes after " + name);
            scenario.put("pass", true);
            return true;
        } catch (Throwable t) {
            scenario.put("pass", false);
            scenario.put("diagnostics", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        } finally {
            scenario.put("durationMs", Duration.between(started, Instant.now()).toMillis());
            scenario.put("sqlite3ProcessCount", sqlite3ProcessCount());
            scenario.put("openFileDescriptors", openFileDescriptorCount());
            if ("bounded-large-result".equals(name) && largeResultMetrics != null) {
                ObjectNode details = scenario.putObject("largeResult");
                details.put("rows", largeResultMetrics.rows);
                details.put("payloadBytes", largeResultMetrics.payloadBytes);
                details.put("fetchSize", largeResultMetrics.fetchSize);
                details.put("estimatedDatasetBytes", largeResultMetrics.estimatedDatasetBytes);
                details.put("baselineHeapBytes", largeResultMetrics.baselineHeapBytes);
                details.put("peakHeapBytes", largeResultMetrics.peakHeapBytes);
                details.put("finalHeapBytes", largeResultMetrics.finalHeapBytes);
                details.put("heapGrowthRatioLimit", largeResultMetrics.heapGrowthRatioLimit);
                details.put("baselineSqlite3RssBytes", largeResultMetrics.baselineSqlite3RssBytes);
                details.put("peakSqlite3RssBytes", largeResultMetrics.peakSqlite3RssBytes);
                details.put("finalSqlite3RssBytes", largeResultMetrics.finalSqlite3RssBytes);
                details.put("streamingInference", "Current sqlite3 JSON CLI mode materializes statement output before JDBC fetch batches; this scenario is a bounded large-result smoke, not proof of true streaming.");
            }
            sampleResources();
        }
    }

    private Void repeatedConnectQueryClose(Path db, int cycles) throws Exception {
        for (int i = 0; i < cycles; i++) {
            try (Connection connection = DriverManager.getConnection(url(db), properties());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM typed")) {
                assertTrue(resultSet.next());
                assertEquals(5, resultSet.getInt(1));
            }
            if (i % 10 == 0) {
                sampleResources();
            }
        }
        return null;
    }

    private Void concurrentNonPooled(Path db, int connections, Duration duration) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(connections);
        CountDownLatch ready = new CountDownLatch(connections);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        Instant stopAt = Instant.now().plus(duration);
        for (int i = 0; i < connections; i++) {
            futures.add(executor.submit(() -> {
                try (Connection connection = DriverManager.getConnection(url(db), properties());
                     Statement statement = connection.createStatement()) {
                    ready.countDown();
                    start.await();
                    while (Instant.now().isBefore(stopAt)) {
                        try (ResultSet resultSet = statement.executeQuery("SELECT id, text_value FROM typed ORDER BY id LIMIT 3")) {
                            int rows = 0;
                            while (resultSet.next()) {
                                rows++;
                            }
                            assertEquals(3, rows);
                        }
                        sampleResources();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(profileWaitMillis(duration), java.util.concurrent.TimeUnit.MILLISECONDS));
        start.countDown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdownNow();
        return null;
    }

    private Void idlePingValidation(Path db, Duration idleDuration) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(db), properties())) {
            assertTrue(connection.isValid(1));
            sleepQuietly(idleDuration);
            assertTrue(connection.isValid(1));
        }
        return null;
    }

    private Void boundedLargeResult(Path db, SoakProfile profile) throws Exception {
        int rows = intProperty("sshsqlite.largeResultRows", profile.largeResultRows);
        int payloadBytes = intProperty("sshsqlite.largeResultPayloadBytes", profile.largeResultPayloadBytes);
        int fetchSize = intProperty("sshsqlite.largeResultFetchSize", profile.largeResultFetchSize);
        double heapGrowthRatioLimit = doubleProperty("sshsqlite.largeResultHeapGrowthRatio", 0.20d);
        createLargeResultTable(db, rows, payloadBytes);

        System.gc();
        sleepQuietly(Duration.ofMillis(200));
        long baselineHeap = 0;
        long baselineRss = 0;
        long scenarioPeakHeap = 0;
        long scenarioPeakRss = 0;
        long checksum = 0;
        int seen = 0;
        int warmupRows = Math.min(rows, Math.max(fetchSize * 5, 1_000));

        Properties props = properties();
        props.setProperty("fetchSize", Integer.toString(fetchSize));
        try (Connection connection = DriverManager.getConnection(url(db), props);
             Statement statement = connection.createStatement()) {
            statement.setFetchSize(fetchSize);
            try (ResultSet resultSet = statement.executeQuery("SELECT id, payload FROM large_result ORDER BY id")) {
                while (resultSet.next()) {
                    seen++;
                    checksum += resultSet.getInt(1);
                    checksum += resultSet.getString(2).length();
                    if (seen == warmupRows) {
                        System.gc();
                        sleepQuietly(Duration.ofMillis(100));
                        baselineHeap = usedHeapBytes();
                        baselineRss = sqlite3RssBytes();
                        scenarioPeakHeap = baselineHeap;
                        scenarioPeakRss = baselineRss;
                    }
                    if (seen % Math.max(fetchSize, 1) == 0) {
                        scenarioPeakHeap = Math.max(scenarioPeakHeap, usedHeapBytes());
                        scenarioPeakRss = Math.max(scenarioPeakRss, sqlite3RssBytes());
                        sampleResources();
                    }
                }
                assertEquals(rows, seen);
                assertTrue(checksum > rows, "large-result checksum should prove rows were materialized");
            }

            try (ResultSet early = statement.executeQuery("SELECT id, payload FROM large_result ORDER BY id")) {
                assertTrue(early.next());
                early.close();
            }
            try (ResultSet followup = statement.executeQuery("SELECT count(*) FROM typed")) {
                assertTrue(followup.next());
                assertEquals(5, followup.getInt(1));
            }
        }

        System.gc();
        sleepQuietly(Duration.ofMillis(300));
        long finalHeap = usedHeapBytes();
        long finalRss = sqlite3RssBytes();
        if (baselineHeap == 0) {
            baselineHeap = finalHeap;
        }
        scenarioPeakHeap = Math.max(scenarioPeakHeap, finalHeap);
        scenarioPeakRss = Math.max(scenarioPeakRss, finalRss);
        long estimatedDatasetBytes = (long) rows * payloadBytes;

        largeResultMetrics = new LargeResultMetrics(rows, payloadBytes, fetchSize, estimatedDatasetBytes,
                baselineHeap, scenarioPeakHeap, finalHeap, heapGrowthRatioLimit, baselineRss, scenarioPeakRss, finalRss);
        assertTrue(rows > fetchSize, "large result must require multiple JDBC fetch batches");
        assertTrue(withinGrowthCeiling(baselineHeap, finalHeap, heapGrowthRatioLimit, 4 * 1024 * 1024L),
                "JVM heap after bounded large-result read exceeded documented growth ceiling after GC");
        assertTrue(withinGrowthCeiling(baselineRss, finalRss, 0.20d, 4 * 1024 * 1024L),
                "sqlite3 RSS after bounded large-result read exceeded documented growth ceiling");
        return null;
    }

    private Void sqlite3CrashEofBehavior(Path db) throws Exception {
        Path script = tempDir.resolve("crash-sqlite3.sh");
        Files.writeString(script, "#!/bin/sh\nexit 12\n");
        assertTrue(script.toFile().setExecutable(true));
        Properties props = properties();
        props.setProperty("sqlite3.path", script.toString());
        assertThrowsSql(() -> DriverManager.getConnection(url(db), props).close());
        return null;
    }

    private Void malformedCliOutputFailure(Path db) throws Exception {
        Path script = tempDir.resolve("malformed-sqlite3.sh");
        Files.writeString(script, "#!/bin/sh\nprintf 'not-json\\n'\n");
        assertTrue(script.toFile().setExecutable(true));
        Properties props = properties();
        props.setProperty("sqlite3.path", script.toString());
        assertThrowsSql(() -> DriverManager.getConnection(url(db), props).close());
        return null;
    }

    private String cliSqliteVersion(Path db) {
        try (Connection connection = DriverManager.getConnection(url(db), properties())) {
            return ((SshSqliteConnection) connection).sqliteVersion();
        } catch (Exception e) {
            return "unknown: " + e.getClass().getSimpleName();
        }
    }

    private Path createDatabase() throws Exception {
        Path db = tempDir.resolve("fixture.db");
        Process process = new ProcessBuilder("sqlite3", db.toString(), "CREATE TABLE typed(id INTEGER PRIMARY KEY, text_value TEXT);" +
                "INSERT INTO typed VALUES (1, 'one'), (2, 'two'), (3, 'three'), (4, 'four'), (5, 'five');").start();
        assertEquals(0, process.waitFor());
        return db;
    }

    private void createLargeResultTable(Path db, int rows, int payloadBytes) throws Exception {
        String sql = "DROP TABLE IF EXISTS large_result;" +
                "CREATE TABLE large_result(id INTEGER PRIMARY KEY, payload TEXT NOT NULL);" +
                "WITH RECURSIVE seq(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM seq WHERE x < " + rows + ") " +
                "INSERT INTO large_result(id, payload) SELECT x, printf('%0" + payloadBytes + "d', x) FROM seq;";
        Process process = new ProcessBuilder("sqlite3", db.toString(), sql).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private String url(Path db) {
        return "jdbc:sshsqlite://local" + db;
    }

    private Properties properties() {
        Properties props = new Properties();
        props.setProperty("helper.transport", "local");
        props.setProperty("sqlite3.path", "sqlite3");
        props.setProperty("sqlite3.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        props.setProperty("fetchSize", "50");
        return props;
    }

    private void sampleResources() {
        peakHeapBytes = Math.max(peakHeapBytes, usedHeapBytes());
        peakOpenFds = Math.max(peakOpenFds, openFileDescriptorCount());
        peakSqlite3RssBytes = Math.max(peakSqlite3RssBytes, sqlite3RssBytes());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long openFileDescriptorCount() {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return -1;
        }
        try (var stream = Files.list(Path.of("/proc/self/fd"))) {
            return stream.count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long sqlite3ProcessCount() {
        return ProcessHandle.current().descendants()
                .filter(ReadOnlySoakTest::isSqlite3Process)
                .count();
    }

    private static long sqlite3RssBytes() {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return 0;
        }
        return ProcessHandle.current().descendants()
                .filter(ReadOnlySoakTest::isSqlite3Process)
                .mapToLong(handle -> processRssBytes(handle.pid()))
                .sum();
    }

    private static boolean isSqlite3Process(ProcessHandle handle) {
        String command = handle.info().command().orElse("");
        return command.equals("sqlite3") || command.endsWith("/sqlite3");
    }

    private static long processRssBytes(long pid) {
        try {
            String statm = Files.readString(Path.of("/proc", Long.toString(pid), "statm"));
            String[] fields = statm.trim().split("\\s+");
            if (fields.length < 2) return 0;
            return Long.parseLong(fields[1]) * 4096L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean withinGrowthCeiling(long baseline, long actual, double ratio, long absoluteAllowance) {
        if (baseline < 0 || actual < 0) {
            return true;
        }
        long allowedGrowth = Math.max((long) Math.ceil(baseline * ratio), absoluteAllowance);
        return actual <= baseline + allowedGrowth;
    }

    private static void assertThrowsSql(SqlRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("expected SQLException");
        } catch (SQLException expected) {
            // expected
        }
    }

    private static long profileWaitMillis(Duration duration) {
        return Math.max(10_000L, duration.toMillis() + 10_000L);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String commandOutput(List<String> command, Path workingDir) {
        try {
            Process process = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 ? output : "unavailable";
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private static double doubleProperty(String name, double defaultValue) {
        return Double.parseDouble(System.getProperty(name, Double.toString(defaultValue)));
    }

    private static final class SoakProfile {
        final String name;
        final int sequentialCycles;
        final int concurrentConnections;
        final Duration concurrentDuration;
        final Duration idleDuration;
        final int largeResultRows;
        final int largeResultPayloadBytes;
        final int largeResultFetchSize;

        private SoakProfile(String name, int sequentialCycles, int concurrentConnections, Duration concurrentDuration, Duration idleDuration,
                int largeResultRows, int largeResultPayloadBytes, int largeResultFetchSize) {
            this.name = name;
            this.sequentialCycles = sequentialCycles;
            this.concurrentConnections = concurrentConnections;
            this.concurrentDuration = concurrentDuration;
            this.idleDuration = idleDuration;
            this.largeResultRows = largeResultRows;
            this.largeResultPayloadBytes = largeResultPayloadBytes;
            this.largeResultFetchSize = largeResultFetchSize;
        }

        String name() { return name; }
        int sequentialCycles() { return sequentialCycles; }
        int concurrentConnections() { return concurrentConnections; }
        Duration concurrentDuration() { return concurrentDuration; }
        Duration idleDuration() { return idleDuration; }
        int largeResultRows() { return largeResultRows; }
        int largeResultPayloadBytes() { return largeResultPayloadBytes; }
        int largeResultFetchSize() { return largeResultFetchSize; }

        static SoakProfile from(String name) {
            if ("full".equalsIgnoreCase(name)) {
                return new SoakProfile("full", 1000, 5, Duration.ofHours(1), Duration.ofHours(8), 1_000_000, 64, 500);
            }
            return new SoakProfile("short", 25, 3, Duration.ofSeconds(5), Duration.ofSeconds(2), 100_000, 256, 500);
        }
    }

    private static final class LargeResultMetrics {
        final int rows;
        final int payloadBytes;
        final int fetchSize;
        final long estimatedDatasetBytes;
        final long baselineHeapBytes;
        final long peakHeapBytes;
        final long finalHeapBytes;
        final double heapGrowthRatioLimit;
        final long baselineSqlite3RssBytes;
        final long peakSqlite3RssBytes;
        final long finalSqlite3RssBytes;

        private LargeResultMetrics(int rows, int payloadBytes, int fetchSize, long estimatedDatasetBytes,
                long baselineHeapBytes, long peakHeapBytes, long finalHeapBytes, double heapGrowthRatioLimit,
                long baselineSqlite3RssBytes, long peakSqlite3RssBytes, long finalSqlite3RssBytes) {
            this.rows = rows;
            this.payloadBytes = payloadBytes;
            this.fetchSize = fetchSize;
            this.estimatedDatasetBytes = estimatedDatasetBytes;
            this.baselineHeapBytes = baselineHeapBytes;
            this.peakHeapBytes = peakHeapBytes;
            this.finalHeapBytes = finalHeapBytes;
            this.heapGrowthRatioLimit = heapGrowthRatioLimit;
            this.baselineSqlite3RssBytes = baselineSqlite3RssBytes;
            this.peakSqlite3RssBytes = peakSqlite3RssBytes;
            this.finalSqlite3RssBytes = finalSqlite3RssBytes;
        }

        int rows() { return rows; }
        int payloadBytes() { return payloadBytes; }
        int fetchSize() { return fetchSize; }
        long estimatedDatasetBytes() { return estimatedDatasetBytes; }
        long baselineHeapBytes() { return baselineHeapBytes; }
        long peakHeapBytes() { return peakHeapBytes; }
        long finalHeapBytes() { return finalHeapBytes; }
        double heapGrowthRatioLimit() { return heapGrowthRatioLimit; }
        long baselineSqlite3RssBytes() { return baselineSqlite3RssBytes; }
        long peakSqlite3RssBytes() { return peakSqlite3RssBytes; }
        long finalSqlite3RssBytes() { return finalSqlite3RssBytes; }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }
}
