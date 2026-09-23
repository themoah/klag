package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Over-the-wire integration tests for the MCP endpoint: real Vert.x HTTP server + client,
 * exercising auth, the GET-405 rule, JSON parse errors, and a full initialize->tools/call flow.
 */
@ExtendWith(VertxExtension.class)
class McpHttpIntegrationTest {

  private Vertx vertx;
  private HttpClient client;
  private int port;

  private static SnapshotStore storeWithPayments() {
    PartitionLag p = PartitionLag.of("orders", 0, 100, 0, 0, 0, 40);
    ConsumerGroupLag lag = ConsumerGroupLag.fromPartitions("payments", List.of(p));
    GroupSnapshot g = new GroupSnapshot("payments", State.STABLE, lag.totalLag(), lag.maxLag(),
      lag.minLag(), lag.partitions(), List.of(), List.of(), List.of(), List.of(), List.of());
    SnapshotStore store = new SnapshotStore();
    store.set(new MetricsSnapshot(System.currentTimeMillis(), List.of(g), List.of()));
    return store;
  }

  private static McpConfig enabled(String token) {
    return McpConfig.from(k -> switch (k) {
      case "MCP_ENABLED" -> "true";
      case "MCP_AUTH_TOKEN" -> token;
      default -> null;
    });
  }

