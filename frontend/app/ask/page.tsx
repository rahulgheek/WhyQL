"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import {
  useAskQuestion,
  useDataSources,
  useJob,
  useJobUpdates,
  useSaveToDashboard,
  type JobView,
} from "@/lib/graphql";
import {
  MAX_SESSION_WIDGETS,
  loadSessionWidgets,
  removeSessionWidget,
  saveSessionWidget,
  type SessionWidget,
} from "@/lib/sessionWidgets";
import ResultView, {
  type ChartState,
  resolveChartState,
} from "@/app/components/ResultView";

interface AskQuestionReply {
  askQuestion?: { jobId: string; status: string };
}

export interface DataSourcesReply {
  dataSources: { id: string; name: string; engine: string }[];
}

interface ChatMessage {
  /** Locally unique message id. */
  id: string;
  role: "user" | "assistant";
  /** User: the question. Assistant: the displayed question echo. */
  text: string;
  /** Assistant only: the linked pipeline job. */
  jobId?: string;
}

let localMsgId = 0;
function nextMsgId(): string {
  return `m-${Date.now()}-${localMsgId++}`;
}

export default function AskPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [dataSourceId, setDataSourceId] = useState("");
  const [sendError, setSendError] = useState("");
  const [widgets, setWidgets] = useState<SessionWidget[]>([]);

  const [askQuestion, askState] = useAskQuestion();
  const { data: datasourcesData, loading: dsLoading } = useDataSources();

  const bottomRef = useRef<HTMLDivElement>(null);
  const datasources = (datasourcesData as DataSourcesReply | undefined)?.dataSources ?? [];

  useEffect(() => {
    setWidgets(loadSessionWidgets());
  }, []);

  // Follow the newest message like a chat.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages.length]);

  async function send(questionText: string) {
    const text = questionText.trim();
    if (!text || !dataSourceId) return;
    setSendError("");
    setInput("");

    const pendingId = nextMsgId();
    setMessages((prev) => [
      ...prev,
      { id: nextMsgId(), role: "user", text },
      { id: pendingId, role: "assistant", text },
    ]);

    try {
      const res = await askQuestion({
        variables: { input: { dataSourceId, question: text } },
      });
      const jobId =
        (res.data as AskQuestionReply | null)?.askQuestion?.jobId ?? null;
      setMessages((prev) =>
        prev.map((m) => (m.id === pendingId ? { ...m, jobId: jobId ?? undefined } : m)),
      );
      if (!jobId) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === pendingId
              ? { ...m, jobId: undefined, text: "" }
              : m,
          ),
        );
      }
    } catch (err) {
      // Remove the pending bubble and surface the failure inline.
      setMessages((prev) => prev.filter((m) => m.id !== pendingId));
      setSendError(err instanceof Error ? err.message : "Failed to submit question");
    }
  }

  function handlePin(saved: SessionWidget[]) {
    setWidgets(saved);
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <main className="max-w-7xl mx-auto px-6 py-6 flex gap-6">
        {/* ── Chat column ── */}
        <section className="flex-1 min-w-0 flex flex-col" style={{ minHeight: "80vh" }}>
          <div className="flex items-center justify-between flex-wrap gap-3 mb-5">
            <div>
              <h1 className="text-xl font-bold text-slate-900">Ask your data</h1>
              <p className="text-sm text-slate-600">
                Chat-style answers with charts or tables — ask follow-ups any time,
                even while others are still running.
              </p>
            </div>
            <select
              value={dataSourceId}
              onChange={(e) => setDataSourceId(e.target.value)}
              className="border border-slate-300 rounded px-3 py-2 text-sm bg-white"
            >
              <option value="" disabled>
                {dsLoading ? "Loading…" : "Data source"}
              </option>
              {datasources.map((ds) => (
                <option key={ds.id} value={ds.id}>
                  {ds.name} ({ds.engine})
                </option>
              ))}
            </select>
          </div>

          {messages.length === 0 ? (
            <EmptyState onPick={send} disabled={!dataSourceId} />
          ) : (
            <div className="flex-1 space-y-5 overflow-y-auto pr-1">
              {messages.map((m) =>
                m.role === "user" ? (
                  <UserBubble key={m.id} text={m.text} />
                ) : (
                  <AssistantBubble
                    key={m.id}
                    message={m}
                    onPin={handlePin}
                    pinDisabled={widgets.length >= MAX_SESSION_WIDGETS}
                  />
                ),
              )}
              <div ref={bottomRef} />
            </div>
          )}

          {sendError && (
            <div className="mb-2 p-3 rounded bg-red-50 text-red-700 text-sm">
              {sendError}
            </div>
          )}

          <Composer
            value={input}
            onChange={setInput}
            onSend={send}
            disabled={!dataSourceId || askState.loading}
            disabledHint={
              !dataSourceId ? "Select a data source first" : undefined
            }
          />
        </section>

        {/* ── Session rail ── */}
        <aside className="hidden lg:block w-80 shrink-0">
          <SessionRail widgets={widgets} onRemove={(id) => setWidgets(removeSessionWidget(id))} />
        </aside>
      </main>
    </div>
  );
}

