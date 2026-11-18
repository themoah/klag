package io.github.themoah.klag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Java4Shell (CVE-2026-114938): Vert.x h2c upgrade Base64-decodes the client's HTTP2-Settings
 * header pre-auth. The server must answer an h2c upgrade request with plain HTTP/1.1, never 101.
 */
class HttpServerOptionsTest {

  @Test
  void h2cUpgradeIsRefused() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      HttpServer server = vertx.createHttpServer(MainVerticle.httpServerOptions())
        .requestHandler(req -> req.response().end("ok"))
        .listen(0)
        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

      try (Socket socket = new Socket("localhost", server.actualPort())) {
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /healthz HTTP/1.1\r\n"
          + "Host: localhost\r\n"
          + "Connection: Upgrade, HTTP2-Settings\r\n"
          + "Upgrade: h2c\r\n"
          + "HTTP2-Settings: AAMAAABkAAQAAP__\r\n"
          + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        assertEquals("HTTP/1.1 200 OK", statusLine);
      }
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }
}
