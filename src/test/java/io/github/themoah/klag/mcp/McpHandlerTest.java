package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class McpHandlerTest {

  private static McpHandler handler(McpConfig config) {
    return new McpHandler(config, new McpTools(new SnapshotStore()));
  }

  private static McpHandler openHandler() {
    return handler(McpConfig.from(k -> "MCP_ENABLED".equals(k) ? "true" : null));
  }

  private static JsonObject request(int id, String method) {
    return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
  }

  @Test
  void initializeReturnsProtocolVersionAndServerInfo() {
    JsonObject resp = openHandler().dispatch(request(1, McpProtocol.INITIALIZE)).orElseThrow();
    JsonObject result = resp.getJsonObject("result");
    assertEquals(McpProtocol.PROTOCOL_VERSION, result.getString("protocolVersion"));
    assertTrue(result.getJsonObject("capabilities").containsKey("tools"));
    assertEquals("klag", result.getJsonObject("serverInfo").getString("name"));
  }

  @Test
  void pingReturnsEmptyResult() {
    JsonObject resp = openHandler().dispatch(request(2, McpProtocol.PING)).orElseThrow();
    assertTrue(resp.containsKey("result"));
  }

  @Test
  void toolsListReturnsToolArray() {
    JsonObject resp = openHandler().dispatch(request(3, McpProtocol.TOOLS_LIST)).orElseThrow();
    assertEquals(4, resp.getJsonObject("result").getJsonArray("tools").size());
  }

  @Test
  void toolsCallWithoutNameIsInvalidParams() {
    JsonObject req = request(4, McpProtocol.TOOLS_CALL).put("params", new JsonObject());
    JsonObject resp = openHandler().dispatch(req).orElseThrow();
    assertEquals(McpProtocol.INVALID_PARAMS, resp.getJsonObject("error").getInteger("code"));
  }

  @Test
  void unknownMethodReturnsMethodNotFound() {
    JsonObject resp = openHandler().dispatch(request(5, "does/notExist")).orElseThrow();
    assertEquals(McpProtocol.METHOD_NOT_FOUND, resp.getJsonObject("error").getInteger("code"));
  }

  @Test
  void missingMethodIsInvalidRequest() {
    JsonObject req = new JsonObject().put("jsonrpc", "2.0").put("id", 6);
    JsonObject resp = openHandler().dispatch(req).orElseThrow();
    assertEquals(McpProtocol.INVALID_REQUEST, resp.getJsonObject("error").getInteger("code"));
  }

  @Test
  void notificationProducesNoResponse() {
    JsonObject notif = new JsonObject().put("jsonrpc", "2.0").put("method", McpProtocol.INITIALIZED);
    Optional<JsonObject> resp = openHandler().dispatch(notif);
    assertTrue(resp.isEmpty());
  }

  @Test
  void authDisabledAllowsAnyRequest() {
    assertTrue(openHandler().authorized(null));
  }

  @Test
  void authEnabledRejectsMissingOrWrongToken() {
    McpHandler h = handler(McpConfig.from(k -> switch (k) {
      case "MCP_ENABLED" -> "true";
      case "MCP_AUTH_TOKEN" -> "s3cret";
      default -> null;
    }));
    assertFalse(h.authorized(null));
    assertFalse(h.authorized("Bearer wrong"));
    assertFalse(h.authorized("s3cret"));
    assertTrue(h.authorized("Bearer s3cret"));
  }

  @Test
  void authAcceptsCaseInsensitiveSchemeAndExtraWhitespace() {
    McpHandler h = handler(McpConfig.from(k -> switch (k) {
      case "MCP_ENABLED" -> "true";
      case "MCP_AUTH_TOKEN" -> "s3cret";
      default -> null;
    }));
    assertTrue(h.authorized("bearer s3cret"));
    assertTrue(h.authorized("BEARER s3cret"));
    assertTrue(h.authorized("Bearer   s3cret"));
    assertTrue(h.authorized("  Bearer s3cret  "));
    assertTrue(h.authorized("Bearer\ts3cret"));
    // Token comparison stays exact.
    assertFalse(h.authorized("Bearer s3cret extra"));
    assertFalse(h.authorized("Bearer S3CRET"));
    assertFalse(h.authorized("Basic s3cret"));
    assertFalse(h.authorized("Bearer"));
  }
}
