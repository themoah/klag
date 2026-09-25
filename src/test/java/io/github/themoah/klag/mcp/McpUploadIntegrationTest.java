package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.file.FileSystemOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpUploadIntegrationTest {

  @TempDir
  Path workingDirectory;

  private Vertx vertx;
  private HttpClient client;
  private URI endpoint;

  @BeforeEach
  void setUp() {
    // Vert.x captures this override when its file resolver is constructed.
    // Restore it immediately so no other test inherits this temporary directory.
    String previousCwd = System.getProperty("vertx.cwd");
    try {
      System.setProperty("vertx.cwd", workingDirectory.toString());
      vertx = Vertx.vertx(new VertxOptions().setFileSystemOptions(
        new FileSystemOptions().setClassPathResolvingEnabled(false)));
    } finally {
      if (previousCwd == null) {
        System.clearProperty("vertx.cwd");
      } else {
        System.setProperty("vertx.cwd", previousCwd);
      }
    }
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  private void deploy(String token) throws Exception {
    McpConfig config = McpConfig.from(key -> switch (key) {
      case "MCP_ENABLED" -> "true";
      case "MCP_AUTH_TOKEN" -> token;
      default -> null;
    });
    Router router = Router.router(vertx);
    new McpHandler(config, new McpTools(new SnapshotStore())).registerRoutes(router);
    HttpServer server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
      .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    endpoint = URI.create("http://127.0.0.1:" + server.actualPort() + "/mcp");
  }

  private HttpResponse<String> post(String contentType, String body, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", contentType)
      .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void multipartDoesNotCreateUploadsAndJsonStillWorks() throws Exception {
    deploy(null);
    String multipart = "--klag-test\r\n"
      + "Content-Disposition: form-data; name=\"file\"; filename=\"sample.txt\"\r\n"
      + "Content-Type: text/plain\r\n\r\n"
      + "uploaded data\r\n--klag-test--\r\n";
    HttpResponse<String> upload = post("multipart/form-data; boundary=klag-test", multipart, null);
    assertEquals(200, upload.statusCode());
    assertTrue(new JsonObject(upload.body()).containsKey("error"));
    assertFalse(Files.exists(workingDirectory.resolve("file-uploads")),
      "MCP must not create an upload directory or persist multipart file parts");

    HttpResponse<String> ping = post("application/json",
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);
    assertEquals(200, ping.statusCode());
    assertTrue(new JsonObject(ping.body()).containsKey("result"));
  }

  @Test
  void oversizedBodyStillReturnsJsonRpc413() throws Exception {
    deploy(null);
    HttpResponse<String> response = post("application/json", "x".repeat(1024 * 1024 + 1), null);
    assertEquals(413, response.statusCode());
    assertEquals("Request body too large",
      new JsonObject(response.body()).getJsonObject("error").getString("message"));
  }

  @Test
  void unauthorizedBodyIsRejectedBeforeBodyLimit() throws Exception {
    deploy("test-token");
    HttpResponse<String> response = post("application/json", "x".repeat(1024 * 1024 + 1), null);
    assertEquals(401, response.statusCode());
    assertFalse(Files.exists(workingDirectory.resolve("file-uploads")));
  }
}
