package com.kopite.devspace.github.domain;

public class GitHubException extends RuntimeException {
    private final String code;
    private final int upstreamStatus;
    public GitHubException(String code) { this(code, 0); }
    public GitHubException(String code, int upstreamStatus) { super(code); this.code = code; this.upstreamStatus = upstreamStatus; }
    public String code() { return code; }
    public int upstreamStatus() { return upstreamStatus; }
}
