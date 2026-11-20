package io.github.themoah.klag;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the wiring of the local docker-compose stack (Prometheus + Grafana around Klag).
 * Checks the files reference each other correctly; it does not start containers.
 */
class DockerComposeStackTest {

  private static String read(String relative) throws Exception {
    return Files.readString(Path.of(relative));
  }

  @Test
  void composeDefinesPrometheusAndGrafana() throws Exception {
    String compose = read("docker-compose.yaml");
    assertTrue(compose.contains("\n  prometheus:\n"), "prometheus service missing");
    assertTrue(compose.contains("\n  grafana:\n"), "grafana service missing");
    assertTrue(compose.contains("./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml"),
        "prometheus config not mounted");
    assertTrue(compose.contains("./docker/grafana/provisioning:/etc/grafana/provisioning"),
        "grafana provisioning not mounted");
    assertTrue(compose.contains("./dashboard/klag-grafana-com.json:"),
        "dashboard not mounted into grafana");
  }

  @Test
  void prometheusScrapesKlagMetricsEndpoint() throws Exception {
    String prom = read("docker/prometheus/prometheus.yml");
    assertTrue(prom.contains("targets: [\"klag:8888\"]"), "klag scrape target missing");
    assertTrue(prom.contains("metrics_path: /metrics"), "metrics_path not /metrics");
  }

  @Test
  void grafanaProvisionsPrometheusDatasourceAndDashboardPath() throws Exception {
    String ds = read("docker/grafana/provisioning/datasources/prometheus.yml");
    assertTrue(ds.contains("url: http://prometheus:9090"), "datasource URL does not match service");
    String dash = read("docker/grafana/provisioning/dashboards/klag.yml");
    assertTrue(dash.contains("path: /var/lib/grafana/dashboards/klag"),
        "dashboard provider path does not match the compose mount");
  }
}
