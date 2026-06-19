package io.github.themoah.klag.mcp;

import io.github.themoah.klag.mcp.Diagnoser.Diagnosis;
import io.github.themoah.klag.mcp.Diagnoser.Finding;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagTrend;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.StateTransition;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Implements the read-only MCP tools, served entirely from the latest {@link MetricsSnapshot}.
 *
 * <p>No method here touches Kafka or the metrics collector — it only reads the snapshot
 * published by the collector, guaranteeing isolation from the collection flow.
 */
public class McpTools {

  static final String LIST_GROUPS = "list_consumer_groups";
  static final String GET_GROUP_LAG = "get_consumer_group_lag";
  static final String FIND_LAGGING = "find_lagging_groups";
  static final String DIAGNOSE = "diagnose";

  private static final int DEFAULT_LIMIT = 10;

  private final SnapshotStore store;

  public McpTools(SnapshotStore store) {
    this.store = store;
  }

  /**
   * Returns the MCP tool definitions for {@code tools/list}.
   *
   * @return array of tool descriptors (all read-only)
   */
  public JsonArray toolDefinitions() {
    return new JsonArray()
      .add(tool(LIST_GROUPS,
        "List all monitored Kafka consumer groups with a lag/state summary.",
        new JsonObject().put("type", "object").put("properties", new JsonObject())))
      .add(tool(GET_GROUP_LAG,
        "Get detailed lag for one consumer group: per-topic and per-partition lag, offsets, "
          + "velocity, lag in ms, time-to-close, and retention risk.",
        objectSchema("group", "The consumer group ID", true)))
      .add(tool(FIND_LAGGING,
        "Rank consumer groups by severity to triage what is falling behind. "
          + "sortBy one of lag|velocity|retention (default lag); limit caps results.",
        new JsonObject()
          .put("type", "object")
          .put("properties", new JsonObject()
            .put("sortBy", new JsonObject().put("type", "string")
              .put("enum", new JsonArray().add("lag").add("velocity").add("retention")))
            .put("limit", new JsonObject().put("type", "integer")))))
      .add(tool(DIAGNOSE,
        "Diagnose one consumer group: combines state, lag trend, retention risk, and hot "
          + "partitions into a severity-rated assessment with likely-cause hints.",
        objectSchema("group", "The consumer group ID", true)));
  }

  /**
   * Dispatches a {@code tools/call}.
   *
   * @param name the tool name
   * @param args the tool arguments (never null)
   * @return an MCP tool result ({@code content} + {@code isError})
   */
  public JsonObject call(String name, JsonObject args) {
    Optional<MetricsSnapshot> snapshot = store.latest();
    if (snapshot.isEmpty()) {
      return text("Snapshot not ready yet — the metrics collector has not completed its first "
        + "cycle. Try again shortly.");
    }
    MetricsSnapshot snap = snapshot.get();

    return switch (name) {
      case LIST_GROUPS -> listGroups(snap);
      case GET_GROUP_LAG -> getGroupLag(snap, args);
      case FIND_LAGGING -> findLagging(snap, args);
      case DIAGNOSE -> diagnose(snap, args);
      default -> error("Unknown tool: " + name);
    };
  }

  private JsonObject listGroups(MetricsSnapshot snap) {
    JsonArray groups = new JsonArray();
    for (GroupSnapshot g : snap.groups()) {
      groups.add(new JsonObject()
        .put("group", g.consumerGroup())
        .put("state", g.state().toMetricValue())
        .put("totalLag", g.totalLag())
        .put("overallTrend", g.overallTrend().name().toLowerCase())
        .put("topics", topicCount(g)));
    }
    JsonObject body = new JsonObject()
      .put("snapshotAgeMs", System.currentTimeMillis() - snap.timestampMs())
      .put("groupCount", snap.groups().size())
      .put("groups", groups);
    return text(body.encodePrettily());
  }

