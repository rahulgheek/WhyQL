package com.whyql.whyql.integration.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.whyql.whyql.integration.GitHubRepoFetcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub REST implementation (JDK HttpClient, follows codeload redirects).
 * Two calls: repo metadata for the default branch when no ref is given, then the
 * zipball download. Rate limits are GitHub's (60/hour anonymous, 5,000 with a
 * token); failures surface the status so callers can tell "not found" from
 * "rate limited".
 */
@Service
public class HttpGitHubRepoFetcher implements GitHubRepoFetcher {

  private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;

  private final HttpClient http;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String apiBase;
  private final String downloadBase;

  public HttpGitHubRepoFetcher(
      @Value("${whyql.github.api-url:https://api.github.com}") String apiBase,
      @Value("${whyql.github.download-url:https://codeload.github.com}") String downloadBase) {
    this.apiBase = stripTrailingSlash(apiBase);
    this.downloadBase = stripTrailingSlash(downloadBase);
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Override
  public byte[] downloadZip(String owner, String repo, String ref, String token) {
    GitHubRepoFetcher.validateName(owner, "owner");
    GitHubRepoFetcher.validateName(repo, "repository");
    String cleanOwner = owner.trim();
    String cleanRepo = repo.trim();
    String effectiveRef = (ref == null || ref.isBlank()) ? defaultBranch(cleanOwner, cleanRepo, token)
        : GitHubRepoFetcher.validateRef(ref);

    // codeload mirrors api.github.com/repos/{o}/{r}/zipball/{ref}; the configurable
    // download base exists so tests can point at a local stub.
    String url = downloadBase + "/" + cleanOwner + "/" + cleanRepo + "/zipball/refs/heads/" + effectiveRef;
    String apiUrl = apiBase + "/repos/" + cleanOwner + "/" + cleanRepo + "/zipball/" + effectiveRef;
    try {
      byte[] body = getBytes(apiUrl, token);
      if (body != null) {
        return body;
      }
      return getBytes(url, token);
    } catch (GitHubRepoFetcher.GitHubFetchException e) {
      throw e;
    } catch (Exception e) {
      throw new GitHubRepoFetcher.GitHubFetchException("GitHub download failed for " + cleanOwner + "/" + cleanRepo
          + "@" + effectiveRef + ": " + e.getMessage(), e);
    }
  }

  private String defaultBranch(String owner, String repo, String token) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(apiBase + "/repos/" + owner + "/" + repo))
          .header("Accept", "application/vnd.github+json")
          .timeout(Duration.ofSeconds(10))
          .GET();
      if (token != null && !token.isBlank()) {
        builder.header("Authorization", "Bearer " + token.trim());
      }
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        JsonNode node = objectMapper.readTree(response.body());
        if (node.hasNonNull("default_branch")) {
          return node.get("default_branch").asText();
        }
      } else if (response.statusCode() == 404) {
        throw new GitHubRepoFetcher.GitHubFetchException("GitHub repository not found: " + owner + "/" + repo);
      } else if (response.statusCode() == 401 || response.statusCode() == 403) {
        throw new GitHubRepoFetcher.GitHubFetchException("GitHub access denied for " + owner + "/" + repo
            + " (private repo needs a token, or rate limit exceeded)");
      }
    } catch (GitHubRepoFetcher.GitHubFetchException e) {
      throw e;
    } catch (Exception e) {
      throw new GitHubRepoFetcher.GitHubFetchException(
          "GitHub request failed for " + owner + "/" + repo + ": " + e.getMessage(), e);
    }
    return "main";
  }

  private byte[] getBytes(String url, String token) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(60))
        .GET();
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", "Bearer " + token.trim());
    }
    HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    int status = response.statusCode();
    if (status == 200) {
      byte[] body = response.body();
      if (body.length > MAX_ZIP_BYTES) {
        throw new GitHubRepoFetcher.GitHubFetchException("GitHub zip exceeds 50 MB");
      }
      return body;
    }
    if (status == 404 || status == 422) {
      return null; // try the alternate URL form
    }
    if (status == 401 || status == 403) {
      throw new GitHubRepoFetcher.GitHubFetchException(
          "GitHub access denied (private repo needs a token, or rate limit exceeded)");
    }
    throw new GitHubRepoFetcher.GitHubFetchException("GitHub download failed with status " + status);
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.trim();
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }
}
