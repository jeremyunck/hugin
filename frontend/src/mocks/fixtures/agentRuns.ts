import type { AgentRun } from "../../lib/types";
import { minutesAgo } from "./auth";

/** In-flight agent runs shown on the Agent threads screen. */
export const mockAgentRuns: AgentRun[] = [
  {
    id: "run-7c1a",
    owner: "ada@bouw.dev",
    sessionId: "thread-agent-1",
    agentId: "agent-research",
    prompt: "Research the most popular charting libraries and summarise trade-offs in a markdown table.",
    model: "anthropic/claude-opus-4.8",
    sandboxId: null,
    startedAt: minutesAgo(4),
    disconnected: false,
    cancellationRequested: false
  },
  {
    id: "run-3f88",
    owner: "ada@bouw.dev",
    sessionId: "thread-github-1",
    agentId: "agent-coder",
    prompt: "Fix the onboarding wizard mobile bug and add a regression test.",
    model: "anthropic/claude-sonnet-4.6",
    sandboxId: "sandbox-bouw-demo",
    startedAt: minutesAgo(11),
    disconnected: true,
    cancellationRequested: false
  }
];

/** Tools advertised to a sandbox; not surfaced on the screenshot screens but kept for completeness. */
export const mockTools = [
  { name: "read_file", description: "Read a file from the workspace.", server: "workspace", transport: "stdio" },
  { name: "write_file", description: "Create or overwrite a file in the workspace.", server: "workspace", transport: "stdio" },
  { name: "run_command", description: "Run a shell command in the sandbox.", server: "sandbox", transport: "stdio" }
];
