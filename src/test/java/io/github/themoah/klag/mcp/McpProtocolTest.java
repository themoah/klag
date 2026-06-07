package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class McpProtocolTest {

  @Test
  void successEnvelopeHasJsonRpcVersionAndId() {
    JsonObject result = new JsonObject().put("ok", true);
    JsonObject resp = McpProtocol.success(1, result);

    assertEquals("2.0", resp.getString("jsonrpc"));
    assertEquals(1, resp.getInteger("id"));
    assertEquals(result, resp.getJsonObject("result"));
    assertFalse(resp.containsKey("error"));
  }

  @Test
  void errorEnvelopeCarriesCodeAndMessage() {
    JsonObject resp = McpProtocol.error("abc", McpProtocol.METHOD_NOT_FOUND, "no such method");

    assertEquals("2.0", resp.getString("jsonrpc"));
    assertEquals("abc", resp.getString("id"));
    JsonObject err = resp.getJsonObject("error");
    assertEquals(McpProtocol.METHOD_NOT_FOUND, err.getInteger("code"));
    assertEquals("no such method", err.getString("message"));
    assertFalse(resp.containsKey("result"));
  }

  @Test
  void errorWithNullIdSerializesNullId() {
    JsonObject resp = McpProtocol.error(null, McpProtocol.PARSE_ERROR, "bad");
    assertTrue(resp.containsKey("id"));
    assertNull(resp.getValue("id"));
  }

  @Test
  void notificationHasNoId() {
    JsonObject notif = new JsonObject().put("jsonrpc", "2.0").put("method", "notifications/initialized");
    assertTrue(McpProtocol.isNotification(notif));

    JsonObject request = new JsonObject().put("jsonrpc", "2.0").put("id", 5).put("method", "ping");
    assertFalse(McpProtocol.isNotification(request));
  }

  @Test
  void errorCodesMatchJsonRpcSpec() {
    assertEquals(-32700, McpProtocol.PARSE_ERROR);
    assertEquals(-32600, McpProtocol.INVALID_REQUEST);
    assertEquals(-32601, McpProtocol.METHOD_NOT_FOUND);
    assertEquals(-32602, McpProtocol.INVALID_PARAMS);
    assertEquals(-32603, McpProtocol.INTERNAL_ERROR);
  }
}
