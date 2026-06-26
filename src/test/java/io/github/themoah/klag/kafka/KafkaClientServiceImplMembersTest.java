package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.MemberAssignment;
import io.vertx.kafka.admin.ConsumerGroupDescription;
import io.vertx.kafka.admin.MemberDescription;
import io.vertx.kafka.client.common.TopicPartition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaClientServiceImpl#partitionOwners} — flattens Kafka member
 * assignments into the (topic, partition) -> owning member lookup that drives the
 * kafka-lag-exporter-parity member labels.
 */
class KafkaClientServiceImplMembersTest {

  private static MemberDescription member(String consumerId, String clientId, String host,
      TopicPartition... tps) {
    return new MemberDescription(consumerId, clientId, host,
      new io.vertx.kafka.admin.MemberAssignment(Set.of(tps)));
  }

  @Test
  void flattensEachAssignedPartitionToItsOwner() {
    ConsumerGroupDescription desc = new ConsumerGroupDescription()
      .setGroupId("payments")
      .setMembers(List.of(
        member("c-1", "svc-a", "10.0.0.1",
          new TopicPartition("orders", 0), new TopicPartition("orders", 1)),
        member("c-2", "svc-b", "10.0.0.2",
          new TopicPartition("orders", 2))));

    Map<TopicPartitionKey, MemberAssignment> owners =
      KafkaClientServiceImpl.partitionOwners(desc);

    assertEquals(3, owners.size());
    assertEquals(new MemberAssignment("10.0.0.1", "c-1", "svc-a"),
      owners.get(new TopicPartitionKey("orders", 0)));
    assertEquals(new MemberAssignment("10.0.0.1", "c-1", "svc-a"),
      owners.get(new TopicPartitionKey("orders", 1)));
    assertEquals(new MemberAssignment("10.0.0.2", "c-2", "svc-b"),
      owners.get(new TopicPartitionKey("orders", 2)));
  }

  @Test
  void emptyGroupYieldsEmptyMap() {
    ConsumerGroupDescription desc = new ConsumerGroupDescription()
      .setGroupId("idle").setMembers(List.of());
    assertTrue(KafkaClientServiceImpl.partitionOwners(desc).isEmpty());
  }
}
