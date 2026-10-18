"use client";

import { useState } from "react";
import Link from "next/link";
import { useDataSources, useAddDataSource } from "@/lib/graphql";

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
      <main className="max-w-3xl mx-auto py-8 px-6">
        <h1 className="text-2xl font-bold text-slate-900 mb-8">Data Sources</h1>

        <div className="grid gap-6 md:grid-cols-2">
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
                className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
              />
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
              disabled={!name || !jdbcUrl}
            >
              Test & Add
            </button>
          </form>

          <div className="p-6 border border-slate-200 rounded-xl bg-white">
            <h3 className="font-semibold text-slate-900 mb-4">Your data sources</h3>
            {loading ? (
              <p className="text-slate-500">Loading...</p>
            ) : queryError ? (
              <p className="text-sm text-red-600">{queryError.message}</p>
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
      </main>
    </div>
  );
}
