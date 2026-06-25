import type { GitHubBranch, GitHubRepository, GitHubStatus } from "../lib/types";
import { apiFetch } from "./apiClient";

type GitHubConnectResponse = {
  status?: {
    message?: string;
  };
  installUrl: string | null;
};

export async function connectGitHub(token: string, returnTo: string): Promise<GitHubConnectResponse> {
  return apiFetch<GitHubConnectResponse>(
    "/api/github/connect",
    { method: "POST", body: JSON.stringify({ returnTo }) },
    token
  );
}

export async function disconnectGitHub(token: string): Promise<void> {
  await apiFetch("/api/github/disconnect", { method: "POST", body: JSON.stringify({}) }, token);
}

export async function fetchGitHubStatus(token: string): Promise<GitHubStatus> {
  return apiFetch<GitHubStatus>("/api/github/status", {}, token);
}

export async function fetchGitHubRepositories(token: string): Promise<GitHubRepository[]> {
  return apiFetch<GitHubRepository[]>("/api/github/repositories", {}, token);
}

export async function fetchGitHubBranches(token: string, repoFullName: string): Promise<GitHubBranch[]> {
  const [owner, repo] = repoFullName.split("/", 2);
  if (!owner || !repo) {
    throw new Error("Repository must be in owner/repo format.");
  }
  return apiFetch<GitHubBranch[]>(
    `/api/github/repositories/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches`,
    {},
    token
  );
}