function EmptyState({ onPick, disabled }: { onPick: (q: string) => void; disabled: boolean }) {
  const suggestions = [
    "How many tables are there?",
    "What tables do I have?",
    "Top 5 products by total sales",
    "Monthly revenue for the last 12 months",
  ];
  return (
    <div className="flex-1 flex flex-col items-center justify-center text-center">
      <h2 className="text-2xl font-bold text-slate-900 mb-2">
        What do you want to know about your data?
      </h2>
      <p className="text-sm text-slate-600 mb-6">
        Ask in plain English. Answers render as a chart when the data allows,
        otherwise as a table.
      </p>
      <div className="grid grid-cols-2 gap-2 max-w-md w-full">
        {suggestions.map((s) => (
          <button
            key={s}
            type="button"
            disabled={disabled}
            onClick={() => onPick(s)}
            className="p-3 text-xs rounded-lg border border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50/40 disabled:opacity-50 text-left"
          >
            {s}
          </button>
        ))}
      </div>
      {disabled && (
        <p className="mt-4 text-xs text-slate-500">
          Select a data source above first — or{" "}
          <Link href="/datasources" className="text-blue-600 hover:underline">
            add one
          </Link>
          .
        </p>
      )}
    </div>
  );
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-2xl px-4 py-2.5 rounded-2xl rounded-br-md bg-blue-600 text-white text-sm whitespace-pre-wrap">
        {text}
      </div>
    </div>
  );
}

function AssistantBubble({
  message,
  onPin,
  pinDisabled,
}: {
  message: ChatMessage;
  onPin: (saved: SessionWidget[]) => void;
  pinDisabled: boolean;
}) {
  if (!message.jobId) {
    return (
      <div className="max-w-2xl p-4 rounded-2xl rounded-bl-md bg-white border border-slate-200 text-sm text-slate-700">
        Ask me anything about your data.
      </div>
    );
  }
  return (
    <JobAnswer
      key={message.jobId}
      jobId={message.jobId}
      question={message.text}
      onPin={onPin}
      pinDisabled={pinDisabled}
    />
  );
}

/** Renders one pipeline job as an assistant answer — parallel-safe: every
 * bubble owns its own query + subscription. */
