# ---- build stage: compiles the jar (tests run in CI and with ./mvnw verify, not here) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# the dependencies are downloaded in their own layer, so they are cached until pom.xml changes
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests && cp target/ticketflow-*.jar app.jar

# ---- runtime stage: only a JRE, no build tools, no root ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 ticketflow
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER ticketflow
EXPOSE 8080
# the JVM sizes its heap from the container memory limit
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
