# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy POMs first so dependency resolution is cached separately from source.
COPY pom.xml .
COPY agent-core/pom.xml agent-core/
COPY mcp-client/pom.xml mcp-client/
COPY mcp-integration/pom.xml mcp-integration/
RUN mvn -pl mcp-integration -am dependency:go-offline --no-transfer-progress -q

# Copy sources and package the mcp-integration fat jar.
COPY agent-core/src agent-core/src
COPY mcp-client/src mcp-client/src
COPY mcp-integration/src mcp-integration/src
RUN mvn -pl mcp-integration -am clean package -DskipTests --no-transfer-progress -q

# ---- Runtime stage ----
# Lean JRE image; Python 3 is added for the OpenRouter web-search MCP server.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv curl && \
    python3 -m venv /opt/mcp-venv && \
    /opt/mcp-venv/bin/pip install --no-cache-dir mcp && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app

COPY --from=build /build/mcp-integration/target/mcp-integration-*.jar app.jar
COPY openrouter-search-mcp.py /app/openrouter-search-mcp.py
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# Add the venv to PATH so subprocesses spawned by the JVM resolve python3 from it.
ENV PATH="/opt/mcp-venv/bin:$PATH"
# Override relative paths from application.yml with absolute paths valid inside the container.
# MCP_CONFIG_FILE: if absent, the server starts with no persisted servers (web-search is auto-registered).
# SEARCH_OPENROUTER_SCRIPT: points at the copy bundled in the image.
# AGENT_TOOLS_ENABLED: disabled by default in cloud; set to true + mount a workspace if needed.
ENV MCP_CONFIG_FILE=/app/mcp-servers.json
ENV SEARCH_OPENROUTER_SCRIPT=/app/openrouter-search-mcp.py
ENV AGENT_TOOLS_ENABLED=false

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
