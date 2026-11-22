package io.github.themoah.klag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the wiring of the local docker-compose stack (Prometheus + Grafana around Klag).
 * Checks the parsed configuration; it does not start containers.
 */
class DockerComposeStackTest {

  private static Map<String, Object> readYaml(String relative) throws Exception {
    String contents = Files.readString(Path.of(relative));
    Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(contents);
    return asMap(document);
  }

  private static Map<String, Object> asMap(Object value) {
    assertInstanceOf(Map.class, value);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) value;
    return result;
  }

  private static List<Object> asList(Object value) {
    assertInstanceOf(List.class, value);
    @SuppressWarnings("unchecked")
    List<Object> result = (List<Object>) value;
    return result;
  }

  @Test
  void composeWiresKraftKafkaAndMonitoringServices() throws Exception {
    Map<String, Object> services = asMap(readYaml("docker-compose.yaml").get("services"));
    Map<String, Object> kafka = asMap(services.get("kafka"));
    Map<String, Object> klag = asMap(services.get("klag"));
    Map<String, Object> prometheus = asMap(services.get("prometheus"));
    Map<String, Object> grafana = asMap(services.get("grafana"));
    Map<String, Object> kafkaEnvironment = asMap(kafka.get("environment"));

    assertEquals("confluentinc/cp-kafka:8.1.1", kafka.get("image"));
    assertEquals(1, kafkaEnvironment.get("KAFKA_NODE_ID"));
    assertEquals("broker,controller", kafkaEnvironment.get("KAFKA_PROCESS_ROLES"));
    assertEquals("1@kafka:29093", kafkaEnvironment.get("KAFKA_CONTROLLER_QUORUM_VOTERS"));
    assertEquals("CONTROLLER", kafkaEnvironment.get("KAFKA_CONTROLLER_LISTENER_NAMES"));
    assertTrue(kafkaEnvironment.get("KAFKA_LISTENERS").toString().contains("CONTROLLER://"),
        "KRaft controller listener missing");
    assertTrue(!services.containsKey("zookeeper"), "KRaft stack must not define Zookeeper");
    assertTrue(!kafkaEnvironment.containsKey("KAFKA_ZOOKEEPER_CONNECT"),
        "KRaft broker must not connect to Zookeeper");
    assertEquals("true", asMap(klag.get("environment")).get("METRICS_JVM_ENABLED"));
    assertTrue(asList(prometheus.get("volumes")).stream()
        .anyMatch(volume -> volume.toString().startsWith("./docker/prometheus/prometheus.yml:")),
        "Prometheus config not mounted into its service");
    assertTrue(asList(grafana.get("volumes")).stream()
        .anyMatch(volume -> volume.toString().startsWith("./docker/grafana/provisioning:")),
        "Grafana provisioning not mounted into its service");
    assertTrue(asList(grafana.get("volumes")).stream()
        .anyMatch(volume -> volume.toString().startsWith("./dashboard/klag-grafana-com.json:")),
        "Dashboard not mounted into Grafana");
    assertTrue(asList(prometheus.get("ports")).contains("127.0.0.1:9090:9090"),
        "Prometheus must only be published on host loopback");
    assertTrue(asList(grafana.get("ports")).contains("127.0.0.1:3000:3000"),
        "Grafana must only be published on host loopback");
  }

  @Test
  void prometheusScrapesKlagMetricsEndpoint() throws Exception {
    Map<String, Object> config = readYaml("docker/prometheus/prometheus.yml");
    Map<String, Object> job = asMap(asList(config.get("scrape_configs")).getFirst());
    Map<String, Object> staticConfig = asMap(asList(job.get("static_configs")).getFirst());

    assertEquals("klag", job.get("job_name"));
    assertEquals("/metrics", job.getOrDefault("metrics_path", "/metrics"));
    assertTrue(asList(staticConfig.get("targets")).contains("klag:8888"),
        "Klag scrape target missing from the klag job");
  }

  @Test
  void grafanaProvisionsPrometheusDatasourceAndDashboardPath() throws Exception {
    Map<String, Object> datasourceFile =
        readYaml("docker/grafana/provisioning/datasources/prometheus.yml");
    Map<String, Object> datasource = asMap(asList(datasourceFile.get("datasources")).getFirst());
    assertEquals("Prometheus", datasource.get("name"));
    assertEquals("http://prometheus:9090", datasource.get("url"));

    Map<String, Object> dashboardFile =
        readYaml("docker/grafana/provisioning/dashboards/klag.yml");
    Map<String, Object> provider = asMap(asList(dashboardFile.get("providers")).getFirst());
    Map<String, Object> options = asMap(provider.get("options"));
    assertEquals("klag", provider.get("name"));
    assertEquals("/var/lib/grafana/dashboards/klag", options.get("path"));
  }
}
