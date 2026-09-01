package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class ChunkConfigTest {

  private static final String COUNT = "KAFKA_CHUNK_COUNT";
  private static final String DELAY = "KAFKA_CHUNK_DELAY_MS";

  @AfterEach
  void clearProperties() {
    System.clearProperty(COUNT);
    System.clearProperty(DELAY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1"})
  void invalidChunkCountFallsBackToDefault(String value) {
    System.setProperty(COUNT, value);

    ChunkConfig config = ChunkConfig.fromEnvironment();

    assertEquals(1, config.chunkCount());
    assertFalse(config.isChunkingEnabled());
  }

  @Test
  void negativeChunkDelayFallsBackToDefault() {
    System.setProperty(DELAY, "-1");

    ChunkConfig config = ChunkConfig.fromEnvironment();

    assertEquals(0, config.chunkDelayMs());
  }

  @Test
  void boundaryValuesArePreserved() {
    System.setProperty(COUNT, "1");
    System.setProperty(DELAY, "0");

    ChunkConfig config = ChunkConfig.fromEnvironment();

    assertEquals(1, config.chunkCount());
    assertEquals(0, config.chunkDelayMs());
    assertFalse(config.isChunkingEnabled());
  }

  @Test
  void validCustomValuesArePreserved() {
    System.setProperty(COUNT, "5");
    System.setProperty(DELAY, "100");

    ChunkConfig config = ChunkConfig.fromEnvironment();

    assertEquals(5, config.chunkCount());
    assertEquals(100, config.chunkDelayMs());
    assertTrue(config.isChunkingEnabled());
  }

  @Test
  void invalidValuesLogSettingsAndFallbacks() {
    Logger logger = (Logger) LoggerFactory.getLogger(ChunkConfig.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      System.setProperty(COUNT, "0");
      System.setProperty(DELAY, "-1");

      ChunkConfig.fromEnvironment();

      List<String> warnings = appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
      assertEquals(List.of(
        "KAFKA_CHUNK_COUNT must be >= 1, using default: 1",
        "KAFKA_CHUNK_DELAY_MS must be >= 0, using default: 0"), warnings);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void directConstructionRemainsUnvalidated() {
    ChunkConfig config = new ChunkConfig(0, -1);

    assertEquals(0, config.chunkCount());
    assertEquals(-1, config.chunkDelayMs());
  }
}
