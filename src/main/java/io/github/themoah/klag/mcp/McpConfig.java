package io.github.themoah.klag.mcp;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the MCP server endpoint, loaded from environment variables.
 *
 * <p>MCP is opt-in ({@code MCP_ENABLED=false} by default) so existing deployments are
 * unaffected. When enabled, an optional {@code MCP_AUTH_TOKEN} turns on Bearer-token auth.
 *
 * @param enabled whether the MCP endpoint is registered
 * @param authToken bearer token required for requests (blank = open)
 * @param path HTTP path of the MCP endpoint (always leading-slashed)
 */
public record McpConfig(
  boolean enabled,
  String authToken,
  String path
) {
  private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

  private static final String DEFAULT_PATH = "/mcp";

  /**
   * Loads configuration from the process environment.
   *
   * @return the MCP configuration
   */
  public static McpConfig fromEnvironment() {
    return from(System::getenv);
  }

  /**
   * Loads configuration from an arbitrary key lookup (used by tests).
   *
   * @param env name to value lookup (may return null)
   * @return the MCP configuration
   */
  public static McpConfig from(Function<String, String> env) {
    boolean enabled = "true".equalsIgnoreCase(trimmed(env.apply("MCP_ENABLED")));
    String token = trimmed(env.apply("MCP_AUTH_TOKEN"));
    String rawPath = trimmed(env.apply("MCP_PATH"));

    String path = (rawPath == null || rawPath.isBlank()) ? DEFAULT_PATH : normalizePath(rawPath);

    McpConfig config = new McpConfig(enabled, token == null ? "" : token, path);
    if (enabled) {
      log.info("MCP endpoint enabled at {} (auth {})", path, config.authEnabled() ? "on" : "OFF");
      if (!config.authEnabled()) {
        log.warn("MCP endpoint is enabled WITHOUT authentication; set MCP_AUTH_TOKEN to require a bearer token");
      }
    }
    return config;
  }

  /**
   * Whether bearer-token authentication is required.
   *
   * @return true if a non-blank token is configured
   */
  public boolean authEnabled() {
    return authToken != null && !authToken.isBlank();
  }

  private static String normalizePath(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }

  private static String trimmed(String value) {
    return value == null ? null : value.trim();
  }
}
