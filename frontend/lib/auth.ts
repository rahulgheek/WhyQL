const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export async function authRegister(email: string, password: string) {
  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "register failed");
  return data;
}

export async function authVerifyOtp(email: string, code: string) {
  const res = await fetch(`${API_BASE}/api/auth/verify-otp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, code }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "verify failed");
  return data;
}

export async function authResendOtp(email: string) {
  const res = await fetch(`${API_BASE}/api/auth/resend-otp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "resend failed");
  return data;
}

export async function authLogin(email: string, password: string) {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "login failed");
  // store tokens
  if (typeof window !== "undefined") {
    localStorage.setItem("access_token", data.accessToken);
    localStorage.setItem("refresh_token", data.refreshToken);
  }
  return data;
}

export function getAccessToken() {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("access_token");
}

export type MeResponse = { id: string; email: string; status: string };

/** Rotate the pair via /api/auth/refresh; returns false when the refresh token is dead. */
export async function refreshTokens(): Promise<boolean> {
  if (typeof window === "undefined") return false;
  const refreshToken = localStorage.getItem("refresh_token");
  if (!refreshToken) return false;
  try {
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) return false;
    const data = await res.json();
    localStorage.setItem("access_token", data.accessToken);
    localStorage.setItem("refresh_token", data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

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
    localStorage.removeItem("access_token");
    localStorage.removeItem("refresh_token");
  }
}
