package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class KafkaClustersTest {

  @Test
  void singletonFromBootstrapEnvAndOptionalName() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_BOOTSTRAP_SERVERS", "broker:9092");
    env.put("KAFKA_CLUSTER_NAME", "msk-a");
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    List<KafkaClusterSpec> clusters = KafkaClusters.load(env, defaults);

    assertEquals(1, clusters.size());
    assertEquals("msk-a", clusters.get(0).name());
    assertEquals("broker:9092", clusters.get(0).clientConfig().getBootstrapServers());
    assertNull(clusters.get(0).groupFilter());
  }

  @Test
  void jsonArrayOverridesBootstrapAndInheritsSasl() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_BOOTSTRAP_SERVERS", "unused:9092");
    env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
    env.put("KAFKA_CLUSTERS", """
      [
        {"name":"msk-a","bootstrapServers":"a:9092"},
        {"name":"msk-b","bootstrapServers":"b:9092","groupFilter":"app-*"}
      ]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    List<KafkaClusterSpec> clusters = KafkaClusters.load(env, defaults);

    assertEquals(2, clusters.size());
    assertEquals("msk-a", clusters.get(0).name());
    assertEquals("a:9092", clusters.get(0).clientConfig().getBootstrapServers());
    assertEquals("SASL_SSL", clusters.get(0).clientConfig().toProperties().get("security.protocol"));
    assertEquals("msk-b", clusters.get(1).name());
    assertEquals("b:9092", clusters.get(1).clientConfig().getBootstrapServers());
    assertEquals("app-*", clusters.get(1).resolvedGroupFilter("*"));
    assertEquals("*", clusters.get(0).resolvedGroupFilter("*"));
  }

  @Test
  void reservedEnvNamesAreNotAdminClientProperties() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_BOOTSTRAP_SERVERS", "broker:9092");
    env.put("KAFKA_CLUSTER_NAME", "msk-a");
    env.put("KAFKA_CLUSTERS", "[]");
    KafkaClientConfig config = KafkaClientConfig.fromEnvironment(env);

    Map<String, String> props = config.toProperties();
    assertFalse(props.containsKey("clusters"));
    assertFalse(props.containsKey("cluster.name"));
  }

  @Test
  void jsonRequiresUniqueNamesWhenMultipleClusters() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS", """
      [
        {"name":"same","bootstrapServers":"a:9092"},
        {"name":"same","bootstrapServers":"b:9092"}
      ]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    assertThrows(IllegalArgumentException.class, () -> KafkaClusters.load(env, defaults));
  }

  @Test
  void jsonRequiresNameWhenMultipleClusters() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS", """
      [
        {"bootstrapServers":"a:9092"},
        {"name":"b","bootstrapServers":"b:9092"}
      ]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    assertThrows(IllegalArgumentException.class, () -> KafkaClusters.load(env, defaults));
  }

  @Test
  void invalidJsonFailsFast() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS", "{not-an-array}");
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());

    assertThrows(IllegalArgumentException.class, () -> KafkaClusters.load(env, defaults));
  }

  @Test
  void jsonRequiresBootstrapServersEvenWhenProcessDefaultExists() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_BOOTSTRAP_SERVERS", "default:9092");
    env.put("KAFKA_CLUSTERS", """
      [{"name":"msk-a"},{"name":"msk-b","bootstrapServers":"b:9092"}]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    assertThrows(IllegalArgumentException.class, () -> KafkaClusters.load(env, defaults));
  }

  @Test
  void nestedPropertiesFlattenToDottedAdminKeys() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS", """
      [{"name":"secure","bootstrapServers":"a:9093",
        "properties":{"security":{"protocol":"SSL"}}}]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());

    KafkaClusterSpec spec = KafkaClusters.load(env, defaults).get(0);
    assertEquals("SSL", spec.clientConfig().toProperties().get("security.protocol"));
  }

  @Test
  void helmRenderedClustersJsonParses() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS",
      "[{\"bootstrapServers\":\"a.example.com:9092\",\"name\":\"msk-a\"},"
        + "{\"bootstrapServers\":\"b.example.com:9092\",\"groupFilter\":\"app-*\","
        + "\"name\":\"msk-b\"}]");
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());

    List<KafkaClusterSpec> clusters = KafkaClusters.load(env, defaults);
    assertEquals(2, clusters.size());
    assertEquals("msk-a", clusters.get(0).name());
    assertEquals("app-*", clusters.get(1).groupFilter());
  }

  @Test
  void requestTimeoutMsAcceptsJsonString() {
    Map<String, String> env = Map.of("KAFKA_CLUSTERS", """
      [{"name":"a","bootstrapServers":"a:9092","requestTimeoutMs":"45000"}]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());

    assertEquals(45000, KafkaClusters.load(env, defaults).get(0).clientConfig().getRequestTimeoutMs());
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void jvmExactPropertySuppliesClusterNameWhenEnvUnset() {
    Assumptions.assumeTrue(envUnset(KafkaClusters.ENV_CLUSTER_NAME));
    Assumptions.assumeTrue(envUnset(KafkaClusters.ENV_CLUSTERS),
      "process KAFKA_CLUSTERS would take precedence over a JVM cluster name");
    String exact = KafkaClusters.ENV_CLUSTER_NAME;
    String dotted = "kafka.cluster.name";
    String previousExact = System.getProperty(exact);
    String previousDotted = System.getProperty(dotted);
    try {
      System.clearProperty(dotted);
      System.setProperty(exact, "from-d");
      Map<String, String> settings = KafkaClusters.processSettings(Map.of());
      KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());
      assertEquals("from-d", KafkaClusters.load(settings, defaults).get(0).name());
    } finally {
      restoreProperty(exact, previousExact);
      restoreProperty(dotted, previousDotted);
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void dottedJvmPropertySuppliesClustersJsonWhenEnvUnset() {
    Assumptions.assumeTrue(envUnset(KafkaClusters.ENV_CLUSTERS));
    Assumptions.assumeTrue(envUnset(KafkaClusters.ENV_CLUSTER_NAME));
    String exact = KafkaClusters.ENV_CLUSTERS;
    String dotted = "kafka.clusters";
    String previousExact = System.getProperty(exact);
    String previousDotted = System.getProperty(dotted);
    try {
      System.clearProperty(exact);
      System.setProperty(dotted,
        "[{\"name\":\"from-dotted\",\"bootstrapServers\":\"d:9092\"}]");
      Map<String, String> settings = KafkaClusters.processSettings(Map.of());
      KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());
      List<KafkaClusterSpec> clusters = KafkaClusters.load(settings, defaults);
      assertEquals(1, clusters.size());
      assertEquals("from-dotted", clusters.get(0).name());
      assertEquals("d:9092", clusters.get(0).clientConfig().getBootstrapServers());
    } finally {
      restoreProperty(exact, previousExact);
      restoreProperty(dotted, previousDotted);
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void envMapWinsOverJvmPropertyForClusterName() {
    Assumptions.assumeTrue(envUnset(KafkaClusters.ENV_CLUSTERS),
      "process KAFKA_CLUSTERS would ignore the injected cluster name");
    String previous = System.getProperty(KafkaClusters.ENV_CLUSTER_NAME);
    try {
      System.setProperty(KafkaClusters.ENV_CLUSTER_NAME, "from-d");
      Map<String, String> settings = KafkaClusters.processSettings(
        Map.of(KafkaClusters.ENV_CLUSTER_NAME, "from-env"));
      KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(Map.of());
      assertEquals("from-env", KafkaClusters.load(settings, defaults).get(0).name());
    } finally {
      restoreProperty(KafkaClusters.ENV_CLUSTER_NAME, previous);
    }
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

  @Test
  void perClusterPropertiesOverlayGlobals() {
    Map<String, String> env = new HashMap<>();
    env.put("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT");
    env.put("KAFKA_CLUSTERS", """
      [{"name":"secure","bootstrapServers":"a:9093",
        "properties":{"security.protocol":"SSL"}}]
      """);
    KafkaClientConfig defaults = KafkaClientConfig.fromEnvironment(env);

    KafkaClusterSpec spec = KafkaClusters.load(env, defaults).get(0);
    assertEquals("SSL", spec.clientConfig().toProperties().get("security.protocol"));
    assertTrue(spec.hasClusterName());
  }
}
