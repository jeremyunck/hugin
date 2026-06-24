import { describe, expect, it } from "vitest";

import { resolveDefaultModelId } from "./preferences";
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
