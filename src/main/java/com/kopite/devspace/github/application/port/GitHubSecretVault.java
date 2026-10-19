package com.kopite.devspace.github.application.port;

import java.util.UUID;

public interface GitHubSecretVault {
    String encrypt(UUID owner, String purpose, String plaintext);
    String decrypt(UUID owner, String purpose, String ciphertext);
}
