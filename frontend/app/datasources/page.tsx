"use client";

import { useState } from "react";
import Link from "next/link";
import { useDataSources, useAddDataSource } from "@/lib/graphql";

/** Which URL schemes each engine's JDBC driver accepts. */
const ENGINE_SCHEME: Record<string, string> = {
  POSTGRES: "jdbc:postgresql://",
  MYSQL: "jdbc:mysql://",
};

export interface UrlCheck {
  level: "ok" | "warn" | "error";
  /** Prose shown under the field; '' when nothing to say. */
  message: string;
}

/**
 * Client-side mirror of the backend's datasource checks so obviously broken
 * URLs fail on screen — before a network round-trip returns an exception.
 * Still not authoritative: the backend performs the real connection test.
 */
export function checkJdbcUrl(engine: string, rawUrl: string): UrlCheck {
  const url = rawUrl.trim();
  if (!url) return { level: "error", message: "JDBC URL is required." };

  const scheme = ENGINE_SCHEME[engine];
  if (url.startsWith("jdbc:") && !url.startsWith(scheme)) {
    const actual = url.slice(5, url.indexOf("://") + 3);
    return {
      level: "error",
      message: `This is a ${actual || "unknown-driver"} URL but the engine selected above is ${engine}. Either fix the URL prefix or switch the engine.`,
    };
  }
  if (!url.startsWith(scheme)) {
    return {
      level: "error",
      message: `Must start with ${scheme} — the driver accepts no other scheme for ${engine}.`,
    };
  }

  const rest = url.slice(scheme.length);
  if (url.includes(" ")) {
    return { level: "error", message: "The URL cannot contain spaces." };
  }

  // Optional "user:pass@" is allowed by the backend regex — discourage it.
  const restNoUserInfo = rest.replace(/^[^/@]*@/, "");
  const hostPort = restNoUserInfo.split(/[/?]/)[0];
  if (!hostPort) {
    return {
      level: "error",
      message: `Missing host: write ${scheme}host[:port]/database.`,
    };
  }
  const [host, port = ""] = hostPort.split(":");
  if (!host) {
    return { level: "error", message: "Missing host — expected something like ep-xxx.aws.neon.tech or localhost." };
  }
  if (port && !/^\d+$/.test(port)) {
    return { level: "error", message: `The port "${port}" is not numeric.` };
  }
  if (port && (Number(port) < 1 || Number(port) > 65535)) {
    return { level: "error", message: `The port ${port} is outside 1–65535.` };
  }

  const path = restNoUserInfo.slice(hostPort.length + 1); // after '/' or '?'
  const database = path.split("?")[0];
  const urlParams = restNoUserInfo.includes("?") ? path.slice(path.indexOf("?") + 1) : "";

  if (/[?&](user|username|password)=/i.test(urlParams)) {
    return {
      level: "warn",
      message: "Credentials are inside the URL — put them in the Username/Password fields instead (stored encrypted; URL params are not).",
    };
  }

  if (!database) {
    return {
      level: "warn",
      message: engine === "POSTGRES"
        ? "No database name after the host — most providers need /<databasename> (PostgreSQL default is usually \"postgres\" or your project's db)."
        : "No database name after the host — MySQL URLs normally need /<databasename>.",
    };
  }

  const isLocal = /^(localhost|127\.\d+\.\d+\.\d+|::1|.*\.local)$/.test(host)
    || isLocalIp(host);
  if (isLocal) {
    return {
      level: "warn",
      message: "Local/private address: reachable only when the app runs with cloud mode OFF (WHYQL_CLOUD_MODE=false) or the host is on the allowlist. Cloud databases (Neon, Supabase, RDS…) need a public hostname.",
    };
  }

  return { level: "ok", message: "Looks structurally valid — the live connection test runs on submit." };
}

