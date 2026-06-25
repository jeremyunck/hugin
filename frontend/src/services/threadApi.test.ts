import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchChatSessionEvents } from "./threadApi";

describe("threadApi reconnect/resume", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("requests only events after the last seen sequence number on reconnect", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ sessionId: "s1", events: [{ id: "e", seq: 6, type: "assistant_token" }] })
    });
    vi.stubGlobal("fetch", fetchMock);

    const events = await fetchChatSessionEvents("token", "s1", 5);

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toBe("/api/chat/sessions/s1/events?afterSeq=5");
    expect(events).toHaveLength(1);
    expect(events[0].seq).toBe(6);
  });

  it("omits the afterSeq query for a fresh load so the full transcript is fetched", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ sessionId: "s1", events: [] })
    });
    vi.stubGlobal("fetch", fetchMock);

    await fetchChatSessionEvents("token", "s1");

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toBe("/api/chat/sessions/s1/events");
  });

  it("surfaces the server error body when the events request fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: "Not Found",
        json: async () => ({ error: "session not found" })
      })
    );

    await expect(fetchChatSessionEvents("token", "missing")).rejects.toThrow("session not found");
  });
});
