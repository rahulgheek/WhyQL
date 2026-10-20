"use client";

import { startOAuth } from "@/lib/auth";

/** "Continue with GitHub / Google" button opening the backend's SPA OAuth entry. */
export function OAuthButton({
  provider,
  label,
}: {
  provider: "github" | "google";
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={() => startOAuth(provider)}
      className="w-full py-2.5 rounded-xl border border-white/20 bg-white/5 text-slate-200 text-sm font-medium hover:bg-white/10 hover:border-white/30 flex items-center justify-center gap-2 shadow-sm transition"
    >
      {provider === "github" ? <GitHubMark /> : null}
      {label}
    </button>
  );
}

function GitHubMark() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-2.91-.88-2.91-2.89 0-.94.33-1.71.88-2.31-.09-.24-.4-1.02.09-2.12 0 0 .72-.23 2.36.88a7.9 7.9 0 0 1 2.04 0c1.63-1.11 2.35-.88 2.35-.88.49 1.1.18 1.88.09 2.12.55.6.87 1.37.87 2.31 0 2.02-1.13 2.69-2.92 2.89.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}
