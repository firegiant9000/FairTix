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
FROM eclipse-temurin:25-jre
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
