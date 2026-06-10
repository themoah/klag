package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.themoah.klag.kafka.ChunkConfig;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.github.themoah.klag.metrics.timelag.TimeLagConfig;
import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that chunked collection (KAFKA_CHUNK_COUNT > 1) does not let one chunk
 * invalidate state accumulated by another chunk, and that one failed chunk neither
 * aborts the remaining chunks nor causes gauges to be deleted.
 */
@ExtendWith(VertxExtension.class)
class MetricsCollectorChunkingTest {

  /**
   * Stateful fake: offsets advance each cycle (cycle = one listConsumerGroups call),
   * Kafka message timestamps are always invalid (0) so lag_ms must use the poll-history
   * fallback, and describeConsumerGroups can be made to fail for selected groups.
   */
  private static class ChunkedFakeKafka implements KafkaClientService {
    final AtomicInteger cycle = new AtomicInteger();
    final Map<String, String> groupToTopic;
    final Map<String, Integer> topicPartitions;
    volatile Map<String, State> states = new HashMap<>();
    volatile Set<String> failingGroups = Set.of();

    ChunkedFakeKafka(Map<String, String> groupToTopic, Map<String, Integer> topicPartitions) {
      this.groupToTopic = groupToTopic;
      this.topicPartitions = topicPartitions;
      groupToTopic.keySet().forEach(g -> states.put(g, State.STABLE));
    }

    long logEnd() { return 100L * cycle.get(); }

    // committed lands inside the retained poll-history window from cycle 2 onward
    // (cycle 1: 50, cycle 2: 100 >= oldest recorded logEnd 100)
    long committed() { return logEnd() - 50L * cycle.get(); }

    @Override public Future<Set<String>> listConsumerGroups() {
      cycle.incrementAndGet();
      return Future.succeededFuture(Set.copyOf(groupToTopic.keySet()));
    }

    @Override public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
      String topic = groupToTopic.get(groupId);
      Map<TopicPartitionKey, Long> offsets = new HashMap<>();
      for (int p = 0; p < topicPartitions.get(topic); p++) {
        offsets.put(new TopicPartitionKey(topic, p), committed());
      }
      return Future.succeededFuture(new ConsumerGroupOffsets(groupId, offsets));
    }

