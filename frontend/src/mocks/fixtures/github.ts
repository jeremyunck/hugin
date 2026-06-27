import type {
  BugReportSummary,
  GitHubBranch,
  GitHubRepository,
  GitHubRepositoryDetail,
  GitHubStatus
} from "../../lib/types";
import { daysAgo } from "./auth";

export const mockGitHubStatus: GitHubStatus = {
  active: true,
  configured: true,
  reconnectable: true,
  authMode: "github_app",
  account: "ada-lovelace",
  message: "Connected as @ada-lovelace."
};

/** Public, illustrative repositories only — no private repos and nothing real. */
export const mockRepositories: GitHubRepository[] = [
  {
    fullName: "ada-lovelace/bouw-demo",
    name: "bouw-demo",
    owner: "ada-lovelace",
    privateRepo: false,
    defaultBranch: "main",
    description: "Example application used to demo Bouw's project mode."
  },
  {
    fullName: "ada-lovelace/analytical-engine",
    name: "analytical-engine",
    owner: "ada-lovelace",
    privateRepo: false,
    defaultBranch: "main",
    description: "A small TypeScript library of numerical helpers."
  },
  {
    fullName: "ada-lovelace/notebook-site",
    name: "notebook-site",
    owner: "ada-lovelace",
    privateRepo: false,
    defaultBranch: "develop",
    description: "Static site for publishing research notes."
  }
];

const BRANCHES_BY_REPO: Record<string, GitHubBranch[]> = {
  "ada-lovelace/bouw-demo": [{ name: "main" }, { name: "develop" }, { name: "feature/onboarding" }],
  "ada-lovelace/analytical-engine": [{ name: "main" }, { name: "release/1.x" }],
  "ada-lovelace/notebook-site": [{ name: "develop" }, { name: "main" }, { name: "draft/essays" }]
};

export function mockBranchesFor(repoFullName: string): GitHubBranch[] {
  return BRANCHES_BY_REPO[repoFullName] ?? [{ name: "main" }];
}

const DETAIL_BY_REPO: Record<string, GitHubRepositoryDetail> = {
  "ada-lovelace/bouw-demo": {
    fullName: "ada-lovelace/bouw-demo",
    name: "bouw-demo",
    owner: "ada-lovelace",
    privateRepo: false,
    defaultBranch: "main",
    description: "Example application used to demo Bouw's project mode.",
    language: "TypeScript",
    stargazers: 128,
    forks: 14,
    openIssues: 3,
    htmlUrl: "https://github.com/ada-lovelace/bouw-demo",
    pushedAt: daysAgo(1)
  }
};

export function mockRepositoryDetail(repoFullName: string): GitHubRepositoryDetail {
  const existing = DETAIL_BY_REPO[repoFullName];
  if (existing) return existing;
  const repo = mockRepositories.find((item) => item.fullName === repoFullName) ?? mockRepositories[0];
  return {
    fullName: repo.fullName,
    name: repo.name,
    owner: repo.owner,
    privateRepo: repo.privateRepo,
    defaultBranch: repo.defaultBranch,
    description: repo.description,
    language: "TypeScript",
    stargazers: 42,
    forks: 6,
    openIssues: 1,
    htmlUrl: `https://github.com/${repo.fullName}`,
    pushedAt: daysAgo(2)
  };
}

export const mockBugReports: BugReportSummary[] = [
  {
    id: "bug-1042",
    title: "Onboarding wizard skips the workspace step on mobile",
    relativePath: "bug-reports/bug-1042.json",
    createdAt: daysAgo(1)
  },
  {
    id: "bug-1039",
    title: "Composer loses focus after sending a message with an attachment",
    relativePath: "bug-reports/bug-1039.json",
    createdAt: daysAgo(3)
  }
];
