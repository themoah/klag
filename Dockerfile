# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Install Gradle
RUN apt-get update && \
    apt-get install -y wget unzip && \
    wget https://services.gradle.org/distributions/gradle-8.14.3-bin.zip -P /tmp && \
    unzip -d /opt/gradle /tmp/gradle-8.14.3-bin.zip && \
    ln -s /opt/gradle/gradle-8.14.3/bin/gradle /usr/bin/gradle && \
    rm -rf /tmp/gradle-8.14.3-bin.zip

# Copy build files
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src src

# Build fat JAR
RUN gradle assemble --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy fat JAR from builder
COPY --from=builder /app/build/libs/*-fat.jar app.jar

# Default port
EXPOSE 8888

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
