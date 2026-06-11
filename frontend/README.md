# HuginApp

HuginApp is the web console for the Hugin backend. It connects to the local agent server,
streams chat responses, and surfaces the agent's built-in tools.

## Requirements

- Node.js 18 or newer
- The Hugin backend running on `http://localhost:8080`

## Run the frontend

```bash
npm install
npm run dev
```

Then open:

```bash
http://127.0.0.1:5173
```

The app defaults to `http://localhost:8080` for API calls, so it should work with the local Hugin backend out of the box.

In Settings, you can configure a routing trio for the agent:

- `Decision model` classifies each prompt as simple or complex
- `Complex model` handles heavier requests
- `Simple model` handles lighter requests
- Leave those fields blank to fall back to the legacy single-model path

## Build for production

```bash
npm run build
```

## What the app uses

- `POST /api/agent/stream` for streamed chat responses
- `GET /api/agent/tools` for the tool catalog
- `GET /api/agent/agents` for agent management

## Notes

- The Agents tab is currently a no-op mock.
- If your backend runs on a different host or port, update the Base URL in Settings.
