package io.github.themoah.klag.metrics.hotpartition;

import java.util.List;

/**
 * Utility methods for statistical calculations used in hot partition detection.
 */
public final class StatisticalUtils {

  private StatisticalUtils() {}

  /**
   * Calculates statistics (mean and standard deviation) for a list of values.
   * Uses population standard deviation (divide by n, not n-1) since we have
   * all partitions in the topic, not a sample.
   *
   * @param values the values to analyze
   * @return statistics containing mean and standard deviation
   */
  public static Stats calculateStats(List<? extends Number> values) {
    if (values == null || values.isEmpty()) {
      return new Stats(0.0, 0.0);
    }

    int n = values.size();
    double sum = 0.0;
    for (Number value : values) {
      sum += value.doubleValue();
    }
    double mean = sum / n;

    double sumSquaredDiffs = 0.0;
    for (Number value : values) {
      double diff = value.doubleValue() - mean;
      sumSquaredDiffs += diff * diff;
    }
    double variance = sumSquaredDiffs / n;  // Population standard deviation
    double stdDev = Math.sqrt(variance);

    return new Stats(mean, stdDev);
  }

  /**
   * Determines if a value is a statistical outlier (above the threshold).
   *
   * @param value the value to check
   * @param mean the mean of the distribution
   * @param stdDev the standard deviation
   * @param sigmaMultiplier the number of standard deviations for outlier threshold
   * @return true if value > mean + (sigmaMultiplier * stdDev)
   */
  public static boolean isOutlier(double value, double mean, double stdDev, double sigmaMultiplier) {
    if (stdDev < 1e-10) {
      return false;  // No variance means no outliers
    }
    double threshold = mean + (sigmaMultiplier * stdDev);
    return value > threshold;
  }

  /**
   * Calculates the z-score for a value.
   * The z-score represents how many standard deviations a value is from the mean.
   *
   * @param value the value
   * @param mean the mean
   * @param stdDev the standard deviation
   * @return (value - mean) / stdDev, or 0 if stdDev is near zero
   */
  public static double zScore(double value, double mean, double stdDev) {
    if (stdDev < 1e-10) {
      return 0.0;
    }
    return (value - mean) / stdDev;
  }

  /**
   * Statistics result record containing mean and standard deviation.
   *
   * @param mean the arithmetic mean
   * @param stdDev the standard deviation
   */
  public record Stats(double mean, double stdDev) {}
}
