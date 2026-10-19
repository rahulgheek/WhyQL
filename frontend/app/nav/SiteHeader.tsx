"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { fetchCurrentUser, logout } from "@/lib/auth";

/**
 * Shared auth-aware site header: shows the signed-in profile (email + log out)
 * when a valid session exists, otherwise plain Login/Register links.
 */
export default function SiteHeader() {
  const [email, setEmail] = useState<string | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    fetchCurrentUser()
      .then((me) => setEmail(me?.email ?? null))
      .finally(() => setChecked(true));
  }, []);

  function handleLogout() {
    logout();
    setEmail(null);
    window.location.href = "/login";
  }

  return (
    <nav className="border-b border-slate-200 bg-white px-6 py-4 flex justify-between items-center">
      <div className="flex items-center gap-8">
        <Link href="/" className="font-bold text-xl text-slate-900">
          WhyQL
        </Link>
        <div className="space-x-5">
          <Link href="/" className="text-sm text-slate-600 hover:text-slate-900">
            Home
          </Link>
          <Link href="/datasources" className="text-sm text-slate-600 hover:text-slate-900">
            Data Sources
          </Link>
          <Link href="/ask" className="text-sm text-slate-600 hover:text-slate-900">
            Ask a Question
          </Link>
          <Link href="/dashboard" className="text-sm text-slate-600 hover:text-slate-900">
            Dashboards
          </Link>
          <Link href="/builder" className="text-sm text-slate-600 hover:text-slate-900">
            Boards
          </Link>
        </div>
      </div>
      {checked ? (
        email ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-700">{email}</span>
            <button
              type="button"
              onClick={handleLogout}
              className="text-sm px-3 py-1.5 rounded border border-slate-300 text-slate-700 hover:bg-slate-50"
            >
              Log out
            </button>
          </div>
        ) : (
          <div className="space-x-5">
            <Link href="/login" className="text-sm text-slate-600 hover:text-slate-900">
              Login
            </Link>
            <Link href="/register" className="text-sm text-slate-600 hover:text-slate-900">
              Register
            </Link>
          </div>
        )
      ) : null}
    </nav>
  );
}
