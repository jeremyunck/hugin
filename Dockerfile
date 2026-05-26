# syntax=docker/dockerfile:1

# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -pl mcp-integration -am clean package -DskipTests \
        --batch-mode --no-transfer-progress

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# python3 + mcp package (for the openrouter-search MCP stdio server)
# wget is used by the HEALTHCHECK
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-venv wget \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir mcp \
    && rm -rf /var/lib/apt/lists/*

# Put the venv on PATH so subprocesses spawned by the JVM find the right python3
ENV PATH="/opt/venv/bin:$PATH"

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app
COPY --from=build /build/mcp-integration/target/mcp-integration-0.0.1-SNAPSHOT.jar app.jar
COPY openrouter-search-mcp.py /app/openrouter-search-mcp.py

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

# Pin container-local paths; override at runtime via docker run -e / docker compose env.
# MCP_CONFIGFILE points to a non-existent file by default — the server falls back to the
# search.provider auto-registration and starts with no persisted servers.
ENV SEARCH_OPENROUTERSCRIPT=/app/openrouter-search-mcp.py \
    MCP_CONFIGFILE=/app/mcp-servers.json \
    AGENT_TOOLS_ENABLED=false \
    AGENT_TOOLS_WORKSPACEROOT=/workspace

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
