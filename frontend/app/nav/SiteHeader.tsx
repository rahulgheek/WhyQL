"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Menu, X } from "lucide-react";
import { fetchCurrentUser, logout } from "@/lib/auth";

/**
 * Shared auth-aware site header: dark theme to match the landing page,
 * responsive with a collapsible mobile menu.
 */
export default function SiteHeader() {
  const [email, setEmail] = useState<string | null>(null);
  const [checked, setChecked] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [hovered, setHovered] = useState<"dashboard" | "ask" | "builder" | "datasources" | null>(null);

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

  const navLinks = (
    <div className="hidden md:flex items-center space-x-6">
      <NavLink href="/" label="Home" active={false} />
      <NavLink
        href="/datasources"
        label="Data Sources"
        active={hovered === "datasources"}
        onMouseEnter={() => setHovered("datasources")}
        onMouseLeave={() => setHovered(null)}
      />
      <NavLink
        href="/ask"
        label="Ask"
        active={hovered === "ask"}
        onMouseEnter={() => setHovered("ask")}
        onMouseLeave={() => setHovered(null)}
      />
      <NavLink
        href="/dashboard"
        label="Dashboards"
        active={hovered === "dashboard"}
        onMouseEnter={() => setHovered("dashboard")}
        onMouseLeave={() => setHovered(null)}
      />
      <NavLink
        href="/builder"
        label="Boards"
        active={hovered === "builder"}
        onMouseEnter={() => setHovered("builder")}
        onMouseLeave={() => setHovered(null)}
      />
    </div>
  );

  return (
    <nav className="border-b border-white/10 bg-[#0a0b12]/80 backdrop-blur px-6 py-4 shrink-0 sticky top-0 z-40">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-8">
          <Link
            href="/"
            className="flex items-center gap-2 font-semibold tracking-tight text-white"
          >
            <span className="inline-flex h-7 w-7 items-center justify-center rounded-xl bg-indigo-500 text-white font-bold text-sm">
              Q
            </span>
            <span>WhyQL</span>
          </Link>
          {navLinks}
        </div>
        <div className="flex items-center gap-3">
          {checked ? (
            email ? (
              <div className="flex items-center gap-3 hidden md:block">
                <span className="text-sm font-medium text-slate-300">{email}</span>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="text-sm font-medium text-slate-300 px-3 py-1.5 rounded border border-white/20 hover:bg-white/10 hover:border-white/30 transition"
                >
                  Log out
                </button>
              </div>
            ) : (
              <div className="space-x-3 hidden md:flex">
                <Link href="/login" className="text-sm font-medium text-slate-400 hover:text-white transition">
                  Login
                </Link>
                <Link
                  href="/register"
                  className="text-sm font-medium text-slate-400 px-3 py-1.5 rounded border border-white/20 hover:bg-white/10 hover:border-white/30 hover:text-white transition"
                >
                  Register
                </Link>
              </div>
            )
          ) : null}
          <button
            type="button"
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="md:hidden p-2 rounded text-slate-300 hover:bg-white/10 hover:text-white transition"
            aria-label="Toggle menu"
          >
            {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>
      </div>

      {/* Mobile menu panel */}
      {mobileMenuOpen && (
        <div className="md:hidden border-t border-white/10 bg-[#0a0b12]/95 backdrop-blur p-4 animate-fade-in">
          <div className="space-y-4">
            <Link
              href="/"
              onClick={() => setMobileMenuOpen(false)}
              className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
            >
              Home
            </Link>
            <Link
              href="/datasources"
              onClick={() => setMobileMenuOpen(false)}
              className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
            >
              Data Sources
            </Link>
            <Link
              href="/ask"
              onClick={() => setMobileMenuOpen(false)}
              className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
            >
              Ask
            </Link>
            <Link
              href="/dashboard"
              onClick={() => setMobileMenuOpen(false)}
              className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
            >
              Dashboards
            </Link>
            <Link
              href="/builder"
              onClick={() => setMobileMenuOpen(false)}
              className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
            >
              Boards
            </Link>
            {checked ? (
              email ? (
                <>
                  <div className="pt-4 border-t border-white/10">
                    <span className="text-xs font-medium text-slate-500 block mb-2">
                      {email}
                    </span>
                    <button
                      type="button"
                      onClick={handleLogout}
                      className="text-sm font-medium text-slate-300 w-full py-2 rounded border border-white/20 hover:bg-white/10 hover:border-white/30 transition text-left"
                    >
                      Log out
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <div className="pt-4 border-t border-white/10 space-y-3">
                    <Link
                      href="/login"
                      onClick={() => setMobileMenuOpen(false)}
                      className="block text-sm font-medium text-slate-300 hover:text-white transition py-2"
                    >
                      Login
                    </Link>
                    <Link
                      href="/register"
                      onClick={() => setMobileMenuOpen(false)}
                      className="block text-sm font-medium text-slate-300 hover:text-white transition py-2 rounded border border-white/20"
                    >
                      Register
                    </Link>
                  </div>
                </>
              )
            ) : null}
          </div>
        </div>
      )}
    </nav>
  );
}

function NavLink({
  href,
  label,
  active,
  onMouseEnter,
  onMouseLeave,
}: {
  href: string;
  label: string;
  active: boolean;
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
}) {
  return (
    <Link
      href={href}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      className={`text-sm font-medium transition-colors ${
        active
          ? "text-indigo-400"
          : "text-slate-400 hover:text-white"
      }`}
    >
      {label}
    </Link>
  );
}
