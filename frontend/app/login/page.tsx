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
    <div className="min-h-screen flex items-center justify-center bg-[#0a0b12]">
      <div className="p-8 border border-white/10 rounded-2xl bg-white/5 shadow-sm w-full max-w-md animate-fade-in">
        {step === "login" ? (
          <>
            <div className="mb-6">
              <div className="flex items-center gap-2 mb-2">
                <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm">
                  Q
                </div>
                <h2 className="text-xl font-bold tracking-tight text-white">Log in</h2>
              </div>
              <p className="text-sm text-slate-400">Welcome back. Sign in to your account.</p>
            </div>
            {expiredBanner && (
              <div className="mb-4 p-3 rounded-xl bg-amber-500/10 text-amber-400 text-sm border border-amber-500/20">
                {expiredBanner}
              </div>
            )}
            {error && (
              <div className="mb-4 p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20">
                {error}
              </div>
            )}
            <form onSubmit={handleLogin} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  Email
                </label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full border border-white/20 rounded-xl px-3 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  Password
                </label>
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full border border-white/20 rounded-xl px-3 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
                />
              </div>
              <button
                type="submit"
                className="w-full py-2.5 rounded-xl bg-indigo-600 text-white font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 disabled:opacity-50 transition active:scale-95"
                disabled={!email || !password}
              >
                Log in
              </button>
            </form>
            <div className="my-6 flex items-center gap-3 text-xs text-slate-400">
              <span className="h-px flex-1 bg-white/10" />
              or
              <span className="h-px flex-1 bg-white/10" />
            </div>
            <div className="space-y-2">
              <OAuthButton provider="github" label="Continue with GitHub" />
              <OAuthButton provider="google" label="Continue with Google" />
            </div>
            <p className="mt-5 text-sm text-center text-slate-400">
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
                className="text-indigo-400 hover:underline font-medium"
              >
                Enter verification code
              </button>
            </p>
            <p className="mt-6 text-sm text-center text-slate-400">
              Don&apos;t have an account?{" "}
              <Link href="/register" className="text-indigo-400 hover:underline font-medium">
                Register
              </Link>
            </p>
          </>
        ) : (
          <>
            <div className="mb-6">
              <div className="flex items-center gap-2 mb-2">
                <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm">
                  Q
                </div>
                <h2 className="text-xl font-bold tracking-tight text-white">
                  Verify email
                </h2>
              </div>
              <p className="text-sm text-slate-400">Enter the 6-digit code sent to your inbox.</p>
            </div>
            {error && (
              <div className="mb-4 p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20">
                {error}
              </div>
            )}
            <VerifyOtpForm
              email={email}
              onVerified={() => {
                window.location.href = "/";
              }}
            />
            <p className="mt-5 text-sm text-center text-slate-400">
              <button
                type="button"
                onClick={() => setStep("login")}
                className="text-indigo-400 hover:underline font-medium"
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
