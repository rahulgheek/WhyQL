"use client";

import { useState } from "react";
import Link from "next/link";
import {
  useAskQuestion,
  useDataSources,
  useJob,
  useJobUpdates,
  useSaveToDashboard,
} from "@/lib/graphql";

interface AskQuestionReply {
  askQuestion?: { jobId: string; status: string };
}

interface DataSourcesReply {
  dataSources: { id: string; name: string; engine: string }[];
}

interface JobReply {
  job?: JobView;
}

interface JobUpdateReply {
  jobUpdates?: JobView;
}

interface JobView {
  id: string;
  status: string;
  progress?: number;
  result: {
    columns: { name: string; typeName: string }[];
    rows: unknown[];
    truncated: boolean;
  } | null;
  errorMessage?: string;
}

export default function AskPage() {
  const [question, setQuestion] = useState("");
  const [dataSourceId, setDataSourceId] = useState("");
  const [jobId, setJobId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const [askQuestion] = useAskQuestion();
  const [saveToDashboard, saveState] = useSaveToDashboard();
  const [savedCardId, setSavedCardId] = useState(false);
  const [saveError, setSaveError] = useState("");
  const { data: datasourcesData, loading: dsLoading } = useDataSources();

  // Initial job load + refetch safety; live stage transitions arrive via the
  // jobUpdates subscription below (snapshot first, then every stage change).
  const { data: jobQueryData } = useJob(jobId);
  const { data: jobUpdatesData, error: jobUpdateError } = useJobUpdates(jobId);

  const datasources = (datasourcesData as DataSourcesReply | undefined)?.dataSources ?? [];

  async function handleAsk(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      const res = await askQuestion({
        variables: { input: { dataSourceId, question } },
      });
      setJobId((res.data as AskQuestionReply | null)?.askQuestion?.jobId ?? null);
      // Fresh job: clear any save state from the previous one.
      setSavedCardId(false);
      setSaveError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to submit question");
    }
  }

  // Prefer the subscription's latest snapshot; fall back to the polled query
  // (covers the brief window before the WS connection is acknowledged).
  const job =
    (jobUpdatesData as JobUpdateReply | undefined)?.jobUpdates ??
    (jobQueryData as JobReply | undefined)?.job ??
    null;

  if (jobId && job) {
    return (
      <div className="min-h-screen bg-slate-50 p-6">
        <nav className="mb-6">
          <Link href="/" className="text-sm text-blue-600 hover:underline">← Back</Link>
        </nav>
        <h1 className="text-xl font-bold text-slate-900 mb-2">Job {job.id}</h1>
        <div className="flex items-center gap-3 mb-4">
          <span
            className={`px-2.5 py-0.5 rounded-full text-xs font-medium ${
              job.status === "COMPLETED"
                ? "bg-green-100 text-green-700"
                : job.status === "FAILED" || job.status === "NEEDS_CLARIFICATION"
                  ? "bg-red-100 text-red-700"
                  : "bg-blue-100 text-blue-700"
            }`}
          >
            {job.status}
          </span>
          {typeof job.progress === "number" && (
            <div className="flex-1 max-w-xs h-2 bg-slate-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-blue-600 rounded-full transition-all"
                style={{ width: `${job.progress}%` }}
              />
            </div>
          )}
        </div>
        {job.result ? (
          <div className="p-4 border border-slate-200 rounded-xl bg-white">
            <h3 className="font-medium text-slate-900 mb-2">Result</h3>
            <pre className="text-xs text-slate-600 overflow-x-auto">
              {JSON.stringify(job.result, null, 2)}
            </pre>
          </div>
        ) : (
          <p className="text-slate-500">Live updates will appear here…</p>
        )}
        {job.errorMessage && (
          <div className="p-3 rounded bg-red-50 text-red-700 text-sm mt-3">
            {job.errorMessage}
          </div>
        )}
        {job.status === "COMPLETED" && (
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              onClick={async () => {
                setSaveError("");
                try {
                  await saveToDashboard({ variables: { jobId: job.id } });
                  setSavedCardId(true);
                } catch (err) {
                  setSaveError(err instanceof Error ? err.message : "Save failed");
                }
              }}
              disabled={savedCardId || saveState.loading}
              className="px-4 py-2 rounded bg-slate-800 text-white text-sm font-medium hover:bg-slate-700 disabled:opacity-50"
            >
              {savedCardId
                ? "✓ Saved to dashboard"
                : saveState.loading
                  ? "Saving…"
                  : "Save to dashboard"}
            </button>
            {savedCardId && (
              <Link href="/dashboard" className="text-sm text-blue-600 hover:underline">
                View dashboard →
              </Link>
            )}
            {saveError && <span className="text-sm text-red-600">{saveError}</span>}
          </div>
        )}
        {jobUpdateError && (
          <p className="mt-2 text-xs text-slate-500">
            Live updates unavailable ({String(jobUpdateError)}) — showing last known state.
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 p-6">
      <nav className="mb-6">
        <Link href="/" className="text-sm text-blue-600 hover:underline">← Back</Link>
      </nav>
      <h1 className="text-xl font-bold text-slate-900 mb-6">Ask a question</h1>
      {error && (
        <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">{error}</div>
      )}
      <form onSubmit={handleAsk} className="p-6 border border-slate-200 rounded-xl bg-white space-y-4 max-w-2xl">
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1">
            Data source
          </label>
          <select
            required
            value={dataSourceId}
            onChange={(e) => setDataSourceId(e.target.value)}
            className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
          >
            <option value="" disabled>
              {dsLoading ? "Loading…" : "Select a data source"}
            </option>
            {datasources.map((ds) => (
              <option key={ds.id} value={ds.id}>
                {ds.name} ({ds.engine})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1">
            Question
          </label>
          <textarea
            required
            rows={3}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            className="w-full border border-slate-300 rounded px-3 py-2 focus:ring-2 focus:ring-blue-500"
            placeholder="e.g. What are the top 5 products by total sales?"
          />
        </div>
        <button
          type="submit"
          className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          disabled={!question || !dataSourceId}
        >
          Ask
        </button>
      </form>
      <p className="mt-4 text-sm text-center text-slate-600">
        Stage updates stream live over a GraphQL subscription while the job runs.
      </p>
    </div>
  );
}
