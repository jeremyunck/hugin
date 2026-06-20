// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { HistoryScreen } from "./App";
import type { ChatThread } from "./lib/types";

function buildThread(id: string, title: string, updatedAt: string): ChatThread {
  return {
    id,
    title,
    kind: "chat",
    modelId: "gpt-5",
    reasoningEffort: "medium",
    createdAt: updatedAt,
    updatedAt,
    entries: [
      {
        id: `user-${id}`,
        type: "user",
        content: title,
        createdAt: updatedAt
      }
    ]
  };
}

describe("HistoryScreen", () => {
  afterEach(() => {
    cleanup();
  });

  const threads = [
    buildThread("thread-1", "Delete me", "2026-06-19T10:00:00.000Z"),
    buildThread("thread-2", "Keep me", "2026-06-18T10:00:00.000Z")
  ];

  it("renders a delete button for each thread and calls onDelete for the selected item", async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn();

    const { container } = render(
      <HistoryScreen
        threads={threads}
        onMenu={() => {}}
        onOpen={() => {}}
        onDelete={onDelete}
        onNew={() => {}}
        deletingId={null}
        query=""
        onQuery={() => {}}
      />
    );

    const deleteButtons = [...container.querySelectorAll<HTMLButtonElement>(".history-card-delete")];
    expect(deleteButtons).toHaveLength(threads.length);

    await user.click(deleteButtons[0]);
    expect(onDelete).toHaveBeenCalledWith(threads[0]);
  });

  it("disables the active delete button while a deletion is in progress", () => {
    const { container } = render(
      <HistoryScreen
        threads={threads}
        onMenu={() => {}}
        onOpen={() => {}}
        onDelete={() => {}}
        onNew={() => {}}
        deletingId="thread-1"
        query=""
        onQuery={() => {}}
      />
    );

    const deleteButtons = [...container.querySelectorAll<HTMLButtonElement>(".history-card-delete")];
    expect(deleteButtons[0]).toHaveProperty("disabled", true);
    expect(deleteButtons[1]).toHaveProperty("disabled", false);
  });
});
