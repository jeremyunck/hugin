# Bouw Frontend

A React + Vite client for the Bouw agent backend. It signs in against the backend, streams agent
responses over Server-Sent Events, runs sandbox sessions with a live workspace file tree, and manages
integrations — all against the REST API in `../backend`.

## Run

```bash
cd frontend
npm install
npm run dev
```

Open:

```text
http://127.0.0.1:5173
```

The dev server proxies `/api` to `http://localhost:8080` (see `vite.config.ts`), so start the backend
first.

## Build

```bash
npm run build
```

The production build is also produced as part of the backend Maven build and served by it.

## How it connects to the backend

- **Auth** — `POST /api/auth/login` and `GET /api/auth/me`; the JWT is sent as a bearer token on every
  request and persisted in `localStorage`.
- **Chat** — `POST /api/agent/stream` (SSE). Pure chats omit `sandboxId`; sandbox sessions send it so
  the agent gains filesystem/shell tools.
- **Sandboxes** — `POST /api/sandboxes` to start one and `GET /api/sandboxes/{id}/files` to render the
  workspace file tree.
- **Integrations** — `GET /api/integrations` lists each integration and whether it is connected;
  Google Workspace connect/disconnect goes through `/api/google/reconnect` and `/api/google/disconnect`.
- **History** is kept client-side in `localStorage`. A new conversation is only saved once the user has
  sent at least one message.

All API access lives in `src/services/guildService.ts`.
