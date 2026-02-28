package io.github.themoah.klag.health;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionHandler {
  private static final Logger log = LoggerFactory.getLogger(VersionHandler.class);
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");
  private static final Pattern VERTX_VERSION_PATTERN = Pattern.compile("(?:val|var|ext\\.)?\\s*vertxVersion\\s*(?:=|=)\\s*\"([^\"]+)\"");

  public void registerRoutes(Router router) {
    router.get("/version").handler(this::handleGetVersionInfo);
    log.info("Version info route registered: /version");
  }

  private void handleGetVersionInfo(RoutingContext ctx) {
    VersionInfoResponse response = getVersionInfo();

    ctx.response()
      .putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON)
      .setStatusCode(200)
      .end(response.toJson().encode());
  }

  private VersionInfoResponse getVersionInfo() {
    String javaVersion = System.getProperty("java.version");

    String version = null;
    String vertxVersion = null;
    try {
      Path gradlePath = Paths.get("build.gradle.kts").normalize();
      if (Files.exists(gradlePath)) {
        String content = Files.readString(gradlePath);
        Matcher matcher = VERSION_PATTERN.matcher(content);
        if (matcher.find()) {
          version = matcher.group(1);
        }
        Matcher vertxMatcher = VERTX_VERSION_PATTERN.matcher(content);
        if (vertxMatcher.find()) {
          vertxVersion = vertxMatcher.group(1);
        }
      }
    } catch (Exception e) {
      log.error("Error reading build.gradle.kts", e);
    }

    return new VersionInfoResponse(version, vertxVersion, javaVersion);
  }
}