  /** POST JSON body, return [statusCode, bodyJsonOrNull] via callback. */
  private void post(String authHeader, JsonObject body, Function<HttpResult, Void> assertion, VertxTestContext ctx) {
    client.request(HttpMethod.POST, port, "localhost", "/mcp")
      .compose(req -> {
        req.putHeader("content-type", "application/json");
        if (authHeader != null) {
          req.putHeader("Authorization", authHeader);
        }
        return req.send(Buffer.buffer(body.encode()));
      })
      .compose(resp -> resp.body().map(b -> new HttpResult(resp.statusCode(), b)))
      .onSuccess(result -> ctx.verify(() -> {
        assertion.apply(result);
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
  }

  private record HttpResult(int status, Buffer body) {
    JsonObject json() {
      return body.length() == 0 ? new JsonObject() : new JsonObject(body);
    }
  }

  @BeforeEach
  void setUp(Vertx vertx) {
    this.vertx = vertx;
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void getReturns405(VertxTestContext ctx) {
    deployThen(enabled(null), new SnapshotStore(), ctx, () ->
      client.request(HttpMethod.GET, port, "localhost", "/mcp")
        .compose(req -> req.send())
        .onSuccess(resp -> ctx.verify(() -> {
          assertEquals(405, resp.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow));
  }

  @Test
  void unauthorizedWhenTokenMissing(VertxTestContext ctx) {
    deployThen(enabled("s3cret"), storeWithPayments(), ctx, () ->
      post(null, new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"),
        r -> { assertEquals(401, r.status()); return null; }, ctx));
  }

  @Test
  void authorizedWithCorrectToken(VertxTestContext ctx) {
    deployThen(enabled("s3cret"), storeWithPayments(), ctx, () ->
      post("Bearer s3cret", new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"),
        r -> {
          assertEquals(200, r.status());
          assertTrue(r.json().containsKey("result"));
          return null;
        }, ctx));
  }

  @Test
  void parseErrorOnInvalidJson(VertxTestContext ctx) {
    deployThen(enabled(null), new SnapshotStore(), ctx, () ->
      client.request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> {
          req.putHeader("content-type", "application/json");
          return req.send(Buffer.buffer("{not json"));
        })
        .compose(resp -> resp.body().map(b -> new HttpResult(resp.statusCode(), b)))
        .onSuccess(r -> ctx.verify(() -> {
          assertEquals(-32700, r.json().getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow));
  }

  @Test
  void batchArrayIsInvalidRequestNotParseError(VertxTestContext ctx) {
    // A JSON array is valid JSON, so it must not be reported as a -32700 parse error;
    // batching is unsupported, which is -32600 Invalid Request.
    deployThen(enabled(null), new SnapshotStore(), ctx, () ->
      client.request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> {
          req.putHeader("content-type", "application/json");
          return req.send(Buffer.buffer(
            "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
              + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]"));
        })
        .compose(resp -> resp.body().map(b -> new HttpResult(resp.statusCode(), b)))
        .onSuccess(r -> ctx.verify(() -> {
          // JSON-RPC errors ride on HTTP 200; only the envelope carries the failure.
          assertEquals(200, r.status());
          assertEquals(-32600, r.json().getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow));
  }

  @Test
  void fullToolsCallFlow(VertxTestContext ctx) {
    deployThen(enabled(null), storeWithPayments(), ctx, () ->
      post(null, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 9).put("method", "tools/call")
          .put("params", new JsonObject().put("name", "diagnose")
            .put("arguments", new JsonObject().put("group", "payments"))),
        r -> {
          assertEquals(200, r.status());
          JsonObject result = r.json().getJsonObject("result");
          String text = result.getJsonArray("content").getJsonObject(0).getString("text");
          assertTrue(text.contains("payments"));
          return null;
        }, ctx));
  }

  /**
   * Regression test for issue #115: a multipart/form-data POST to /mcp must not write any
   * files. The old BodyHandler.create() (handleFileUploads=true) would stream uploaded parts
   * to ./file-uploads/ without deleting them; BodyHandler.create(false) must prevent this.
   *
   * <p>Vert.x BodyHandler resolves the upload directory via {@code new File("file-uploads")}
   * and {@code vertx.fileSystem().mkdirsBlocking}, both of which use the native OS process
   * working directory — not {@code System.getProperty("user.dir")} (which Java's {@code File}
   * constructor ignores). The test therefore checks {@code file-uploads/} relative to the real
   * absolute CWD, and performs cleanup in both async callbacks so no files leak between runs.
   */
  @Test
  void multipartFormDataPostDoesNotCreateFileUploadsDirectory(VertxTestContext ctx) {
    // Resolve against the real native CWD, which is what BodyHandler uses.
    Path fileUploadsDir = Path.of("file-uploads").toAbsolutePath();

    // Multipart body simulating the reproduction case from issue #115.
    String boundary = "testboundary";
    String crlf = "\r\n";
    String multipartBody =
        "--" + boundary + crlf
        + "Content-Disposition: form-data; name=\"f\"; filename=\"blob.bin\"" + crlf
        + "Content-Type: application/octet-stream" + crlf
        + crlf
        + "BINARYDATA"  // small stand-in for /dev/urandom output
        + crlf
        + "--" + boundary + "--" + crlf;

    // Remove any pre-existing file-uploads/ directory so the before-state is clean.
    deleteRecursively(fileUploadsDir);

    deployThen(enabled(null), new SnapshotStore(), ctx, () ->
      client.request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> {
          req.putHeader("content-type", "multipart/form-data; boundary=" + boundary);
          return req.send(Buffer.buffer(multipartBody.getBytes(StandardCharsets.UTF_8)));
        })
        .compose(resp -> resp.body().map(b -> new HttpResult(resp.statusCode(), b)))
        .onSuccess(r -> {
          // BodyHandler has finished; capture existence before cleanup.
          boolean created = Files.exists(fileUploadsDir);
          // Always clean up in case the old (buggy) handler wrote files.
          deleteRecursively(fileUploadsDir);
          ctx.verify(() -> {
            assertFalse(created,
              "file-uploads/ directory must not be created by a multipart POST to /mcp");
            ctx.completeNow();
          });
        })
        .onFailure(t -> {
          deleteRecursively(fileUploadsDir);
          ctx.failNow(t);
        }));
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.delete(p); } catch (java.io.IOException ignored) {} });
    } catch (java.io.IOException ignored) {}
  }

  /** Deploy, then run the test body once the server is listening. */
  private void deployThen(McpConfig config, SnapshotStore store, VertxTestContext ctx, Runnable body) {
    Router router = Router.router(vertx);
    new McpHandler(config, new McpTools(store)).registerRoutes(router);
    router.route().handler(c -> c.response().setStatusCode(404).end());
    vertx.createHttpServer().requestHandler(router).listen(0)
      .onSuccess(server -> {
        port = server.actualPort();
        client = vertx.createHttpClient();
        body.run();
      })
      .onFailure(ctx::failNow);
  }
}
