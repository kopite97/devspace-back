package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubCryptographyTests {
    @TempDir Path directory;
    @Test void authenticatedEncryptionBindsOwnerPurposeAndKeyVersion() {
        var props = new GitHubProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var vault = new AesGitHubSecretVault(props); UUID owner = UUID.randomUUID();
        String first = vault.encrypt(owner, "access", "secret-token"), second = vault.encrypt(owner, "access", "secret-token");
        assertNotEquals(first, second); assertFalse(first.contains("secret-token"));
        assertEquals("secret-token", vault.decrypt(owner, "access", first));
        assertThrows(GitHubException.class, () -> vault.decrypt(UUID.randomUUID(), "access", first));
        assertThrows(GitHubException.class, () -> vault.decrypt(owner, "refresh", first));
        String tampered = first.substring(0, first.length() - 4) + "AAAA";
        assertThrows(GitHubException.class, () -> vault.decrypt(owner, "access", tampered));
        props.setEncryptionKeyId("v2");
        assertThrows(GitHubException.class, () -> vault.decrypt(owner, "access", first));
    }
    @Test void jwtHasVerifiableSignatureAndBoundedLifetime() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
        var file = directory.resolve("test.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        var settings = new GitHubProperties(); settings.setEnabled(true); settings.setClientId("test-client"); settings.setPrivateKeyPath(file.toString());
        var clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        String[] parts = new GitHubAppJwt(settings, clock).issue().split("\\.");
        var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
        var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("test-client", payload.get("iss").asString());
        assertEquals(clock.instant().getEpochSecond() - 60, payload.get("iat").asLong());
        assertEquals(600, payload.get("exp").asLong() - payload.get("iat").asLong());
    }
    @Test void githubPkcs1KeyIsWrappedForJca() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
        byte[] encoded = pair.getPrivate().getEncoded();
        // Extract PrivateKeyInfo's RSA OCTET STRING with the ASN.1 sequence lengths.
        int pos = skipHeader(encoded, 0); pos = skipValue(encoded, pos); pos = skipValue(encoded, pos);
        int body = skipHeader(encoded, pos); byte[] pkcs1 = Arrays.copyOfRange(encoded, body, encoded.length);
        byte[] wrapped = GitHubAppJwt.pkcs8(pkcs1);
        var key = KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(wrapped));
        assertArrayEquals(encoded, key.getEncoded());
    }
    private static int skipHeader(byte[] data, int pos) { int len = data[pos + 1] & 255; return pos + 2 + (len < 128 ? 0 : len & 127); }
    private static int skipValue(byte[] data, int pos) {
        int len = data[pos + 1] & 255, header = skipHeader(data, pos);
        if (len >= 128) { int size = len & 127; len = 0; for (int i = 0; i < size; i++) len = (len << 8) | (data[pos + 2 + i] & 255); }
        return header + len;
    }
}
