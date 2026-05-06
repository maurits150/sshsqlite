package org.sshsqlite.jdbc;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinaKnownHostsTest {
    @TempDir
    Path tempDir;

    @Test
    void createsFailClosedKnownHostsVerifier() {
        ServerKeyVerifier verifier = MinaKnownHosts.verifier(Path.of("/tmp/nonexistent-known-hosts"));

        assertNotNull(verifier);
    }

    @Test
    void acceptsHashedHostnameEntry() throws Exception {
        KeyPair key = rsaKey();
        Path knownHosts = knownHosts(hashedHost("example.com") + " " + rsaPublicKey(key));

        assertTrue(verify(knownHosts, "example.com", 22, key));
    }

    @Test
    void acceptsHashedBracketedNonDefaultPortEntry() throws Exception {
        KeyPair key = rsaKey();
        Path knownHosts = knownHosts(hashedHost("[example.com]:2222") + " " + rsaPublicKey(key));

        assertTrue(verify(knownHosts, "example.com", 2222, key));
    }

    @Test
    void revokedEntryOverridesMatchingGoodKey() throws Exception {
        KeyPair key = rsaKey();
        String good = "example.com " + rsaPublicKey(key);
        String revoked = "@revoked " + hashedHost("example.com") + " " + rsaPublicKey(key);
        Path knownHosts = knownHosts(good + System.lineSeparator() + revoked);

        assertFalse(verify(knownHosts, "example.com", 22, key));
    }

    @Test
    void certAuthorityEntriesFailClosed() throws Exception {
        KeyPair key = rsaKey();
        Path knownHosts = knownHosts("@cert-authority example.com " + rsaPublicKey(key));

        assertFalse(verify(knownHosts, "example.com", 22, key));
    }

    @Test
    void hostCertificateEntriesFailClosed() throws Exception {
        KeyPair key = rsaKey();
        Path knownHosts = knownHosts("example.com " + rsaPublicKey(key) + System.lineSeparator()
                + "example.com ssh-ed25519-cert-v01@openssh.com AAAA");

        assertFalse(verify(knownHosts, "example.com", 22, key));
    }

    @Test
    void bracketedIpv6AndMultipleAlgorithmsWork() throws Exception {
        KeyPair wrong = rsaKey();
        KeyPair key = ecdsaKey();
        Path knownHosts = knownHosts("[::1]:2222 " + rsaPublicKey(wrong) + System.lineSeparator()
                + "[::1]:2222 " + ecdsaPublicKey(key));

        assertTrue(verify(knownHosts, "::1", 2222, key));
    }

    private boolean verify(Path knownHosts, String host, int port, KeyPair key) {
        return MinaKnownHosts.verifier(knownHosts).verifyServerKey(null, InetSocketAddress.createUnresolved(host, port), key.getPublic());
    }

    private Path knownHosts(String content) throws Exception {
        Path knownHosts = tempDir.resolve("known_hosts");
        java.nio.file.Files.writeString(knownHosts, content + System.lineSeparator());
        return knownHosts;
    }

    private static KeyPair rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ecdsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String hashedHost(String host) throws Exception {
        byte[] salt = "0123456789abcdef0123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        return "|1|" + Base64.getEncoder().encodeToString(salt) + "|"
                + Base64.getEncoder().encodeToString(mac.doFinal(host.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String rsaPublicKey(KeyPair key) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, "ssh-rsa".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeMpint(out, publicKey.getPublicExponent());
        writeMpint(out, publicKey.getModulus());
        return "ssh-rsa " + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String ecdsaPublicKey(KeyPair key) throws Exception {
        ECPublicKey publicKey = (ECPublicKey) key.getPublic();
        int fieldBytes = (publicKey.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        ByteArrayOutputStream point = new ByteArrayOutputStream();
        point.write(4);
        point.write(fixedWidth(publicKey.getW().getAffineX(), fieldBytes));
        point.write(fixedWidth(publicKey.getW().getAffineY(), fieldBytes));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, "ecdsa-sha2-nistp256".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeString(out, "nistp256".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeString(out, point.toByteArray());
        return "ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static void writeString(ByteArrayOutputStream out, byte[] bytes) {
        out.write((bytes.length >>> 24) & 0xff);
        out.write((bytes.length >>> 16) & 0xff);
        out.write((bytes.length >>> 8) & 0xff);
        out.write(bytes.length & 0xff);
        out.writeBytes(bytes);
    }

    private static void writeMpint(ByteArrayOutputStream out, BigInteger value) {
        writeString(out, value.toByteArray());
    }

    private static byte[] fixedWidth(BigInteger value, int width) {
        byte[] source = value.toByteArray();
        byte[] target = new byte[width];
        int copy = Math.min(source.length, width);
        System.arraycopy(source, source.length - copy, target, width - copy, copy);
        return target;
    }
}
