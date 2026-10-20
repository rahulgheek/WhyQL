"use client";

import { useState } from "react";
import { authRegister } from "@/lib/auth";
import { OAuthButton } from "@/app/auth/OAuthButton";
import { VerifyOtpForm } from "@/app/auth/VerifyOtpForm";
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
      <div className="min-h-screen flex items-center justify-center bg-[#0a0b12]">
        <div className="p-8 border border-white/10 rounded-2xl bg-white/5 shadow-sm w-full max-w-md animate-fade-in">
          <div className="mb-6">
            <div className="flex items-center gap-2 mb-2">
              <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm">
                Q
              </div>
              <h2 className="text-xl font-bold tracking-tight text-white">Check your inbox</h2>
            </div>
            <p className="text-sm text-slate-400">
              We sent a 6-digit verification code to{" "}
              <span className="font-medium text-white">{email}</span>. Enter it
              below to activate your account.
            </p>
          </div>
          <VerifyOtpForm
            email={email}
            onVerified={() => {
              window.location.href = "/";
            }}
          />
          <p className="mt-6 text-sm text-center text-slate-400">
            <Link href="/login" className="text-indigo-400 hover:underline font-medium">
              Back to login
            </Link>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#0a0b12]">
      <div className="p-8 border border-white/10 rounded-2xl bg-white/5 shadow-sm w-full max-w-md animate-fade-in">
        <div className="mb-6">
          <div className="flex items-center gap-2 mb-2">
            <div className="h-8 w-8 rounded-xl bg-indigo-500 text-white flex items-center justify-center font-bold text-sm">
              Q
            </div>
            <h2 className="text-xl font-bold tracking-tight text-white">Create account</h2>
          </div>
          <p className="text-sm text-slate-400">
            Create your WhyQL account to start asking questions about your data.
          </p>
        </div>
        {error && (
          <div className="mb-4 p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20">
            {error}
          </div>
        )}
        <form onSubmit={handleSubmit} className="space-y-4">
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
              minLength={8}
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
            Register & Send OTP
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
        <p className="mt-6 text-sm text-center text-slate-400">
          Already have an account?{" "}
          <Link href="/login" className="text-indigo-400 hover:underline font-medium">
            Log in
          </Link>
        </p>
      </div>
    </div>
  );
}
