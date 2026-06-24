import { describe, expect, it } from "vitest";

import {
  MAX_TOOL_CALLS_MAX,
  MAX_TOOL_CALLS_MIN,
  REQUEST_TIMEOUT_MAX,
  REQUEST_TIMEOUT_MIN,
  normalizeMaxToolCalls,
  normalizeRequestTimeout,
  resolveDefaultModelId
} from "./preferences";
import type { ModelOption } from "./types";

function model(id: string, enabled: boolean): ModelOption {
  return { id, name: id.toUpperCase(), reasoningOptions: [], enabled };
}

describe("resolveDefaultModelId", () => {
  it("keeps the current default while it stays enabled", () => {
    const models = [model("a", true), model("b", true), model("c", true)];
    expect(resolveDefaultModelId(models, "b")).toBe("b");
  });

  it("advances to the next enabled model in the list when the default is disabled", () => {
    const models = [model("a", true), model("b", false), model("c", true)];
    expect(resolveDefaultModelId(models, "b")).toBe("c");
  });

  it("skips other disabled models when finding the next default", () => {
    const models = [model("a", true), model("b", false), model("c", false), model("d", true)];
    expect(resolveDefaultModelId(models, "b")).toBe("d");
  });

  it("falls back to the first enabled model when nothing follows the disabled default", () => {
    const models = [model("a", true), model("b", true), model("c", false)];
    expect(resolveDefaultModelId(models, "c")).toBe("a");
  });

  it("seeds the first enabled model when there is no stored default", () => {
    const models = [model("a", false), model("b", true), model("c", true)];
    expect(resolveDefaultModelId(models, null)).toBe("b");
  });

  it("returns null when no models are enabled", () => {
    const models = [model("a", false), model("b", false)];
    expect(resolveDefaultModelId(models, "a")).toBeNull();
  });
});

describe("normalizeMaxToolCalls", () => {
  it("keeps a valid in-range value", () => {
    expect(normalizeMaxToolCalls(25)).toBe(25);
  });

  it("parses numeric strings and rounds to whole steps", () => {
    expect(normalizeMaxToolCalls("12")).toBe(12);
    expect(normalizeMaxToolCalls("12.6")).toBe(13);
  });

  it("treats blank, zero, negative, and non-numeric input as the server default (null)", () => {
    expect(normalizeMaxToolCalls("")).toBeNull();
    expect(normalizeMaxToolCalls(0)).toBeNull();
    expect(normalizeMaxToolCalls(-5)).toBeNull();
    expect(normalizeMaxToolCalls("abc")).toBeNull();
    expect(normalizeMaxToolCalls(null)).toBeNull();
  });

  it("clamps values to the supported range", () => {
    expect(normalizeMaxToolCalls(5000)).toBe(MAX_TOOL_CALLS_MAX);
    expect(normalizeMaxToolCalls(MAX_TOOL_CALLS_MIN)).toBe(MAX_TOOL_CALLS_MIN);
  });
});

describe("normalizeRequestTimeout", () => {
  it("keeps a valid in-range value", () => {
    expect(normalizeRequestTimeout(120)).toBe(120);
  });

  it("parses numeric strings and rounds to whole seconds", () => {
    expect(normalizeRequestTimeout("90")).toBe(90);
    expect(normalizeRequestTimeout("90.4")).toBe(90);
  });

  it("treats blank, zero, negative, and non-numeric input as the server default (null)", () => {
    expect(normalizeRequestTimeout("")).toBeNull();
    expect(normalizeRequestTimeout(0)).toBeNull();
    expect(normalizeRequestTimeout(-5)).toBeNull();
    expect(normalizeRequestTimeout("abc")).toBeNull();
    expect(normalizeRequestTimeout(null)).toBeNull();
  });

  it("clamps values to the supported range", () => {
    expect(normalizeRequestTimeout(99999)).toBe(REQUEST_TIMEOUT_MAX);
    expect(normalizeRequestTimeout(1)).toBe(REQUEST_TIMEOUT_MIN);
  });
});
