"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { storeTokens } from "@/lib/tokenRefresh";

/**
 * Target of the backend's OAuth 303 redirect: the token pair rides in the URL
 * fragment (#accessToken=...), which browsers never send to servers. Copies it
 * into localStorage exactly like the password login does, then continues home.
 */
type Step = "loading" | "error";

export default function OAuthCallbackPage() {
  const [step, setStep] = useState<Step>("loading");
  const [message, setMessage] = useState("");

  function setDescription(detail: string | null) {
    setStep("error");
    setMessage(detail ? `OAuth login failed: ${detail}` : "OAuth login returned no tokens");
  }

  useEffect(() => {
    const fragment = window.location.hash.replace(/^#/, "");
    const params = new URLSearchParams(fragment);
    const accessToken = params.get("accessToken");
    const refreshToken = params.get("refreshToken");

    if (!accessToken || !refreshToken) {
      // Async dispatch: the fragment is only present client-side, and the
      // compiler warns on synchronous setState inside effects.
      const detail = params.get("errorDescription") ?? params.get("error");
      queueMicrotask(() => setDescription(detail));
      return;
    }

    const expiresInSeconds = Number(params.get("expiresInSeconds"));
    storeTokens({
      accessToken,
      refreshToken,
      expiresInSeconds: Number.isFinite(expiresInSeconds) && expiresInSeconds > 0
        ? expiresInSeconds
        : 15 * 60,
    });
    // Scrub the tokens out of the address bar and continue.
    window.location.replace("/");
  }, []);

  if (step === "error") {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#0a0b12]">
        <div className="p-8 border border-white/10 rounded-2xl bg-white/5 shadow-sm w-full max-w-md animate-fade-in">
          <div className="flex items-center gap-2 mb-4">
            <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm">
              Q
            </div>
            <h2 className="text-xl font-bold tracking-tight text-white">Sign-in failed</h2>
          </div>
          <p className="text-sm text-slate-400 mb-6">{message}</p>
          <Link
            href="/login"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 transition active:scale-95"
          >
            Back to login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#0a0b12]">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm animate-gentle-pulse">
          Q
        </div>
        <p className="text-slate-400">Finishing sign-in…</p>
      </div>
    </div>
  );
}
