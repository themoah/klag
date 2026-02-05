package io.github.themoah.klag.model;

/**
 * A point in the (offset, timestamp) space for interpolation.
 * Used to track log end offset progression over time.
 *
 * @param offset the log end offset at the recorded time
 * @param timestamp the system timestamp when this offset was recorded (millis since epoch)
 */
public record OffsetTimestampPoint(long offset, long timestamp) {}
