import { ArrowLeft, Github } from "lucide-react";

import type { BugReportSummary, GitHubBranch, GitHubRepository } from "../lib/types";

/**
 * Picks the branch to preselect in the project setup screen: the currently chosen branch if it still
 * exists, else the repository default branch, else the first available branch.
 */
export function resolvePreferredGitHubBranch(
  branches: GitHubBranch[],
  defaultBranch: string | null | undefined,
  currentBranch?: string
) {
  if (currentBranch && branches.some((branch) => branch.name === currentBranch)) {
    return currentBranch;
  }
  if (defaultBranch) {
    const matchingDefault = branches.find((branch) => branch.name === defaultBranch);
    if (matchingDefault) return matchingDefault.name;
  }
  return branches[0]?.name ?? "";
}

export function GitHubProjectSetupScreen(props: {
  busy: boolean;
  loadingRepos: boolean;
  loadingBranches: boolean;
  loadingBugReports: boolean;
  repositories: GitHubRepository[];
  branches: GitHubBranch[];
  bugReports: BugReportSummary[];
  selectedRepo: string;
  selectedBranch: string;
  selectedBugReportId: string;
  error: string | null;
  onBack: () => void;
  onRepoChange: (value: string) => void;
  onBranchChange: (value: string) => void;
  onBugReportChange: (value: string) => void;
  onConfirm: () => void;
}) {
  const {
    busy,
    loadingRepos,
    loadingBranches,
    loadingBugReports,
    repositories,
    branches,
    bugReports,
    selectedRepo,
    selectedBranch,
    selectedBugReportId,
    error,
    onBack,
    onRepoChange,
    onBranchChange,
    onBugReportChange,
    onConfirm
  } = props;
  const selectedRepoMeta = repositories.find((repo) => repo.fullName === selectedRepo);
  const ready = Boolean(selectedRepo && selectedBranch) && !busy && !loadingRepos && !loadingBranches;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Project</h1>
        <p className="integration-subtitle">
          Pick a repository and branch, then Hugin will open a fresh workspace with a clean pull of that branch.
        </p>
      </div>

      <div className="repo-setup-card">
        <label className="composer-select repo-setup-select">
          <span>Repository</span>
          <select value={selectedRepo} onChange={(event) => onRepoChange(event.target.value)} disabled={busy || loadingRepos}>
            <option value="">{loadingRepos ? "Loading repositories…" : "Select a repository"}</option>
            {repositories.map((repo) => (
              <option key={repo.fullName} value={repo.fullName}>
                {repo.fullName}
              </option>
            ))}
          </select>
        </label>

        <label className="composer-select repo-setup-select">
          <span>Branch</span>
          <select
            value={selectedBranch}
            onChange={(event) => onBranchChange(event.target.value)}
            disabled={busy || !selectedRepo || loadingBranches}
          >
            <option value="">
              {!selectedRepo ? "Select a repository first" : loadingBranches ? "Loading branches…" : "Select a branch"}
            </option>
            {branches.map((branch) => (
              <option key={branch.name} value={branch.name}>
                {branch.name}
              </option>
            ))}
          </select>
        </label>

        <label className="composer-select repo-setup-select">
          <span>Bug report</span>
          <select
            value={selectedBugReportId}
            onChange={(event) => onBugReportChange(event.target.value)}
            disabled={busy || loadingBugReports}
          >
            <option value="">
              {loadingBugReports ? "Loading bug reports…" : "None"}
            </option>
            {bugReports.map((report) => (
              <option key={report.id} value={report.id}>
                {report.title}
              </option>
            ))}
          </select>
        </label>

        {selectedRepoMeta ? (
          <div className="repo-summary">
            <div className="repo-summary-title">
              <Github size={16} strokeWidth={2} />
              <span>{selectedRepoMeta.fullName}</span>
            </div>
            <div className="repo-summary-meta">
              <span>{selectedRepoMeta.privateRepo ? "Private" : "Public"}</span>
              <span>Default {selectedRepoMeta.defaultBranch || "unknown"}</span>
            </div>
            {selectedRepoMeta.description ? <p>{selectedRepoMeta.description}</p> : null}
          </div>
        ) : null}

        {error ? <p className="login-error">{error}</p> : null}

        <button type="button" className="primary-button repo-confirm-button" onClick={onConfirm} disabled={!ready}>
          {busy ? "Creating workspace…" : "Open project"}
        </button>
      </div>
    </>
  );
}
