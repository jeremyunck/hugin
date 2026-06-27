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
    && cp backend/target/bouw-backend-*.jar /build/app.jar

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 — Slim Java 21 runtime image.
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

# The Docker CLI client lets Bouw orchestrate per-project sandbox containers through the host's
# Docker socket (mounted via docker-compose.sandbox.yml). Only the client is installed — there is no
# daemon in this image; it talks to the host daemon over the bind-mounted /var/run/docker.sock.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg git \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli \
    && rm -rf /var/lib/apt/lists/*

# Run as a dedicated non-root user. AGENT_HOME defaults under this user's home.
RUN groupadd --system bouw \
    && useradd --system --create-home --home-dir /home/bouw --gid bouw bouw \
    && mkdir -p /home/bouw/.bouw /workspace \
    && chown -R bouw:bouw /home/bouw /workspace

WORKDIR /home/bouw
COPY --from=backend /build/app.jar /app/app.jar

ENV AGENT_HOME=/home/bouw/.bouw \
    JAVA_OPTS=""

USER bouw
EXPOSE 8080

# Allow JAVA_OPTS to be injected (e.g. memory limits) without rebuilding.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
