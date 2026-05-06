package org.sshsqlite.jdbc;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Redactor {
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:sshsqlite://[^\\s,;\\\"']+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile("(?i)\\b(password|passphrase|privateKey|privateKeyPassphrase|token|secret|db\\.path|helper\\.path|helper\\.localPath|helper\\.localAllowlist|ssh\\.knownHosts)\\b\\s*[:=]\\s*([^\\s,;]+)");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?<![A-Za-z0-9._:/-])/(?:[^\\s,;:\\\"']+/)*[^\\s,;:\\\"']+");
    private static final Pattern SQL_TEXT = Pattern.compile("(?is)\\b(select|insert|update|delete|replace|pragma|attach|vacuum|with)\\b\\s+.*");

    private Redactor() {
    }

    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = JDBC_URL.matcher(text).replaceAll("jdbc:sshsqlite://<redacted>");
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1=<redacted>");
        redacted = redactSqlLines(redacted);
        return ABSOLUTE_PATH.matcher(redacted).replaceAll("<path-redacted>");
    }

    static Map<String, String> sanitizeProperties(Map<String, String> properties) {
        return properties.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> sensitiveKey(entry.getKey()) ? "<redacted>" : sanitize(entry.getValue()),
                (a, b) -> b,
                java.util.TreeMap::new));
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passphrase")
                || normalized.contains("privatekey")
                || normalized.contains("token")
                || normalized.contains("secret");
    }

    private static String redactSqlLines(String text) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = SQL_TEXT.matcher(lines[i]).replaceAll("<sql-redacted>");
        }
        return String.join(System.lineSeparator(), lines);
    }
}
