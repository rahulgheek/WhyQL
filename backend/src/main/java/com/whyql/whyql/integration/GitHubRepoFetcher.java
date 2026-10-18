package com.whyql.whyql.integration;

import java.util.regex.Pattern;

/**
 * Downloads a GitHub repository as a zip for schema analysis. Only GitHub's own
 * hosts are ever contacted (fixed base URLs, owner/repo validated), so a malicious
 * repo reference cannot turn this into an SSRF vector. OAuth sign-in and scheduled
 * re-sync arrive in Phase 7; until then the caller passes an optional token
 * per request (private repos, higher rate limits) which is never stored.
 */
public interface GitHubRepoFetcher {

  Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+$");
  Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9_./-]+$");

  /** Fetch owner/repo at ref (branch, tag or SHA; null/blank = default branch). */
  byte[] downloadZip(String owner, String repo, String ref, String token);

  static void validateName(String value, String what) {
    if (value == null || !NAME_PATTERN.matcher(value.trim()).matches()) {
      throw new IllegalArgumentException("invalid GitHub " + what + ": " + value);
    }
  }

  static String validateRef(String ref) {
    String clean = ref == null ? "" : ref.trim();
    if (!REF_PATTERN.matcher(clean).matches() || clean.contains("..")) {
      throw new IllegalArgumentException("invalid GitHub ref: " + ref);
    }
    return clean;
  }

  /** Failure with a user-facing message (status-aware, no stack-trace leaking). */
  class GitHubFetchException extends RuntimeException {
    public GitHubFetchException(String message) {
      super(message);
    }

    public GitHubFetchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
