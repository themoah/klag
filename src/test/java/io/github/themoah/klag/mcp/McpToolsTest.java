package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.LagTrend;
import io.github.themoah.klag.model.LagTrend.Direction;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.StateTransition;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class McpToolsTest {

  private static GroupSnapshot group(String name, State state, long lag, double velocity, double retention) {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000 - lag);
    return new GroupSnapshot(name, state, lag, lag, 0, List.of(p),
      List.of(new LagVelocity(name, "orders", velocity, 1000, 3)),
      List.of(), List.of(),
      List.of(new RetentionRisk(name, "orders", retention)),
      List.of());
  }

  private static GroupSnapshot groupWithTrend(String name, long lag, Direction dir, double velocity,
      List<StateTransition> transitions) {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000 - lag);
    return new GroupSnapshot(name, State.STABLE, lag, lag, 0, List.of(p),
      List.of(new LagVelocity(name, "orders", velocity, 1000, 3)),
      List.of(), List.of(), List.of(), List.of(),
      transitions,
      List.of(new LagTrend("orders", dir, velocity)),
      dir);
  }

  private static SnapshotStore storeWith(GroupSnapshot... groups) {
    SnapshotStore store = new SnapshotStore();
    store.set(new MetricsSnapshot(1000L, List.of(groups), List.of()));
    return store;
  }

  private static String textOf(JsonObject result) {
    return result.getJsonArray("content").getJsonObject(0).getString("text");
  }

  @Test
  void toolDefinitionsExposeFourReadOnlyTools() {
    McpTools tools = new McpTools(new SnapshotStore());
    JsonArray defs = tools.toolDefinitions();

    assertEquals(4, defs.size());
    for (int i = 0; i < defs.size(); i++) {
      JsonObject t = defs.getJsonObject(i);
      assertTrue(t.containsKey("name"));
      assertTrue(t.containsKey("inputSchema"));
      assertTrue(t.getJsonObject("annotations").getBoolean("readOnlyHint"));
    }
  }

  @Test
  void unknownToolIsError() {
    McpTools tools = new McpTools(storeWith(group("g", State.STABLE, 0, 0, 0)));
    JsonObject r = tools.call("bogus", new JsonObject());
    assertTrue(r.getBoolean("isError"));
  }

  @Test
  void notReadyBeforeFirstSnapshot() {
    McpTools tools = new McpTools(new SnapshotStore());
    JsonObject r = tools.call("list_consumer_groups", new JsonObject());
    assertFalse(r.getBoolean("isError"));
    assertTrue(textOf(r).toLowerCase(Locale.ROOT).contains("not ready"));
  }

  @Test
  void listConsumerGroupsNamesAllGroups() {
    McpTools tools = new McpTools(storeWith(
      group("payments", State.STABLE, 50, 1, 5),
      group("billing", State.EMPTY, 200, 0, 10)));

    String text = textOf(tools.call("list_consumer_groups", new JsonObject()));
    assertTrue(text.contains("payments"));
    assertTrue(text.contains("billing"));
  }

  @Test
  void getConsumerGroupLagRequiresGroupArg() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 50, 1, 5)));
    JsonObject r = tools.call("get_consumer_group_lag", new JsonObject());
    assertTrue(r.getBoolean("isError"));
  }

  @Test
  void getConsumerGroupLagUnknownGroupIsError() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 50, 1, 5)));
    JsonObject r = tools.call("get_consumer_group_lag", new JsonObject().put("group", "ghost"));
    assertTrue(r.getBoolean("isError"));
    assertTrue(textOf(r).toLowerCase(Locale.ROOT).contains("not found"));
  }

  @Test
  void diagnoseUnknownGroupIsError() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 50, 1, 5)));
    JsonObject r = tools.call("diagnose", new JsonObject().put("group", "ghost"));
    assertTrue(r.getBoolean("isError"));
    assertTrue(textOf(r).toLowerCase(Locale.ROOT).contains("not found"));
  }

  @Test
  void getConsumerGroupLagReturnsDetail() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 50, 1, 5)));
    String text = textOf(tools.call("get_consumer_group_lag", new JsonObject().put("group", "payments")));
    assertTrue(text.contains("payments"));
    assertTrue(text.contains("orders"));
    assertTrue(text.contains("50"));
  }

  @Test
  void getConsumerGroupLagIncludesTrendAndTransitions() {
    StateTransition t = new StateTransition(State.STABLE, State.EMPTY, 500L);
    McpTools tools = new McpTools(storeWith(
      groupWithTrend("payments", 5000, Direction.GROWING, 42.0, List.of(t))));

    JsonObject r = tools.call("get_consumer_group_lag", new JsonObject().put("group", "payments"));
    String body = textOf(r);
    JsonObject parsed = new JsonObject(body);

    assertEquals("growing", parsed.getString("overallTrend"));
    JsonArray trends = parsed.getJsonArray("trends");
    assertEquals(1, trends.size());
    assertEquals("growing", trends.getJsonObject(0).getString("direction"));
    assertEquals("orders", trends.getJsonObject(0).getString("topic"));

    JsonArray transitions = parsed.getJsonArray("recentTransitions");
    assertEquals(1, transitions.size());
    assertEquals("stable", transitions.getJsonObject(0).getString("from"));
    assertEquals("empty", transitions.getJsonObject(0).getString("to"));
    assertTrue(transitions.getJsonObject(0).containsKey("ageMs"));
  }

  @Test
  void listConsumerGroupsIncludesOverallTrend() {
    McpTools tools = new McpTools(storeWith(
      groupWithTrend("payments", 5000, Direction.GROWING, 42.0, List.of())));

    String text = textOf(tools.call("list_consumer_groups", new JsonObject()));
    assertTrue(text.contains("overallTrend"));
    assertTrue(text.contains("growing"));
  }

  @Test
  void findLaggingGroupsRanksByLagDescending() {
    McpTools tools = new McpTools(storeWith(
      group("low", State.STABLE, 10, 0, 1),
      group("high", State.STABLE, 9000, 0, 1),
      group("mid", State.STABLE, 500, 0, 1)));

    String text = textOf(tools.call("find_lagging_groups", new JsonObject()));
    assertTrue(text.indexOf("high") < text.indexOf("mid"));
    assertTrue(text.indexOf("mid") < text.indexOf("low"));
  }

  @Test
  void findLaggingGroupsRespectsLimit() {
    McpTools tools = new McpTools(storeWith(
      group("a", State.STABLE, 30, 0, 1),
      group("b", State.STABLE, 20, 0, 1),
      group("c", State.STABLE, 10, 0, 1)));

    String text = textOf(tools.call("find_lagging_groups", new JsonObject().put("limit", 1)));
    assertTrue(text.contains("a"));
    assertFalse(text.contains("\"c\""));
  }

  @Test
  void findLaggingGroupsRejectsInvalidSortBy() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 50, 1, 5)));
    JsonObject r = tools.call("find_lagging_groups", new JsonObject().put("sortBy", "bogus"));
    assertTrue(r.getBoolean("isError"));
    String text = textOf(r);
    assertTrue(text.contains("lag") && text.contains("velocity") && text.contains("retention"),
      "error must name the valid sortBy values: " + text);
  }

  @Test
  void findLaggingGroupsSortsByVelocity() {
    McpTools tools = new McpTools(storeWith(
      group("slow", State.STABLE, 9000, 1.0, 1),
      group("fast", State.STABLE, 10, 99.0, 1)));

    JsonObject r = tools.call("find_lagging_groups", new JsonObject().put("sortBy", "velocity"));
    assertFalse(r.getBoolean("isError"));
    String text = textOf(r);
    assertTrue(text.indexOf("fast") < text.indexOf("slow"));
  }

  @Test
  void diagnoseReturnsSeverityAndFindings() {
    McpTools tools = new McpTools(storeWith(group("payments", State.STABLE, 9000, 0, 100)));
    String text = textOf(tools.call("diagnose", new JsonObject().put("group", "payments")));
    assertTrue(text.contains("CRITICAL"));
    assertTrue(text.toLowerCase(Locale.ROOT).contains("data loss"));
  }
}
