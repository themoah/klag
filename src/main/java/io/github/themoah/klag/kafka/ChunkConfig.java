package io.github.themoah.klag.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for Kafka request chunking.
 *
 * @param chunkCount number of chunks to split requests into (1 = no chunking)
 * @param chunkDelayMs delay in milliseconds between processing chunks
 */
public record ChunkConfig(
  int chunkCount,
  long chunkDelayMs
) {

  private static final Logger log = LoggerFactory.getLogger(ChunkConfig.class);

  private static final int DEFAULT_CHUNK_COUNT = 1;
  private static final long DEFAULT_CHUNK_DELAY_MS = 0;

  /**
   * Returns true if chunking is enabled (chunkCount > 1).
   */
  public boolean isChunkingEnabled() {
    return chunkCount > 1;
  }

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>KAFKA_CHUNK_COUNT - Number of chunks (default: 1, meaning no chunking)</li>
   *   <li>KAFKA_CHUNK_DELAY_MS - Delay between chunks in milliseconds (default: 0)</li>
   * </ul>
   */
  public static ChunkConfig fromEnvironment() {
    int chunkCount = parseInt("KAFKA_CHUNK_COUNT", DEFAULT_CHUNK_COUNT);
    long chunkDelayMs = parseLong("KAFKA_CHUNK_DELAY_MS", DEFAULT_CHUNK_DELAY_MS);

    if (chunkCount < 1) {
      log.warn("KAFKA_CHUNK_COUNT must be >= 1, using default: {}", DEFAULT_CHUNK_COUNT);
      chunkCount = DEFAULT_CHUNK_COUNT;
    }

    ChunkConfig config = new ChunkConfig(chunkCount, chunkDelayMs);
    log.info("Chunk config: chunkCount={}, chunkDelayMs={}, enabled={}",
      chunkCount, chunkDelayMs, config.isChunkingEnabled());

    return config;
  }

  private static int parseInt(String envVar, int defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", envVar, value, defaultValue);
      return defaultValue;
    }
  }

  private static long parseLong(String envVar, long defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", envVar, value, defaultValue);
      return defaultValue;
    }
  }
}
