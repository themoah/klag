package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link KafkaClientServiceImpl#processOffsets} — covers null
 * handling (issue #27), topic-aggregated WARN output, and signature-based WARN
 * dedupe for steady-state idle/new groups (PR #28 review feedback).
 */
public class KafkaClientServiceImplOffsetsTest {

  private KafkaClientServiceImpl service;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    service = new KafkaClientServiceImpl();
    logger = (Logger) LoggerFactory.getLogger(KafkaClientServiceImpl.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    // Ensure DEBUG-and-up reach the appender so tests can assert absence/presence
    // of specific levels precisely.
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  private TopicPartition tp(String topic, int partition) {
    return new TopicPartition(topic, partition);
  }

  private OffsetAndMetadata om(long offset) {
    return new OffsetAndMetadata(offset, "");
  }

  private long countAtLevel(Level level) {
    return appender.list.stream().filter(e -> e.getLevel() == level).count();
  }

  private List<ILoggingEvent> eventsAtLevel(Level level) {
    return appender.list.stream().filter(e -> e.getLevel() == level).toList();
  }

  @Test
  void allCommitted_returnsAllOffsets_noWarn() {
    Map<TopicPartition, OffsetAndMetadata> input = new HashMap<>();
    input.put(tp("orders", 0), om(100L));
    input.put(tp("orders", 1), om(200L));

    ConsumerGroupOffsets result = service.processOffsets("g1", input);

    assertEquals(2, result.offsets().size());
    assertEquals(100L, result.offsets().get(new TopicPartitionKey("orders", 0)));
    assertEquals(200L, result.offsets().get(new TopicPartitionKey("orders", 1)));
    assertFalse(service.lastMissingByGroup.containsKey("g1"));
    assertEquals(0, countAtLevel(Level.WARN));
  }

  @Test
  void allMissing_emitsOneWarn_recordsSignature() {
    Map<TopicPartition, OffsetAndMetadata> input = new LinkedHashMap<>();
    input.put(tp("orders", 0), null);
    input.put(tp("orders", 1), null);
    input.put(tp("orders", 2), null);

    ConsumerGroupOffsets result = service.processOffsets("g1", input);

    assertTrue(result.offsets().isEmpty());
    assertEquals(Map.of("orders", 3), service.lastMissingByGroup.get("g1"));
    List<ILoggingEvent> warns = eventsAtLevel(Level.WARN);
    assertEquals(1, warns.size());
    assertTrue(warns.get(0).getFormattedMessage().contains("uncommitted partitions"));
    assertTrue(warns.get(0).getFormattedMessage().contains("orders=3"));
  }

  @Test
  void mixedNullAndCommitted_aggregatesAndWarns() {
    Map<TopicPartition, OffsetAndMetadata> input = new LinkedHashMap<>();
    input.put(tp("orders", 0), om(50L));
    input.put(tp("orders", 1), null);
    input.put(tp("payments", 0), null);
    input.put(tp("payments", 1), null);

    ConsumerGroupOffsets result = service.processOffsets("g1", input);

    assertEquals(1, result.offsets().size());
    assertEquals(50L, result.offsets().get(new TopicPartitionKey("orders", 0)));
    assertEquals(Map.of("orders", 1, "payments", 2), service.lastMissingByGroup.get("g1"));
    assertEquals(1, countAtLevel(Level.WARN));
  }

  @Test
  void dedupe_sameSignature_secondCallNoWarn() {
    Map<TopicPartition, OffsetAndMetadata> input = new LinkedHashMap<>();
    input.put(tp("orders", 0), null);
    input.put(tp("orders", 1), null);

    service.processOffsets("g1", input);
    appender.list.clear();
    service.processOffsets("g1", input);

    assertEquals(0, countAtLevel(Level.WARN));
    // DEBUG "unchanged" line should be emitted
    assertTrue(eventsAtLevel(Level.DEBUG).stream()
      .anyMatch(e -> e.getFormattedMessage().contains("uncommitted partitions unchanged")));
    assertEquals(Map.of("orders", 2), service.lastMissingByGroup.get("g1"));
  }

  @Test
  void signatureChange_emitsFreshWarn() {
    Map<TopicPartition, OffsetAndMetadata> first = new LinkedHashMap<>();
    first.put(tp("orders", 0), null);
    first.put(tp("orders", 1), null);
    service.processOffsets("g1", first);
    appender.list.clear();

    // New topic appears with uncommitted partitions
    Map<TopicPartition, OffsetAndMetadata> second = new LinkedHashMap<>();
    second.put(tp("orders", 0), null);
    second.put(tp("orders", 1), null);
    second.put(tp("payments", 0), null);
    service.processOffsets("g1", second);

    assertEquals(1, countAtLevel(Level.WARN));
    assertEquals(Map.of("orders", 2, "payments", 1), service.lastMissingByGroup.get("g1"));
  }

  @Test
  void recovery_emitsInfoAndClearsState() {
    Map<TopicPartition, OffsetAndMetadata> missing = new LinkedHashMap<>();
    missing.put(tp("orders", 0), null);
    service.processOffsets("g1", missing);
    appender.list.clear();

    Map<TopicPartition, OffsetAndMetadata> committed = new LinkedHashMap<>();
    committed.put(tp("orders", 0), om(1L));
    service.processOffsets("g1", committed);

    assertNull(service.lastMissingByGroup.get("g1"));
    List<ILoggingEvent> infos = eventsAtLevel(Level.INFO);
    assertTrue(infos.stream()
      .anyMatch(e -> e.getFormattedMessage().contains("all partitions now committed")));
    assertEquals(0, countAtLevel(Level.WARN));
  }

  @Test
  void partitionCountChange_treatedAsSignatureChange() {
    Map<TopicPartition, OffsetAndMetadata> first = new LinkedHashMap<>();
    first.put(tp("orders", 0), null);
    first.put(tp("orders", 1), null);
    first.put(tp("orders", 2), null);
    first.put(tp("orders", 3), null);
    service.processOffsets("g1", first);
    appender.list.clear();

    // Topic expanded from 4 to 8 partitions, none committed
    Map<TopicPartition, OffsetAndMetadata> second = new LinkedHashMap<>();
    for (int i = 0; i < 8; i++) {
      second.put(tp("orders", i), null);
    }
    service.processOffsets("g1", second);

    assertEquals(1, countAtLevel(Level.WARN));
    assertEquals(Map.of("orders", 8), service.lastMissingByGroup.get("g1"));
  }

  // Reviewer concern (PR #28 review): if processOffsets returns an empty
  // offsetMap, downstream aggregation must not divide by zero or otherwise
  // misbehave. ConsumerGroupLag.fromPartitions is the only aggregation path
  // that touches partition data; pin its empty-list contract here so the
  // safety guarantee is visible alongside the NPE-fix tests.
  @Test
  void emptyOffsetMap_downstreamAggregationIsSafe() {
    Map<TopicPartition, OffsetAndMetadata> input = new LinkedHashMap<>();
    input.put(tp("orders", 0), null);
    input.put(tp("orders", 1), null);

    ConsumerGroupOffsets result = service.processOffsets("g1", input);
    assertTrue(result.offsets().isEmpty());

    ConsumerGroupLag lag = ConsumerGroupLag.fromPartitions("g1", List.of());
    assertEquals(0L, lag.totalLag());
    assertEquals(0L, lag.maxLag());
    assertEquals(0L, lag.minLag());
    assertTrue(lag.partitions().isEmpty());
  }

  @Test
  void independentGroups_dedupeStateIsolated() {
    Map<TopicPartition, OffsetAndMetadata> missing = new LinkedHashMap<>();
    missing.put(tp("orders", 0), null);

    service.processOffsets("g1", missing);
    long warnsAfterG1 = countAtLevel(Level.WARN);
    service.processOffsets("g2", missing);

    // Group g2 hits the WARN path independently of g1's dedupe state.
    assertEquals(warnsAfterG1 + 1, countAtLevel(Level.WARN));
    assertTrue(service.lastMissingByGroup.containsKey("g1"));
    assertTrue(service.lastMissingByGroup.containsKey("g2"));
  }
}
