package io.github.themoah.klag.metrics.hotpartition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class HotPartitionConfigTest {

  private static final String SIGMA = "HOT_PARTITION_SIGMA_MULTIPLIER";
  private static final String MIN_PARTITIONS = "HOT_PARTITION_MIN_PARTITIONS";
  private static final String MIN_SAMPLES = "HOT_PARTITION_MIN_SAMPLES";
  private static final String BUFFER_SIZE = "HOT_PARTITION_BUFFER_SIZE";

  @AfterEach
  void clearProperties() {
    System.clearProperty(SIGMA);
    System.clearProperty(MIN_PARTITIONS);
    System.clearProperty(MIN_SAMPLES);
    System.clearProperty(BUFFER_SIZE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "-Infinity"})
  void invalidSigmaFallsBackToDefault(String value) {
    System.setProperty(SIGMA, value);

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(2.0, config.sigmaMultiplier());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1", "-1"})
  void invalidMinPartitionsFallsBackToDefault(String value) {
    System.setProperty(MIN_PARTITIONS, value);

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(3, config.minPartitions());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1", "-1"})
  void invalidMinSamplesFallsBackToDefault(String value) {
    System.setProperty(MIN_SAMPLES, value);

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(3, config.minSamples());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1", "-1"})
  void invalidBufferSizeFallsBackToCompatibleDefaults(String value) {
    System.setProperty(BUFFER_SIZE, value);

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(3, config.minSamples());
    assertEquals(20, config.bufferSize());
  }

  @Test
  void bufferSmallerThanMinSamplesIsRaisedToMinSamples() {
    System.setProperty(MIN_SAMPLES, "30");
    System.setProperty(BUFFER_SIZE, "20");

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(30, config.minSamples());
    assertEquals(30, config.bufferSize());
  }

  @Test
  void boundaryValuesArePreserved() {
    System.setProperty(SIGMA, Double.toString(Double.MIN_VALUE));
    System.setProperty(MIN_PARTITIONS, "2");
    System.setProperty(MIN_SAMPLES, "2");
    System.setProperty(BUFFER_SIZE, "2");

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(Double.MIN_VALUE, config.sigmaMultiplier());
    assertEquals(2, config.minPartitions());
    assertEquals(2, config.minSamples());
    assertEquals(2, config.bufferSize());
  }

  @Test
  void validCustomValuesArePreserved() {
    System.setProperty(SIGMA, "3.5");
    System.setProperty(MIN_PARTITIONS, "10");
    System.setProperty(MIN_SAMPLES, "12");
    System.setProperty(BUFFER_SIZE, "50");

    HotPartitionConfig config = HotPartitionConfig.fromEnvironment();

    assertEquals(3.5, config.sigmaMultiplier());
    assertEquals(10, config.minPartitions());
    assertEquals(12, config.minSamples());
    assertEquals(50, config.bufferSize());
  }

  @Test
  void invalidValueLogsSettingAndFallback() {
    Logger logger = (Logger) LoggerFactory.getLogger(HotPartitionConfig.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      System.setProperty(SIGMA, "NaN");

      HotPartitionConfig.fromEnvironment();

      List<String> warnings = appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
      assertEquals(List.of(
        "HOT_PARTITION_SIGMA_MULTIPLIER must be finite and > 0, using default: 2.0"), warnings);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void directConstructionRemainsUnvalidated() {
    HotPartitionConfig config = new HotPartitionConfig(true, -1, 1, 10, 5);

    assertEquals(-1, config.sigmaMultiplier());
    assertEquals(1, config.minPartitions());
    assertEquals(10, config.minSamples());
    assertEquals(5, config.bufferSize());
  }
}