  private JsonObject getGroupLag(MetricsSnapshot snap, JsonObject args) {
    String group = args.getString("group");
    if (group == null || group.isBlank()) {
      return error("Missing required argument: group");
    }
    Optional<GroupSnapshot> gs = snap.group(group);
    if (gs.isEmpty()) {
      return error("Consumer group not found in the latest snapshot: " + group);
    }
    GroupSnapshot g = gs.get();

    JsonArray partitions = new JsonArray();
    for (PartitionLag p : g.partitions()) {
      partitions.add(new JsonObject()
        .put("topic", p.topic())
        .put("partition", p.partition())
        .put("lag", p.lag())
        .put("committedOffset", p.committedOffset())
        .put("logEndOffset", p.logEndOffset())
        .put("logStartOffset", p.logStartOffset()));
    }

    JsonObject body = new JsonObject()
      .put("group", g.consumerGroup())
      .put("state", g.state().toMetricValue())
      .put("totalLag", g.totalLag())
      .put("maxLag", g.maxLag())
      .put("minLag", g.minLag())
      .put("partitions", partitions)
      .put("velocity", velocityArray(g.velocities()))
      .put("trends", trendArray(g.trends()))
      .put("overallTrend", g.overallTrend().name().toLowerCase())
      .put("recentTransitions", transitionArray(g.recentTransitions()))
      .put("lagMs", lagMsArray(g.lagMs()))
      .put("timeToClose", timeToCloseArray(g.timeToClose()))
      .put("retentionRisk", retentionArray(g.retentionRisks()))
      .put("commitStalenessSeconds", g.maxCommitStalenessSeconds());
    return text(body.encodePrettily());
  }

  private JsonObject findLagging(MetricsSnapshot snap, JsonObject args) {
    String sortBy = args.getValue("sortBy") != null ? String.valueOf(args.getValue("sortBy")) : "lag";
    if (!sortBy.equals("lag") && !sortBy.equals("velocity") && !sortBy.equals("retention")) {
      return error("Invalid sortBy: " + sortBy + " (expected one of: lag, velocity, retention)");
    }
    // Accept any numeric limit (truncating floats); ignore non-numeric junk rather than letting
    // the cast throw. Non-positive or out-of-range values fall back to the default.
    Object limitVal = args.getValue("limit");
    int limit = limitVal instanceof Number n ? n.intValue() : DEFAULT_LIMIT;
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }

    Comparator<GroupSnapshot> comparator = switch (sortBy) {
      case "velocity" -> Comparator.comparingDouble(McpTools::maxVelocity).reversed();
      case "retention" -> Comparator.comparingDouble(McpTools::maxRetention).reversed();
      default -> Comparator.comparingLong(GroupSnapshot::totalLag).reversed();
    };

    List<GroupSnapshot> ranked = snap.groups().stream()
      .sorted(comparator)
      .limit(limit)
      .toList();

