"use client";

/**
 * Session widget rail store. A session id lives in sessionStorage (new per
 * tab), and the widgets keyed by it live in localStorage — so the rail
 * survives a refresh in the same tab, but is naturally scoped to the session
 * ("saved in a session"). Capped at 4 widgets.
 */

export interface SessionWidget {
  id: string;
  /** The question that produced the result, as the card title. */
  title: string;
  result: {
    columns: { name: string; typeName: string }[];
    rows: unknown[];
    truncated: boolean;
  };
  /** Chart shape selected at pin time; undefined = infer automatically. */
  chartKind?: "BAR" | "LINE" | "PIE";
  savedAt: number;
}

export const MAX_SESSION_WIDGETS = 4;

function storageKey(): string | null {
  if (typeof window === "undefined") return null;
  let sessionId = sessionStorage.getItem("whyql.sessionId");
  if (!sessionId) {
    sessionId =
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `s-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
    sessionStorage.setItem("whyql.sessionId", sessionId);
  }
  return `whyql.sessionWidgets.${sessionId}`;
}

export function loadSessionWidgets(): SessionWidget[] {
  const key = storageKey();
  if (!key) return [];
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (w) =>
        w &&
        typeof w.id === "string" &&
        typeof w.title === "string" &&
        w.result &&
        Array.isArray(w.result.columns) &&
        Array.isArray(w.result.rows),
    ) as SessionWidget[];
  } catch {
    return [];
  }
}

export type SaveResult =
  | { ok: true; widgets: SessionWidget[] }
  | { ok: false; reason: string; widgets: SessionWidget[] };

export function saveSessionWidget(
  widget: Omit<SessionWidget, "id" | "savedAt">,
): SaveResult {
  const key = storageKey();
  const current = loadSessionWidgets();
  if (!key) {
    return { ok: false, reason: "session unavailable", widgets: current };
  }
  if (current.length >= MAX_SESSION_WIDGETS) {
    return {
      ok: false,
      reason: `Session limit reached (${MAX_SESSION_WIDGETS} widgets)`,
      widgets: current,
    };
  }
  const next: SessionWidget[] = [
    ...current,
    {
      ...widget,
      id:
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `w-${Date.now()}-${Math.floor(Math.random() * 1e9)}`,
      savedAt: Date.now(),
    },
  ];
  try {
    window.localStorage.setItem(key, JSON.stringify(next));
    return { ok: true, widgets: next };
  } catch {
    return {
      ok: false,
      reason: "Result too large to save — try fewer rows",
      widgets: current,
    };
  }
}

export function removeSessionWidget(id: string): SessionWidget[] {
  const key = storageKey();
  const next = loadSessionWidgets().filter((w) => w.id !== id);
  if (key) {
    try {
      window.localStorage.setItem(key, JSON.stringify(next));
    } catch {
      // ignore — the rail will re-persist on the next save
    }
  }
  return next;
}
