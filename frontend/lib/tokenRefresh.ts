/**
 * Access-token lifecycle for WhyQL: storage, single-flight refresh rotation,
 * proactive renewal ahead of expiry, and the shared "session expired" exit.
 *
 * The backend issues 15-minute access tokens plus a rotating refresh token —
 * /api/auth/refresh swaps the pair. Everything GraphQL (Apollo HTTP + WS) is
 * wired through here so a session stays alive indefinitely while the user is
 * around instead of dying 15 minutes after login.
 */

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

const ACCESS_TOKEN_KEY = "access_token";
const REFRESH_TOKEN_KEY = "refresh_token";
const EXPIRES_AT_KEY = "token_expires_at";

export interface TokenPairResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

export function getAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function clearTokens() {
  if (typeof window === "undefined") return;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(EXPIRES_AT_KEY);
}

/** Persist a fresh pair and arm the proactive renewal timer. */
export function storeTokens(data: TokenPairResponse) {
  if (typeof window === "undefined") return;
  localStorage.setItem(ACCESS_TOKEN_KEY, data.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, data.refreshToken);
  const seconds = Number(data.expiresInSeconds);
  if (Number.isFinite(seconds) && seconds > 0) {
    localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() + seconds * 1000));
  } else {
    localStorage.removeItem(EXPIRES_AT_KEY);
  }
  scheduleProactiveRefresh();
}

/**
 * Drop the stale token and send the user to the login page (guarded so login
 * itself cannot loop). Used once on hard session death only — normal expiry
 * is silently refreshed instead.
 */
export function handleSessionExpired() {
  if (typeof window === "undefined") return;
  clearTokens();
  if (window.location.pathname === "/login") return;
  window.location.href = "/login?expired=1";
}

let sessionExpiredHandling = false;
/** Deduped variant for use from retry loops and per-operation errors. */
export function handleSessionExpiredOnce() {
  if (sessionExpiredHandling) return;
  sessionExpiredHandling = true;
  handleSessionExpired();
}

/** Rotate the token pair via /api/auth/refresh; false when the refresh token is dead. */
export async function refreshTokens(): Promise<boolean> {
  if (typeof window === "undefined") return false;
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshToken) return false;
  try {
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) {
      if (res.status === 404) {
        return false; // refresh token rejected/expired — hard expiry
      }
      return false;
    }
    const data = (await res.json()) as TokenPairResponse;
    storeTokens(data);
    return true;
  } catch {
    return false;
  }
}

// ── Single-flight refresh: parallel 401s share one rotation ──────────────────

let inFlightRefresh: Promise<boolean> | null = null;

/** Concurrent callers await one shared rotation instead of racing. */
export function refreshOnce(): Promise<boolean> {
  if (!inFlightRefresh) {
    inFlightRefresh = refreshTokens().finally(() => {
      inFlightRefresh = null;
    });
  }
  return inFlightRefresh;
}

// ── Proactive renewal: refresh ~90s before expiry so requests never 401 ──────

let refreshTimer: ReturnType<typeof setTimeout> | null = null;

export function scheduleProactiveRefresh() {
  if (typeof window === "undefined") return;
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
  if (!localStorage.getItem(REFRESH_TOKEN_KEY)) return;

  const expiresAt = Number(localStorage.getItem(EXPIRES_AT_KEY));
  if (!Number.isFinite(expiresAt) || expiresAt <= 0) return;

  const delay = expiresAt - Date.now() - 90_000; // renew 90s early
  if (delay <= 0) {
    // Already at/near expiry (fresh reload after sleep): renew immediately.
    void maybeProactiveRefresh();
    return;
  }
  refreshTimer = setTimeout(maybeProactiveRefresh, delay);
}

async function maybeProactiveRefresh() {
  refreshTimer = null;
  // Only renew when a session still exists and the user would notice the loss.
  if (!getAccessToken()) return;
  const ok = await refreshOnce();
  if (!ok) {
    handleSessionExpiredOnce();
  }
}
