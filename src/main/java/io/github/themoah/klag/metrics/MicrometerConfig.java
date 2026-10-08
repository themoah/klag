package io.github.themoah.klag.metrics;

import io.github.themoah.klag.config.Env;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpHttpMetricsSender;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdFlavor;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating Micrometer registries.
 */
public final class MicrometerConfig {

  private static final Logger log = LoggerFactory.getLogger(MicrometerConfig.class);

  // Timeouts for the custom OTLP HTTP sender (used only on the extra-CA-trust path). Mirrors the
  // former PushRegistryConfig defaults, whose connectTimeout()/readTimeout() are deprecated in
  // Micrometer 1.16; the sender owns its timeouts now instead of reading them off the registry config.
  private static final Duration OTLP_CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration OTLP_READ_TIMEOUT = Duration.ofSeconds(10);

  private static final String DEFAULT_STATSD_HOST = "localhost";
  private static final int DEFAULT_STATSD_PORT = 8125;

  private MicrometerConfig() {}

  /**
   * Creates a Datadog meter registry configured from environment variables.
   */
  public static MeterRegistry createDatadogRegistry() {
    log.info("Creating Datadog meter registry");

    DatadogConfig config = new DatadogConfig() {
      @Override
      public String apiKey() {
        return System.getenv("DD_API_KEY");
      }

      @Override
      public String applicationKey() {
        return System.getenv("DD_APP_KEY");
      }

      @Override
      public String uri() {
        String site = System.getenv().getOrDefault("DD_SITE", "datadoghq.com");
        return "https://api." + site;
      }

      @Override
      public String get(String key) {
        return null;
      }
    };

    return new DatadogMeterRegistry(config, Clock.SYSTEM);
  }

  /**
   * Creates a Prometheus meter registry.
   */
  public static PrometheusMeterRegistry createPrometheusRegistry() {
    log.info("Creating Prometheus meter registry");
    return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  }

  /**
   * Creates an OTLP meter registry configured from environment variables.
   * Supports both standard OTEL_* env vars and custom OTLP_* env vars.
   *
   * Protocol: HTTP only (port 4318). TODO: Add gRPC support in the future.
   * Temporality: Cumulative only. TODO: Add delta temporality support.
   */
  public static MeterRegistry createOtlpRegistry() {
    log.info("Creating OTLP meter registry");

    OtlpConfig config = new OtlpConfig() {
      @Override
      public String url() {
        // Priority: OTLP_ENDPOINT > OTEL_EXPORTER_OTLP_METRICS_ENDPOINT >
        //           OTEL_EXPORTER_OTLP_ENDPOINT + /v1/metrics > default
        String url = System.getenv("OTLP_ENDPOINT");
        if (url == null || url.isBlank()) {
          url = System.getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT");
        }
        if (url == null || url.isBlank()) {
          String baseEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
          if (baseEndpoint != null && !baseEndpoint.isBlank()) {
            url = baseEndpoint.endsWith("/v1/metrics")
                ? baseEndpoint
                : baseEndpoint + "/v1/metrics";
          }
        }
        return (url != null && !url.isBlank()) ? url : "http://localhost:4318/v1/metrics";
      }

      @Override
      public AggregationTemporality aggregationTemporality() {
        // NOTE: Currently only CUMULATIVE is supported. Delta temporality can be
        // added in the future by reading OTLP_AGGREGATION_TEMPORALITY env var.
        return AggregationTemporality.CUMULATIVE;
      }

      @Override
      public Duration step() {
        // Priority: OTLP_STEP_MS > OTEL_METRIC_EXPORT_INTERVAL > default (60s)
        String stepMs = System.getenv("OTLP_STEP_MS");
        if (stepMs == null || stepMs.isBlank()) {
          String otelInterval = System.getenv("OTEL_METRIC_EXPORT_INTERVAL");
          if (otelInterval != null && !otelInterval.isBlank()) {
            try {
              return Duration.ofMillis(Long.parseLong(otelInterval));
            } catch (NumberFormatException e) {
              log.warn("Invalid OTEL_METRIC_EXPORT_INTERVAL: {}, using default 60s", otelInterval);
            }
          }
        } else {
          try {
            return Duration.ofMillis(Long.parseLong(stepMs));
          } catch (NumberFormatException e) {
            log.warn("Invalid OTLP_STEP_MS: {}, using default 60s", stepMs);
          }
        }
        return Duration.ofSeconds(60);
      }

      @Override
      public Map<String, String> headers() {
        // Priority: OTLP_HEADERS > OTEL_EXPORTER_OTLP_METRICS_HEADERS >
        //           OTEL_EXPORTER_OTLP_HEADERS
        String headers = System.getenv("OTLP_HEADERS");
        if (headers == null || headers.isBlank()) {
          headers = System.getenv("OTEL_EXPORTER_OTLP_METRICS_HEADERS");
        }
        if (headers == null || headers.isBlank()) {
          headers = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        }

        if (headers != null && !headers.isBlank()) {
          // Parse "key1=value1,key2=value2" format
          Map<String, String> headerMap = new java.util.HashMap<>();
          for (String header : headers.split(",")) {
            String[] parts = header.trim().split("=", 2);
            if (parts.length == 2) {
              headerMap.put(parts[0].trim(), parts[1].trim());
            } else {
              log.warn("Invalid header format (expected key=value): {}", header);
            }
          }
          return headerMap;
        }
        return Map.of();
      }

      @Override
      public Map<String, String> resourceAttributes() {
        Map<String, String> attributes = new java.util.HashMap<>();

        // OTEL_SERVICE_NAME takes priority
        String serviceName = System.getenv("OTEL_SERVICE_NAME");
        if (serviceName != null && !serviceName.isBlank()) {
          attributes.put("service.name", serviceName);
        }

        // Parse OTEL_RESOURCE_ATTRIBUTES (key1=value1,key2=value2)
        String otelAttrs = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
        if (otelAttrs != null && !otelAttrs.isBlank()) {
          for (String attr : otelAttrs.split(",")) {
            String[] parts = attr.trim().split("=", 2);
            if (parts.length == 2) {
              attributes.put(parts[0].trim(), parts[1].trim());
            }
          }
        }

        // OTLP_RESOURCE_ATTRIBUTES override
        String customAttrs = System.getenv("OTLP_RESOURCE_ATTRIBUTES");
        if (customAttrs != null && !customAttrs.isBlank()) {
          for (String attr : customAttrs.split(",")) {
            String[] parts = attr.trim().split("=", 2);
            if (parts.length == 2) {
              attributes.put(parts[0].trim(), parts[1].trim());
            }
          }
        }

        // Default service name if none provided
        if (!attributes.containsKey("service.name")) {
          attributes.put("service.name", "klag");
        }

        return attributes;
      }

      @Override
      public String get(String key) {
        return null; // Use defaults for other properties
      }
    };

    // When a CA-cert path is configured (OTLP_CA_CERT_PATH / OTEL_EXPORTER_OTLP_CERTIFICATE),
    // route exports through an HTTP sender whose SSLContext additively trusts those CAs so an
    // internally-signed HTTPS collector validates. Otherwise use the stock registry unchanged.
    Optional<SSLContext> tls = OtlpTls.sslContextFromEnvironment();
    OtlpMeterRegistry registry = tls
        .map(ctx -> OtlpMeterRegistry.builder(config)
            .metricsSender(new OtlpHttpMetricsSender(
                OtlpTls.httpSender(ctx, OTLP_CONNECT_TIMEOUT, OTLP_READ_TIMEOUT)))
            .build())
        .orElseGet(() -> new OtlpMeterRegistry(config, Clock.SYSTEM));
    log.info("OTLP registry created - endpoint: {}, temporality: {}, customCaTrust: {}",
             config.url(), config.aggregationTemporality(), tls.isPresent());
    return registry;
  }

