package io.github.themoah.klag.metrics.timelag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TimeLagConfigTest {

  private static final String MIN_MESSAGES = "TIME_LAG_MIN_MESSAGES";
  private static final String BUFFER_SIZE = "TIME_LAG_INTERPOLATION_BUFFER_SIZE";
  private static final String STALE_THRESHOLD = "TIME_LAG_STALE_PRODUCER_THRESHOLD_MS";

  @AfterEach
  void clearProperties() {
    System.clearProperty(MIN_MESSAGES);
    System.clearProperty(BUFFER_SIZE);
    System.clearProperty(STALE_THRESHOLD);
  }

  @Test
  void invalidValuesFallBackToDefaults() {
    System.setProperty(MIN_MESSAGES, "-1");
    System.setProperty(BUFFER_SIZE, "1");
    System.setProperty(STALE_THRESHOLD, "0");

    TimeLagConfig config = TimeLagConfig.fromEnvironment();

    assertEquals(100, config.minLagMessages());
    assertEquals(60, config.interpolationBufferSize());
    assertEquals(180000, config.staleProducerThresholdMs());
  }

  @Test
  void negativeStaleThresholdFallsBackToDefault() {
    System.setProperty(STALE_THRESHOLD, "-1");

    TimeLagConfig config = TimeLagConfig.fromEnvironment();

    assertEquals(180000, config.staleProducerThresholdMs());
  }

  @Test
  void boundaryValuesArePreserved() {
    System.setProperty(MIN_MESSAGES, "0");
    System.setProperty(BUFFER_SIZE, "2");
    System.setProperty(STALE_THRESHOLD, "1");

    TimeLagConfig config = TimeLagConfig.fromEnvironment();

    assertEquals(0, config.minLagMessages());
    assertEquals(2, config.interpolationBufferSize());
    assertEquals(1, config.staleProducerThresholdMs());
  }

  @Test
  void validCustomValuesArePreserved() {
    System.setProperty(MIN_MESSAGES, "500");
    System.setProperty(BUFFER_SIZE, "120");
    System.setProperty(STALE_THRESHOLD, "300000");

    TimeLagConfig config = TimeLagConfig.fromEnvironment();

    assertEquals(500, config.minLagMessages());
    assertEquals(120, config.interpolationBufferSize());
    assertEquals(300000, config.staleProducerThresholdMs());
  }

  @Test
  void invalidValuesLogSettingsAndFallbacks() {
    Logger logger = (Logger) LoggerFactory.getLogger(TimeLagConfig.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      System.setProperty(MIN_MESSAGES, "-1");
      System.setProperty(BUFFER_SIZE, "1");
      System.setProperty(STALE_THRESHOLD, "0");

      TimeLagConfig.fromEnvironment();

      List<String> warnings = appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
      assertEquals(List.of(
        "TIME_LAG_MIN_MESSAGES must be >= 0, using default: 100",
        "TIME_LAG_INTERPOLATION_BUFFER_SIZE must be >= 2, using default: 60",
        "TIME_LAG_STALE_PRODUCER_THRESHOLD_MS must be > 0, using default: 180000"), warnings);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void directConstructionRemainsUnvalidated() {
    TimeLagConfig config = new TimeLagConfig(true, -1, 1, 0);

    assertEquals(-1, config.minLagMessages());
    assertEquals(1, config.interpolationBufferSize());
    assertEquals(0, config.staleProducerThresholdMs());
  }
}
