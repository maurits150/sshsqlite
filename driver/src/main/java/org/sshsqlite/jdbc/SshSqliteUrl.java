package org.sshsqlite.jdbc;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class SshSqliteUrl {
    static final String PREFIX = "jdbc:sshsqlite:";
    private static final Map<String, String> FORBIDDEN_URL_KEYS = Map.of(
            "ssh.password", "secret",
            "ssh.privateKey", "secret",
            "ssh.privateKeyPassphrase", "secret",
            "ssh.privateKeyMaterial", "secret",
            "privateKey", "secret",
            "password", "secret");

    private final Map<String, String> properties;

    private SshSqliteUrl(Map<String, String> properties) {
        this.properties = Map.copyOf(properties);
    }

    static SshSqliteUrl parse(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(PREFIX)) {
            throw new IllegalArgumentException("URL must start with " + PREFIX);
        }
        String uriPart = jdbcUrl.substring(PREFIX.length());
        URI uri;
        try {
            uri = new URI(uriPart);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid jdbc:sshsqlite URL", e);
        }
        if (!"//".equals(uriPart.length() >= 2 ? uriPart.substring(0, 2) : uriPart) || uri.getHost() == null) {
            throw new IllegalArgumentException("URL must be hierarchical with host");
        }
        if (uri.getRawUserInfo() != null && uri.getRawUserInfo().contains(":")) {
            throw new IllegalArgumentException("Passwords are not allowed in jdbc:sshsqlite URLs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ssh.host", stripIpv6Brackets(uri.getHost()));
        if (uri.getPort() != -1) {
            values.put("ssh.port", Integer.toString(uri.getPort()));
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
            values.put("ssh.user", percentDecode(uri.getRawUserInfo()));
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isBlank()) {
            values.put("db.path", percentDecode(rawPath));
        }
        if (uri.getRawQuery() != null) {
            parseQuery(uri.getRawQuery(), values);
        }
        return new SshSqliteUrl(values);
    }

    Map<String, String> properties() {
        return properties;
    }

    private static void parseQuery(String rawQuery, Map<String, String> values) {
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawKey = equals >= 0 ? pair.substring(0, equals) : pair;
            String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";
            String key = percentDecode(rawKey);
            if (FORBIDDEN_URL_KEYS.containsKey(key)) {
                throw new IllegalArgumentException("Secrets are not allowed in jdbc:sshsqlite URLs: " + key);
            }
            if (!ConnectionConfig.PropertyNames.KNOWN.contains(key)) {
                throw new IllegalArgumentException("Unknown URL query parameter: " + key);
            }
            values.put(key, percentDecode(rawValue));
        }
    }

    private static String percentDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