function JobAnswer({
  jobId,
  question,
  onPin,
  pinDisabled,
}: {
  jobId: string;
  question: string;
  onPin: (saved: SessionWidget[]) => void;
  pinDisabled: boolean;
}) {
  const { data: jobQueryData } = useJob(jobId);
  const [stalled, setStalled] = useState(false);
  const updatesRef = useRef<JobView | null>(null);

  // Live stage updates — until the answer turns terminal or the bubble gives
  // up (stalled). Skipping afterwards guarantees no stream can spin forever.
  const { data: jobUpdatesData } = useJobUpdates(stalled ? null : jobId);
  const updates = (jobUpdatesData as { jobUpdates?: JobView } | undefined)?.jobUpdates;
  // The last live snapshot survives unsubscribing.
  if (updates) {
    updatesRef.current = updates;
  }
  const liveJob = updates ?? updatesRef.current;
  const polledJob = (jobQueryData as { job?: JobView } | undefined)?.job ?? null;

  // Safety net: whatever happens on the backend, a bubble waits at most three
  // minutes, then stops and shows prose instead of thinking forever.
  const jobForClock = liveJob ?? polledJob;
  useEffect(() => {
    if (stalled) {
      return;
    }
    if (jobForClock && isTerminal(jobForClock.status)) {
      return;
    }
    const timer = setTimeout(() => setStalled(true), 180_000);
    return () => clearTimeout(timer);
  }, [stalled, jobForClock]);

  const job = stalled && !jobForClock ? null : jobForClock;
  const [saveToDashboard, saveState] = useSaveToDashboard();
  const [dashSaved, setDashSaved] = useState(false);
  const [pinHint, setPinHint] = useState("");
  // Chart settings for this answer; seeded from what the question asked for.
  const [chartState, setChartState] = useState<ChartState>(() => ({
    view: "auto",
    kind: inferChartHint(question) ?? "AUTO",
  }));

  // Subscription first (live), polled query as fallback.
  const result = job?.result ?? null;

  return (
    <div className="max-w-2xl p-4 rounded-2xl rounded-bl-md bg-white border border-slate-200 space-y-3">
      <p className="text-xs text-slate-400 font-mono truncate" title={question}>
        re: {question}
      </p>

      {!job && !stalled && <ThinkingFallback />}
      {job && !isTerminal(job.status) && !stalled && <ThinkingTimeline status={job.status} />}
      {stalled && !(job && isTerminal(job.status)) && (
        <div className="p-3 rounded bg-red-50 text-red-700 text-xs">
          Stopped waiting after three minutes — this question didn't finish. Try asking again,
          or rephrase it.
        </div>
      )}

      {job?.status === "NEEDS_CLARIFICATION" && (
        <ClarificationView questions={job?.clarifyingQuestions ?? []} />
      )}

      {job?.status === "COMPLETED" && job?.analysis && (
        <div
          className={
            result
              ? "p-3 rounded-lg bg-indigo-50 border border-indigo-100 space-y-1"
              : "p-3 rounded-lg bg-slate-50 border border-slate-200 space-y-1"
          }
        >
          <p className="text-[11px] font-semibold uppercase tracking-wide text-indigo-500">
            {result ? "Why — from the evidence" : "Answer"}
          </p>
          <p className="text-sm text-slate-700 leading-relaxed">{job.analysis}</p>
        </div>
      )}

      {job?.status === "COMPLETED" && result && (
        <>
          <ResultView
            result={result}
            value={chartState}
            onValueChange={setChartState}
          />
          <div className="flex items-center gap-2 flex-wrap pt-1 border-t border-slate-100">
            <button
              type="button"
              onClick={() => {
                const resolved = resolveChartState(result, chartState);
                const res = saveSessionWidget({
                  title: question,
                  result,
                  // Pinned widgets keep the exact shape you were looking at;
                  // "auto" stays auto.
                  chartKind:
                    chartState.kind === "AUTO"
                      ? resolved.view === "chart"
                        ? resolved.kind
                        : undefined
                      : chartState.kind,
                });
                if (res.ok) {
                  onPin(res.widgets);
                  setPinHint("Pinned ✓");
                } else {
                  setPinHint(res.reason);
                }
              }}
              disabled={pinDisabled}
              className="text-xs px-3 py-1.5 rounded border border-slate-300 text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              title="Pin to the session rail (max 4)"
            >
              {pinDisabled ? "Rail full (4)" : "📌 Pin for this session"}
            </button>
            <button
              type="button"
              disabled={dashSaved || saveState.loading}
              onClick={async () => {
                try {
                  await saveToDashboard({ variables: { jobId } });
                  setDashSaved(true);
                } catch {
                  /* dashboard save errors surface in the dashboard page */
                }
              }}
              className="text-xs px-3 py-1.5 rounded bg-slate-800 text-white hover:bg-slate-700 disabled:opacity-50"
            >
              {dashSaved ? "✓ On dashboard" : saveState.loading ? "Saving…" : "Save to dashboard"}
            </button>
            {pinHint && <span className="text-xs text-slate-500">{pinHint}</span>}
          </div>
        </>
      )}

      {job?.status === "FAILED" && (
        <div className="p-3 rounded bg-red-50 text-red-700 text-xs whitespace-pre-wrap">
          {job.errorMessage ?? "The query failed."}
        </div>
      )}
    </div>
  );
}

/**
 * Chart shape the user's question asks for, seeding the result's default:
 * "pie chart" → pie, trends/over-time phrasing → line, "bar" → bar.
 */
function inferChartHint(question: string): "BAR" | "LINE" | "PIE" | undefined {
  const q = question.toLowerCase();
  if (/\bpie\b/.test(q)) return "PIE";
  if (/\bbar\b/.test(q)) return "BAR";
  if (/\bline\b|\btrend\b|\bover time\b|\bmonthly\b|\bweekly\b|\bdaily\b|\byearly\b|\bby month\b|\bby day\b/.test(q)) {
    return "LINE";
  }
  return undefined;
}

function isTerminal(status: string): boolean {
  return status === "COMPLETED" || status === "FAILED" || status === "NEEDS_CLARIFICATION";
}

function ThinkingFallback() {
  return (
    <div className="space-y-2.5 py-1">
      <p className="text-sm font-medium text-slate-500 animate-pulse">Thinking…</p>
      <div className="space-y-1.5">
        <div className="h-2 w-3/4 rounded bg-slate-100 animate-pulse" />
        <div className="h-2 w-2/3 rounded bg-slate-100 animate-pulse" />
      </div>
    </div>
  );
}

