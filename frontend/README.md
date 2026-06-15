# Hugin Frontend

This frontend is currently a fully mocked React + Vite prototype that matches the provided product design.
It intentionally does not connect to the backend yet and simulates login, history, integrations, and sandbox file activity in-memory.

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

## Notes

- The entire app lives in `src/App.tsx` and `src/styles.css`.
- All data is mocked locally in component state.
- The sandbox file tree updates are simulated to demonstrate the intended future backend behavior.
