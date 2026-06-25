import { useCallback, useEffect, useState } from "react";

import type {
  AuthSession,
  BugReportSummary,
  ChatAttachment,
  GitHubBranch,
  GitHubRepository,
  GitHubStatus
} from "../lib/types";
import type { Screen } from "../lib/screen";
import { resolvePreferredGitHubBranch } from "../screens/GitHubProjectSetupScreen";
import { fetchBugReports } from "../services/integrationApi";
import { fetchGitHubBranches, fetchGitHubRepositories } from "../services/githubApi";
import { createGitHubSandbox } from "../services/runApi";
import { createThread } from "../services/threadApi";

const buildGitHubBugReportPrompt = (report: BugReportSummary) =>
  [
    `A bug report has been added to this repository checkout at \`${report.relativePath}\`.`,
    "Before making changes, read `docs/skills/hugin-bug-reports/SKILL.md` and use that skill's workflow to inspect the bug report.",
    "Then diagnose the failure, add or update a regression test that covers it, and implement the fix."
  ].join(" ");

/**
 * Owns the GitHub Project setup screen: loading repositories/branches/bug reports, branch
 * preselection, and confirming a project (which provisions a host-backed clone sandbox, opens a new
 * "github" thread, and optionally queues a bug-report prompt). Navigation and shared chat state are
 * driven through the supplied callbacks so this hook stays focused on the setup flow.
 */
export function useGitHubProjectSetup(params: {
  session: AuthSession | null;
  githubStatus: GitHubStatus | null;
  screen: Screen;
  returnScreen: Screen;
  setReturnScreen: (screen: Screen) => void;
  setScreen: (screen: Screen) => void;
  setMenuOpen: (open: boolean) => void;
  setBugReportNotice: (notice: string | null) => void;
  setError: (message: string | null) => void;
  setFiles: (files: never[]) => void;
  setWsOpen: (open: boolean) => void;
  setDraft: (value: string) => void;
  setDraftAttachment: (attachment: ChatAttachment | null) => void;
  setPendingAutoPrompt: (prompt: string | null) => void;
  switchThread: (thread: ReturnType<typeof createThread>) => void;
  refreshFiles: (sandboxId?: string) => Promise<void> | void;
}) {
  const {
    session,
    githubStatus,
    screen,
    returnScreen,
    setReturnScreen,
    setScreen,
    setMenuOpen,
    setBugReportNotice,
    setError,
    setFiles,
    setWsOpen,
    setDraft,
    setDraftAttachment,
    setPendingAutoPrompt,
    switchThread,
    refreshFiles
  } = params;

  const [repoOptions, setRepoOptions] = useState<GitHubRepository[]>([]);
  const [branchOptions, setBranchOptions] = useState<GitHubBranch[]>([]);
  const [selectedRepo, setSelectedRepo] = useState("");
  const [selectedBranch, setSelectedBranch] = useState("");
  const [bugReports, setBugReports] = useState<BugReportSummary[]>([]);
  const [selectedBugReportId, setSelectedBugReportId] = useState("");
  const [loadingRepos, setLoadingRepos] = useState(false);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [loadingBugReports, setLoadingBugReports] = useState(false);
  const [workspaceBusy, setWorkspaceBusy] = useState(false);

  const openGitHubRepoSetup = useCallback(async () => {
    if (!session || !githubStatus?.active) return;
    setReturnScreen(screen === "github-repo" ? returnScreen : screen);
    setScreen("github-repo");
    setMenuOpen(false);
    setBugReportNotice(null);
    setError(null);
    setPendingAutoPrompt(null);
    setSelectedRepo("");
    setSelectedBranch("");
    setSelectedBugReportId("");
    setBranchOptions([]);
    setBugReports([]);
    setLoadingRepos(true);
    setLoadingBugReports(githubStatus?.active === true);
    try {
      const [repos, reports] = await Promise.all([
        fetchGitHubRepositories(session.token),
        githubStatus?.active ? fetchBugReports(session.token) : Promise.resolve([])
      ]);
      setRepoOptions(repos);
      setBugReports(reports);
    } catch (e) {
      setRepoOptions([]);
      setBugReports([]);
      setError(e instanceof Error ? e.message : "Could not load GitHub repositories.");
    } finally {
      setLoadingRepos(false);
      setLoadingBugReports(false);
    }
  }, [session, githubStatus?.active, screen, returnScreen, setReturnScreen, setScreen, setMenuOpen, setBugReportNotice, setError, setPendingAutoPrompt]);

  const chooseRepo = useCallback(
    async (repoFullName: string) => {
      setSelectedRepo(repoFullName);
      setSelectedBranch("");
      setBranchOptions([]);
      if (!session || !repoFullName) return;
      setLoadingBranches(true);
      setError(null);
      try {
        const branches = await fetchGitHubBranches(session.token, repoFullName);
        setBranchOptions(branches);
        const defaultBranch = repoOptions.find((repo) => repo.fullName === repoFullName)?.defaultBranch;
        setSelectedBranch(resolvePreferredGitHubBranch(branches, defaultBranch));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Could not load GitHub branches.");
      } finally {
        setLoadingBranches(false);
      }
    },
    [session, repoOptions, setError]
  );

  useEffect(() => {
    if (!selectedRepo || !branchOptions.length) return;
    const defaultBranch = repoOptions.find((repo) => repo.fullName === selectedRepo)?.defaultBranch;
    const preferredBranch = resolvePreferredGitHubBranch(branchOptions, defaultBranch, selectedBranch);
    if (preferredBranch !== selectedBranch) {
      setSelectedBranch(preferredBranch);
    }
  }, [selectedRepo, selectedBranch, branchOptions, repoOptions]);

  const confirmGitHubRepo = useCallback(async () => {
    if (!session || !selectedRepo || !selectedBranch) return;
    const repo = repoOptions.find((item) => item.fullName === selectedRepo);
    if (!repo) return;
    const selectedBugReport = bugReports.find((item) => item.id === selectedBugReportId);
    setWorkspaceBusy(true);
    setError(null);
    try {
      const sandbox = await createGitHubSandbox(session.token, selectedRepo, selectedBranch, selectedBugReportId || undefined);
      setFiles([]);
      setWsOpen(false);
      setDraft("");
      setDraftAttachment(null);
      switchThread(createThread("github", {
        sandboxId: sandbox.id,
        repoFullName: repo.fullName,
        repoName: repo.name,
        branchName: selectedBranch
      }));
      setScreen("chat");
      if (selectedBugReport) {
        setPendingAutoPrompt(buildGitHubBugReportPrompt(selectedBugReport));
      }
      void refreshFiles(sandbox.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start a GitHub repo sandbox.");
    } finally {
      setWorkspaceBusy(false);
    }
  }, [session, selectedRepo, selectedBranch, selectedBugReportId, repoOptions, bugReports, refreshFiles, switchThread, setFiles, setWsOpen, setDraft, setDraftAttachment, setScreen, setPendingAutoPrompt, setError]);

  return {
    repoOptions,
    branchOptions,
    selectedRepo,
    selectedBranch,
    bugReports,
    selectedBugReportId,
    loadingRepos,
    loadingBranches,
    loadingBugReports,
    workspaceBusy,
    setSelectedBranch,
    setSelectedBugReportId,
    openGitHubRepoSetup,
    chooseRepo,
    confirmGitHubRepo
  };
}
