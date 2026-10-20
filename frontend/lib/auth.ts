import {
  clearTokens,
  getAccessToken,
  refreshTokens,
  storeTokens,
} from "@/lib/tokenRefresh";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

/** Auth API failure with the backend's stable error code (e.g. ACCOUNT_NOT_VERIFIED). */
export class AuthApiError extends Error {
  code?: string;
  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

function throwApiError(data: unknown, fallback: string): never {
  const container = data as { message?: string; code?: string } | null;
  throw new AuthApiError(container?.message || fallback, container?.code);
}

export async function authRegister(email: string, password: string) {
  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const data = await res.json();
  if (!res.ok) throwApiError(data, "register failed");
  return data;
}

export async function authVerifyOtp(email: string, code: string) {
  const res = await fetch(`${API_BASE}/api/auth/verify-otp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, code }),
  });
  const data = await res.json();
  if (!res.ok) throwApiError(data, "verify failed");
  return data;
}

export async function authResendOtp(email: string) {
  const res = await fetch(`${API_BASE}/api/auth/resend-otp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  const data = await res.json();
  if (!res.ok) throwApiError(data, "resend failed");
  return data;
}

export async function authLogin(email: string, password: string) {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const data = await res.json();
  if (!res.ok) throwApiError(data, "login failed");
  if (data?.accessToken && data?.refreshToken) {
    // Persist the pair and arm the silent renewal loop.
    storeTokens(data);
  }
  return data;
}

export { getAccessToken, refreshTokens, clearTokens };

export type MeResponse = { id: string; email: string; status: string };

/**
 * The authenticated profile for the header: 401s are retried once after a
 * refresh-token rotation; a dead refresh token clears the session client-side.
 */
export async function fetchCurrentUser(): Promise<MeResponse | null> {
  if (typeof window === "undefined") return null;
  let token = getAccessToken();
  if (!token) return null;

  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch(`${API_BASE}/api/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok) return await res.json();
    if (res.status !== 401 || attempt === 1) break;
    if (!(await refreshTokens())) {
      logout();
      return null;
    }
    token = getAccessToken();
    if (!token) return null;
  }
  logout();
  return null;
}

// Kick off the OAuth round trip: the backend validates returnTo against its
// allowlist, stores it in a short-lived cookie, and 302s into the provider
// dance; /auth/callback then harvests the token pair from the URL fragment.
export function startOAuth(provider: "github" | "google") {
  if (typeof window === "undefined") return;
  const returnTo = encodeURIComponent(`${window.location.origin}/auth/callback`);
  window.location.href = `${API_BASE}/api/auth/oauth/${provider}?returnTo=${returnTo}`;
}

export function logout() {
  if (typeof window !== "undefined") {
    clearTokens();
  }
}

// ─── Adopt a local password (OAuth-only accounts) ────────────────────────────

/**
 * Step 1: email the signed-in OAuth user a 6-digit code so they can adopt a
 * local password. Requires a Bearer JWT; rate limited server-side (3/hour).
 */
export async function adoptPasswordInit(): Promise<void> {
  let token = getAccessToken();
  if (!token) throw new AuthApiError("Not signed in", "UNAUTHENTICATED");
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch(`${API_BASE}/api/auth/password/set/init`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok) return;
    if (res.status === 401 && attempt === 0) {
      if (!(await refreshTokens())) {
        logout();
        throw new AuthApiError("Session expired", "UNAUTHENTICATED");
      }
      token = getAccessToken();
      if (!token) throw new AuthApiError("Session expired", "UNAUTHENTICATED");
      continue;
    }
    const data = await res.json().catch(() => null);
    throwAuthError(data, "could not send the code", res.status);
  }
}

/** Step 2: verify the emailed code and store the new local password. */
export async function adoptPassword(code: string, password: string): Promise<void> {
  let token = getAccessToken();
  if (!token) throw new AuthApiError("Not signed in", "UNAUTHENTICATED");
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch(`${API_BASE}/api/auth/password/set`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ code, password }),
    });
    if (res.ok) return;
    if (res.status === 401 && attempt === 0) {
      if (!(await refreshTokens())) {
        logout();
        throw new AuthApiError("Session expired", "UNAUTHENTICATED");
      }
      token = getAccessToken();
      if (!token) throw new AuthApiError("Session expired", "UNAUTHENTICATED");
      continue;
    }
    const data = await res.json().catch(() => null);
    throwAuthError(data, "could not set the password", res.status);
  }
}

function throwAuthError(data: unknown, fallback: string, status: number): never {
  const container = data as { message?: string; code?: string } | null;
  const msg = container?.message || fallback;
  const code = container?.code || (status === 409 ? "PASSWORD_ALREADY_SET" : undefined);
  throw new AuthApiError(msg, code);
}
