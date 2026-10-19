package com.kopite.devspace.github.application.port;

/** Backend-only installation credentials; never a substitute for a user's access grant. */
public interface GitHubInstallationTokens {
    String token(long installation, long repository);
    void invalidate(long installation);
}
