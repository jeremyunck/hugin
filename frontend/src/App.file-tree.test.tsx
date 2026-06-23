// @vitest-environment jsdom

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";

import { FileTree } from "./App";
import type { FileNode } from "./lib/types";

const githubFiles: FileNode[] = [
  {
    name: "frontend",
    path: "frontend",
    type: "dir",
    children: [
      {
        name: "src",
        path: "frontend/src",
        type: "dir",
        children: []
      }
    ]
  },
  {
    name: "README.md",
    path: "README.md",
    type: "file",
    size: 1024
  }
];

describe("FileTree", () => {
  afterEach(() => {
    cleanup();
  });

  it("uses the repo name as the collapsible root folder instead of a synthetic workspace node", () => {
    render(
      <FileTree
        sessionId="thread-1"
        files={githubFiles}
        wsOpen={true}
        onToggleWs={() => {}}
        label="hugin · main"
        rootName="hugin"
        badge="github"
        defaultOpenDirectories={false}
      />
    );

    expect(screen.getByText("hugin · main")).toBeTruthy();
    expect(screen.getByText("hugin")).toBeTruthy();
    expect(screen.queryByText("workspace")).toBeNull();
    expect(screen.getByText("frontend")).toBeTruthy();
    expect(screen.getByText("README.md")).toBeTruthy();
  });

  it("collapses the root folder in agent chats as well", async () => {
    const user = userEvent.setup();
    function Wrapper() {
      const [open, setOpen] = useState(true);
      return (
        <FileTree
          sessionId="agent-12345678"
          files={githubFiles}
          wsOpen={open}
          onToggleWs={() => setOpen((current) => !current)}
          label="~/"
          rootName="~"
          badge="agent"
          defaultOpenDirectories={true}
        />
      );
    }

    render(<Wrapper />);

    await user.click(screen.getByText("~"));

    expect(screen.queryByText("frontend")).toBeNull();
    expect(screen.queryByText("README.md")).toBeNull();
  });
});
