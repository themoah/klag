package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class McpConfigTest {

  private static final List<String> MCP_ENV_NAMES = List.of(
    "MCP_ENABLED", "MCP_AUTH_TOKEN", "MCP_PATH", "MCP_ALLOWED_ORIGINS");

  private static final List<String> PROPS = MCP_ENV_NAMES.stream()
    .flatMap(name -> Stream.of(name, name.toLowerCase(Locale.ROOT).replace('_', '.')))
    .toList();

  private final Map<String, String> originalProperties = new HashMap<>();

  @BeforeEach
  void saveAndClearProperties() {
    PROPS.forEach(name -> {
      originalProperties.put(name, System.getProperty(name));
      System.clearProperty(name);
    });
  }

  @AfterEach
  void restoreProperties() {
    originalProperties.forEach(McpConfigTest::restoreProperty);
  }

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

  @Test
  void noAllowedOriginsByDefault() {
    assertEquals(List.of(), from(Map.of()).allowedOrigins());
  }

  @Test
  void allowedOriginsAreSplitAndTrimmed() {
    McpConfig c = from(Map.of("MCP_ALLOWED_ORIGINS", " https://ops.example.com , ,http://localhost:3000 "));
    assertEquals(List.of("https://ops.example.com", "http://localhost:3000"), c.allowedOrigins());
  }

  @Test
  void fromEnvironmentReadsExactJvmProperties() {
    assumeMcpEnvUnset();
    System.setProperty("MCP_ENABLED", "true");
    System.setProperty("MCP_AUTH_TOKEN", "secret");
    System.setProperty("MCP_PATH", "agents");
    McpConfig c = McpConfig.fromEnvironment();
    assertTrue(c.enabled());
    assertTrue(c.authEnabled());
    assertEquals("secret", c.authToken());
    assertEquals("/agents", c.path());
  }

  @Test
  void fromEnvironmentReadsDottedJvmProperties() {
    assumeMcpEnvUnset();
    System.setProperty("mcp.enabled", "true");
    System.setProperty("mcp.auth.token", "dotted-secret");
    System.setProperty("mcp.allowed.origins", "https://ops.example.com");
    McpConfig c = McpConfig.fromEnvironment();
    assertTrue(c.enabled());
    assertEquals("dotted-secret", c.authToken());
    assertEquals(List.of("https://ops.example.com"), c.allowedOrigins());
  }

  private static void assumeMcpEnvUnset() {
    MCP_ENV_NAMES.forEach(name -> Assumptions.assumeTrue(envUnset(name),
      "process " + name + " would take precedence over JVM properties"));
  }

  private static boolean envUnset(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank();
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}
