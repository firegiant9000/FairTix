# -------- Build Stage --------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy only the pom first (dependency layer)
COPY pom.xml .
# Download dependencies (cached unless pom changes)
RUN mvn -B -q -e -C dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# -------- Run Stage --------
# Pinned to the Ubuntu 24.04 (noble) variant. The floating 21-jre tag now
# resolves to Ubuntu 26.04, whose image ships usr/bin/pebble built with Go 1.27.1
# (3 HIGH stdlib CVEs, fixed in Go 1.27.2).
# The tag pins the distro, not the digest, so the image still moves with
# upstream patches. Decision (2026-10-20): no digest pin here. Dependabot's
# docker ecosystem and the CI container-scan catch drift, and the L3 deployment
# runs the pushed image by its registry digest.
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# Dedicated non-root user with a fixed numeric id, so orchestrators can enforce
# runAsNonRoot. The app writes only to /tmp (Tomcat work directories).
RUN groupadd --gid 10001 fairtix \
 && useradd --uid 10001 --gid fairtix --no-create-home --shell /usr/sbin/nologin fairtix

COPY --from=build /app/target/*jar app.jar

USER 10001:10001
EXPOSE 8080

# curl already ships in the base image; this adds no package.
# /actuator/health is permitAll and returns 503 when a dependency is down.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS -o /dev/null --max-time 4 http://127.0.0.1:8080/actuator/health || exit 1

# Size the heap from the container's memory limit rather than the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
