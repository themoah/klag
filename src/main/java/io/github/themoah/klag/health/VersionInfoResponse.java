package io.github.themoah.klag.health;

import io.vertx.core.json.JsonObject;

public record VersionInfoResponse(String version, String vertxVersion, String javaVersion) {

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.put("version", version);
    json.put("vertxVersion", vertxVersion);
    json.put("javaVersion", javaVersion);
    return json;
  }
}
