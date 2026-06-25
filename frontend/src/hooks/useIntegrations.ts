import { useCallback, useEffect, useRef, useState } from "react";

import type { AuthSession, GitHubStatus, Integration } from "../lib/types";
import type { Screen } from "../lib/screen";
import { clearLaunchScreen, readLaunchScreen } from "../lib/launch";
import { delay } from "../services/apiClient";
import { disconnectGoogle, fetchIntegrations, reconnectGoogle } from "../services/integrationApi";
import { connectGitHub, disconnectGitHub, fetchGitHubStatus } from "../services/githubApi";

/**
 * Owns the Integrations screen state and the connect/disconnect/reconnect actions for Google and
 * GitHub. Loads on entry, refreshes on tab focus, and polls after a Google OAuth popup so the card
 * flips to "connected" without a manual refresh.
 */
export function useIntegrations(params: {
  session: AuthSession | null;
  screen: Screen;
  onError: (message: string) => void;
  onGitHubStatus: (status: GitHubStatus | null) => void;
}) {
  const { session, screen, onError, onGitHubStatus } = params;
  const [integrations, setIntegrations] = useState<Integration[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const visibleRef = useRef(false);
  useEffect(() => {
    visibleRef.current = screen === "integrations";
  }, [screen]);

  const loadIntegrations = useCallback(
    async (options?: { clearOnFailure?: boolean; silent?: boolean }) => {
      if (!session) return null;
      const silent = Boolean(options?.silent);
      if (!silent) {
        setLoading(true);
        setError(null);
      }
      try {
        const next = await fetchIntegrations(session.token);
        if (!silent || visibleRef.current) {
          setIntegrations(next);
        }
        return next;
      } catch (e) {
        if ((!silent || visibleRef.current) && options?.clearOnFailure) {
          setIntegrations([]);
        }
        if (!silent || visibleRef.current) {
          setError(e instanceof Error ? e.message : "Could not refresh integrations.");
        }
        return null;
      } finally {
        if (!silent) {
          setLoading(false);
        }
      }
    },
    [session]
  );

  const pollGoogleIntegrationRefresh = useCallback(async () => {
    if (!session) return;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      if (!visibleRef.current) {
        return;
      }
      const next = await loadIntegrations({ silent: true });
      if (next?.find((integration) => integration.id === "google")?.connected) {
        return;
      }
      await delay(1500);
    }
  }, [session, loadIntegrations]);

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    void loadIntegrations({ clearOnFailure: true }).finally(() => {
      if (readLaunchScreen() === "integrations") {
        clearLaunchScreen();
      }
    });
  }, [session, screen, loadIntegrations]);

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    const refresh = () => {
      if (document.visibilityState !== "visible") return;
      void loadIntegrations();
    };
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [session, screen, loadIntegrations]);

  const toggleIntegration = useCallback(
    async (integration: Integration) => {
      if (!session || (integration.id !== "google" && integration.id !== "github")) return;
      setBusyId(integration.id);
      try {
        if (integration.id === "google") {
          if (integration.connected) {
            await disconnectGoogle(session.token);
            setIntegrations((current) => current.map((item) => (
              item.id === "google" ? { ...item, connected: false } : item
            )));
          } else {
            const authUrl = await reconnectGoogle(session.token, window.location.href);
            if (authUrl) {
              window.open(authUrl, "_blank", "noopener");
              void pollGoogleIntegrationRefresh();
            }
          }
        } else if (integration.id === "github") {
          if (integration.connected) {
            await disconnectGitHub(session.token);
          } else {
            const response = await connectGitHub(session.token, window.location.href);
            const installUrl = response.installUrl;
            if (installUrl) {
              window.location.assign(installUrl);
              return;
            }
            onError(
              typeof response.status?.message === "string" && response.status.message
                ? response.status.message
                : integration.message || "GitHub connect is unavailable until the GitHub App is configured."
            );
            await loadIntegrations();
            return;
          }
        }
        await loadIntegrations();
        onGitHubStatus(await fetchGitHubStatus(session.token));
      } catch (e) {
        onError(e instanceof Error ? e.message : "Integration update failed.");
      } finally {
        setBusyId(null);
      }
    },
    [session, loadIntegrations, pollGoogleIntegrationRefresh, onError, onGitHubStatus]
  );

  const reconnectIntegration = useCallback(
    async (integration: Integration) => {
      if (!session || (integration.id !== "google" && integration.id !== "github")) return;
      setBusyId(integration.id);
      try {
        if (integration.id === "google") {
          const authUrl = await reconnectGoogle(session.token, window.location.href);
          if (authUrl) {
            window.open(authUrl, "_blank", "noopener");
            void pollGoogleIntegrationRefresh();
          } else {
            await loadIntegrations();
          }
        } else if (integration.id === "github") {
          const response = await connectGitHub(session.token, window.location.href);
          const installUrl = response.installUrl;
          if (installUrl) {
            window.location.assign(installUrl);
            return;
          }
          onError(
            typeof response.status?.message === "string" && response.status.message
              ? response.status.message
              : integration.message || "GitHub connect is unavailable until the GitHub App is configured."
          );
        }
        await loadIntegrations();
        onGitHubStatus(await fetchGitHubStatus(session.token));
      } catch (e) {
        onError(e instanceof Error ? e.message : "Integration update failed.");
      } finally {
        setBusyId(null);
      }
    },
    [session, loadIntegrations, pollGoogleIntegrationRefresh, onError, onGitHubStatus]
  );

  return {
    integrations,
    integrationsLoading: loading,
    integrationsError: error,
    integrationBusy: busyId,
    loadIntegrations,
    toggleIntegration,
    reconnectIntegration
  };
}
