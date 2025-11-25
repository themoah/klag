package io.github.themoah.klag.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration holder for Kafka Admin Client.
 */
public class KafkaClientConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaClientConfig.class);

  private static final String DEFAULT_CONFIG_FILE = "application.properties";
  private static final String PROP_BOOTSTRAP_SERVERS = "kafka.bootstrap.servers";
  private static final String PROP_REQUEST_TIMEOUT_MS = "kafka.request.timeout.ms";
  private static final String PROP_PREFIX = "kafka.";

  private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
  private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30000;

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

  public static KafkaClientConfig fromEnvironment() {
    return builder()
      .bootstrapServers(
        System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP_SERVERS)
      )
      .requestTimeoutMs(
        Integer.parseInt(
          System.getenv().getOrDefault("KAFKA_REQUEST_TIMEOUT_MS",
            String.valueOf(DEFAULT_REQUEST_TIMEOUT_MS))
        )
      )
      .build();
  }

  /**
   * Loads configuration from the default application.properties file on the classpath.
   *
   * @return KafkaClientConfig loaded from classpath
   * @throws IOException if the config file cannot be read
   */
  public static KafkaClientConfig fromClasspath() throws IOException {
    return fromClasspath(DEFAULT_CONFIG_FILE);
  }

  /**
   * Loads configuration from a properties file on the classpath.
   *
   * @param resourceName the name of the properties file on the classpath
   * @return KafkaClientConfig loaded from the resource
   * @throws IOException if the config file cannot be read
   */
  public static KafkaClientConfig fromClasspath(String resourceName) throws IOException {
    log.info("Loading configuration from classpath: {}", resourceName);
    try (InputStream is = KafkaClientConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (is == null) {
        throw new IOException("Resource not found on classpath: " + resourceName);
      }
      Properties props = new Properties();
      props.load(is);
      return fromProperties(props);
    }
  }

  /**
   * Loads configuration from a properties file at the given path.
   *
   * @param path the path to the properties file
   * @return KafkaClientConfig loaded from the file
   * @throws IOException if the file cannot be read
   */
  public static KafkaClientConfig fromFile(Path path) throws IOException {
    log.info("Loading configuration from file: {}", path);
    try (InputStream is = Files.newInputStream(path)) {
      Properties props = new Properties();
      props.load(is);
      return fromProperties(props);
    }
  }

  /**
   * Creates configuration from a Properties object.
   *
   * @param props the properties containing kafka.* configuration
   * @return KafkaClientConfig built from the properties
   */
  public static KafkaClientConfig fromProperties(Properties props) {
    Builder builder = builder();

    String bootstrapServers = props.getProperty(PROP_BOOTSTRAP_SERVERS);
    if (bootstrapServers != null && !bootstrapServers.isBlank()) {
      builder.bootstrapServers(bootstrapServers);
    }

    String requestTimeout = props.getProperty(PROP_REQUEST_TIMEOUT_MS);
    if (requestTimeout != null && !requestTimeout.isBlank()) {
      builder.requestTimeoutMs(Integer.parseInt(requestTimeout));
    }

    // Add any additional kafka.* properties (for security, etc.)
    for (String name : props.stringPropertyNames()) {
      if (name.startsWith(PROP_PREFIX)
          && !name.equals(PROP_BOOTSTRAP_SERVERS)
          && !name.equals(PROP_REQUEST_TIMEOUT_MS)) {
        // Convert kafka.security.protocol -> security.protocol
        String kafkaKey = name.substring(PROP_PREFIX.length());
        builder.property(kafkaKey, props.getProperty(name));
      }
    }

    log.info("Configuration loaded: bootstrapServers={}", builder.bootstrapServers);
    return builder.build();
  }

  public static class Builder {

    private String bootstrapServers = DEFAULT_BOOTSTRAP_SERVERS;
    private int requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    private final Map<String, String> additionalProperties = new HashMap<>();

    public Builder bootstrapServers(String bootstrapServers) {
      this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers cannot be null");
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