    @Override public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
      List<PartitionOffsets> result = new ArrayList<>();
      for (int p = 0; p < topicPartitions.get(topic); p++) {
        // logEndTimestamp=0 and logStartTimestamp=0: Kafka anchors invalid -> fallback path
        result.add(new PartitionOffsets(topic, p, logEnd(), 0, 0, logEnd(), 0));
      }
      return Future.succeededFuture(result);
    }

    @Override public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
      for (String id : groupIds) {
        if (failingGroups.contains(id)) {
          return Future.failedFuture("injected describeConsumerGroups failure for " + id);
        }
      }
      Map<String, ConsumerGroupState> result = new HashMap<>();
      groupIds.forEach(id ->
        result.put(id, new ConsumerGroupState(id, states.getOrDefault(id, State.STABLE))));
      return Future.succeededFuture(result);
    }

    @Override public Future<Set<String>> listTopics() { return Future.succeededFuture(Set.of()); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<String> describeCluster() { return Future.succeededFuture("cluster"); }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  private static final Map<String, String> TWO_GROUPS =
    Map.of("group-a", "topic-a", "group-b", "topic-b");

  private MetricsCollector chunkedCollector(
      Vertx vertx, KafkaClientService kafka, MicrometerReporter reporter) {
    return new MetricsCollector(vertx, kafka, reporter, 60_000, "*", "",
      new LagVelocityTracker(),
      new HotPartitionConfig(false, 2.0, 3, 3, 20),
      new TimeLagConfig(true, 100, 60, 180_000),
      new ChunkConfig(2, 0));
  }

  @Test
  void stateChangeHistorySurvivesOtherChunks(Vertx vertx, VertxTestContext ctx) {
    ChunkedFakeKafka kafka = new ChunkedFakeKafka(TWO_GROUPS,
      Map.of("topic-a", 1, "topic-b", 1));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);
    MetricsCollector collector = chunkedCollector(vertx, kafka, reporter);

    collector.collectOnce()
      .compose(v -> {
        Map<String, State> changed = new HashMap<>(kafka.states);
        changed.put("group-a", State.EMPTY);
        kafka.states = changed;
        return collector.collectOnce();
      })
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        // With per-chunk cleanup, group-b's chunk wipes group-a's state history every
        // cycle, so the change is treated as a first observation (0) and no transition
        // is recorded. The correct values: change count 1, one stable->empty transition.
        Gauge stateGauge = registry.find("klag.consumer.group.state")
          .tag("consumer_group", "group-a")
          .tag("state", "empty")
          .gauge();
        assertNotNull(stateGauge, "state gauge for group-a/empty must exist");
        assertEquals(1.0, stateGauge.value(),
          "state change count must survive the other chunk's cleanup");
        assertFalse(reporter.recentStateTransitions("group-a").isEmpty(),
          "transition history must survive the other chunk's cleanup");
        ctx.completeNow();
      })));
  }

  @Test
  void lagMsFallbackHistorySurvivesOtherChunks(Vertx vertx, VertxTestContext ctx) {
    ChunkedFakeKafka kafka = new ChunkedFakeKafka(TWO_GROUPS,
      Map.of("topic-a", 1, "topic-b", 1));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MetricsCollector collector = chunkedCollector(vertx, kafka, new MicrometerReporter(registry));

    // Fallback interpolation needs 2 poll samples per partition. With per-chunk cleanup
    // each chunk wipes the other chunk's poll history, so no partition ever has 2 samples
    // and klag.consumer.lag.ms stays empty for every topic.
    collector.collectOnce()
      .compose(v -> collector.collectOnce())
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        for (String group : TWO_GROUPS.keySet()) {
          assertNotNull(
            registry.find("klag.consumer.lag.ms").tag("consumer_group", group).gauge(),
            "lag_ms gauge must exist for " + group + " after two cycles");
        }
        ctx.completeNow();
      })));
  }

  @Test
  void failedChunkDoesNotAbortRemainingChunksOrDeleteGauges(Vertx vertx, VertxTestContext ctx) {
    // topic-a has 2 partitions so after cycle 1 group-a is the heaviest group and is
    // deterministically placed (and processed) first; its failure must not stop group-b.
    ChunkedFakeKafka kafka = new ChunkedFakeKafka(TWO_GROUPS,
      Map.of("topic-a", 2, "topic-b", 1));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MetricsCollector collector = chunkedCollector(vertx, kafka, new MicrometerReporter(registry));

    collector.collectOnce()
      .compose(v -> {
        kafka.failingGroups = Set.of("group-a");
        return collector.collectOnce(); // cycle 2: group-a's chunk fails
      })
      .compose(v -> collector.collectOnce()) // cycle 3: still failing
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        // group-b must have been collected in cycle 3 (lag per partition = 50 * cycle).
        Gauge lagSumB = registry.find("klag.consumer.lag.sum")
          .tag("consumer_group", "group-b").gauge();
        assertNotNull(lagSumB, "group-b lag gauge must exist");
        assertEquals(150.0, lagSumB.value(),
          "group-b must still be collected after group-a's chunk fails");

        // group-a's gauges must survive two consecutive failed cycles: a partial cycle
        // must not delete series (stale beats deleted for monitoring continuity).
        Gauge lagSumA = registry.find("klag.consumer.lag.sum")
          .tag("consumer_group", "group-a").gauge();
        assertNotNull(lagSumA, "group-a gauges must not be deleted on partial cycles");
        assertEquals(100.0, lagSumA.value(), "group-a keeps its last good value (cycle 1)");
        ctx.completeNow();
      })));
  }
}
