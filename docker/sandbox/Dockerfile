# Bouw agent sandbox image.
#
# Each project (GitHub repository) chat runs in its own container built from this image. The Bouw
# backend orchestrates the agent loop and streams results; this container is purely the isolated
# execution environment — it holds the repository checkout (in a Docker volume mounted at /workspace)
# and runs the agent's bash/git/build/test commands. It never runs an HTTP server or agent runtime;
# the backend drives it entirely through `docker exec`.
#
# Build:
#   docker build -t bouw-agent-sandbox:latest docker/sandbox
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# Core toolchain the agent needs inside the sandbox: git, bash, curl, python, node/npm, jq, and the
# CA certificates required to clone over https.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        bash \
        jq \
        python3 \
        python3-pip \
        python3-venv \
        nodejs \
        npm \
        unzip \
        less \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

# Non-root user that owns the workspace. The container runs as this user so the agent cannot act as
# root inside the sandbox (reinforced by --cap-drop=ALL and --security-opt=no-new-privileges on run).
RUN useradd --create-home --shell /bin/bash bouw \
    && mkdir -p /workspace \
    && chown -R bouw:bouw /workspace

USER bouw
WORKDIR /workspace

# Keep the container alive so the backend can exec into it for the lifetime of the chat. The run
# command overrides this with `sleep infinity`; this default mirrors that for ad-hoc `docker run`.
CMD ["sleep", "infinity"]
