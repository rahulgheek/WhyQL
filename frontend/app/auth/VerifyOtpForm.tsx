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
        <div className="p-3 rounded bg-red-50 text-red-700 text-sm">{error}</div>
      )}
      {info && (
        <div className="p-3 rounded bg-green-50 text-green-700 text-sm">{info}</div>
      )}
      <div>
        <label className="block text-sm font-medium text-slate-700 mb-1">
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
          className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 tracking-widest text-center text-lg"
        />
      </div>
      <button
        type="submit"
        disabled={code.length !== 6 || busy}
        className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
      >
        {busy ? "Verifying…" : "Verify"}
      </button>
      <p className="text-sm text-center text-slate-600">
        Didn&apos;t get a code?{" "}
        <button
          type="button"
          onClick={handleResend}
          disabled={busy}
          className="text-blue-600 hover:underline"
        >
          Resend OTP
        </button>
      </p>
    </form>
  );
}
