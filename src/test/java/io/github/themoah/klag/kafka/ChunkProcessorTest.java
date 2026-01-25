package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for ChunkProcessor.
 */
@ExtendWith(VertxExtension.class)
public class ChunkProcessorTest {

  @Test
  void balanceIntoChunks_emptyCollection() {
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(List.of(), 3, s -> 1);

    assertTrue(chunks.isEmpty());
  }

  @Test
  void balanceIntoChunks_singleItem() {
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(
        List.of("a"), 3, s -> 1);

    assertEquals(1, chunks.size());
    assertEquals(List.of("a"), chunks.get(0));
  }

  @Test
  void balanceIntoChunks_chunkCountOne() {
    List<String> items = List.of("a", "b", "c");
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(items, 1, s -> 1);

    assertEquals(1, chunks.size());
    assertEquals(3, chunks.get(0).size());
  }

  @Test
  void balanceIntoChunks_moreChunksThanItems() {
    List<String> items = List.of("a", "b");
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(items, 5, s -> 1);

    // Effective chunks = items.size() = 2
    assertEquals(2, chunks.size());
    assertEquals(1, chunks.get(0).size());
    assertEquals(1, chunks.get(1).size());
  }

  @Test
  void balanceIntoChunks_equalWeights() {
    List<String> items = List.of("a", "b", "c", "d");
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(items, 2, s -> 1);

    assertEquals(2, chunks.size());
    assertEquals(2, chunks.get(0).size());
    assertEquals(2, chunks.get(1).size());
  }

  @Test
  void balanceIntoChunks_unequalWeights_balancesCorrectly() {
    // Weights: a=10, b=5, c=3, d=2
    List<String> items = List.of("a", "b", "c", "d");
    java.util.function.Function<String, Integer> weightFn = s -> switch (s) {
      case "a" -> 10;
      case "b" -> 5;
      case "c" -> 3;
      case "d" -> 2;
      default -> 1;
    };
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(items, 2, weightFn);

    assertEquals(2, chunks.size());

    // All items should be distributed across chunks
    int totalItems = chunks.stream().mapToInt(List::size).sum();
    assertEquals(4, totalItems);

    // Compute actual chunk weights
    int weight0 = chunks.get(0).stream().mapToInt(weightFn::apply).sum();
    int weight1 = chunks.get(1).stream().mapToInt(weightFn::apply).sum();

    // Total weight should be preserved
    assertEquals(20, weight0 + weight1);

    // Greedy bin-packing: a(10)→c0, b(5)→c1, c(3)→c1, d(2)→c1 (weights: 10 vs 10)
    // or a(10)→c0, b(5)→c1, c(3)→c1, d(2)→c0 (weights: 12 vs 8)
    // Either way, difference should be small
    assertTrue(Math.abs(weight0 - weight1) <= 4,
        "Chunks should be reasonably balanced, got " + weight0 + " vs " + weight1);
  }

  @Test
  void balanceIntoChunks_threeChunks() {
    List<String> items = List.of("a", "b", "c", "d", "e", "f");
    List<List<String>> chunks = ChunkProcessor.balanceIntoChunks(items, 3, s -> 1);

    assertEquals(3, chunks.size());
    assertEquals(2, chunks.get(0).size());
    assertEquals(2, chunks.get(1).size());
    assertEquals(2, chunks.get(2).size());
  }

  @Test
  void processSequentially_emptyChunks(Vertx vertx, VertxTestContext ctx) throws Exception {
    Future<List<String>> result = ChunkProcessor.processSequentially(
        vertx, List.of(), 0, chunk -> Future.succeededFuture("done"));

    result.onComplete(ctx.succeeding(results -> ctx.verify(() -> {
      assertTrue(results.isEmpty());
      ctx.completeNow();
    })));

    assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
  }

  @Test
  void processSequentially_singleChunk(Vertx vertx, VertxTestContext ctx) throws Exception {
    List<List<String>> chunks = List.of(List.of("a", "b"));

    Future<List<Integer>> result = ChunkProcessor.processSequentially(
        vertx, chunks, 0, chunk -> Future.succeededFuture(chunk.size()));

    result.onComplete(ctx.succeeding(results -> ctx.verify(() -> {
      assertEquals(1, results.size());
      assertEquals(2, results.get(0));
      ctx.completeNow();
    })));

    assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
  }

  @Test
  void processSequentially_multipleChunks_maintainsOrder(Vertx vertx, VertxTestContext ctx) throws Exception {
    List<List<String>> chunks = List.of(
        List.of("a"),
        List.of("b", "c"),
        List.of("d", "e", "f")
    );

    Future<List<Integer>> result = ChunkProcessor.processSequentially(
        vertx, chunks, 0, chunk -> Future.succeededFuture(chunk.size()));

    result.onComplete(ctx.succeeding(results -> ctx.verify(() -> {
      assertEquals(3, results.size());
      assertEquals(1, results.get(0));
      assertEquals(2, results.get(1));
      assertEquals(3, results.get(2));
      ctx.completeNow();
    })));

    assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
  }

  @Test
  void processSequentially_withDelay_processesInOrder(Vertx vertx, VertxTestContext ctx) throws Exception {
    List<List<String>> chunks = List.of(
        List.of("first"),
        List.of("second"),
        List.of("third")
    );

    List<String> executionOrder = new ArrayList<>();

    Future<List<String>> result = ChunkProcessor.processSequentially(
        vertx, chunks, 10, chunk -> {
          executionOrder.add(chunk.get(0));
          return Future.succeededFuture(chunk.get(0));
        });

    result.onComplete(ctx.succeeding(results -> ctx.verify(() -> {
      assertEquals(List.of("first", "second", "third"), executionOrder);
      assertEquals(List.of("first", "second", "third"), results);
      ctx.completeNow();
    })));

    assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
  }

  @Test
  void processSequentially_handlesFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
    List<List<String>> chunks = List.of(
        List.of("ok"),
        List.of("fail")
    );

    Future<List<String>> result = ChunkProcessor.processSequentially(
        vertx, chunks, 0, chunk -> {
          if (chunk.get(0).equals("fail")) {
            return Future.failedFuture(new RuntimeException("test error"));
          }
          return Future.succeededFuture(chunk.get(0));
        });

    result.onComplete(ctx.failing(err -> ctx.verify(() -> {
      assertEquals("test error", err.getMessage());
      ctx.completeNow();
    })));

    assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
  }
}
