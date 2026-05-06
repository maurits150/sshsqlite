package org.sshsqlite.jdbc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class FixtureHelper {
    private FixtureHelper() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "normal" : args[0];
        if ("fail".equals(mode)) {
            System.err.println("fixture startup failed password=hunter2 db.path=/srv/private/secret.db SELECT * FROM secrets WHERE token = 'abc'");
            System.exit(7);
        }
        if ("mismatch".equals(mode)) {
            FrameCodec codec = new FrameCodec(1024 * 1024);
            codec.readJson(System.in);
            codec.writeJson(System.out, Map.of("id", 1, "ok", true, "op", "helloAck", "protocolVersion", 99));
            return;
        }
        if ("badHelperVersion".equals(mode) || "missingCapability".equals(mode) || "badTarget".equals(mode)) {
            FrameCodec codec = new FrameCodec(1024 * 1024);
            codec.readJson(System.in);
            Map<String, Object> response = helloAck(1);
            if ("badHelperVersion".equals(mode)) {
                response.put("helperVersion", "9.0.0");
            }
            if ("missingCapability".equals(mode)) {
                response.put("compileTimeCapabilities", new String[]{"cgo", "sqliteVersion"});
            }
            if ("badTarget".equals(mode)) {
                response.put("os", "darwin");
                response.put("arch", "amd64");
            }
            codec.writeJson(System.out, response);
            return;
        }
        FrameCodec codec = new FrameCodec(1024 * 1024);
        OutputStream out = System.out;
        while (true) {
            var request = codec.readJson(System.in);
            long id = request.path("id").asLong();
            String op = request.path("op").asText();
            if ("hello".equals(op)) {
                if ("stderrDuringStartup".equals(mode)) {
                    System.err.println("startup diagnostic on stderr only");
                }
                codec.writeJson(out, helloAck(id));
            } else if ("open".equals(op)) {
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "openAck", "readonly", true));
                if ("crashAfterOpen".equals(mode)) {
                    System.exit(9);
                }
            } else if ("ping".equals(op)) {
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "pong"));
            } else if ("query".equals(op)) {
                if ("crashOnQuery".equals(mode)) {
                    System.err.println("crash db.path=/srv/private/secret.db password=hunter2");
                    System.exit(11);
                }
                if ("hangOnQuery".equals(mode)) {
                    Thread.sleep(10_000);
                    continue;
                }
                if ("malformedOnQuery".equals(mode)) {
                    byte[] payload = "{not-json".getBytes(StandardCharsets.UTF_8);
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(payload.length);
                    out.write(payload);
                    out.flush();
                    continue;
                }
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "queryStarted", "cursorId", "fixture-cursor", "columns", new Object[0]));
            } else if ("fetch".equals(op)) {
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "rowBatch", "cursorId", request.path("cursorId").asText(), "rows", new Object[0], "done", true));
            } else if ("closeCursor".equals(op)) {
                if ("closeCursorFailure".equals(mode)) {
                    System.exit(12);
                }
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "closeCursorAck", "cursorId", request.path("cursorId").asText(), "finalized", true));
            } else {
                codec.writeJson(out, Map.of("id", id, "ok", false, "op", "error", "error", Map.of("message", "unsupported SELECT * FROM secret WHERE password = 'pw'")));
            }
        }
    }

    private static Map<String, Object> helloAck(long id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("ok", true);
        response.put("op", "helloAck");
        response.put("protocolVersion", 1);
        response.put("helperVersion", "0.1.0-fixture");
        response.put("sqliteVersion", "3.fixture");
        response.put("os", "linux");
        response.put("arch", "amd64");
        response.put("sqliteBinding", "fixture cgo sqlite binding");
        response.put("sqliteCompileOptions", new String[]{"THREADSAFE=1"});
        response.put("compileTimeCapabilities", new String[]{"cgo", "sqliteVersion", "sqliteCompileOptions", "readonlyAuthorizer"});
        response.put("capabilities", new String[]{"query", "open", "cursorFetch", "readonlyAuthorizer"});
        response.put("limits", Map.of("maxFrameBytes", 1024 * 1024));
        return response;
    }
}
