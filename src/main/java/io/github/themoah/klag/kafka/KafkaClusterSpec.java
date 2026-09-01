package io.github.themoah.klag.kafka;

/**
 * One Kafka cluster this process should scrape.
 *
 * @param name optional {@code cluster_name} metric tag; blank omits the label
 * @param clientConfig AdminClient settings for this cluster
 * @param groupFilter override for {@code METRICS_GROUP_FILTER}; blank inherits the process default
 * @param groupExclude override for {@code METRICS_GROUP_EXCLUDE}; blank inherits the process default
 */
public record KafkaClusterSpec(
  String name,
  KafkaClientConfig clientConfig,
  String groupFilter,
  String groupExclude
) {

  public boolean hasClusterName() {
    return name != null && !name.isBlank();
  }

  public String resolvedGroupFilter(String processDefault) {
    return firstNonBlank(groupFilter, processDefault);
  }

  public String resolvedGroupExclude(String processDefault) {
    return firstNonBlank(groupExclude, processDefault);
  }

  private static String firstNonBlank(String override, String processDefault) {
    if (override != null && !override.isBlank()) {
      return override;
    }
    return processDefault;
  }
}
