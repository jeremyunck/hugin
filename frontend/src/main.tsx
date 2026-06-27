import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";

/**
 * In mock mode we seed browser storage before the first render so the app boots straight into the
 * signed-in experience. The seed module (and all fixture data) is dynamically imported behind the
 * build-time `VITE_BOUW_MOCK_MODE` guard, so it never loads in a normal build.
 */
async function bootstrap() {
  if (import.meta.env.VITE_BOUW_MOCK_MODE === "true") {
    const { seedMockStorage } = await import("./mocks/seed");
    seedMockStorage();
  }

  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}

void bootstrap();
