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
  private static final String VERSION_PROPERTIES = "/version.properties";
  private static final String CONTENT_TYPE_JSON = "application/json";

  private final Properties versionProperties;

  public VersionHandler() {
    this.versionProperties = loadVersionProperties();
  }

  private Properties loadVersionProperties() {
    Properties props = new Properties();

    try (InputStream is = VersionHandler.class.getResourceAsStream(VERSION_PROPERTIES)) {
      props.load(is);
      log.info("Successfully loaded version.properties from classpath");
    } catch (IOException e) {
      log.error("Failed to load version.properties, falling back to filesystem parsing", e);
    }

    return props;
  }

  private String getVersion() {
    return versionProperties.getProperty("project.version", "unknown");
  }

  private String getVertxVersion() {
    return versionProperties.getProperty("vertx.version", "unknown");
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
    String javaVersion = System.getProperty("java.version");

    return new VersionInfoResponse(getVersion(), getVertxVersion(), javaVersion);
  }
}
