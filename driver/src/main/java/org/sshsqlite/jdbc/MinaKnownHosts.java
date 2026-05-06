package org.sshsqlite.jdbc;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class MinaKnownHosts {
    private MinaKnownHosts() {
    }

    static ServerKeyVerifier verifier(Path knownHosts) {
        return (session, remoteAddress, serverKey) -> verify(session, remoteAddress, serverKey, knownHosts);
    }

    private static boolean verify(ClientSession session, SocketAddress remoteAddress, PublicKey serverKey, Path knownHosts) {
        if (knownHosts == null || !Files.isRegularFile(knownHosts)) {
            return false;
        }
        HostPort hostPort = hostPort(remoteAddress);
        try {
            boolean accepted = false;
            for (String rawLine : Files.readAllLines(knownHosts)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                KnownHostEntry entry = parse(line);
                if (!entry.matches(hostPort)) {
                    continue;
                }
                if (entry.hostCertificate) {
                    throw new IllegalArgumentException("OpenSSH host certificate known_hosts entries are not supported");
                }
                if (entry.revoked) {
                    if (matchesKey(session, entry, serverKey)) {
                        return false;
                    }
                    continue;
                }
                if (matchesKey(session, entry, serverKey)) {
                    accepted = true;
                }
            }
            return accepted;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean matchesKey(ClientSession session, KnownHostEntry entry, PublicKey serverKey)
            throws GeneralSecurityException, IOException {
        PublicKey expected = AuthorizedKeyEntry.parseAuthorizedKeyEntry(entry.keyText)
                .resolvePublicKey(session, PublicKeyEntryResolver.FAILING);
        return KeyUtils.compareKeys(expected, serverKey);
    }

    private static KnownHostEntry parse(String line) {
        String[] parts = line.split("\\s+");
        boolean revoked = false;
        boolean certAuthority = false;
        int index = 0;
        if (parts[0].startsWith("@")) {
            if ("@revoked".equals(parts[0])) {
                revoked = true;
            } else if ("@cert-authority".equals(parts[0])) {
                certAuthority = true;
            } else {
                throw new IllegalArgumentException("Unsupported known_hosts marker: " + parts[0]);
            }
            index = 1;
        }
        if (parts.length < index + 3) {
            throw new IllegalArgumentException("Malformed known_hosts entry");
        }
        if (certAuthority) {
            throw new IllegalArgumentException("OpenSSH @cert-authority known_hosts entries are not supported");
        }
        boolean hostCertificate = parts[index + 1].contains("-cert-v01@openssh.com");
        return new KnownHostEntry(List.of(parts[index].split(",")), parts[index + 1] + " " + parts[index + 2], revoked, hostCertificate);
    }

    private static HostPort hostPort(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) remoteAddress;
            return new HostPort(inet.getHostString(), inet.getPort());
        }
        return new HostPort(remoteAddress.toString(), -1);
    }

    private static final class KnownHostEntry {
        private final List<String> patterns;
        private final String keyText;
        private final boolean revoked;
        private final boolean hostCertificate;

        private KnownHostEntry(List<String> patterns, String keyText, boolean revoked, boolean hostCertificate) {
            this.patterns = patterns;
            this.keyText = keyText;
            this.revoked = revoked;
            this.hostCertificate = hostCertificate;
        }

        boolean matches(HostPort hostPort) {
            List<String> candidates = hostPort.candidates();
            for (String pattern : patterns) {
                if (matchesAnyCandidate(pattern, candidates)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesAnyCandidate(String pattern, List<String> candidates) {
            if (pattern.startsWith("|")) {
                return matchesHashed(pattern, candidates);
            }
            return candidates.contains(pattern);
        }

        private boolean matchesHashed(String pattern, List<String> candidates) {
            String[] parts = pattern.split("\\|", -1);
            if (parts.length != 4 || !parts[0].isEmpty() || !"1".equals(parts[1])) {
                throw new IllegalArgumentException("Unsupported hashed known_hosts pattern");
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            for (String candidate : candidates) {
                try {
                    Mac mac = Mac.getInstance("HmacSHA1");
                    mac.init(new SecretKeySpec(salt, "HmacSHA1"));
                    if (MessageDigest.isEqual(expected, mac.doFinal(candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                        return true;
                    }
                } catch (GeneralSecurityException e) {
                    throw new IllegalArgumentException("Unable to evaluate hashed known_hosts pattern", e);
                }
            }
            return false;
        }
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        List<String> candidates() {
            List<String> candidates = new ArrayList<>();
            candidates.add("[" + host + "]:" + port);
            if (port == 22) {
                candidates.add(host);
            }
            return candidates;
        }
    }
}
