"use client";

import { useState } from "react";
import { authVerifyOtp, authResendOtp } from "@/lib/auth";

/**
 * Shared OTP verification form: 6-digit code entry plus resend. Used by the
 * login page (unverified-account flow) and inline on the register success
 * screen so a fresh registration can verify without hunting for the form.
 */
export function VerifyOtpForm({
  email,
  onVerified,
}: {
  email: string;
  onVerified: () => void;
}) {
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleVerify(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setInfo("");
    setBusy(true);
    try {
      await authVerifyOtp(email, code);
      onVerified();
    } catch (err: any) {
      setError(err.message || "Verification failed");
    } finally {
      setBusy(false);
    }
  }

  async function handleResend() {
    setError("");
    setInfo("");
    setBusy(true);
    try {
      await authResendOtp(email);
      setInfo("A new code is on its way — check your inbox.");
    } catch (err: any) {
      setError(err.message || "Failed to resend code");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleVerify} className="space-y-4">
      {error && (
        <div className="p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20">{error}</div>
      )}
      {info && (
        <div className="p-3 rounded-xl bg-emerald-500/10 text-emerald-400 text-sm border border-emerald-500/20">{info}</div>
      )}
      <div>
        <label className="block text-sm font-medium text-slate-300 mb-1.5">
          6-digit code sent to {email}
        </label>
        <input
          type="text"
          required
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          className="w-full border border-white/20 rounded-xl px-3 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 tracking-widest text-center text-lg transition"
        />
      </div>
      <button
        type="submit"
        disabled={code.length !== 6 || busy}
        className="w-full py-2.5 rounded-xl bg-indigo-600 text-white font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 disabled:opacity-50 transition active:scale-95"
      >
        {busy ? "Verifying…" : "Verify"}
      </button>
      <p className="text-sm text-center text-slate-400">
        Didn&apos;t get a code?{" "}
        <button
          type="button"
          onClick={handleResend}
          disabled={busy}
          className="text-indigo-400 hover:underline font-medium"
        >
          Resend OTP
        </button>
      </p>
    </form>
  );
}