const STAGES: { status: string; label: string }[] = [
  { status: "QUEUED", label: "Reading the schema" },
  { status: "GENERATING", label: "Understanding + writing the query" },
  { status: "VALIDATING", label: "Checking the query" },
  { status: "EXECUTING", label: "Running it against your data" },
  { status: "ANALYZING", label: "Summarizing results" },
];

/** GPT-style "thinking" indicator: live stage timeline from the subscription. */
function ThinkingTimeline({ status }: { status: string }) {
  const activeIdx = Math.max(
    0,
    STAGES.findIndex((s) => s.status === status),
  );
  return (
    <div className="space-y-2 text-xs">
      <p className="text-sm font-medium text-slate-600 flex items-center gap-2">
        <span className="inline-block w-2 h-2 rounded-full bg-blue-500 animate-pulse" />
        Thinking…
      </p>
      <ol className="space-y-1.5 pl-1">
        {STAGES.map((stage, idx) => (
          <li
            key={stage.status}
            className={`flex items-center gap-2 ${
              idx < activeIdx
                ? "text-slate-400"
                : idx === activeIdx
                  ? "text-slate-700 font-medium"
                  : "text-slate-300"
            }`}
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                idx < activeIdx
                  ? "bg-slate-300"
                  : idx === activeIdx
                    ? "bg-blue-500 animate-pulse"
                    : "bg-slate-100"
              }`}
            />
            {stage.label}
            {idx < activeIdx && <span aria-hidden>✓</span>}
          </li>
        ))}
      </ol>
    </div>
  );
}

function ClarificationView({ questions }: { questions: string[] }) {
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-slate-700">
        I can answer this more accurately with a bit more detail:
      </p>
      {questions.length > 0 ? (
        <ul className="list-disc pl-5 text-sm text-slate-600 space-y-1">
          {questions.map((q, i) => (
            <li key={i}>{q}</li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-slate-600">
          The request was too ambiguous to write a safe query.
        </p>
      )}
      <p className="text-xs text-slate-400">
        Rephrase with those details and ask again.
      </p>
    </div>
  );
}

function Composer({
  value,
  onChange,
  onSend,
  disabled,
  disabledHint,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: (q: string) => void;
  disabled: boolean;
  disabledHint?: string;
}) {
  return (
    <div className="mt-4">
      <div className="flex items-end gap-2 p-3 rounded-xl border border-slate-300 bg-white focus-within:ring-2 focus-within:ring-blue-500">
        <textarea
          rows={1}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              if (!disabled && value.trim()) onSend(value);
            }
          }}
          placeholder={disabledHint ?? "Ask a question… (Enter to send, Shift+Enter for a new line)"}
          className="flex-1 resize-none outline-none text-sm text-slate-800 bg-transparent"
          style={{ maxHeight: 120 }}
        />
        <button
          type="button"
          disabled={disabled || !value.trim()}
          onClick={() => onSend(value)}
          className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50 shrink-0"
        >
          Ask ↵
        </button>
      </div>
    </div>
  );
}

function SessionRail({
  widgets,
  onRemove,
}: {
  widgets: SessionWidget[];
  onRemove: (id: string) => void;
}) {
  const slots = Array.from({ length: MAX_SESSION_WIDGETS }, (_, i) => widgets[i] ?? null);
  return (
    <div className="sticky top-6 space-y-3">
      <div>
        <h2 className="text-sm font-bold text-slate-900">Saved for this session</h2>
        <p className="text-xs text-slate-500">
          Pin tables or charts to reuse while you keep asking (max {MAX_SESSION_WIDGETS}).
        </p>
      </div>
      {slots.map((w, i) =>
        w ? (
          <div
            key={w.id}
            className="p-3 border border-slate-200 rounded-xl bg-white"
          >
            <div className="flex items-start justify-between gap-2 mb-2">
              <p className="text-xs font-medium text-slate-700 truncate" title={w.title}>
                {w.title}
              </p>
              <button
                type="button"
                onClick={() => onRemove(w.id)}
                className="text-xs text-slate-400 hover:text-red-500 shrink-0"
                title="Remove"
              >
                ✕
              </button>
            </div>
            <ResultView result={w.result} defaultView="auto" chartKind={w.chartKind} />
          </div>
        ) : (
          <div
            key={`slot-${i}`}
            className="h-24 border border-dashed border-slate-200 rounded-xl bg-white/40 flex items-center justify-center"
          >
            <span className="text-xs text-slate-400">Empty slot</span>
          </div>
        ),
      )}
    </div>
  );
}
