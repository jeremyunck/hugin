import { useState } from "react";
import { ChevronDown, ChevronRight, FileText, Folder, FolderOpen, Network } from "lucide-react";

import type { FileNode } from "../lib/types";
import { COLORS } from "../lib/theme";
import { formatBytes } from "../lib/format";

function TreeRow({
  depth = 0,
  onClick,
  children
}: {
  depth?: number;
  onClick?: () => void;
  children: React.ReactNode;
}) {
  return (
    <div
      className={`tree-row ${onClick ? "tree-row-clickable" : ""}`}
      style={{ paddingLeft: depth * 16 }}
      onClick={onClick}
    >
      {children}
    </div>
  );
}

function FileNodeRow({ node, depth, defaultOpen }: { node: FileNode; depth: number; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);

  if (node.type === "dir") {
    return (
      <>
        <TreeRow depth={depth} onClick={() => setOpen((current) => !current)}>
          {open ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
          {open ? (
            <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} />
          ) : (
            <Folder size={14} strokeWidth={2} color={COLORS.ink} />
          )}
          <span>{node.name}</span>
        </TreeRow>
        {open ? node.children?.map((child) => (
          <FileNodeRow key={child.path} node={child} depth={depth + 1} defaultOpen={defaultOpen} />
        )) : null}
      </>
    );
  }

  return (
    <TreeRow depth={depth}>
      <FileText size={13.5} strokeWidth={2} color={COLORS.muted} />
      <span className="mono">{node.name}</span>
      <span className="tree-size mono">{formatBytes(node.size)}</span>
    </TreeRow>
  );
}

/** Sandbox / GitHub repository file tree shown alongside workspace-backed chats. */
export function WorkspacePanel(props: {
  sessionId: string;
  files: FileNode[];
  wsOpen: boolean;
  onToggleWs: () => void;
  label: string;
  rootName: string;
  badge: string;
  defaultOpenDirectories: boolean;
}) {
  const { files, wsOpen, onToggleWs, label, rootName, badge, defaultOpenDirectories } = props;

  return (
    <div className="file-tree">
      <TreeRow>
        <ChevronDown size={13} color={COLORS.faint} />
        <Network size={14} strokeWidth={2} color={COLORS.ink} />
        <span className="mono">{label || "~/"}</span>
        <span className="tree-badge">{badge}</span>
      </TreeRow>

      <TreeRow depth={1} onClick={onToggleWs}>
        {wsOpen ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
        {wsOpen ? (
          <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} />
        ) : (
          <Folder size={14} strokeWidth={2} color={COLORS.ink} />
        )}
        <span>{rootName}</span>
      </TreeRow>

      {wsOpen ? (
        files.length ? (
          files.map((node) => <FileNodeRow key={node.path} node={node} depth={2} defaultOpen={defaultOpenDirectories} />)
        ) : (
          <TreeRow depth={2}>
            <span className="mono" style={{ color: COLORS.faint }}>
              (empty)
            </span>
          </TreeRow>
        )
      ) : null}
    </div>
  );
}
