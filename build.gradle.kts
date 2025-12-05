import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
  java
  application
  id("com.gradleup.shadow") version "9.2.2"
}

group = "io.github.themoah"
version = "0.0.5-SNAPSHOT"

repositories {
  mavenCentral()
}

val vertxVersion = "4.5.22"
val junitJupiterVersion = "5.9.1"
val micrometerVersion = "1.12.0"

val mainVerticleName = "io.github.themoah.klag.MainVerticle"
val launcherClassName = "io.vertx.core.Launcher"

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
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  manifest {
    attributes(mapOf("Main-Verticle" to mainVerticleName))
  }
  mergeServiceFiles()
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}

tasks.withType<JavaExec> {
  args = listOf("run", mainVerticleName, "--redeploy=$watchForChange", "--launcher-class=$launcherClassName", "--on-redeploy=$doOnChange")

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
