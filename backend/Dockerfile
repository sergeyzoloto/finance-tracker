# The backend's image, for production (deploy/app/docker-compose.yml) and the local docker-compose.yml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Small enough to build on the server next to the running stacks (deploy/RUNBOOK.md, memory budget).
ENV MAVEN_OPTS=-Xmx512m
COPY pom.xml .
COPY src src
# The tests run in CI; they need Docker for Testcontainers.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /app/target/finance-tracker-*.jar app.jar
USER app
EXPOSE 8080
# The heap gets 60% of the container's memory limit (mem_limit); the rest is for metaspace, threads and buffers.
# Out of heap, the JVM exits and Docker restarts it, rather than limping on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
# /actuator/health is UP once the database answers. Shell form, so a SERVER_PORT from the environment applies.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health" > /dev/null || exit 1
