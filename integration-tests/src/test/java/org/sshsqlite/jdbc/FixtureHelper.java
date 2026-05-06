package org.sshsqlite.jdbc;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class FixtureHelper {
    private FixtureHelper() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "normal" : args[0];
        FrameCodec codec = new FrameCodec(1024 * 1024);
        OutputStream out = System.out;
        while (true) {
            var request = codec.readJson(System.in);
            long id = request.path("id").asLong();
            String op = request.path("op").asText();
            if ("hello".equals(op)) {
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
                codec.writeJson(out, response);
            } else if ("open".equals(op)) {
                codec.writeJson(out, Map.of("id", id, "ok", true, "op", "openAck", "readonly", true));
                if ("crashAfterOpen".equals(mode)) {
                    System.exit(9);
                }
            } else {
                codec.writeJson(out, Map.of("id", id, "ok", false, "op", "error", "error", Map.of("message", "unsupported")));
            }
        }
    }
}
