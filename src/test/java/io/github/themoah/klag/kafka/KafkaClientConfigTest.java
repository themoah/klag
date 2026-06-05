package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KafkaClientConfigTest {

  @Test
  void envBinding_mapsKafkaPrefixedVarsToKafkaProperties() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_BOOTSTRAP_SERVERS", "broker:9093");
    env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
    env.put("KAFKA_SASL_MECHANISM", "PLAIN");
    env.put("KAFKA_SSL_TRUSTSTORE_LOCATION", "/etc/certs/client.truststore.jks");

    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(env);

    assertEquals("broker:9093", config.getBootstrapServers());
    Map<String, String> props = config.toProperties();
    assertEquals("SASL_SSL", props.get("security.protocol"));
    assertEquals("PLAIN", props.get("sasl.mechanism"));
    assertEquals("/etc/certs/client.truststore.jks", props.get("ssl.truststore.location"));
  }

  @Test
  void envBinding_requestTimeoutParsedAsInt() {
    Map<String, String> env = Map.of("KAFKA_REQUEST_TIMEOUT_MS", "45000");

    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(env);

    assertEquals(45000, config.getRequestTimeoutMs());
    assertEquals(
      "45000",
      config.toProperties().get(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG));
  }

  @Test
  void envBinding_ignoresNonKafkaPrefix() {
    Map<String, String> env = Map.of(
      "HTTP_PORT", "9999",
      "PATH", "/usr/bin",
      "KAFKAFOO", "bar");

    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(env);

    Map<String, String> props = config.toProperties();
    assertFalse(props.containsKey("foo"));
    assertFalse(props.containsKey("kafkafoo"));
  }

  @Test
  void envBinding_skipsLoneKafkaPrefixAndNullValues() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_", "stray");
    env.put("KAFKA_SECURITY_PROTOCOL", null);
    env.put("KAFKA_BOOTSTRAP_SERVERS", "broker:9092");

    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(env);

    Map<String, String> props = config.toProperties();
    assertEquals("broker:9092", config.getBootstrapServers());
    assertNull(props.get("security.protocol"));
  }

  @Test
  void merge_envOverridesPropertiesFile() {
    Properties props = new Properties();
    props.setProperty("kafka.bootstrap.servers", "from-file:9092");
    props.setProperty("kafka.security.protocol", "PLAINTEXT");
    Map<String, String> env = Map.of(
      "KAFKA_BOOTSTRAP_SERVERS", "from-env:9092",
      "KAFKA_SASL_MECHANISM", "PLAIN");

    KafkaClientConfig.applyEnvironmentOverrides(props, env);
    KafkaClientConfig.resolvePlaceholders(props, env);
    KafkaClientConfig config = KafkaClientConfig.fromProperties(props);

    assertEquals("from-env:9092", config.getBootstrapServers());
    assertEquals("PLAINTEXT", config.toProperties().get("security.protocol"));
    assertEquals("PLAIN", config.toProperties().get("sasl.mechanism"));
  }

  @Test
  void placeholders_resolvedFromEnvironment() {
    Properties props = new Properties();
    props.setProperty(
      "kafka.sasl.jaas.config",
      "org.apache.kafka.common.security.plain.PlainLoginModule required "
        + "username=\"${SASL_USERNAME}\" password=\"${SASL_PASSWORD}\";");
    Map<String, String> env = Map.of(
      "SASL_USERNAME", "alice",
      "SASL_PASSWORD", "s3cret");

    KafkaClientConfig.resolvePlaceholders(props, env);
    KafkaClientConfig config = KafkaClientConfig.fromProperties(props);

    String jaas = config.toProperties().get("sasl.jaas.config");
    assertTrue(jaas.contains("username=\"alice\""), jaas);
    assertTrue(jaas.contains("password=\"s3cret\""), jaas);
  }

  @Test
  void placeholders_useDefaultWhenEnvMissing() {
    Properties props = new Properties();
    props.setProperty("kafka.client.id", "${CLIENT_ID:klag-default}");

    KafkaClientConfig.resolvePlaceholders(props, Map.of());
    KafkaClientConfig config = KafkaClientConfig.fromProperties(props);

    assertEquals("klag-default", config.toProperties().get("client.id"));
  }

  @Test
  void placeholders_leftAsIsWhenUnresolvable() {
    Properties props = new Properties();
    props.setProperty("kafka.client.id", "${MISSING_VAR}");

    KafkaClientConfig.resolvePlaceholders(props, Map.of());

    assertEquals("${MISSING_VAR}", props.getProperty("kafka.client.id"));
  }

  @Test
  void defaults_appliedWhenNoSourcesProvided() {
    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(Map.of());

    assertEquals("localhost:9092", config.getBootstrapServers());
    assertEquals(30000, config.getRequestTimeoutMs());
  }

  @Test
  void externalFile_overridesClasspathAndIsOverriddenByEnv(@TempDir Path tmp) throws Exception {
    Path external = tmp.resolve("application.properties");
    Files.writeString(
      external,
      """
      kafka.bootstrap.servers=from-file:9092
      kafka.security.protocol=SASL_PLAINTEXT
      kafka.client.id=from-file
      """);
    Map<String, String> env = Map.of(
      "KLAG_CONFIG_FILE", external.toString(),
      "KAFKA_CLIENT_ID", "from-env");

    KafkaClientConfig config = KafkaClientConfig.load(env);

    assertEquals("from-file:9092", config.getBootstrapServers());
    assertEquals("SASL_PLAINTEXT", config.toProperties().get("security.protocol"));
    assertEquals("from-env", config.toProperties().get("client.id"));
  }

  @Test
  void externalFile_missingPathIsIgnored() {
    Map<String, String> env = Map.of(
      "KLAG_CONFIG_FILE", "/does/not/exist.properties",
      "KAFKA_BOOTSTRAP_SERVERS", "broker:9092");

    KafkaClientConfig config = KafkaClientConfig.load(env);

    assertEquals("broker:9092", config.getBootstrapServers());
  }
}
