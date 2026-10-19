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
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="p-8 border border-slate-200 rounded-xl bg-white w-full max-w-md">
          <h2 className="text-xl font-bold text-slate-900 mb-2">Sign-in failed</h2>
          <p className="text-sm text-slate-600 mb-6">{message}</p>
          <Link href="/login" className="text-sm text-blue-600 hover:underline">
            Back to login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <p className="text-slate-600">Finishing sign-in…</p>
    </div>
  );
}
