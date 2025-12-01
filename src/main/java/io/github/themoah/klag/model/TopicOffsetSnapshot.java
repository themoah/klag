package io.github.themoah.klag.model;

/**
 * Point-in-time snapshot of topic-level offsets (aggregated across partitions).
 */
public record TopicOffsetSnapshot(
  long timestamp,
  long logEndOffset,
  long committedOffset,
  long lag
) {}
