package io.github.themoah.klag.metrics.snapshot;

import io.github.themoah.klag.model.MetricsSnapshot;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free handoff for the latest {@link MetricsSnapshot}.
 *
 * <p>The metrics collector calls {@link #set} at the end of each cycle; the MCP layer calls
 * {@link #latest} to read. Backed by an {@link AtomicReference} so reads and writes never
 * block the Vert.x event loop and never contend with collection.
 */
public class SnapshotStore {

  private final AtomicReference<MetricsSnapshot> ref = new AtomicReference<>();

  /**
   * Publishes the latest snapshot, replacing any previous one.
   *
   * @param snapshot the snapshot to store
   */
  public void set(MetricsSnapshot snapshot) {
    ref.set(snapshot);
  }

  /**
   * Returns the most recently published snapshot, if any.
   *
   * @return the latest snapshot, or empty if none has been published yet
   */
  public Optional<MetricsSnapshot> latest() {
    return Optional.ofNullable(ref.get());
  }
}
