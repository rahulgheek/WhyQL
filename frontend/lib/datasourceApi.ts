import { getAccessToken, refreshTokens, clearTokens } from "@/lib/tokenRefresh";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

/** Datasource REST failure carrying the backend's stable error code. */
export class DatasourceApiError extends Error {
  code?: string;
  status: number;
  constructor(message: string, code?: string, status = 400) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

/**
 * Authenticated datasource REST call with one silent token-refresh retry,
 * mirroring fetchCurrentUser's contract.
 */
async function privateFetch<T>(
  path: string,
  method: string,
  body?: unknown,
): Promise<T> {
  let token = getAccessToken();
  if (!token) throw new DatasourceApiError("Not signed in", "UNAUTHENTICATED", 401);

  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch(`${API_BASE}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.ok) {
      return (statusOnly(method)
        ? (undefined as T)
        : await res.json()) as T;
    }
    if (res.status !== 401 || attempt === 1) {
      const data = await res.json().catch(() => null);
      const container = data as { message?: string; code?: string } | null;
      throw new DatasourceApiError(
        container?.message || "Request failed",
        container?.code,
        res.status,
      );
    }
    if (!(await refreshTokens())) {
      clearTokens();
      throw new DatasourceApiError("Session expired", "UNAUTHENTICATED", 401);
    }
    token = getAccessToken();
    if (!token) throw new DatasourceApiError("Session expired", "UNAUTHENTICATED", 401);
  }
  throw new DatasourceApiError("Request failed", undefined, 500);
}

function statusOnly(method: string): boolean {
  return method.toUpperCase() === "DELETE";
}

export interface TestConnectionResult {
  ok: boolean;
  warnings: string[];
}

export interface UpdateCredentialsInput {
  /** null keeps the stored URL. */
  jdbcUrl?: string | null;
  /** null keeps the stored username. */
  username?: string | null;
  /** null keeps the stored password. */
  password?: string | null;
}

/** Rotates stored credentials (live-tested by the backend before saving).
 * Requires the caller's account password — same gate as reveal. */
export function updateCredentials(
  id: string,
  input: UpdateCredentialsInput,
  accountPassword: string,
): Promise<TestConnectionResult> {
  return privateFetch(`/api/datasources/${id}/credentials`, "PUT", {
    jdbcUrl: input.jdbcUrl ?? null,
    username: input.username ?? null,
    password: input.password ?? null,
    accountPassword,
  });
}

/** Revealed plaintext — only through this account-password gate. */
export interface RevealedCredentials {
  id: string;
  jdbcUrl: string | null;
  username: string | null;
  password: string | null;
}

export function revealCredentials(
  id: string,
  accountPassword: string,
): Promise<RevealedCredentials> {
  return privateFetch(`/api/datasources/${id}/credentials/reveal`, "POST", {
    accountPassword,
  });
}

export function testConnection(id: string): Promise<TestConnectionResult> {
  return privateFetch(`/api/datasources/${id}/test`, "POST");
}
