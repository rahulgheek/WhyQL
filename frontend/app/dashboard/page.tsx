"use client";

import { useState } from "react";
import Link from "next/link";
import { useDashboard, useRemoveDashboardCard } from "@/lib/graphql";
import ResultView from "@/app/components/ResultView";

interface DashboardReply {
  dashboard: {
    id: string;
    jobId: string;
    question: string;
    engine: string;
    createdAt: string;
    result: {
      columns: { name: string; typeName: string }[];
      rows: unknown[];
      truncated: boolean;
    } | null;
  }[];
}

export default function DashboardPage() {
  const { data, loading, error, refetch } = useDashboard();
  const [removeCard] = useRemoveDashboardCard();
  const [removingId, setRemovingId] = useState<string | null>(null);

  const cards = (data as DashboardReply | undefined)?.dashboard ?? [];

  async function handleRemove(cardId: string) {
    setRemovingId(cardId);
    try {
      await removeCard({ variables: { id: cardId } });
      await refetch();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Failed to remove card");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <main className="max-w-6xl mx-auto p-6">
        <h1 className="text-xl font-bold text-slate-900 mb-1">Dashboards</h1>
        <p className="text-sm text-slate-600 mb-6">
          Questions you saved from result views, snapshotted at the moment they
          were answered.
        </p>

        {error && (
          <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
            {error instanceof Error ? error.message : "Failed to load dashboard"}
          </div>
        )}

        {loading ? (
          <p className="text-slate-500">Loading your cards…</p>
        ) : cards.length === 0 ? (
          <div className="p-8 border border-dashed border-slate-300 rounded-xl bg-white text-center">
            <p className="text-slate-600 mb-3">
              No saved cards yet. Ask a question and hit
              <span className="mx-1 font-medium">Save to dashboard</span>
              on a completed result.
            </p>
            <Link
              href="/ask"
              className="inline-block px-4 py-2 rounded bg-blue-600 text-white text-sm font-medium hover:bg-blue-700"
            >
              Ask a question
            </Link>
          </div>
        ) : (
          <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
            {cards.map((card) => (
              <div
                key={card.id}
                className="border border-slate-200 rounded-xl bg-white flex flex-col"
              >
                <div className="p-4 border-b border-slate-100 flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-medium text-slate-900">
                      {card.question}
                    </p>
                    <p className="text-xs text-slate-500 mt-1">
                      {card.engine ? `${card.engine} • ` : ""}
                      {card.createdAt
                        ? new Date(card.createdAt).toLocaleDateString(undefined, {
                            month: "short",
                            day: "numeric",
                          })
                        : ""}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => handleRemove(card.id)}
                    disabled={removingId === card.id}
                    className="text-xs px-2 py-1 rounded border border-slate-200 text-slate-500 hover:bg-slate-50 hover:text-slate-700 disabled:opacity-50"
                    title="Remove from dashboard"
                  >
                    {removingId === card.id ? "…" : "Remove"}
                  </button>
                </div>
                <div className="p-4">
                  <ResultView result={card.result} defaultView="table" />
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
