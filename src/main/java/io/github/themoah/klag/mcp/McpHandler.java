package io.github.themoah.klag.mcp;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP Streamable HTTP endpoint over the existing Vert.x router.
 *
 * <p>Serves JSON-RPC 2.0 over POST (returning {@code application/json}); GET returns 405
 * since this server offers no server-initiated SSE stream. Optional bearer-token auth.
 *
 * <p>The protocol dispatch ({@link #dispatch}) and auth check ({@link #authorized}) are pure
 * and unit-tested independently of the Vert.x routing glue.
 */
public class McpHandler {

  private static final Logger log = LoggerFactory.getLogger(McpHandler.class);
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String SERVER_NAME = "klag";

  private final McpConfig config;
  private final McpTools tools;

  public McpHandler(McpConfig config, McpTools tools) {
    this.config = config;
    this.tools = tools;
  }

  /**
   * Registers the MCP route on the router.
   *
   * @param router the Vert.x router
   */
  public void registerRoutes(Router router) {
    router.post(config.path()).handler(BodyHandler.create()).handler(this::handlePost);
    router.get(config.path()).handler(this::handleGet);
    log.info("MCP endpoint registered at {} (POST)", config.path());
  }

  private void handleGet(RoutingContext ctx) {
    // This server does not offer the optional SSE stream; only POST is supported.
    ctx.response()
      .setStatusCode(405)
      .putHeader("Allow", "POST")
      .putHeader("content-type", CONTENT_TYPE_JSON)
      .end("{\"error\":\"Method Not Allowed; use POST for JSON-RPC\"}");
  }

  private void handlePost(RoutingContext ctx) {
    if (!authorized(ctx.request().getHeader("Authorization"))) {
      ctx.response()
        .setStatusCode(401)
        .putHeader("WWW-Authenticate", "Bearer")
        .putHeader("content-type", CONTENT_TYPE_JSON)
        .end("{\"error\":\"Unauthorized\"}");
      return;
    }

    JsonObject request;
    try {
      request = ctx.body().asJsonObject();
    } catch (DecodeException | ClassCastException e) {
      writeJson(ctx, 200, McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Invalid JSON"));
      return;
    }
    if (request == null) {
      writeJson(ctx, 200, McpProtocol.error(null, McpProtocol.INVALID_REQUEST, "Empty request body"));
      return;
    }

    Optional<JsonObject> response;
    try {
      response = dispatch(request);
    } catch (RuntimeException e) {
      log.warn("MCP dispatch failed", e);
      Object id = request.getValue("id");
      writeJson(ctx, 200, McpProtocol.error(id, McpProtocol.INTERNAL_ERROR, e.getMessage()));
      return;
    }

    // Notifications produce no response body (HTTP 202 Accepted).
    response.ifPresentOrElse(
      resp -> writeJson(ctx, 200, resp),
      () -> ctx.response().setStatusCode(202).end());
  }

  /**
   * Handles a single JSON-RPC message.
   *
   * @param request the parsed JSON-RPC request
   * @return the response, or empty if the message is a notification (no response expected)
   */
  public Optional<JsonObject> dispatch(JsonObject request) {
    if (McpProtocol.isNotification(request)) {
      // The only notification we expect is notifications/initialized; nothing to return.
      return Optional.empty();
    }

    Object id = request.getValue("id");
    String method = request.getString("method");
    if (method == null) {
      return Optional.of(McpProtocol.error(id, McpProtocol.INVALID_REQUEST, "Missing method"));
    }

    return Optional.of(switch (method) {
      case McpProtocol.INITIALIZE -> McpProtocol.success(id, initializeResult());
      case McpProtocol.PING -> McpProtocol.success(id, new JsonObject());
      case McpProtocol.TOOLS_LIST -> McpProtocol.success(id,
        new JsonObject().put("tools", tools.toolDefinitions()));
      case McpProtocol.TOOLS_CALL -> handleToolsCall(id, request.getJsonObject("params"));
      default -> McpProtocol.error(id, McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + method);
    });
  }

  private JsonObject handleToolsCall(Object id, JsonObject params) {
    if (params == null || params.getString("name") == null) {
      return McpProtocol.error(id, McpProtocol.INVALID_PARAMS, "tools/call requires a 'name'");
    }
    JsonObject args = params.getJsonObject("arguments", new JsonObject());
    JsonObject result = tools.call(params.getString("name"), args);
    return McpProtocol.success(id, result);
  }

  private JsonObject initializeResult() {
    return new JsonObject()
      .put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
      .put("capabilities", new JsonObject().put("tools", new JsonObject()))
      .put("serverInfo", new JsonObject()
        .put("name", SERVER_NAME)
        .put("version", io.github.themoah.klag.health.VersionHandler.getVersion()));
  }

  /**
   * Checks whether a request is authorized given its Authorization header.
   *
   * @param authHeader the Authorization header value (may be null)
   * @return true if auth is disabled, or the bearer token matches
   */
  public boolean authorized(String authHeader) {
    if (!config.authEnabled()) {
      return true;
    }
    if (authHeader == null) {
      return false;
    }
    // Auth scheme is case-insensitive (RFC 7235) and surrounding/internal whitespace may
    // be normalized by clients/proxies. Match "Bearer" loosely, compare the token exactly.
    String[] parts = authHeader.trim().split("\\s+", 2);
    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
      return false;
    }
    return config.authToken().equals(parts[1]);
  }

  private static void writeJson(RoutingContext ctx, int status, JsonObject body) {
    ctx.response()
      .setStatusCode(status)
      .putHeader("content-type", CONTENT_TYPE_JSON)
      .end(body.encode());
  }
}