  /**
   * Creates a StatsD meter registry that sends metric lines over UDP to the configured
   * StatsD agent host (Datadog Agent, Telegraf, etc.). DogStatsD is the {@code datadog} flavor.
   */
  public static MeterRegistry createStatsdRegistry() {
    StatsdConfig config = statsdConfigFromEnvironment();
    if (config.flavor() == StatsdFlavor.ETSY) {
      log.warn("STATSD_FLAVOR=etsy has no tag support; tags are folded into metric names");
    }
    log.info("StatsD registry created - endpoint: {}:{}, flavor: {}",
             config.host(), config.port(), config.flavor());
    return new StatsdMeterRegistry(config, Clock.SYSTEM);
  }

  /**
   * Reads StatsD settings once via {@link Env}, so -D properties work and invalid values
   * fall back to defaults with a warning instead of failing registry creation.
   */
  static StatsdConfig statsdConfigFromEnvironment() {
    String host = Env.getString("STATSD_HOST", DEFAULT_STATSD_HOST);
    int port = Env.getInt("STATSD_PORT", DEFAULT_STATSD_PORT);
    if (port < 1 || port > 65535) {
      log.warn("Invalid STATSD_PORT: {}, using default {}", port, DEFAULT_STATSD_PORT);
      port = DEFAULT_STATSD_PORT;
    }
    StatsdFlavor flavor = parseStatsdFlavor(Env.getString("STATSD_FLAVOR", "datadog"));

    int resolvedPort = port;
    return new StatsdConfig() {
      @Override
      public String host() {
        return host;
      }

      @Override
      public int port() {
        return resolvedPort;
      }

      @Override
      public StatsdFlavor flavor() {
        return flavor;
      }

      @Override
      public String get(String key) {
        return null; // Use defaults for other properties
      }
    };
  }

  static StatsdFlavor parseStatsdFlavor(String value) {
    try {
      return StatsdFlavor.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      log.warn("Invalid STATSD_FLAVOR: '{}', using default datadog", value);
      return StatsdFlavor.DATADOG;
    }
  }

  /**
   * Creates a meter registry based on the reporter type.
   *
   * @param reporterType the type of reporter ("datadog", "prometheus", etc.)
   * @return the configured MeterRegistry, or null if type is unknown
   */
  public static MeterRegistry createRegistry(String reporterType) {
    if (reporterType == null) {
      return null;
    }

    return switch (reporterType.toLowerCase()) {
      case "datadog" -> createDatadogRegistry();
      case "prometheus" -> createPrometheusRegistry();
      case "otlp" -> createOtlpRegistry();
      case "statsd" -> createStatsdRegistry();
      default -> {
        log.warn("Unknown reporter type: {}", reporterType);
        yield null;
      }
    };
  }

  /**
   * Binds JVM metrics (memory, GC, threads, CPU) to the given registry.
   *
   * @param registry the meter registry to bind JVM metrics to
   */
  public static void bindJvmMetrics(MeterRegistry registry) {
    log.info("Binding JVM metrics to registry");
    new JvmMemoryMetrics().bindTo(registry);
    new JvmGcMetrics().bindTo(registry);
    new JvmThreadMetrics().bindTo(registry);
    new ProcessorMetrics().bindTo(registry);
  }
}
