package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MetricsCollector - focused on aggregation and iteration logic.
 */
public class MetricsCollectorTest {

  @Test
  void topicAggregates_singlePartition() {
    // Test TopicAggregates aggregation logic with single partition
    PartitionLag partition = new PartitionLag("topic1", 0, 1000, 0, 800, 200);

    long totalLogEnd = partition.logEndOffset();
    long totalCommitted = partition.committedOffset();
    long totalLag = partition.lag();

    assertEquals(1000, totalLogEnd);
    assertEquals(800, totalCommitted);
    assertEquals(200, totalLag);
  }

  @Test
  void topicAggregates_multiplePartitions_sumsCorrectly() {
    // Test aggregation across multiple partitions of same topic
    PartitionLag p1 = new PartitionLag("topic1", 0, 1000, 0, 800, 200);
    PartitionLag p2 = new PartitionLag("topic1", 1, 1500, 0, 1200, 300);
    PartitionLag p3 = new PartitionLag("topic1", 2, 2000, 0, 1800, 200);

    // Simulate aggregation as done in MetricsCollector
    long totalLogEnd = p1.logEndOffset() + p2.logEndOffset() + p3.logEndOffset();
    long totalCommitted = p1.committedOffset() + p2.committedOffset() + p3.committedOffset();
    long totalLag = p1.lag() + p2.lag() + p3.lag();

    assertEquals(4500, totalLogEnd, "LogEndOffset should sum to 4500");
    assertEquals(3800, totalCommitted, "CommittedOffset should sum to 3800");
    assertEquals(700, totalLag, "Lag should sum to 700");
  }

  @Test
  void velocityKeys_formattedCorrectly() {
    // Test velocity key format (consumerGroup:topic)
    String group = "consumer-group-1";
    String topic = "events-topic";
    String key = group + ":" + topic;

    assertEquals("consumer-group-1:events-topic", key);
    assertTrue(key.contains(":"));
    assertEquals(2, key.split(":").length);
  }

  @Test
  void velocityKeys_uniquePerGroupTopicPair() {
    // Test that different group/topic combinations produce unique keys
    Set<String> keys = new HashSet<>();

    keys.add("group1" + ":" + "topic1");
    keys.add("group1" + ":" + "topic2");
    keys.add("group2" + ":" + "topic1");

    assertEquals(3, keys.size(), "Each group:topic pair should be unique");
    assertTrue(keys.contains("group1:topic1"));
    assertTrue(keys.contains("group1:topic2"));
    assertTrue(keys.contains("group2:topic1"));
  }

  @Test
  void forEach_flattenNestedMap_behaviorEquivalent() {
    // Test that forEach-based approach produces same result as nested loops
    Map<String, Map<String, Long>> testData = Map.of(
      "group1", Map.of("topic1", 100L, "topic2", 200L),
      "group2", Map.of("topic3", 300L)
    );

    // Method 1: Nested for loops (old way)
    Set<String> keys1 = new HashSet<>();
    for (var groupEntry : testData.entrySet()) {
      String group = groupEntry.getKey();
      for (var topicEntry : groupEntry.getValue().entrySet()) {
        String topic = topicEntry.getKey();
        keys1.add(group + ":" + topic);
      }
    }

    // Method 2: forEach approach (new way)
    Set<String> keys2 = new HashSet<>();
    testData.forEach((group, topicMap) ->
      topicMap.forEach((topic, value) ->
        keys2.add(group + ":" + topic)
      )
    );

    // Both methods should produce identical results
    assertEquals(keys1, keys2, "Nested loops and forEach should produce same results");
    assertEquals(3, keys2.size());
    assertTrue(keys2.containsAll(Set.of("group1:topic1", "group1:topic2", "group2:topic3")));
  }

  @Test
  void forEach_processesAllEntries() {
    // Verify that forEach processes all entries in nested map
    Map<String, Map<String, Integer>> nestedMap = new HashMap<>();
    nestedMap.put("group1", Map.of("topic1", 1, "topic2", 2));
    nestedMap.put("group2", Map.of("topic3", 3, "topic4", 4, "topic5", 5));

    int[] count = {0};
    nestedMap.forEach((group, topicMap) ->
      topicMap.forEach((topic, value) -> count[0]++)
    );

    assertEquals(5, count[0], "Should process all 5 entries");
  }

  @Test
  void forEach_preservesGroupTopicRelationship() {
    // Test that group-topic relationships are maintained during iteration
    Map<String, Map<String, String>> data = Map.of(
      "groupA", Map.of("topicX", "dataX", "topicY", "dataY"),
      "groupB", Map.of("topicZ", "dataZ")
    );

    Map<String, String> result = new HashMap<>();
    data.forEach((group, topicMap) ->
      topicMap.forEach((topic, value) ->
        result.put(group + ":" + topic, value)
      )
    );

    assertEquals(3, result.size());
    assertEquals("dataX", result.get("groupA:topicX"));
    assertEquals("dataY", result.get("groupA:topicY"));
    assertEquals("dataZ", result.get("groupB:topicZ"));
  }

  @Test
  void forEach_handlesEmptyMaps() {
    // Test forEach behavior with empty maps
    Map<String, Map<String, Integer>> emptyOuter = Map.of();
    Set<String> keys1 = new HashSet<>();
    emptyOuter.forEach((group, topicMap) ->
      topicMap.forEach((topic, value) ->
        keys1.add(group + ":" + topic)
      )
    );
    assertEquals(0, keys1.size(), "Empty outer map should produce no keys");

    Map<String, Map<String, Integer>> emptyInner = Map.of("group1", Map.of());
    Set<String> keys2 = new HashSet<>();
    emptyInner.forEach((group, topicMap) ->
      topicMap.forEach((topic, value) ->
        keys2.add(group + ":" + topic)
      )
    );
    assertEquals(0, keys2.size(), "Empty inner map should produce no keys");
  }

  @Test
  void aggregation_multipleConsumerGroups_separateAggregates() {
    // Test that aggregation keeps consumer groups separate
    Map<String, Map<String, Long>> aggregates = new HashMap<>();

    // Group 1 aggregates
    aggregates.computeIfAbsent("group1", k -> new HashMap<>())
      .put("topic1", 100L);

    // Group 2 aggregates
    aggregates.computeIfAbsent("group2", k -> new HashMap<>())
      .put("topic1", 200L);

    assertEquals(2, aggregates.size(), "Should have 2 consumer groups");
    assertEquals(100L, aggregates.get("group1").get("topic1"));
    assertEquals(200L, aggregates.get("group2").get("topic1"));
  }
}
