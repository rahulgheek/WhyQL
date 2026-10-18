"use client";

import { useState } from "react";
import { authRegister } from "@/lib/auth";
import { OAuthButton } from "@/app/auth/OAuthButton";
import Link from "next/link";

export default function RegisterPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await authRegister(email, password);
      setSent(true);
    } catch (err: any) {
      setError(err.message || "Registration failed");
    }
  }

  if (sent) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="p-8 border border-slate-200 rounded-xl bg-white">
          <h2 className="text-xl font-bold text-slate-900 mb-2">
            Verification email sent
          </h2>
          <p className="text-sm text-slate-600 mb-6">
            Check your inbox (MailHog at http://localhost:8025 in dev) for the
            6-digit code, then verify below.
          </p>
          <Link href="/login" className="text-sm text-blue-600 hover:underline">
            Back to login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="p-8 border border-slate-200 rounded-xl bg-white w-full max-w-md">
        <h2 className="text-xl font-bold text-slate-900 mb-6">Create account</h2>
        {error && (
          <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
            {error}
          </div>
        )}
        <form onSubmit={handleSubmit} className="space-y-4">
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
              minLength={8}
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
            Register & Send OTP
          </button>
        </form>
        <div className="my-5 flex items-center gap-3 text-xs text-slate-400">
          <span className="h-px flex-1 bg-slate-200" />
          or
          <span className="h-px flex-1 bg-slate-200" />
        </div>
        <OAuthButton provider="github" label="Continue with GitHub" />
        <p className="mt-6 text-sm text-center text-slate-600">
          Already have an account?{" "}
          <Link href="/login" className="text-blue-600 hover:underline">
            Log in
          </Link>
        </p>
      </div>
    </div>
  );
}
