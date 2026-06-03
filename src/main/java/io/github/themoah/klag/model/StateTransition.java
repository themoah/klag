package io.github.themoah.klag.model;

import io.github.themoah.klag.model.ConsumerGroupState.State;

/**
 * A single observed consumer-group state change.
 *
 * <p>Recorded whenever a group's state differs from the previously observed state, forming a
 * rolling history the MCP layer exposes so agents can spot rebalance storms and recent
 * EMPTY/DEAD transitions rather than only the current state.
 *
 * @param from the previous state
 * @param to the new state
 * @param timestampMs wall-clock time the change was observed
 */
public record StateTransition(State from, State to, long timestampMs) {}
