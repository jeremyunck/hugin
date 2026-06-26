import { useState } from "react";
import { ChevronDown, ChevronRight, FileText, Folder, FolderOpen, RefreshCw } from "lucide-react";

import type { ChatThread, FileNode } from "../../lib/types";
import { COLORS } from "../../lib/theme";

function FileNodeItem({ node, depth }: { node: FileNode; depth: number }) {
  const [open, setOpen] = useState(depth < 1);

  if (node.type === "dir") {
    return (
      <>
        <div
          className="dp-file-row dp-file-row-dir"
          style={{ paddingLeft: depth * 14 + 8 }}
          onClick={() => setOpen((o) => !o)}
        >
          {open
            ? <ChevronDown size={12} color={COLORS.faint} />
            : <ChevronRight size={12} color={COLORS.faint} />}
          {open
            ? <FolderOpen size={13} strokeWidth={2} color={COLORS.ink} />
            : <Folder size={13} strokeWidth={2} color={COLORS.ink} />}
          <span>{node.name}</span>
        </div>
        {open ? node.children?.map((child) => (
          <FileNodeItem key={child.path} node={child} depth={depth + 1} />
        )) : null}
      </>
    );
  }

  return (
    <div className="dp-file-row" style={{ paddingLeft: depth * 14 + 8 }}>
      <FileText size={12} strokeWidth={2} color={COLORS.muted} />
      <span className="mono">{node.name}</span>
    </div>
  );
}

export function DesktopProjectPanel(props: {
  thread: ChatThread;
  files: FileNode[];
  wsOpen: boolean;
  onToggleWs: () => void;
  sandboxStatus?: string;
}) {
  const { thread, files } = props;
  const projectName =
    thread.repoName ?? thread.repoFullName ?? (thread.kind === "agent" ? "Agent workspace" : "Project");
  const isProject = thread.kind === "github" || thread.kind === "agent";

  return (
    <aside className="desktop-panel">
      {/* Panel header */}
      <div className="dp-header">
        <span className="dp-header-title">Project Context</span>
        <div className="dp-header-actions">
          <button type="button" className="icon-button" aria-label="Refresh context">
            <RefreshCw size={14} strokeWidth={2} />
          </button>
        </div>
      </div>

      {/* Project name */}
      {isProject ? (
        <div className="dp-project-name">
          <span>{projectName}</span>
          <ChevronDown size={14} color={COLORS.muted} />
        </div>
      ) : null}

      {/* Overview */}
      <div className="dp-section">
        <div className="dp-section-label">Overview</div>
        <div className="dp-overview-grid">
          <span className="dp-overview-key">Language</span>
          <span className="dp-overview-val">—</span>
          <span className="dp-overview-key">Framework</span>
          <span className="dp-overview-val">—</span>
          <span className="dp-overview-key">Size</span>
          <span className="dp-overview-val">{files.length > 0 ? `${countFiles(files)} files` : "—"}</span>
          {props.sandboxStatus ? (
            <>
              <span className="dp-overview-key">Sandbox</span>
              <span className="dp-overview-val">{props.sandboxStatus}</span>
            </>
          ) : null}
        </div>
      </div>

      {/* File tree */}
      <div className="dp-files-section">
        <div className="dp-section-label">Files</div>
        <div className="dp-file-tree">
          {files.length > 0 ? (
            files.map((node) => <FileNodeItem key={node.path} node={node} depth={0} />)
          ) : (
            <div className="dp-empty">No files</div>
          )}
        </div>
      </div>

      {/* Terminal */}
      <div className="dp-terminal-section">
        <div className="dp-section-label">Terminal</div>
        <div className="dp-terminal">
          <div className="dp-terminal-tabs">
            <span className="dp-terminal-tab">bash</span>
            <button type="button" className="dp-terminal-tab-action" aria-label="New tab">+</button>
            <button type="button" className="dp-terminal-tab-action" aria-label="Options">···</button>
          </div>
          <div className="dp-terminal-body">
            <div className="dp-terminal-line">
              <span className="dp-terminal-ps1">$</span>
              <span> git status</span>
            </div>
            <div className="dp-terminal-line dp-terminal-out">On branch main</div>
            <div className="dp-terminal-line dp-terminal-out">
              Your branch is up to date with &apos;origin/main&apos;.
            </div>
            <div className="dp-terminal-line dp-terminal-out"> </div>
            <div className="dp-terminal-line dp-terminal-out">nothing to commit, working tree clean</div>
            <div className="dp-terminal-line dp-terminal-out"> </div>
            <div className="dp-terminal-line">
              <span className="dp-terminal-ps1">$</span>
              <span className="dp-terminal-cursor" />
            </div>
          </div>
        </div>
      </div>
    </aside>
  );
}

function countFiles(nodes: FileNode[]): number {
  let n = 0;
  for (const node of nodes) {
    if (node.type === "file") n++;
    else if (node.children) n += countFiles(node.children);
  }
  return n;
}
