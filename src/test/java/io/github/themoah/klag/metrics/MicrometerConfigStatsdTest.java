package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdFlavor;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Unit tests for the StatsD registry in {@link MicrometerConfig}.
 *
 * <p>Env vars can't be set in-process, so settings are supplied as JVM system properties,
 * which {@code Env} resolves after env vars. The UDP test binds a loopback socket as a
 * fake StatsD server and asserts that a registry built by the factory delivers a real gauge
 * line to it.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicrometerConfigStatsdTest {

  private static final List<String> PROPERTY_NAMES = Stream.of(
      "STATSD_HOST", "STATSD_PORT", "STATSD_FLAVOR")
    .flatMap(name -> Stream.of(name, name.toLowerCase(Locale.ROOT).replace('_', '.')))
    .toList();

  private final Map<String, String> originalProperties = new HashMap<>();

  @BeforeEach
  void saveAndClearProperties() {
    PROPERTY_NAMES.forEach(name -> {
      originalProperties.put(name, System.getProperty(name));
      System.clearProperty(name);
    });
  }

  @AfterEach
  void restoreProperties() {
    originalProperties.forEach((name, value) -> {
      if (value == null) {
        System.clearProperty(name);
      } else {
        System.setProperty(name, value);
      }
    });
  }

  @Test
  void defaultsToLocalDogStatsdAgent() {
    StatsdConfig config = MicrometerConfig.statsdConfigFromEnvironment();

    assertEquals("localhost", config.host());
    assertEquals(8125, config.port());
    assertEquals(StatsdFlavor.DATADOG, config.flavor());
  }

  @Test
  void readsExactNameProperties() {
    System.setProperty("STATSD_HOST", "statsd.internal");
    System.setProperty("STATSD_PORT", "9125");
    System.setProperty("STATSD_FLAVOR", "telegraf");

    StatsdConfig config = MicrometerConfig.statsdConfigFromEnvironment();

    assertEquals("statsd.internal", config.host());
    assertEquals(9125, config.port());
    assertEquals(StatsdFlavor.TELEGRAF, config.flavor());
  }

  @Test
  void readsDottedProperties() {
    System.setProperty("statsd.host", "agent");
    System.setProperty("statsd.port", "18125");
    System.setProperty("statsd.flavor", "sysdig");

    StatsdConfig config = MicrometerConfig.statsdConfigFromEnvironment();

    assertEquals("agent", config.host());
    assertEquals(18125, config.port());
    assertEquals(StatsdFlavor.SYSDIG, config.flavor());
  }

  @Test
  void flavorIsCaseInsensitiveAndTrimmed() {
    assertEquals(StatsdFlavor.TELEGRAF, MicrometerConfig.parseStatsdFlavor(" Telegraf "));
    assertEquals(StatsdFlavor.ETSY, MicrometerConfig.parseStatsdFlavor("ETSY"));
  }

  @Test
  @ResourceLock(Resources.LOCALE)
  void flavorParsingIgnoresDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      // Turkish uppercases "i" to dotted "İ", which would turn "sysdig" into "SYSDİG".
      Locale.setDefault(Locale.forLanguageTag("tr"));
      assertEquals(StatsdFlavor.SYSDIG, MicrometerConfig.parseStatsdFlavor("sysdig"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void invalidFlavorFallsBackToDatadog() {
    System.setProperty("STATSD_FLAVOR", "graphite");

    assertEquals(StatsdFlavor.DATADOG, MicrometerConfig.statsdConfigFromEnvironment().flavor());
  }

  @Test
  void nonNumericPortFallsBackToDefault() {
    System.setProperty("STATSD_PORT", "not-a-port");

    assertEquals(8125, MicrometerConfig.statsdConfigFromEnvironment().port());
  }

  @Test
  void outOfRangePortFallsBackToDefault() {
    System.setProperty("STATSD_PORT", "70000");
    assertEquals(8125, MicrometerConfig.statsdConfigFromEnvironment().port());

    System.setProperty("STATSD_PORT", "0");
    assertEquals(8125, MicrometerConfig.statsdConfigFromEnvironment().port());
  }

  /**
   * Drives the production path end to end: {@code createRegistry("statsd")} must send to the
   * port it reads from the environment. The fake server listens on an ephemeral port, so a
   * factory that ignored {@code STATSD_PORT} (e.g. a hardcoded 8125) never delivers.
   */
  @Test
  void factorySendsGaugeLineToConfiguredPort() throws Exception {
    try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(1_000);
      System.setProperty("STATSD_HOST", server.getLocalAddress().getHostAddress());
      System.setProperty("STATSD_PORT", Integer.toString(server.getLocalPort()));

      // The registry connects its UDP channel asynchronously and drops lines emitted before
      // that, so each attempt builds a fresh registry and waits longer before closing.
      String line = null;
      for (long settleMs = 50; line == null && settleMs <= 3_200; settleMs *= 2) {
        MeterRegistry registry = MicrometerConfig.createRegistry("statsd");
        assertInstanceOf(StatsdMeterRegistry.class, registry);
        Gauge.builder("klag.consumer.lag", new AtomicLong(100), AtomicLong::get)
          .tags("consumer_group", "orders", "topic", "payments")
          .strongReference(true)
          .register(registry);

        Thread.sleep(settleMs);
        // close() polls gauges and flushes the line buffer, so the test doesn't wait out
        // the 10s polling interval.
        registry.close();
        line = receiveLineStartingWith(server, "klag.consumer.lag:");
      }

      assertNotNull(line, "no StatsD line arrived on the configured port");
      assertTrue(line.startsWith("klag.consumer.lag:100|g|#"), line);
      assertTrue(line.contains("consumer_group:orders"), line);
      assertTrue(line.contains("topic:payments"), line);
    }
  }

  /**
   * Reads packets until one holds a matching line (a packet may carry several lines), or
   * returns null once the socket times out.
   */
  private static String receiveLineStartingWith(DatagramSocket server, String prefix)
      throws Exception {
    byte[] buffer = new byte[2048];
    while (true) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        server.receive(packet);
      } catch (SocketTimeoutException e) {
        return null;
      }
      String payload = new String(
          packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
      for (String line : payload.split("\n")) {
        if (line.startsWith(prefix)) {
          return line;
        }
      }
    }
  }
}
