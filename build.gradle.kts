import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
  java
  application
  id("com.gradleup.shadow") version "9.2.2"
  id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "io.github.themoah"
version = "0.2.2"

repositories {
  mavenCentral()
}

val vertxVersion = "4.5.22"
val junitJupiterVersion = "5.9.1"
val micrometerVersion = "1.12.0"

val mainVerticleName = "io.github.themoah.klag.MainVerticle"
val launcherClassName = "io.github.themoah.klag.KlagLauncher"

val watchForChange = "src/**/*"
val doOnChange = "${projectDir}/gradlew classes"

application {
  mainClass.set(launcherClassName)
}

dependencies {
  implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
  implementation("io.vertx:vertx-micrometer-metrics")
  implementation("io.vertx:vertx-kafka-client")
  implementation("io.vertx:vertx-web")
  implementation("org.slf4j:slf4j-api:2.0.9")
  implementation("ch.qos.logback:logback-classic:1.4.14")

  // Micrometer registries
  implementation("io.micrometer:micrometer-registry-datadog:$micrometerVersion")
  implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")
  implementation("io.micrometer:micrometer-registry-otlp:$micrometerVersion")

  testImplementation("io.vertx:vertx-junit5")
  testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  manifest {
    attributes(mapOf(
      "Main-Verticle" to mainVerticleName,
      "Main-Class" to launcherClassName
    ))
  }
  mergeServiceFiles()

  from(sourceSets.main.get().output)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}

tasks.withType<JavaExec> {
  mainClass.set("io.vertx.core.Launcher")
  args = listOf("run", mainVerticleName, "--redeploy=$watchForChange", "--launcher-class=io.vertx.core.Launcher", "--on-redeploy=$doOnChange")

  // Load environment variables from .env file if it exists
  val envFile = file(".env")
  if (envFile.exists()) {
    envFile.readLines()
      .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
      .forEach { line ->
        val (key, value) = line.split("=", limit = 2)
        environment(key.trim(), value.trim())
      }
  }
}

tasks.withType<ProcessResources> {
  filesMatching("version.properties") {
    expand(
      "vertxVersion" to vertxVersion,
      "projectVersion" to version
    )
  }
}

// GraalVM native image configuration.
// Entry point is KlagLauncher (direct `new MainVerticle()` - no reflective Vert.x launcher).
// Reachability metadata for Netty, kafka-clients, logback, micrometer is pulled
// from the GraalVM Reachability Metadata Repository; project-specific hints live
// in src/main/resources/META-INF/native-image/.
graalvmNative {
  binaries {
    named("main") {
      imageName.set("klag")
      mainClass.set(launcherClassName)
      buildArgs.add("--no-fallback")
      buildArgs.add("-H:+ReportExceptionStackTraces")
      buildArgs.add("--enable-url-protocols=http,https")
      // Vert.x/Netty/logback are not safe to initialize at build time.
      buildArgs.add("--initialize-at-run-time=io.netty")
      // -PnativeStatic (Linux/CI): statically link everything except libc so the
      // binary runs on a distroless/base image with no libz.so.1 etc. Not used on
      // macOS where static linking is unsupported.
      if (project.hasProperty("nativeStatic")) {
        buildArgs.add("-H:+StaticExecutableWithDynamicLibC")
      }
    }
  }
  metadataRepository {
    enabled.set(true)
  }
  toolchainDetection.set(false)
}
