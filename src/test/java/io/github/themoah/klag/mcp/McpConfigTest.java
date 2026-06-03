package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class McpConfigTest {

  private static McpConfig from(Map<String, String> env) {
    return McpConfig.from(env::get);
  }

  @Test
  void disabledByDefault() {
    assertFalse(from(Map.of()).enabled());
  }

  @Test
  void enabledWhenTrue() {
    assertTrue(from(Map.of("MCP_ENABLED", "true")).enabled());
  }

  @Test
  void defaultPathIsMcp() {
    assertEquals("/mcp", from(Map.of()).path());
  }

  @Test
  void pathGetsLeadingSlash() {
    assertEquals("/custom", from(Map.of("MCP_PATH", "custom")).path());
  }

  @Test
  void authDisabledWhenNoToken() {
    McpConfig c = from(Map.of("MCP_ENABLED", "true"));
    assertFalse(c.authEnabled());
  }

  @Test
  void authEnabledWhenTokenSet() {
    McpConfig c = from(Map.of("MCP_ENABLED", "true", "MCP_AUTH_TOKEN", "secret"));
    assertTrue(c.authEnabled());
    assertEquals("secret", c.authToken());
  }

  @Test
  void blankTokenTreatedAsNoAuth() {
    assertFalse(from(Map.of("MCP_AUTH_TOKEN", "   ")).authEnabled());
  }
}
