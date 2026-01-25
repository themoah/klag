package io.github.themoah.klag.kafka;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Utility class for splitting items into balanced chunks and processing them sequentially.
 */
public final class ChunkProcessor {

  private ChunkProcessor() {}

  /**
   * Splits items into balanced chunks using greedy bin-packing.
   * Items are sorted by weight descending, then each item is assigned to the lightest chunk.
   *
   * @param items the items to split
   * @param chunkCount the number of chunks to create
   * @param weightFn function to determine the weight of each item
   * @return list of chunks, each containing a subset of items
   */
  public static <T> List<List<T>> balanceIntoChunks(
      Collection<T> items, int chunkCount, Function<T, Integer> weightFn) {

    if (items.isEmpty()) {
      return List.of();
    }

    int effectiveChunks = Math.min(chunkCount, items.size());

    // Sort items by weight descending for greedy bin-packing
    List<T> sorted = new ArrayList<>(items);
    sorted.sort(Comparator.comparingInt(item -> -weightFn.apply(item)));

    // Initialize chunks with tracked weights
    List<List<T>> chunks = new ArrayList<>(effectiveChunks);
    long[] chunkWeights = new long[effectiveChunks];
    for (int i = 0; i < effectiveChunks; i++) {
      chunks.add(new ArrayList<>());
    }

    // Assign each item to the lightest chunk
    for (T item : sorted) {
      int lightestIdx = 0;
      for (int i = 1; i < effectiveChunks; i++) {
        if (chunkWeights[i] < chunkWeights[lightestIdx]) {
          lightestIdx = i;
        }
      }
      chunks.get(lightestIdx).add(item);
      chunkWeights[lightestIdx] += weightFn.apply(item);
    }

    return chunks;
  }

  /**
   * Processes chunks sequentially, with an optional delay between chunks.
   * The first chunk is processed immediately. Subsequent chunks are delayed by delayMs
   * (if delayMs > 0) using Vert.x timers.
   *
   * @param vertx the Vert.x instance
   * @param chunks the chunks to process
   * @param delayMs delay in milliseconds between chunks (0 = no delay)
   * @param processor function that processes a chunk and returns a Future with the result
   * @return Future containing all chunk results in order
   */
  public static <T, R> Future<List<R>> processSequentially(
      Vertx vertx, List<List<T>> chunks, long delayMs, Function<List<T>, Future<R>> processor) {

    if (chunks.isEmpty()) {
      return Future.succeededFuture(List.of());
    }

    List<R> results = new ArrayList<>(chunks.size());
    Future<Void> chain = Future.succeededFuture();

    for (int i = 0; i < chunks.size(); i++) {
      final int index = i;
      final List<T> chunk = chunks.get(i);

      if (i == 0 || delayMs <= 0) {
        chain = chain.compose(v -> processor.apply(chunk).<Void>map(r -> {
          results.add(r);
          return null;
        }));
      } else {
        chain = chain.compose(v -> {
          Promise<Void> delayPromise = Promise.promise();
          vertx.setTimer(delayMs, timerId -> {
            processor.apply(chunk).<Void>map(r -> {
              results.add(r);
              return null;
            }).onComplete(delayPromise);
          });
          return delayPromise.future();
        });
      }
    }

    return chain.map(v -> results);
  }
}
