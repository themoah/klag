package io.github.themoah.klag.mcp;

import io.vertx.core.json.JsonObject;

/**
 * JSON-RPC 2.0 envelope helpers and MCP method/error constants.
 *
 * <p>Kept dependency-free (Vert.x {@link JsonObject} only) so it works under GraalVM native.
 */
public final class McpProtocol {

  private McpProtocol() {}

  /** MCP protocol revision implemented by this server. */
  public static final String PROTOCOL_VERSION = "2025-11-25";

  // JSON-RPC 2.0 standard error codes.
  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;

  // MCP methods.
  public static final String INITIALIZE = "initialize";
  public static final String INITIALIZED = "notifications/initialized";
  public static final String TOOLS_LIST = "tools/list";
  public static final String TOOLS_CALL = "tools/call";
  public static final String PING = "ping";

  /**
   * Builds a successful JSON-RPC response.
   *
   * @param id the request id (echoed back)
   * @param result the result payload
   * @return the response envelope
   */
  public static JsonObject success(Object id, JsonObject result) {
    return new JsonObject()
      .put("jsonrpc", "2.0")
      .put("id", id)
      .put("result", result);
  }

  /**
   * Builds a JSON-RPC error response.
   *
   * @param id the request id (may be null for parse/invalid-request errors)
   * @param code the JSON-RPC error code
   * @param message human-readable error message
   * @return the response envelope
   */
  public static JsonObject error(Object id, int code, String message) {
    return new JsonObject()
      .put("jsonrpc", "2.0")
      .put("id", id)
      .put("error", new JsonObject().put("code", code).put("message", message));
  }

  /**
   * Whether a parsed request is a notification (no {@code id}, so no response is expected).
   *
   * @param request the parsed JSON-RPC message
   * @return true if the message has no id
   */
  public static boolean isNotification(JsonObject request) {
    return !request.containsKey("id");
  }
}