    JsonArray groups = new JsonArray();
    for (GroupSnapshot g : ranked) {
      groups.add(new JsonObject()
        .put("group", g.consumerGroup())
        .put("state", g.state().toMetricValue())
        .put("totalLag", g.totalLag())
        .put("overallTrend", g.overallTrend().name().toLowerCase())
        .put("maxVelocity", maxVelocity(g))
        .put("maxRetentionPercent", maxRetention(g))
        .put("commitStalenessSeconds", g.maxCommitStalenessSeconds()));
    }
    JsonObject body = new JsonObject()
      .put("sortBy", sortBy)
      .put("limit", limit)
      .put("groups", groups);
    return text(body.encodePrettily());
  }

  private JsonObject diagnose(MetricsSnapshot snap, JsonObject args) {
    String group = args.getString("group");
    if (group == null || group.isBlank()) {
      return error("Missing required argument: group");
    }
    Optional<GroupSnapshot> gs = snap.group(group);
    if (gs.isEmpty()) {
      return error("Consumer group not found in the latest snapshot: " + group);
    }

    Diagnosis d = Diagnoser.diagnose(gs.get());
    JsonArray findings = new JsonArray();
    for (Finding f : d.findings()) {
      findings.add(new JsonObject()
        .put("severity", f.severity().name())
        .put("title", f.title())
        .put("detail", f.detail()));
    }
    JsonObject body = new JsonObject()
      .put("group", d.group())
      .put("severity", d.overall().name())
      .put("summary", d.summary())
      .put("findings", findings);
    return text(body.encodePrettily());
  }

  // --- helpers ---------------------------------------------------------------

  private static int topicCount(GroupSnapshot g) {
    return (int) g.partitions().stream().map(PartitionLag::topic).distinct().count();
  }

  private static double maxVelocity(GroupSnapshot g) {
    return g.velocities().stream().mapToDouble(LagVelocity::velocity).max().orElse(0.0);
  }

  private static double maxRetention(GroupSnapshot g) {
    return g.retentionRisks().stream().mapToDouble(RetentionRisk::percent).max().orElse(0.0);
  }

  private static JsonArray velocityArray(List<LagVelocity> velocities) {
    JsonArray a = new JsonArray();
    for (LagVelocity v : velocities) {
      a.add(new JsonObject().put("topic", v.topic()).put("messagesPerSec", v.velocity()));
    }
    return a;
  }

  private static JsonArray lagMsArray(List<LagMs> lagMsList) {
    JsonArray a = new JsonArray();
    for (LagMs l : lagMsList) {
      a.add(new JsonObject().put("topic", l.topic()).put("lagMs", l.lagMs())
        .put("lagMessages", l.lagMessages()));
    }
    return a;
  }

  private static JsonArray timeToCloseArray(List<TimeToCloseEstimate> estimates) {
    JsonArray a = new JsonArray();
    for (TimeToCloseEstimate t : estimates) {
      a.add(new JsonObject().put("topic", t.topic())
        .put("estimatedSeconds", t.estimatedTimeToCloseSeconds()));
    }
    return a;
  }

  private static JsonArray trendArray(List<LagTrend> trends) {
    JsonArray a = new JsonArray();
    for (LagTrend t : trends) {
      a.add(new JsonObject()
        .put("topic", t.topic())
        .put("direction", t.direction().name().toLowerCase())
        .put("velocity", t.velocity()));
    }
    return a;
  }

  private static JsonArray transitionArray(List<StateTransition> transitions) {
    long now = System.currentTimeMillis();
    JsonArray a = new JsonArray();
    for (StateTransition t : transitions) {
      a.add(new JsonObject()
        .put("from", t.from().toMetricValue())
        .put("to", t.to().toMetricValue())
        .put("timestampMs", t.timestampMs())
        .put("ageMs", now - t.timestampMs()));
    }
    return a;
  }

  private static JsonArray retentionArray(List<RetentionRisk> risks) {
    JsonArray a = new JsonArray();
    for (RetentionRisk r : risks) {
      a.add(new JsonObject().put("topic", r.topic()).put("percent", r.percent()));
    }
    return a;
  }

  private static JsonObject tool(String name, String description, JsonObject inputSchema) {
    return new JsonObject()
      .put("name", name)
      .put("description", description)
      .put("inputSchema", inputSchema)
      .put("annotations", new JsonObject()
        .put("readOnlyHint", true)
        .put("openWorldHint", false));
  }

  private static JsonObject objectSchema(String prop, String desc, boolean required) {
    JsonObject schema = new JsonObject()
      .put("type", "object")
      .put("properties", new JsonObject()
        .put(prop, new JsonObject().put("type", "string").put("description", desc)));
    if (required) {
      schema.put("required", new JsonArray().add(prop));
    }
    return schema;
  }

  private static JsonObject text(String body) {
    return result(body, false);
  }

  private static JsonObject error(String body) {
    return result(body, true);
  }

  private static JsonObject result(String body, boolean isError) {
    return new JsonObject()
      .put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", body)))
      .put("isError", isError);
  }
}
