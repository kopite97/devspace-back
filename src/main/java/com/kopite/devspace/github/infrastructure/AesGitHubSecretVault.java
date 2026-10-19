package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubSecretVault;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public class AesGitHubSecretVault implements GitHubSecretVault {
    private final GitHubProperties settings;
    private final SecureRandom random = new SecureRandom();
    public AesGitHubSecretVault(GitHubProperties settings) { this.settings = settings; }

    @Override public String encrypt(UUID owner, String purpose, String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, owner, purpose, nonce);
            return settings.getEncryptionKeyId() + "." + Base64.getEncoder().encodeToString(nonce)
                    + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new GitHubException("GITHUB_CREDENTIALS_UNAVAILABLE"); }
    }

    @Override public String decrypt(UUID owner, String purpose, String ciphertext) {
        if (ciphertext == null) return null;
        try {
            String[] parts = ciphertext.split("\\.", -1);
            if (parts.length != 3 || !settings.getEncryptionKeyId().equals(parts[0])) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, owner, purpose, nonce)
                    .doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new GitHubException("GITHUB_CREDENTIALS_UNAVAILABLE"); }
    }

    private Cipher cipher(int mode, UUID owner, String purpose, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] key = Base64.getDecoder().decode(settings.getEncryptionKey());
        if (key.length != 32) throw new IllegalArgumentException();
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("github:" + owner + ":" + purpose + ":" + settings.getEncryptionKeyId()).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
