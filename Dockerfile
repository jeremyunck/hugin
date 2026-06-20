# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build the React frontend with Node.
# ──────────────────────────────────────────────────────────────────────────────
FROM node:22-bookworm-slim AS frontend
WORKDIR /frontend

# Copy manifests first so `npm ci` is cached until dependencies actually change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Now copy the rest of the frontend sources and build the static bundle into dist/.
COPY frontend/ ./
RUN npm run build

# ──────────────────────────────────────────────────────────────────────────────
# Stage 2 — Build the Spring Boot backend with Java 21 + Maven.
# ──────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build

# Copy the POMs first and pre-fetch dependencies so this layer is cached until a
# pom changes. The `frontend` Maven profile (npm install/build) is disabled here
# because the frontend is built in stage 1 and copied in below.
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -P-frontend -pl backend -am dependency:go-offline

# Copy backend sources.
COPY backend/src backend/src

# Drop the frontend bundle from stage 1 into Spring Boot's static resources so it
# is served from the same process and packaged into the jar.
COPY --from=frontend /frontend/dist/ backend/src/main/resources/static/

# Package the executable jar (skip the npm-driven frontend profile and tests).
RUN mvn -B -P-frontend -pl backend -am -DskipTests clean package \
    && cp backend/target/hugin-backend-*.jar /build/app.jar

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 — Slim Java 21 runtime image.
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

# Run as a dedicated non-root user. AGENT_HOME defaults under this user's home.
RUN groupadd --system hugin \
    && useradd --system --create-home --home-dir /home/hugin --gid hugin hugin \
    && mkdir -p /home/hugin/.hugin /workspace \
    && chown -R hugin:hugin /home/hugin /workspace

WORKDIR /home/hugin
COPY --from=backend /build/app.jar /app/app.jar

ENV AGENT_HOME=/home/hugin/.hugin \
    JAVA_OPTS=""

USER hugin
EXPOSE 8080

# Allow JAVA_OPTS to be injected (e.g. memory limits) without rebuilding.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
