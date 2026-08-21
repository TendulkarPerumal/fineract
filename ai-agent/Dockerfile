# Multi-stage: the build tooling is not shipped to Cloud Run.
#
# Note this builds ai-agent alone. Fineract itself is Gradle-built and is not
# part of the runtime image: this service only reads a subset of Fineract's
# schema, it does not run Fineract.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-resolve the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are not run here. The eval suite needs a seeded database and a live
# model; CI runs the tests where those exist, and a Docker build that silently
# skips them would be worse than one that never claims to run them.
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root. Cloud Run does not require it, but a container that only needs to
# read a database has no reason to run as root.
RUN groupadd --system agent && useradd --system --gid agent --home /app agent
COPY --from=build /build/target/*.jar app.jar
USER agent

# Neon runs UTC and Cloud Run runs UTC; pinning the JVM removes the last place
# a LocalDate could shift by a day and turn a not-yet-due instalment into an
# overdue one (docs/DOMAIN.md section 9.7).
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75"

# Cloud Run injects PORT and expects the app to honour it; application.yml
# reads ${PORT:8080}. EXPOSE is documentation only.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
