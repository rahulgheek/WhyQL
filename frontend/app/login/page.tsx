"use client";

import { useEffect, useState } from "react";
import { authLogin } from "@/lib/auth";
import { OAuthButton } from "@/app/auth/OAuthButton";
import { VerifyOtpForm } from "@/app/auth/VerifyOtpForm";
import Link from "next/link";

export default function LoginPage() {
  const [step, setStep] = useState<"login" | "verify">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  // Set by app/lib redirect on auth failure — read post-mount to avoid
  // useSearchParams static-render requirements.
  const [expiredBanner, setExpiredBanner] = useState("");

  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("expired") === "1") {
      setExpiredBanner("Your session expired — please sign in again.");
    }
  }, []);

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await authLogin(email, password);
      // eslint-disable-next-line no-restricted-globals
      window.location.href = "/";
    } catch (err: any) {
      if (err?.code === "ACCOUNT_NOT_VERIFIED") {
        // Unverified account: the OTP form is the fix, not an error dead-end.
        setStep("verify");
        setError("Your email isn't verified yet — enter the 6-digit code we sent you.");
      } else {
        setError(err.message || "Login failed");
      }
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="p-8 border border-slate-200 rounded-xl bg-white w-full max-w-md">
        {step === "login" ? (
          <>
            <h2 className="text-xl font-bold text-slate-900 mb-6">Log in</h2>
            {expiredBanner && (
              <div className="mb-4 p-3 rounded bg-amber-50 text-amber-800 text-sm">
                {expiredBanner}
              </div>
            )}
            {error && (
              <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
                {error}
              </div>
            )}
            <form onSubmit={handleLogin} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  Email
                </label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  Password
                </label>
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                />
              </div>
              <button
                type="submit"
                className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
                disabled={!email || !password}
              >
                Log in
              </button>
            </form>
            <div className="my-5 flex items-center gap-3 text-xs text-slate-400">
              <span className="h-px flex-1 bg-slate-200" />
              or
              <span className="h-px flex-1 bg-slate-200" />
            </div>
            <div className="space-y-2">
              <OAuthButton provider="github" label="Continue with GitHub" />
              <OAuthButton provider="google" label="Continue with Google" />
            </div>
            <p className="mt-4 text-sm text-center text-slate-600">
              Need to verify your email?{" "}
              <button
                type="button"
                onClick={() => {
                  if (!email) {
                    setError("Enter your email above first, then open the verification form.");
                    return;
                  }
                  setError("");
                  setStep("verify");
                }}
                className="text-blue-600 hover:underline"
              >
                Enter verification code
              </button>
            </p>
            <p className="mt-6 text-sm text-center text-slate-600">
              Don&apos;t have an account?{" "}
              <Link href="/register" className="text-blue-600 hover:underline">
                Register
              </Link>
            </p>
          </>
        ) : (
          <>
            <h2 className="text-xl font-bold text-slate-900 mb-6">
              Verify email
            </h2>
            {error && (
              <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
                {error}
              </div>
            )}
            <VerifyOtpForm
              email={email}
              onVerified={() => {
                window.location.href = "/";
              }}
            />
            <p className="mt-4 text-sm text-center text-slate-600">
              <button
                type="button"
                onClick={() => setStep("login")}
                className="text-blue-600 hover:underline"
              >
                Back to login
              </button>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
