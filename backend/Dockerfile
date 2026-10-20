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
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

COPY --from=build /app/target/*jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# # Build
# FROM maven:3.9.9-eclipse-temurin-21 AS build
# WORKDIR /app
# COPY pom.xml .
# COPY src ./src
# RUN mvn clean package -DskipTests
#
# # Run
# FROM eclipse-temurin:21-jre
# WORKDIR /app
# COPY --from=build /app/target/*jar app.jar
# EXPOSE 8080
# ENTRYPOINT ["java", "-jar", "app.jar"]
