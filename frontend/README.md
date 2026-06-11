# Guild Frontend

This is the replacement Guild web UI for Hugin. It is a React + Vite app with hash-based routes so it can run locally without any special server routing.

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

## Build

```bash
npm run build
```

## Routes

- `#/chat` - Chat Home
- `#/chat/new` - New Chat
- `#/chat/check-server-status` - Check Server Status chat
- `#/chat/summarize-emails` - Summarize Emails chat
- `#/chat/research-ai-agents` - Research on AI Agents chat
- `#/history` - History list
- `#/history/:threadId` - Chat from history
- `#/settings` - Settings overview
- `#/settings/integrations` - Integrations list
- `#/settings/integrations/google-workspace` - Google Workspace detail
- `#/settings/appearance` - Appearance settings
- `#/settings/data-privacy` - Data & Privacy

## Notes

- The chat/history/integration data is mocked in `src/services/guildService.ts` and persisted to `localStorage`.
- Clear chat history is a modal state from Settings.
- The mobile menu uses a drawer; desktop uses a left sidebar.
- The raven silhouette mark is used anywhere the agent/logo appears.
