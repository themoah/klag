package io.github.themoah.klag.metrics.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotStoreTest {

  private static MetricsSnapshot snapshotWithGroup(String group) {
    GroupSnapshot gs = new GroupSnapshot(
      group, State.STABLE, 10, 5, 0,
      List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    return new MetricsSnapshot(1000L, List.of(gs), List.of());
  }

  @Test
  void emptyUntilFirstSet() {
    SnapshotStore store = new SnapshotStore();
    assertTrue(store.latest().isEmpty(), "store should be empty before first set");
  }

  @Test
  void returnsLastSetSnapshot() {
    SnapshotStore store = new SnapshotStore();
    MetricsSnapshot snap = snapshotWithGroup("g1");
    store.set(snap);

    Optional<MetricsSnapshot> latest = store.latest();
    assertTrue(latest.isPresent());
    assertEquals(1000L, latest.get().timestampMs());
    assertEquals(1, latest.get().groups().size());
  }

  @Test
  void overwritesPreviousSnapshot() {
    SnapshotStore store = new SnapshotStore();
    store.set(snapshotWithGroup("old"));
    store.set(snapshotWithGroup("new"));

    assertEquals("new", store.latest().get().groups().get(0).consumerGroup());
  }

  @Test
  void groupLookupByIdIsCaseSensitiveAndPresent() {
    MetricsSnapshot snap = snapshotWithGroup("payments");
    assertTrue(snap.group("payments").isPresent());
    assertFalse(snap.group("PAYMENTS").isPresent());
    assertFalse(snap.group("missing").isPresent());
  }
}
