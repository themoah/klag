package io.github.themoah.klag.kafka;

import io.github.themoah.klag.config.Env;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the list of Kafka clusters this process should scrape.
 *
 * <p>When {@code KAFKA_CLUSTERS} is a JSON array, each object is one cluster.
 * Otherwise a single cluster is built from {@link KafkaClientConfig#load()} plus
 * optional {@code KAFKA_CLUSTER_NAME}. Both settings are {@link Env}-backed
 * (env var, then {@code -DNAME}, then dotted {@code -Dname.dotted}).
 */
public final class KafkaClusters {

  private static final Logger log = LoggerFactory.getLogger(KafkaClusters.class);

  public static final String ENV_CLUSTERS = "KAFKA_CLUSTERS";
  public static final String ENV_CLUSTER_NAME = "KAFKA_CLUSTER_NAME";

  private KafkaClusters() {}

  public static List<KafkaClusterSpec> load() {
    return load(processSettings(System.getenv()), KafkaClientConfig.load());
  }

  /**
   * Overlays {@link Env} resolution for {@code KAFKA_CLUSTERS} and
   * {@code KAFKA_CLUSTER_NAME} onto a copy of {@code env}. Env-var values already
   * in the map win; otherwise {@code -DNAME} / dotted JVM properties apply.
   */
  static Map<String, String> processSettings(Map<String, String> env) {
    Map<String, String> settings = new HashMap<>(env);
    overlayEnv(settings, ENV_CLUSTERS);
    overlayEnv(settings, ENV_CLUSTER_NAME);
    return settings;
  }

  private static void overlayEnv(Map<String, String> settings, String name) {
    String existing = settings.get(name);
    if (existing != null && !existing.isBlank()) {
      return;
    }
    String resolved = Env.getString(name, null);
    if (resolved != null) {
      settings.put(name, resolved);
    }
  }

  static List<KafkaClusterSpec> load(Map<String, String> env, KafkaClientConfig defaults) {
    String json = env.get(ENV_CLUSTERS);
    if (json != null && !json.isBlank()) {
      List<KafkaClusterSpec> clusters = parseJson(json, defaults);
      log.info("Loaded {} Kafka cluster(s) from {}", clusters.size(), ENV_CLUSTERS);
      return clusters;
    }
    String name = blankToNull(env.get(ENV_CLUSTER_NAME));
    if (name != null) {
      log.info("Single Kafka cluster with {}: {}", ENV_CLUSTER_NAME, name);
    }
    return List.of(new KafkaClusterSpec(name, defaults, null, null));
  }

  private static List<KafkaClusterSpec> parseJson(String json, KafkaClientConfig defaults) {
    JsonArray array;
    try {
      array = new JsonArray(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(ENV_CLUSTERS + " must be a JSON array", e);
    }

    if (array.isEmpty()) {
      throw new IllegalArgumentException(ENV_CLUSTERS + " must contain at least one cluster");
    }

    List<KafkaClusterSpec> clusters = new ArrayList<>(array.size());
    Set<String> names = new HashSet<>();
    for (int i = 0; i < array.size(); i++) {
      Object raw = array.getValue(i);
      if (!(raw instanceof JsonObject obj)) {
        throw new IllegalArgumentException(
          ENV_CLUSTERS + "[" + i + "] must be a JSON object");
      }
      KafkaClusterSpec spec = parseCluster(i, obj, defaults);
      if (array.size() > 1 && !spec.hasClusterName()) {
        throw new IllegalArgumentException(
          ENV_CLUSTERS + "[" + i + "] requires a unique non-blank \"name\" "
            + "when more than one cluster is configured");
      }
      if (spec.hasClusterName() && !names.add(spec.name())) {
        throw new IllegalArgumentException(
          "Duplicate cluster name in " + ENV_CLUSTERS + ": " + spec.name());
      }
      clusters.add(spec);
    }
    return List.copyOf(clusters);
  }

  private static KafkaClusterSpec parseCluster(
      int index, JsonObject obj, KafkaClientConfig defaults) {
    String name = blankToNull(stringValue(obj, "name"));
    String bootstrapServers = stringValue(obj, "bootstrapServers");
    Integer requestTimeoutMs = intValue(obj, "requestTimeoutMs");
    String groupFilter = blankToNull(stringValue(obj, "groupFilter"));
    String groupExclude = blankToNull(stringValue(obj, "groupExclude"));

    Map<String, String> extra = new LinkedHashMap<>();
    Object propertiesRaw = obj.getValue("properties");
    if (propertiesRaw instanceof JsonObject properties) {
      flattenProperties("", properties, extra);
    } else if (propertiesRaw != null) {
      throw new IllegalArgumentException(
        ENV_CLUSTERS + "[" + index + "] properties must be a JSON object");
    }

    if (bootstrapServers == null || bootstrapServers.isBlank()) {
      throw new IllegalArgumentException(
        ENV_CLUSTERS + "[" + index + "] requires bootstrapServers");
    }

    KafkaClientConfig client = defaults.overlay(bootstrapServers, requestTimeoutMs, extra);
    return new KafkaClusterSpec(name, client, groupFilter, groupExclude);
  }

  /**
   * Flattens nested JSON objects to dotted AdminClient keys so Helm YAML
   * {@code security: { protocol: SSL }} and a quoted {@code security.protocol} key
   * both become {@code security.protocol=SSL}.
   */
  private static void flattenProperties(String prefix, JsonObject obj, Map<String, String> out) {
    for (String key : obj.fieldNames()) {
      Object value = obj.getValue(key);
      if (value == null) {
        continue;
      }
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      if (value instanceof JsonObject nested) {
        flattenProperties(path, nested, out);
      } else {
        out.put(path, String.valueOf(value));
      }
    }
  }

  private static String stringValue(JsonObject obj, String key) {
    Object raw = obj.getValue(key);
    if (raw == null) {
      return null;
    }
    if (raw instanceof String text) {
      return text;
    }
    throw new IllegalArgumentException(ENV_CLUSTERS + " field " + key + " must be a string");
  }

  private static Integer intValue(JsonObject obj, String key) {
    Object raw = obj.getValue(key);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.intValue();
    }
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(ENV_CLUSTERS + " field " + key + " is not an integer: " + text);
      }
    }
    throw new IllegalArgumentException(ENV_CLUSTERS + " field " + key + " must be an integer");
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }
}
