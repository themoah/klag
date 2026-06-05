package io.github.themoah.klag.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration holder for the Kafka Admin Client.
 *
 * <p>Configuration is layered Spring Boot-style; later sources override earlier ones:
 * <ol>
 *   <li>Built-in defaults</li>
 *   <li>{@code application.properties} on the classpath (if present)</li>
 *   <li>External properties file referenced by {@code KLAG_CONFIG_FILE} (if set and readable)</li>
 *   <li>Environment variables — any {@code KAFKA_X_Y_Z} maps to {@code kafka.x.y.z}
 *       (lowercase, underscores become dots). For example
 *       {@code KAFKA_SECURITY_PROTOCOL} sets {@code kafka.security.protocol}.</li>
 * </ol>
 *
 * <p>Property values may contain {@code ${VAR}} or {@code ${VAR:default}} placeholders;
 * placeholders are resolved against environment variables (then other properties).
 */
public class KafkaClientConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaClientConfig.class);

  private static final String DEFAULT_CONFIG_FILE = "application.properties";
  private static final String PROP_BOOTSTRAP_SERVERS = "kafka.bootstrap.servers";
  private static final String PROP_REQUEST_TIMEOUT_MS = "kafka.request.timeout.ms";
  private static final String PROP_PREFIX = "kafka.";
  private static final String ENV_PREFIX = "KAFKA_";
  private static final String EXTERNAL_CONFIG_ENV = "KLAG_CONFIG_FILE";

  private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
  private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30000;

  private static final Pattern PLACEHOLDER_PATTERN =
    Pattern.compile("\\$\\{([^${}:]+)(?::([^}]*))?}");

  private static final int MAX_PLACEHOLDER_PASSES = 10;

  private final String bootstrapServers;
  private final int requestTimeoutMs;
  private final Map<String, String> additionalProperties;

  private KafkaClientConfig(Builder builder) {
    this.bootstrapServers = builder.bootstrapServers;
    this.requestTimeoutMs = builder.requestTimeoutMs;
    this.additionalProperties = new HashMap<>(builder.additionalProperties);
  }

  public String getBootstrapServers() {
    return bootstrapServers;
  }

  public int getRequestTimeoutMs() {
    return requestTimeoutMs;
  }

  public Map<String, String> toProperties() {
    Map<String, String> props = new HashMap<>();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(requestTimeoutMs));
    props.putAll(additionalProperties);
    return props;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Loads configuration with full layered precedence: defaults &lt; classpath
   * {@code application.properties} &lt; external file referenced by {@code KLAG_CONFIG_FILE}
   * &lt; {@code KAFKA_*} environment variables. Placeholders in values are resolved against
   * the process environment.
   */
  public static KafkaClientConfig load() {
    return load(System.getenv());
  }

  static KafkaClientConfig load(Map<String, String> env) {
    Properties props = new Properties();
    loadClasspathInto(props, DEFAULT_CONFIG_FILE);
    String externalPath = env.get(EXTERNAL_CONFIG_ENV);
    if (externalPath != null && !externalPath.isBlank()) {
      loadFileInto(props, Path.of(externalPath));
    }
    applyEnvironmentOverrides(props, env);
    resolvePlaceholders(props, env);
    return fromProperties(props);
  }

  /**
   * Loads configuration purely from environment variables (no classpath file).
   * Provided for backward compatibility; prefer {@link #load()}.
   */
  public static KafkaClientConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  static KafkaClientConfig fromEnvironment(Map<String, String> env) {
    Properties props = new Properties();
    applyEnvironmentOverrides(props, env);
    resolvePlaceholders(props, env);
    return fromProperties(props);
  }

  /**
   * Loads configuration from the default {@code application.properties} on the classpath
   * (without env var overrides). Provided for backward compatibility; prefer {@link #load()}.
   */
  public static KafkaClientConfig fromClasspath() throws IOException {
    return fromClasspath(DEFAULT_CONFIG_FILE);
  }

  public static KafkaClientConfig fromClasspath(String resourceName) throws IOException {
    log.info("Loading configuration from classpath: {}", resourceName);
    try (InputStream is =
        KafkaClientConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (is == null) {
        throw new IOException("Resource not found on classpath: " + resourceName);
      }
      Properties props = new Properties();
      props.load(is);
      resolvePlaceholders(props, System.getenv());
      return fromProperties(props);
    }
  }

  public static KafkaClientConfig fromFile(Path path) throws IOException {
    log.info("Loading configuration from file: {}", path);
    try (InputStream is = Files.newInputStream(path)) {
      Properties props = new Properties();
      props.load(is);
      resolvePlaceholders(props, System.getenv());
      return fromProperties(props);
    }
  }

  /**
   * Builds a {@link KafkaClientConfig} from an already-resolved {@link Properties} bag.
   * All {@code kafka.*} keys are pulled through; {@code kafka.bootstrap.servers} and
   * {@code kafka.request.timeout.ms} are surfaced as first-class fields, the rest pass
   * through to the AdminClient as additional properties.
   */
  public static KafkaClientConfig fromProperties(Properties props) {
    Builder builder = builder();

    String bootstrapServers = props.getProperty(PROP_BOOTSTRAP_SERVERS);
    if (bootstrapServers != null && !bootstrapServers.isBlank()) {
      builder.bootstrapServers(bootstrapServers);
    }

    String requestTimeout = props.getProperty(PROP_REQUEST_TIMEOUT_MS);
    if (requestTimeout != null && !requestTimeout.isBlank()) {
      try {
        builder.requestTimeoutMs(Integer.parseInt(requestTimeout));
      } catch (NumberFormatException e) {
        log.warn(
          "Invalid integer for {}: {}, using default: {}",
          PROP_REQUEST_TIMEOUT_MS, requestTimeout, DEFAULT_REQUEST_TIMEOUT_MS);
      }
    }

    for (String name : props.stringPropertyNames()) {
      if (name.startsWith(PROP_PREFIX)
          && !name.equals(PROP_BOOTSTRAP_SERVERS)
          && !name.equals(PROP_REQUEST_TIMEOUT_MS)) {
        String kafkaKey = name.substring(PROP_PREFIX.length());
        builder.property(kafkaKey, props.getProperty(name));
      }
    }

    log.info("Configuration loaded: bootstrapServers={}", builder.bootstrapServers);
    return builder.build();
  }

  // --- internals ---

  private static void loadClasspathInto(Properties props, String resourceName) {
    try (InputStream is =
        KafkaClientConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (is == null) {
        log.debug(
          "No {} on classpath; relying on environment variables and defaults", resourceName);
        return;
      }
      log.info("Loading configuration from classpath: {}", resourceName);
      props.load(is);
    } catch (IOException e) {
      log.warn("Failed to load {}: {}", resourceName, e.getMessage());
    }
  }

  /**
   * Layers properties from an external file path on top of the existing bag.
   * If the file is missing or unreadable, the failure is logged and the bag is unchanged —
   * the caller can still fall back to env vars / defaults.
   */
  static void loadFileInto(Properties props, Path path) {
    if (!Files.isReadable(path)) {
      log.warn("External config file not readable, skipping: {}", path);
      return;
    }
    try (InputStream is = Files.newInputStream(path)) {
      log.info("Loading configuration from external file: {}", path);
      props.load(is);
    } catch (IOException e) {
      log.warn("Failed to load external config file {}: {}", path, e.getMessage());
    }
  }

  /**
   * Layers {@code KAFKA_*} environment variables into the properties bag (env wins).
   * Each {@code KAFKA_X_Y_Z} env var becomes {@code kafka.x.y.z}.
   */
  static void applyEnvironmentOverrides(Properties props, Map<String, String> env) {
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String name = entry.getKey();
      if (name == null || !name.startsWith(ENV_PREFIX) || name.length() == ENV_PREFIX.length()) {
        continue;
      }
      String value = entry.getValue();
      if (value == null) {
        continue;
      }
      String propertyKey =
        PROP_PREFIX
          + name.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).replace('_', '.');
      props.setProperty(propertyKey, value);
    }
  }

  /**
   * Resolves {@code ${VAR}} and {@code ${VAR:default}} placeholders in property values.
   * Looks up the variable in the environment first, then in the properties bag itself.
   * Resolution runs as a fixed-point iteration (up to {@link #MAX_PLACEHOLDER_PASSES}
   * passes) so chained references — e.g. {@code A=${B}}, {@code B=${ENV_VAR}} — fully
   * resolve regardless of iteration order. Unresolved placeholders are left as-is;
   * cycles terminate at the pass cap.
   */
  static void resolvePlaceholders(Properties props, Map<String, String> env) {
    for (int pass = 0; pass < MAX_PLACEHOLDER_PASSES; pass++) {
      boolean changed = false;
      for (String name : props.stringPropertyNames()) {
        String value = props.getProperty(name);
        String resolved = resolveString(value, env, props);
        if (!Objects.equals(value, resolved)) {
          props.setProperty(name, resolved);
          changed = true;
        }
      }
      if (!changed) {
        return;
      }
    }
    log.warn(
      "Placeholder resolution did not stabilise after {} passes; possible cycle",
      MAX_PLACEHOLDER_PASSES);
  }

  private static String resolveString(String value, Map<String, String> env, Properties props) {
    if (value == null || value.indexOf("${") < 0) {
      return value;
    }
    Matcher m = PLACEHOLDER_PATTERN.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String var = m.group(1);
      String dflt = m.group(2);
      String replacement = env.get(var);
      if (replacement == null) {
        replacement = props.getProperty(var);
      }
      if (replacement == null) {
        replacement = dflt;
      }
      if (replacement == null) {
        replacement = m.group(0);
      }
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  public static class Builder {

    private String bootstrapServers = DEFAULT_BOOTSTRAP_SERVERS;
    private int requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    private final Map<String, String> additionalProperties = new HashMap<>();

    public Builder bootstrapServers(String bootstrapServers) {
      this.bootstrapServers =
        Objects.requireNonNull(bootstrapServers, "bootstrapServers cannot be null");
      return this;
    }

    public Builder requestTimeoutMs(int requestTimeoutMs) {
      this.requestTimeoutMs = requestTimeoutMs;
      return this;
    }

    public Builder property(String key, String value) {
      this.additionalProperties.put(key, value);
      return this;
    }

    public KafkaClientConfig build() {
      return new KafkaClientConfig(this);
    }
  }
}
