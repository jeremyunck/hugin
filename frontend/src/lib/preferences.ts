import type { ModelOption } from "./types";

const PREFERENCES_STORAGE_KEY = "hugin-preferences-v1";

export type FontSizeId = "small" | "medium" | "large" | "xlarge";

export type FontSizeOption = {
  id: FontSizeId;
  label: string;
  /** Multiplier applied to every font-size in the app via the --font-scale CSS variable. */
  scale: number;
};

/** Ordered smallest → largest so the Settings page can render them as a row of choices. */
export const FONT_SIZE_OPTIONS: FontSizeOption[] = [
  { id: "small", label: "Small", scale: 0.9 },
  { id: "medium", label: "Default", scale: 1 },
  { id: "large", label: "Large", scale: 1.15 },
  { id: "xlarge", label: "Extra large", scale: 1.3 }
];

export const DEFAULT_FONT_SIZE: FontSizeId = "medium";

export type AppPreferences = {
  fontSize: FontSizeId;
  /** Preferred model for new chats; falls back to the first enabled model when unset/disabled. */
  defaultModelId: string | null;
};

export function defaultPreferences(): AppPreferences {
  return { fontSize: DEFAULT_FONT_SIZE, defaultModelId: null };
}

function isFontSizeId(value: unknown): value is FontSizeId {
  return typeof value === "string" && FONT_SIZE_OPTIONS.some((option) => option.id === value);
}

export function loadPreferences(): AppPreferences {
  if (typeof window === "undefined") return defaultPreferences();
  const raw = window.localStorage.getItem(PREFERENCES_STORAGE_KEY);
  if (!raw) return defaultPreferences();
  try {
    const parsed = JSON.parse(raw) as Partial<AppPreferences>;
    return {
      fontSize: isFontSizeId(parsed.fontSize) ? parsed.fontSize : DEFAULT_FONT_SIZE,
      defaultModelId: typeof parsed.defaultModelId === "string" ? parsed.defaultModelId : null
    };
  } catch {
    return defaultPreferences();
  }
}

export function savePreferences(preferences: AppPreferences) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(preferences));
}

export function fontScaleFor(fontSize: FontSizeId): number {
  return FONT_SIZE_OPTIONS.find((option) => option.id === fontSize)?.scale ?? 1;
}

/** Pushes the chosen font scale onto the document root so every `--font-scale` consumer updates. */
export function applyFontSize(fontSize: FontSizeId) {
  if (typeof document === "undefined") return;
  document.documentElement.style.setProperty("--font-scale", String(fontScaleFor(fontSize)));
}

/**
 * Resolves the default model id against the currently enabled models.
 *
 * - Keeps the current default when it is still enabled.
 * - When the current default was disabled, advances to the next enabled model *after* it in the
 *   full model list (the same order shown in Model settings), wrapping to the first enabled model
 *   when nothing follows.
 * - Returns null when no models are enabled.
 */
export function resolveDefaultModelId(models: ModelOption[], currentDefaultId: string | null): string | null {
  const enabled = models.filter((model) => model.enabled);
  if (enabled.length === 0) return null;
  if (currentDefaultId && enabled.some((model) => model.id === currentDefaultId)) {
    return currentDefaultId;
  }
  if (currentDefaultId) {
    const previousIndex = models.findIndex((model) => model.id === currentDefaultId);
    if (previousIndex >= 0) {
      const nextEnabled = models.slice(previousIndex + 1).find((model) => model.enabled);
      if (nextEnabled) return nextEnabled.id;
    }
  }
  return enabled[0].id;
}