/** Rough private-IP detection for hostnames entered as raw dotted numbers. */
function isLocalIp(host: string): boolean {
  const parts = host.split(".");
  if (parts.length !== 4 || parts.some((p) => !/^\d+$/.test(p) || Number(p) > 255)) {
    return false;
  }
  const [a, b] = parts.map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

export default function DataSourcesPage() {
  const [name, setName] = useState("");
  const [engine, setEngine] = useState("POSTGRES");
  const [jdbcUrl, setJdbcUrl] = useState("jdbc:postgresql://localhost:5432/");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const { data, loading, error: queryError } = useDataSources();
  const [addDataSource] = useAddDataSource();

  const check = checkJdbcUrl(engine, jdbcUrl);
  const submitBlocked = check.level === "error" || !name || !jdbcUrl.trim();

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setSuccess("");
    try {
      await addDataSource({
        variables: {
          name,
          engine,
          jdbcUrl,
          username: username || null,
          password: password || null,
        },
      });
      setSuccess("Data source added. Connection was tested before saving.");
      setName("");
      setUsername("");
      setPassword("");
    } catch (err: any) {
      setError(err.message || "Failed to add data source");
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <main className="max-w-7xl mx-auto py-8 px-6 flex flex-col xl:flex-row gap-8">
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-slate-900 mb-8">Data Sources</h1>

          <div className="grid gap-6 md:grid-cols-2 items-start">
            <form onSubmit={handleAdd} className="p-6 border border-slate-200 rounded-xl bg-white space-y-4">
              <h3 className="font-semibold text-slate-900">Add data source</h3>
              {error && (
                <div className="p-3 rounded bg-red-50 text-red-700 text-sm">
                  {error}
                </div>
              )}
              {success && (
                <div className="p-3 rounded bg-green-50 text-green-700 text-sm">
                  {success}
                </div>
              )}
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  Name
                </label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  Engine
                </label>
                <select
                  value={engine}
                  onChange={(e) => setEngine(e.target.value)}
                  className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
                >
                  <option value="POSTGRES">PostgreSQL</option>
                  <option value="MYSQL">MySQL</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  JDBC URL
                </label>
                <input
                  type="text"
                  required
                  value={jdbcUrl}
                  onChange={(e) => setJdbcUrl(e.target.value)}
                  placeholder={
                    ENGINE_SCHEME[engine] + "host[:port]/database"
                  }
                  className={`w-full border rounded px-3 py-2 focus:ring-2 focus:ring-blue-500 ${
                    check.level === "error" ? "border-red-400" : "border-slate-300"
                  }`}
                />
                {check.message && (
                  <p
                    className={`mt-1.5 text-xs ${
                      check.level === "error"
                        ? "text-red-600"
                        : check.level === "warn"
                          ? "text-amber-600"
                          : "text-green-600"
                    }`}
                  >
                    {check.level === "ok" ? "✓ " : check.level === "warn" ? "⚠ " : "✕ "}
                    {check.message}
                  </p>
                )}
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1">
                    Username
                  </label>
                  <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1">
                    Password
                  </label>
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
                  />
                </div>
              </div>
              <button
                type="submit"
                className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
                disabled={submitBlocked}
              >
                Test &amp; Add
              </button>
              {check.level === "error" && !!name && (
                <p className="text-xs text-slate-500">
                  Fix the URL problem above to enable testing.
                </p>
              )}
            </form>

            <div className="p-6 border border-slate-200 rounded-xl bg-white">
              <h3 className="font-semibold text-slate-900 mb-4">Your data sources</h3>
              {loading ? (
                <p className="text-slate-500">Loading...</p>
              ) : queryError ? (
                <p className="text-sm text-red-600">{queryError.message}</p>
              ) : (data as any)?.dataSources?.length === 0 ? (
                <p className="text-xs text-slate-500">
                  Nothing yet — the manual on the right shows exactly what to paste.
                </p>
              ) : (
                <ul className="space-y-2">
                  {(data as any)?.dataSources?.map((ds: any) => (
                    <li key={ds.id} className="p-3 border border-slate-200 rounded text-sm">
                      <div className="font-medium text-slate-900">{ds.name}</div>
                      <div className="text-xs text-slate-500">
                        {ds.engine} · {ds.sandbox ? "sandbox" : "live"}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>

        <ConnectionManual engine={engine} />
      </main>
    </div>
  );
}

const EXAMPLES: { label: string; url: string; note: string }[] = [
  {
    label: "Local MySQL (your machine)",
    url: "jdbc:mysql://localhost:3306/your_db",
    note: "Username root is common locally. Works only with the app in local mode.",
  },
  {
    label: "Neon (cloud PostgreSQL)",
    url: "jdbc:postgresql://ep-xyz123.eu-central-1.aws.neon.tech/neondb",
    note: "Copy the host from the Neon dashboard 'Connection Details' and append /databasename. Username/password from the same page.",
  },
  {
    label: "Supabase (cloud PostgreSQL)",
    url: "jdbc:postgresql://aws-0-<region>.pooler.supabase.com:6543/postgres",
    note: "Use the pooled connection string; database is called 'postgres'. Username looks like postgres.<projectref>.",
  },
  {
    label: "AWS RDS (PostgreSQL)",
    url: "jdbc:postgresql://mydb.abc123xyz.eu-north-1.rds.amazonaws.com:5432/mydb",
    note: "The RDS 'Endpoint' is the host; the 'Database name' you set on creation goes after / (default postgres).",
  },
  {
    label: "Cloud MySQL (generic)",
    url: "jdbc:mysql://mydb.abc123.ap-south-1.rds.amazonaws.com:3306/mydb",
    note: "Same shape as local; only host/port differ. If the provider disables direct connections, allow your IP in their console.",
  },
];

function ConnectionManual({ engine }: { engine: string }) {
  const scheme = ENGINE_SCHEME[engine] ?? "jdbc:postgresql://";
  return (
    <aside className="w-full xl:w-96 shrink-0">
      <div className="sticky top-8 p-5 border border-slate-200 rounded-xl bg-white space-y-5 text-sm">
        <div>
          <h3 className="font-semibold text-slate-900">Connection manual</h3>
          <p className="text-xs text-slate-500 mt-1">
            What actually works — and how to read the error when it does not.
          </p>
        </div>

        <div>
          <h4 className="text-xs font-bold text-slate-400 uppercase tracking-wide mb-1.5">
            The formula ({engine === "MYSQL" ? "MySQL" : "PostgreSQL"})
          </h4>
          <code className="block p-2.5 rounded bg-slate-50 border border-slate-100 text-xs text-slate-700">
            {scheme}HOST[:PORT]/DATABASE
          </code>
          <ul className="mt-2 space-y-1 text-xs text-slate-600 list-disc pl-4">
            <li>HOST — the public hostname from your provider, or <code>localhost</code> for your machine
            </li>
            <li>PORT — optional if standard: 5432 is Postgres default, 3306 is MySQL default
            </li>
            <li>DATABASE — the name after the <code>/</code>; leaving it empty usually fails
            </li>
            <li>
              Engine selected on the form must match the URL prefix
              ({scheme.startsWith("jdbc:postgresql") ? " jdbc:postgresql://" : " jdbc:mysql://"} here)
            </li>
            <li>
              Username/Password belong in the two fields below the URL — never attach
              <code> user=…</code> or password values into the URL itself
            </li>
          </ul>
        </div>

        <div>
          <h4 className="text-xs font-bold text-slate-400 uppercase tracking-wide mb-1.5">
            Valid examples
          </h4>
          <ul className="space-y-2.5">
            {EXAMPLES.map((ex) => (
              <li key={ex.label} className="border border-slate-100 rounded p-2.5">
                <p className="text-xs font-medium text-slate-800 mb-1">{ex.label}</p>
                <p className="text-[11px] font-mono text-slate-700 break-all bg-slate-50 border border-slate-100 rounded px-2 py-1">
                  {ex.url}
                </p>
                <p className="text-[11px] text-slate-500 mt-1">{ex.note}</p>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <h4 className="text-xs font-bold text-slate-400 uppercase tracking-wide mb-1.5">
            Not valid
          </h4>
          <ul className="space-y-1.5 text-xs text-slate-600">
            <li><code className="text-red-600">postgresql://host/db</code> — missing <code>jdbc:</code> prefix</li>
            <li><code className="text-red-600">{scheme}host</code> — no <code>/database</code> part</li>
            <li><code className="text-red-600">jdbc:postgresql://host/db</code> chosen while engine = MySQL (scheme must match)</li>
            <li><code className="text-red-600">{scheme}localhost:3306/db</code> for a <em>cloud</em> database — localhost is your own machine, the app cannot reach its DB until it runs in local mode</li>
          </ul>
        </div>

        <div>
          <h4 className="text-xs font-bold text-slate-400 uppercase tracking-wide mb-1.5">
            Errors and what they confess
          </h4>
          <ul className="space-y-1.5 text-xs text-slate-600">
            <li><b>Username or password was rejected</b> — the server answered but refused the login: wrong password, wrong username, or the account does not exist. On Neon/Supabase, reset the password from the dashboard and paste carefully (no quotes, no spaces).</li>
            <li><b>Database does not exist</b> — the host is right but the name after <code>/</code> is wrong.</li>
            <li><b>Could not reach the database server</b> — wrong host/port, the provider blocks your IP (add it to their IP allowlist), or the database is paused (Neon free tiers scale to zero).</li>
            <li><b>Host name could not be resolved</b> — typo in the host, or you pasted only part of it.</li>
            <li><b>Host resolves to a blocked (internal) address</b> — you pointed at a private/localhost address while the app runs in cloud mode; run locally or allowlist the host.</li>
            <li><b>URL scheme does not match the engine</b> — postgres URL with engine MySQL or vice versa.</li>
          </ul>
        </div>

        <p className="text-xs text-slate-400 border-t border-slate-100 pt-3">
          The database user only needs <b>read</b> access — WhyQL is read-only by design
          ({""}
          <Link href="/ask" className="text-blue-600 hover:underline">
            try a question
          </Link>
          ). A read-only role is safer and satisfies the superuser warning.
        </p>
      </div>
    </aside>
  );
}
