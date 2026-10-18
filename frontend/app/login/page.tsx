"use client";

import { useState } from "react";
import { authVerifyOtp, authLogin } from "@/lib/auth";
import { OAuthButton } from "@/app/auth/OAuthButton";
import Link from "next/link";

export default function LoginPage() {
  const [step, setStep] = useState<"login" | "verify">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await authLogin(email, password);
      // eslint-disable-next-line no-restricted-globals
      window.location.href = "/";
    } catch (err: any) {
      setError(err.message || "Login failed");
    }
  }

  async function handleVerify(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await authVerifyOtp(email, code);
      // eslint-disable-next-line no-restricted-globals
      window.location.href = "/";
    } catch (err: any) {
      setError(err.message || "Verification failed");
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="p-8 border border-slate-200 rounded-xl bg-white w-full max-w-md">
        {step === "login" ? (
          <>
            <h2 className="text-xl font-bold text-slate-900 mb-6">Log in</h2>
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
            <OAuthButton provider="github" label="Continue with GitHub" />
            <p className="mt-6 text-sm text-center text-slate-600">
              Don't have an account?{" "}
              <Link href="/register" className="text-blue-600 hover:underline">
                Register
              </Link>
            </p>
          </>
        ) : (
          <>
            <h2 className="text-xl font-bold text-slate-900 mb-6">
              Verify email for {email}
            </h2>
            {error && (
              <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
                {error}
              </div>
            )}
            <form onSubmit={handleVerify} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  6-digit code
                </label>
                <input
                  type="text"
                  required
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                  className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                />
              </div>
              <button
                type="submit"
                className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
                disabled={code.length !== 6}
              >
                Verify
              </button>
            </form>
            <p className="mt-4 text-sm text-center text-slate-600">
              Didn't get a code?{" "}
              <button
                onClick={(e) => {
                  e.preventDefault();
                  fetch("http://localhost:8080/api/auth/resend-otp", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ email }),
                  });
                }}
                className="text-blue-600 hover:underline"
              >
                Resend OTP
              </button>
            </p>
            <p className="mt-4 text-sm text-center text-slate-600">
              <button
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
