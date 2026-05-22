package io.github.themoah.klag.health;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionHandler {
  private static final Logger log = LoggerFactory.getLogger(VersionHandler.class);
  private static final String VERSION_PROPERTIES_PATH = "/version.properties";
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String READ_FAIL_MESSAGE = "Failed to load version.properties, falling back to defaults";

  private static final Properties VERSION_PROPERTIES = loadVersionProperties();

  public static String getVersion() {
    return VERSION_PROPERTIES.getProperty("project.version", "unknown");
  }

  public static String getVertxVersion() {
    return VERSION_PROPERTIES.getProperty("vertx.version", "unknown");
  }

  public static String getJavaVersion() {
    return System.getProperty("java.version", "unknown");
  }

  private static Properties loadVersionProperties() {
    Properties props = new Properties();

    try (InputStream is = VersionHandler.class.getResourceAsStream(VERSION_PROPERTIES_PATH)) {
      if (is == null) {
        log.error(READ_FAIL_MESSAGE);
        return props;
      }
      props.load(is);
      log.info("Successfully loaded version.properties from classpath");
    } catch (IOException e) {
      log.error(READ_FAIL_MESSAGE, e);
    }

    return props;
  }

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
    return new VersionInfoResponse(getVersion(), getVertxVersion(), getJavaVersion());
  }
}
