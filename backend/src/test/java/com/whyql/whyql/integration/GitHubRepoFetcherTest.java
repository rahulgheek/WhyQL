package com.whyql.whyql.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.whyql.whyql.integration.github.HttpGitHubRepoFetcher;

/**
 * GitHub fetcher against a local stub (stdlib HttpServer, no network, no Spring):
 * default-branch resolution, zip download, 404 handling, name validation.
 * The configurable base URLs exist precisely to make this test possible.
 */
class GitHubRepoFetcherTest {

  private static HttpServer stub;
  private static String base;
  private static byte[] zipBytes;

  @BeforeAll
  static void startStub() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("repo/schema.sql"));
      zip.write("CREATE TABLE t (id INT PRIMARY KEY);".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    zipBytes = bytes.toByteArray();

    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/repos/acme/shop", exchange -> {
      byte[] body = "{\"default_branch\":\"main\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.createContext("/repos/acme/shop/zipball/main", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "application/zip");
      exchange.sendResponseHeaders(200, zipBytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(zipBytes);
      }
    });
    stub.createContext("/repos/acme/missing", exchange -> {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    stub.start();
    base = "http://127.0.0.1:" + stub.getAddress().getPort();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  private HttpGitHubRepoFetcher fetcher() {
    return new HttpGitHubRepoFetcher(base, base);
  }

  @Test
  void downloadsZipUsingDefaultBranch() {
    assertThat(fetcher().downloadZip("acme", "shop", null, null)).isEqualTo(zipBytes);
    assertThat(fetcher().downloadZip("acme", "shop", "main", "token-123")).isEqualTo(zipBytes);
  }

  @Test
  void missingRepoFailsWithClearMessage() {
    assertThatThrownBy(() -> fetcher().downloadZip("acme", "missing", null, null))
        .isInstanceOf(GitHubRepoFetcher.GitHubFetchException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void ownerRepoRefValidatedBeforeAnyNetwork() {
    assertThatThrownBy(() -> fetcher().downloadZip("../evil", "shop", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fetcher().downloadZip("acme", "shop;rm", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fetcher().downloadZip("acme", "shop", "../../etc", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
