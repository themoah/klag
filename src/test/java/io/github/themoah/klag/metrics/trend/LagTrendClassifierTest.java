package io.github.themoah.klag.metrics.trend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.themoah.klag.model.LagTrend;
import io.github.themoah.klag.model.LagTrend.Direction;
import io.github.themoah.klag.model.LagVelocity;
import java.util.List;
import org.junit.jupiter.api.Test;

class LagTrendClassifierTest {

  private static LagVelocity vel(String topic, double v) {
    return new LagVelocity("g", topic, v, 1000, 3);
  }

  @Test
  void classifyAboveDeadbandIsGrowing() {
    assertEquals(Direction.GROWING, LagTrendClassifier.classify(5.0, 1.0));
  }

  @Test
  void classifyBelowNegativeDeadbandIsShrinking() {
    assertEquals(Direction.SHRINKING, LagTrendClassifier.classify(-5.0, 1.0));
  }

  @Test
  void classifyWithinDeadbandIsStable() {
    assertEquals(Direction.STABLE, LagTrendClassifier.classify(0.5, 1.0));
    assertEquals(Direction.STABLE, LagTrendClassifier.classify(-0.5, 1.0));
  }

  @Test
  void classifyExactlyAtDeadbandIsStable() {
    assertEquals(Direction.STABLE, LagTrendClassifier.classify(1.0, 1.0));
    assertEquals(Direction.STABLE, LagTrendClassifier.classify(-1.0, 1.0));
  }

  @Test
  void perTopicMapsEachVelocity() {
    List<LagTrend> trends = LagTrendClassifier.perTopic(
      List.of(vel("a", 10.0), vel("b", -10.0), vel("c", 0.0)), 1.0);

    assertEquals(3, trends.size());
    assertEquals(Direction.GROWING, trends.get(0).direction());
    assertEquals(Direction.SHRINKING, trends.get(1).direction());
    assertEquals(Direction.STABLE, trends.get(2).direction());
    assertEquals("a", trends.get(0).topic());
    assertEquals(10.0, trends.get(0).velocity());
  }

  @Test
  void overallIsStableWhenNoLag() {
    List<LagTrend> trends = LagTrendClassifier.perTopic(List.of(vel("a", 100.0)), 1.0);
    assertEquals(Direction.STABLE, LagTrendClassifier.overall(trends, 0));
  }

  @Test
  void overallGrowingWinsOverShrinking() {
    List<LagTrend> trends = LagTrendClassifier.perTopic(
      List.of(vel("a", -50.0), vel("b", 50.0)), 1.0);
    assertEquals(Direction.GROWING, LagTrendClassifier.overall(trends, 500));
  }

  @Test
  void overallShrinkingWhenAllShrinkingAndLagged() {
    List<LagTrend> trends = LagTrendClassifier.perTopic(
      List.of(vel("a", -50.0), vel("b", -10.0)), 1.0);
    assertEquals(Direction.SHRINKING, LagTrendClassifier.overall(trends, 500));
  }

  @Test
  void overallStableWhenLaggedButFlat() {
    List<LagTrend> trends = LagTrendClassifier.perTopic(List.of(vel("a", 0.0)), 1.0);
    assertEquals(Direction.STABLE, LagTrendClassifier.overall(trends, 500));
  }
}
