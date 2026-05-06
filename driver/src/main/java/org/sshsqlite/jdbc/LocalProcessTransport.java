package org.sshsqlite.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class LocalProcessTransport implements Transport {
    private final Process process;
    private final SqlClient protocol;
    private final BoundedCapture stderr;
    private final ExecutorService executor;

    private LocalProcessTransport(Process process, SqlClient protocol, BoundedCapture stderr, ExecutorService executor) {
        this.process = process;
        this.protocol = protocol;
        this.stderr = stderr;
        this.executor = executor;
    }

    static LocalProcessTransport start(List<String> command, ConnectionConfig config) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "sshsqlite-local-transport");
            thread.setDaemon(true);
            return thread;
        });
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        executor.submit(stderr);
        ProtocolClient protocol = new ProtocolClient(process.getInputStream(), process.getOutputStream(), config.maxFrameBytes);
        LocalProcessTransport transport = new LocalProcessTransport(process, protocol, stderr, executor);
        try {
            transport.withTimeout(config.startupTimeout(), () -> {
                protocol.hello();
                protocol.open(config);
                return null;
            });
        } catch (Exception e) {
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            transport.close();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Helper startup failed: " + stderr.text(), e);
        }
        return transport;
    }

    static LocalProcessTransport start(ConnectionConfig config) throws IOException {
        if (config.helperLocalPath == null || config.helperLocalPath.isBlank()) {
            return startSqlite3(config);
        }
        List<String> command = new java.util.ArrayList<>();
        command.add(config.helperLocalPath);
        command.add("--stdio");
        if (config.helperLocalAllowlist != null && !config.helperLocalAllowlist.isBlank()) {
            command.add("--allowlist");
            command.add(config.helperLocalAllowlist);
        }
        return start(command, config);
    }

    private static LocalProcessTransport startSqlite3(ConnectionConfig config) throws IOException {
        RemoteCommand.validateSqlite3Path(config.sqlite3Path);
        List<String> command = new java.util.ArrayList<>();
        command.add(config.sqlite3Path);
        command.add("-batch");
        if (config.readonly) {
            command.add("-readonly");
        }
        command.add(config.dbPath);
        Process process = new ProcessBuilder(command).start();
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "sshsqlite-local-sqlite3-transport");
            thread.setDaemon(true);
            return thread;
        });
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        executor.submit(stderr);
        try {
            CliProtocolClient protocol = new CliProtocolClient(process.getInputStream(), process.getOutputStream(), config, stderr, () -> {
                process.destroyForcibly();
                executor.shutdownNow();
            });
            return new LocalProcessTransport(process, protocol, stderr, executor);
        } catch (Exception e) {
            process.destroyForcibly();
            executor.shutdownNow();
            throw new IOException("sqlite3 CLI startup failed: " + stderr.text(), e);
        }
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
        process.getOutputStream().close();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while closing helper", e);
        } finally {
            executor.shutdownNow();
            protocol.closeReader();
        }
    }

    private <T> T withTimeout(Duration timeout, Callable<T> callable) throws Exception {
        Future<T> future = executor.submit(callable);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out waiting for helper startup", e);
        }
    }
}
