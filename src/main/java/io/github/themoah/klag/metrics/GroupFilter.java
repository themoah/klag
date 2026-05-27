package io.github.themoah.klag.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether a consumer group should be monitored based on
 * comma-separated include and exclude glob lists.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Each input is split on {@code ,}; segments are trimmed; blank segments dropped.</li>
 *   <li>A bare {@code *} segment in the include list collapses the include to match-all.</li>
 *   <li>Group is accepted iff <em>(includes empty OR any include matches)</em>
 *       AND <em>no exclude matches</em>.</li>
 *   <li>Glob syntax: {@code *} = any chars, {@code ?} = single char. Every other
 *       regex metachar is escaped literally — including {@code |}, for back-compat.</li>
 * </ul>
 */
final class GroupFilter {

  private final List<Pattern> includes;
  private final List<Pattern> excludes;
  private final String includeRaw;
  private final String excludeRaw;

  GroupFilter(String includeCsv, String excludeCsv) {
    this.includeRaw = includeCsv;
    this.excludeRaw = excludeCsv;
    this.includes = compileList(includeCsv, true);
    this.excludes = compileList(excludeCsv, false);
  }

  boolean matches(String groupId) {
    for (Pattern p : excludes) {
      if (p.matcher(groupId).matches()) {
        return false;
      }
    }
    if (includes.isEmpty()) {
      return true;
    }
    for (Pattern p : includes) {
      if (p.matcher(groupId).matches()) {
        return true;
      }
    }
    return false;
  }

  String includeDescription() {
    return (includeRaw == null || includeRaw.isBlank()) ? "*" : includeRaw;
  }

  String excludeDescription() {
    return (excludeRaw == null || excludeRaw.isBlank()) ? "(none)" : excludeRaw;
  }

  /**
   * Splits {@code csv} on commas and compiles each non-blank segment.
   * For the include list, presence of a bare {@code *} collapses to match-all (empty list).
   * The exclude list never collapses on {@code *} — a literal {@code *} there means "exclude everything".
   */
  private static List<Pattern> compileList(String csv, boolean collapseOnStar) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<Pattern> patterns = new ArrayList<>();
    for (String raw : csv.split(",")) {
      String segment = raw.trim();
      if (segment.isEmpty()) {
        continue;
      }
      if (collapseOnStar && segment.equals("*")) {
        return List.of();
      }
      patterns.add(compileGlob(segment));
    }
    return patterns;
  }

  /**
   * Converts a glob pattern to a regex {@link Pattern}.
   * Supports {@code *} (any chars) and {@code ?} (single char).
   * All other regex metachars are escaped — including {@code |}.
   */
  static Pattern compileGlob(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append(".");
        case '.' -> regex.append("\\.");
        case '\\' -> regex.append("\\\\");
        case '[', ']', '(', ')', '{', '}', '^', '$', '|', '+' -> regex.append("\\").append(c);
        default -> regex.append(c);
      }
    }
    regex.append("$");
    return Pattern.compile(regex.toString());
  }
}
