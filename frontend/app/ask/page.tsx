"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import {
  useAskQuestion,
  useChatMessages,
  useChatSessions,
  useCreateChatSession,
  useDataSources,
  useDeleteChatSession,
  useJob,
  useJobUpdates,
  useRenameChatSession,
  useSaveToDashboard,
  type ChatMessagePageView,
  type ChatSessionView,
  type JobView,
  type ResultSectionView,
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

const SESSIONS_PAGE_SIZE = 12;

export default function AskPage() {
  return (
    <Suspense
      // Load-bearing during prerender: useSearchParams below opts its tree
      // into client rendering, so removing this boundary would break SSG.
      fallback={
        <div className="min-h-screen bg-[#0a0b12] flex items-center justify-center text-slate-400 animate-gentle-pulse">
          Loading…
        </div>
      }
    >
      <AskPageContent />
    </Suspense>
  );
}

function AskPageContent() {
  const router = useRouter();

  // ── Thread state ────────────────────────────────────────────────────────────
  // ?session=<id> resumes a saved conversation; the first ask of a fresh page
  // creates one lazily and its id is mirrored back into the URL.
  const searchParams = useSearchParams();
  const [activeSessionId, setActiveSessionId] = useState<string | null>(
    searchParams.get("session"),
  );
  const [activeSessionTitle, setActiveSessionTitle] = useState<string | null>(
    () => searchParams.get("session"),
  );
  const [creatingSession, setCreatingSession] = useState(false);
  const [creatingError, setCreatingError] = useState("");

  // ── Chat history (backwards paged, page 0 = newest window) ─────────────────
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [historyPagesLoaded, setHistoryPagesLoaded] = useState(0);
  const [hasOlderMessages, setHasOlderMessages] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);

  // ── Sidebar sessions (paged; pages accumulate for one scrolling list) ───────
  const [sessionPagesLoaded, setSessionPagesLoaded] = useState(0);
  const [sessions, setSessions] = useState<ChatSessionView[]>([]);
  const [sessionsHasNext, setSessionsHasNext] = useState(false);

  const [input, setInput] = useState("");
  const [dataSourceId, setDataSourceId] = useState("");
  const [sendError, setSendError] = useState("");
  const [widgets, setWidgets] = useState<SessionWidget[]>([]);

  const [askQuestion, askState] = useAskQuestion();
  const [createChatSession] = useCreateChatSession();
  const [renameChatSession] = useRenameChatSession();
  const [deleteChatSession] = useDeleteChatSession();
  const { data: datasourcesData, loading: dsLoading } = useDataSources();
  const { data: sessionsData, loading: sessionsLoading } = useChatSessions(
    sessionPagesLoaded,
    SESSIONS_PAGE_SIZE,
  );
  // Backwards paged server history: page 0 = newest window, ascending order.
  const { data: chatHistoryData, loading: historyLoading } = useChatMessages(
    activeSessionId,
    historyPagesLoaded,
    30,
  );

  const releasingLoadRef = useRef<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const datasources = (datasourcesData as DataSourcesReply | undefined)?.dataSources ?? [];

  useEffect(() => {
    setWidgets(loadSessionWidgets());
  }, []);

  // ── Sidebar: merge each sessions page (dedup by id) ────────────────────────
  const sessionsPage = (sessionsData as
    | { chatSessions?: { items: ChatSessionView[]; hasNext: boolean } }
    | undefined)?.chatSessions;
  useEffect(() => {
    if (!sessionsPage) return;
    setSessionsHasNext(sessionsPage.hasNext);
    setSessions((prev) => {
      const seen = new Set(prev.map((s) => s.id));
      return [...prev, ...sessionsPage.items.filter((s) => !seen.has(s.id))];
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionsData]);

  // Going back to "New chat" resets the sidebar pagination to the newest page,
  // so a just-created conversation appears at the top.
  function backToPageZero() {
    setSessions([]);
    setSessionsHasNext(false);
    setSessionPagesLoaded(0);
  }

  // ── Transcript: switch sessions → clear, then merge pages (dedup by id) ────
  useEffect(() => {
    setMessages([]);
    setHistoryPagesLoaded(0);
    setHasOlderMessages(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  const historyPage = (chatHistoryData as { chatMessages?: ChatMessagePageView } | undefined)
    ?.chatMessages;
  useEffect(() => {
    if (!historyPage || !activeSessionId) return;
    if (releasingLoadRef.current === `${activeSessionId}-${historyPage.page}`) return;
    releasingLoadRef.current = `${activeSessionId}-${historyPage.page}`;
    setHasOlderMessages(historyPage.hasNext);
    const mapped = historyPage.items.map((m) => ({
      id: m.id,
      role: m.role === "USER" ? ("user" as const) : ("assistant" as const),
      text: m.text ?? "",
      jobId: m.jobId ?? undefined,
    }));
    setMessages((prev) => {
      const seen = new Set(prev.map((m) => m.id));
      return [...mapped.filter((m) => !seen.has(m.id)), ...prev];
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatHistoryData, activeSessionId]);

  async function loadOlder() {
    if (!activeSessionId || loadingOlder) return;
    setLoadingOlder(true);
    setHistoryPagesLoaded((p) => p + 1);
    // The effect above prepends; release the button on the next data tick.
    setTimeout(() => setLoadingOlder(false), 800);
  }

  async function loadOlderSessions() {
    if (!sessionsHasNext || sessionsLoading) return;
    setSessionPagesLoaded((p) => p + 1);
  }

  // ── Sidebar actions ─────────────────────────────────────────────────────────
  function openSession(id: string) {
    if (id === activeSessionId) return;
    setActiveSessionId(id);
    const known = sessions.find((s) => s.id === id);
    setActiveSessionTitle(known?.title ?? null);
    // Facets of the page above read ?session= — keep the URL shareable.
    router.replace(`/ask?session=${id}`, { scroll: false });
  }

  function newChat() {
    setActiveSessionId(null);
    setActiveSessionTitle(null);
    setSendError("");
    setCreatingError("");
    backToPageZero();
    router.replace("/ask", { scroll: false });
  }

  async function handleRename(id: string, title: string) {
    const current = sessions.find((s) => s.id === id)?.title ?? "";
    const next = (title || "").trim();
    if (!next || next === current) return;
    try {
      await renameChatSession({ variables: { id, title: next } });
      setSessions((prev) => prev.map((s) => (s.id === id ? { ...s, title: next } : s)));
      if (id === activeSessionId) setActiveSessionTitle(next);
    } catch (err) {
      setSendError(err instanceof Error ? err.message : "Renaming the thread failed");
    }
  }

  async function handleDelete(id: string) {
    try {
      await deleteChatSession({ variables: { id } });
    } catch (err) {
      setSendError(err instanceof Error ? err.message : "Deleting the thread failed");
      return;
    }
    setSessions((prev) => prev.filter((s) => s.id !== id));
    if (id === activeSessionId) newChat();
  }

  // Follow the newest message like a chat.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages.length]);

  // ── Sending ─────────────────────────────────────────────────────────────────
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
      // Lazy session: the first ask of a fresh page opens one and every ask
      // of this thread rides the same id into the pipeline.
      let sessionId = activeSessionId;
      if (!sessionId) {
        setCreatingSession(true);
        const created = await createChatSession({
          variables: { title: text.slice(0, 60), dataSourceId },
        });
        sessionId =
          (created.data as
            | { createChatSession?: { id?: string } }
            | null)?.createChatSession?.id ?? null;
        setCreatingSession(false);
        if (!sessionId) {
          setCreatingError("Couldn't create a chat session — asking anyway.");
        } else {
          setActiveSessionId(sessionId);
          setActiveSessionTitle(text.slice(0, 60));
          router.replace(`/ask?session=${sessionId}`, { scroll: false });
        }
      }

      const res = await askQuestion({
        variables: {
          input: {
            dataSourceId,
            question: text,
            ...(sessionId ? { chatSessionId: sessionId } : {}),
          },
        },
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
              ? { ...m, jobId: undefined, text }
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

  const showEmptyState = messages.length === 0;

  return (
    <div className="min-h-screen bg-[#0a0b12] p-4">
      <div className="max-w-7xl mx-auto flex gap-4 items-stretch" style={{ height: "calc(100vh - 2rem)" }}>
        {/* ── Left sidebar: sessions (Zyricon style) ── */}
        <Sidebar
          sessions={sessions}
          loading={sessionsLoading && sessions.length === 0}
          hasNext={sessionsHasNext}
          activeSessionId={activeSessionId}
          hasUnsavedDraft={activeSessionId === null && (creatingSession || messages.length > 0)}
          onNewChat={newChat}
          onOpen={openSession}
          onRename={handleRename}
          onDelete={handleDelete}
          onLoadOlder={loadOlderSessions}
        />

        {/* ── Center: the chat ── */}
        <main className="flex-1 min-w-0 flex flex-col rounded-2xl border border-white/10 bg-white/[0.015] overflow-hidden">
          <div className="flex items-center gap-3 px-5 py-3.5 border-b border-white/10 flex-shrink-0">
            <div className="flex-1 min-w-0">
              <h1 className="text-sm font-semibold text-slate-100 truncate">
                {activeSessionId
                  ? activeSessionTitle || "Untitled thread"
                  : creatingSession
                    ? "Starting a new thread…"
                    : "New conversation"}
              </h1>
              <p className="text-[11px] text-slate-500 truncate">
                {activeSessionId
                  ? "Auto-saved — reopen anytime from this sidebar or the dashboard."
                  : "Answers auto-save to a thread after your first ask."}
              </p>
            </div>
            <select
              aria-label="Data source"
              value={dataSourceId}
              onChange={(e) => setDataSourceId(e.target.value)}
              className="input-refined text-xs max-w-[220px]"
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

          {showEmptyState ? (
            historyLoading && activeSessionId ? (
              <div className="flex-1 flex items-center justify-center text-slate-400 text-sm animate-gentle-pulse">
                Loading conversation…
              </div>
            ) : (
              <EmptyState onPick={send} disabled={!dataSourceId} />
            )
          ) : (
            <div className="flex-1 space-y-5 overflow-y-auto px-6 py-5">
              {hasOlderMessages && (
                <div className="text-center">
                  <button
                    type="button"
                    onClick={loadOlder}
                    disabled={loadingOlder}
                    className="text-xs px-4 py-1.5 rounded-full border border-white/15 text-slate-400 hover:bg-white/10 hover:text-white disabled:opacity-50 transition"
                  >
                    {loadingOlder ? "Loading…" : "Load older messages"}
                  </button>
                </div>
              )}
              {messages.map((m) =>
                m.role === "user" ? (
                  <UserBubble key={m.id} text={m.text} />
                ) : (
                  <AssistantBubble
                    key={m.id}
                    message={m}
                    onPin={handlePin}
                    pinDisabled={widgets.length >= MAX_SESSION_WIDGETS}
                    onReAsk={send}
                  />
                ),
              )}
              <div ref={bottomRef} />
            </div>
          )}

          {sendError && (
            <div className="mx-5 mb-2 p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20 flex-shrink-0">
              {sendError}
            </div>
          )}

          {creatingError && (
            <div className="mx-5 mb-2 p-2.5 rounded-xl bg-amber-500/10 text-amber-300 text-xs border border-amber-500/30 flex-shrink-0">
              {creatingError}
            </div>
          )}

          {widgets.length > 0 && (
            <PinnedStrip widgets={widgets} onRemove={(id) => setWidgets(removeSessionWidget(id))} />
          )}

          <div className="px-5 py-4 border-t border-white/10 flex-shrink-0">
            <Composer
              value={input}
              onChange={setInput}
              onSend={send}
              disabled={!dataSourceId || askState.loading}
              disabledHint={!dataSourceId ? "Select a data source first" : undefined}
            />
          </div>
        </main>
      </div>
    </div>
  );
}

// ─── Left sidebar ────────────────────────────────────────────────────────────

function Sidebar({
  sessions,
  loading,
  hasNext,
  hasUnsavedDraft,
  activeSessionId,
  onNewChat,
  onOpen,
  onRename,
  onDelete,
  onLoadOlder,
}: {
  sessions: ChatSessionView[];
  loading: boolean;
  hasNext: boolean;
  activeSessionId: string | null;
  hasUnsavedDraft: boolean;
  onNewChat: () => void;
  onOpen: (id: string) => void;
  onRename: (id: string, title: string) => void | Promise<void>;
  onDelete: (id: string) => void | Promise<void>;
  onLoadOlder: () => void;
}) {
  const [query, setQuery] = useState("");
  const filtered = query.trim()
    ? sessions.filter((s) =>
        (s.title ?? "").toLowerCase().includes(query.trim().toLowerCase()),
      )
    : sessions;

  return (
    <aside className="w-72 shrink-0 flex flex-col rounded-2xl border border-white/10 bg-white/[0.02] overflow-hidden">
      <div className="px-4 pt-4 pb-3 space-y-3 flex-shrink-0">
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold tracking-[0.2em] text-slate-500">
            WHYQL
          </span>
          <span className="text-[10px] text-slate-700">·</span>
          <span className="text-[10px] text-slate-600 font-medium">Ask your data</span>
        </div>
        <button
          type="button"
          onClick={onNewChat}
          disabled={hasUnsavedDraft}
          title={hasUnsavedDraft ? "Finish or clear this draft first" : undefined}
          className="w-full flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl bg-indigo-600 text-white text-xs font-semibold hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/25 disabled:opacity-40 disabled:hover:bg-indigo-600 disabled:cursor-not-allowed transition"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          New chat
        </button>
        <div className="relative">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-600" aria-hidden>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <circle cx="11" cy="11" r="7" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
          </span>
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search threads…"
            className="input-refined text-xs pl-8"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-2 pb-2">
        {loading ? (
          <div className="flex items-center justify-center text-slate-500 text-xs py-10 animate-gentle-pulse">
            Loading threads…
          </div>
        ) : filtered.length === 0 ? (
          <p className="text-[11px] text-slate-600 text-center leading-relaxed px-4 py-10">
            {query.trim() ? "No thread matches." : "No saved threads yet. Your first ask starts one."}
          </p>
        ) : (
          <nav className="space-y-0.5">
            {filtered.map((session) => {
              const isActive = session.id === activeSessionId;
              const label = session.title || "Untitled thread";
              return (
                <div
                  key={session.id}
                  className={`group flex items-center rounded-xl transition ${
                    isActive ? "bg-indigo-500/15" : "hover:bg-white/5"
                  }`}
                >
                  {isActive && (
                    <span className="ml-1.5 h-5 w-0.5 rounded-full bg-indigo-400" aria-hidden />
                  )}
                  <button
                    type="button"
                    onClick={() => onOpen(session.id)}
                    className={`flex-1 min-w-0 text-left px-3 py-2 ${isActive ? "" : "pl-3.5"}`}
                    title={label}
                  >
                    <p
                      className={`truncate text-[13px] ${
                        isActive ? "text-white font-medium" : "text-slate-400"
                      }`}
                    >
                      {label}
                    </p>
                    <p className="text-[10px] text-slate-600 truncate">
                      {session.messageCount} message{session.messageCount === 1 ? "" : "s"}
                    </p>
                  </button>
                  <span className="flex items-center pr-1.5">
                    <button
                      type="button"
                      aria-label={`Rename "${label}"`}
                      onClick={() => {
                        const next = window.prompt("Rename thread", label);
                        if (next !== null) void onRename(session.id, next);
                      }}
                      className="p-1 rounded-md text-slate-600 hover:text-slate-200 hover:bg-white/10 opacity-0 group-hover:opacity-100 transition"
                    >
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                      </svg>
                    </button>
                    <button
                      type="button"
                      aria-label={`Delete "${label}"`}
                      onClick={() => {
                        if (
                          window.confirm(
                            `Delete "${label}"? This permanently removes its messages.`,
                          )
                        ) {
                          void onDelete(session.id);
                        }
                      }}
                      className="p-1 rounded-md text-slate-600 hover:text-red-400 hover:bg-red-500/10 opacity-0 group-hover:opacity-100 transition"
                    >
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="3 6 5 6 21 6" />
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                      </svg>
                    </button>
                  </span>
                </div>
              );
            })}
            {hasNext && !query.trim() && (
              <button
                type="button"
                onClick={onLoadOlder}
                className="w-full text-center text-[11px] text-slate-500 hover:text-slate-300 py-2.5 transition"
              >
                Load older threads
              </button>
            )}
          </nav>
        )}
      </div>

      <Link
        href="/dashboard"
        className="flex items-center gap-2 px-4 py-3.5 border-t border-white/10 text-xs text-slate-500 hover:text-slate-200 hover:bg-white/5 transition flex-shrink-0"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6" />
        </svg>
        Back to dashboard
      </Link>
    </aside>
  );
}

// ─── Chat primitives (unchanged behavior, restyled pane) ─────────────────────

function EmptyState({ onPick, disabled }: { onPick: (q: string) => void; disabled: boolean }) {
  const suggestions = [
    "How many tables are there?",
    "What tables do I have?",
    "Top 5 products by total sales",
    "Monthly revenue for the last 12 months",
  ];
  return (
    <div className="flex-1 flex flex-col items-center justify-center text-center animate-fade-in px-6">
      <div className="inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-500/15 text-indigo-300 mb-4">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </div>
      <h2 className="text-xl font-bold text-white mb-2 tracking-tight">
        What do you want to know?
      </h2>
      <p className="text-sm text-slate-500 mb-7 max-w-xs">
        Ask in plain English — answers render as a chart when the data allows,
        otherwise as a table.
      </p>
      <div className="grid grid-cols-2 gap-2 max-w-md w-full">
        {suggestions.map((s) => (
          <button
            key={s}
            type="button"
            disabled={disabled}
            onClick={() => onPick(s)}
            className="p-3 text-[12px] rounded-xl border border-white/10 bg-white/[0.03] text-slate-400 hover:border-indigo-500/50 hover:text-slate-200 disabled:opacity-50 text-left transition"
          >
            {s}
          </button>
        ))}
      </div>
      {disabled && (
        <p className="mt-5 text-xs text-slate-500 leading-relaxed">
          Select a data source above first — or{" "}
          <Link href="/datasources" className="text-indigo-400 hover:underline font-medium">
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
    <div className="flex justify-end animate-slide-in-up" style={{ animationDelay: "60ms" }}>
      <div className="max-w-[82%] px-4 py-3 rounded-2xl rounded-br-md bg-gradient-to-br from-indigo-500 to-violet-600 text-white text-sm font-medium whitespace-pre-wrap shadow-lg shadow-indigo-500/30">
        {text}
      </div>
    </div>
  );
}

function AssistantBubble({
  message,
  onPin,
  pinDisabled,
  onReAsk,
}: {
  message: ChatMessage;
  onPin: (saved: SessionWidget[]) => void;
  pinDisabled: boolean;
  onReAsk: (question: string) => void;
}) {
  if (!message.jobId) {
    // History text answers (general/meta) carry prose, not a job.
    if (message.text) {
      return (
        <div className="max-w-2xl p-4 rounded-2xl rounded-bl-md bg-white/[0.04] border border-white/10 animate-fade-in">
          <p className="text-sm text-slate-300 whitespace-pre-wrap leading-relaxed">
            {message.text}
          </p>
        </div>
      );
    }
    return (
      <div className="max-w-2xl p-4 rounded-2xl rounded-bl-md bg-white/[0.04] border border-white/10 animate-fade-in">
        <div className="flex items-center gap-3 py-1">
          <span className="inline-block h-2.5 w-2.5 rounded-full bg-indigo-400 animate-pulse" />
          <span className="text-sm text-slate-400">Working on it…</span>
        </div>
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
      onReAsk={onReAsk}
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
  onReAsk,
}: {
  jobId: string;
  question: string;
  onPin: (saved: SessionWidget[]) => void;
  pinDisabled: boolean;
  onReAsk: (question: string) => void;
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
  // Chart settings per answer section (a question may render several views);
  // seeded from what the question asked for.
  const [chartStates, setChartStates] = useState<Record<number, ChartState>>(
    () => ({ 0: { view: "auto", kind: inferChartHint(question) ?? "AUTO" } }),
  );
  const chartStateFor = (index: number): ChartState =>
    chartStates[index] ?? { view: "auto", kind: inferChartHint(question) ?? "AUTO" };
  const setChartStateFor = (index: number, value: ChartState) =>
    setChartStates((prev) => ({ ...prev, [index]: value }));

  // Subscription first (live), polled query as fallback.
  const hasData = (job?.status === "COMPLETED") && (
    (job?.resultSections?.length ?? 0) > 0 || job?.result != null);
  // New backends ship sections (multi-view + size policy); older snapshots
  // had only the flat result.
  const sections: ResultSectionView[] =
    job?.status === "COMPLETED"
      ? (job?.resultSections?.length
          ? job.resultSections
          : job?.result
            ? [{ sizeCategory: "OK", result: job.result }]
            : [])
      : [];

  return (
    <div className="max-w-2xl p-4 rounded-2xl rounded-bl-md bg-white/[0.04] border border-white/10 space-y-3 animate-slide-in-up" style={{ animationDelay: "100ms" }}>
      <div className="flex items-center gap-2">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500 truncate flex-1" title={question}>
          re: {question}
        </p>
      </div>

      {!job && !stalled && <ThinkingFallback />}
      {job && !isTerminal(job.status) && !stalled && <ThinkingTimeline status={job.status} />}
      {stalled && !(job && isTerminal(job.status)) && (
        <div className="p-3 rounded-xl bg-red-500/10 text-red-400 text-xs border border-red-500/20">
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
            hasData
              ? "p-3 rounded-xl bg-indigo-500/10 text-slate-200 border border-indigo-500/20 space-y-1"
              : "p-3 rounded-xl bg-white/5 text-slate-200 border border-white/10 space-y-1"
          }
        >
          <p className="text-[11px] font-semibold uppercase tracking-wide text-indigo-400">
            {hasData ? "Why — from the evidence" : "Answer"}
          </p>
          <p className="text-sm text-slate-300 leading-relaxed">{job.analysis}</p>
        </div>
      )}

      {job?.status === "COMPLETED" && (
        <>
          {sections.map((section, index) =>
            section.sizeCategory === "TOO_LARGE" ? (
              <TooLargeView
                key={`section-${index}`}
                title={section.title}
                totalRows={section.totalRows}
                question={question}
                onReAsk={onReAsk}
              />
            ) : (
              section.result && (
                <div key={`section-${index}`} className="space-y-1">
                  {sections.length > 1 && (
                    <p className="text-xs font-semibold text-slate-500">
                      {section.title || "Main result"}
                    </p>
                  )}
                  <ResultView
                    result={section.result}
                    value={chartStateFor(index)}
                    onValueChange={(value) => setChartStateFor(index, value)}
                  />
                  <div className="flex items-center gap-2 flex-wrap pt-1 border-t border-white/10">
                    <button
                      type="button"
                      onClick={() => {
                        if (!section.result) return;
                        const state = chartStateFor(index);
                        const resolved = resolveChartState(section.result, state);
                        const res = saveSessionWidget({
                          title: section.title
                            ? `${question} — ${section.title}`
                            : question,
                          result: section.result,
                          // Pinned widgets keep the exact shape you were looking at;
                          // "auto" stays auto.
                          chartKind:
                            state.kind === "AUTO"
                              ? resolved.view === "chart"
                                ? resolved.kind
                                : undefined
                              : state.kind,
                        });
                        if (res.ok) {
                          onPin(res.widgets);
                          setPinHint("Pinned ✓");
                        } else {
                          setPinHint(res.reason);
                        }
                      }}
                      disabled={pinDisabled}
                      className="text-xs font-medium px-3 py-1.5 rounded-xl border border-white/20 text-slate-300 hover:bg-white/10 hover:border-white/30 disabled:opacity-50 transition"
                      title="Pin to the strip below (max 4)"
                    >
                      {pinDisabled ? "Strip full (4)" : "📌 Pin for this session"}
                    </button>
                    {pinHint && <span className="text-xs text-slate-500">{pinHint}</span>}
                    {index === sections.length - 1 && (
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
                        className="text-xs font-medium px-3 py-1.5 rounded-xl bg-indigo-600 text-white hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 disabled:opacity-50 transition"
                      >
                        {dashSaved
                          ? "✓ On dashboard"
                          : saveState.loading ? "Saving…" : "Save to dashboard"}
                      </button>
                    )}
                  </div>
                </div>
              )
            )
          )}
        </>
      )}

      {job?.status === "FAILED" && (
        <div className="p-3 rounded-xl bg-red-500/10 text-red-400 text-xs whitespace-pre-wrap border border-red-500/20">
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

/**
 * The honest answer for "load everything": the rows exist but are far too many
 * to hand over raw — say so with the real count, and offer the two escapes the
 * pipeline is actually good at (an aggregate/chart or a narrower ask).
 */
function TooLargeView({
  title,
  totalRows,
  question,
  onReAsk,
}: {
  title?: string | null;
  totalRows?: number | null;
  question: string;
  onReAsk: (question: string) => void;
}) {
  const count = totalRows != null
    ? new Intl.NumberFormat("en-US").format(totalRows)
    : "many";
  return (
    <div className="p-3 rounded-xl bg-amber-500/10 text-amber-400 border border-amber-500/20 space-y-1.5">
      <p className="text-[11px] font-semibold uppercase tracking-wide text-amber-400">
        {title ? `Too large — ${title}` : "Result too large"}
      </p>
      <p className="text-sm text-slate-300 leading-relaxed">
        This result has {count} row{totalRows === 1 ? "" : "s"} — far too many to show as
        raw data, so the rows were withheld to keep the page fast. Ask for the{" "}
        <span className="font-medium">shape</span> instead: totals, monthly breakdowns,
        top-N lists and groups return small results that chart beautifully.
      </p>
      <button
        type="button"
        onClick={() =>
          onReAsk(
            `${question} — aggregate it for a chart (group, count or sum, then order and limit to the top 30 rows)`,
          )
        }
        className="text-xs font-medium px-3 py-1.5 rounded-xl border border-amber-500/50 bg-white/5 text-amber-300 hover:bg-amber-500/10 hover:border-amber-400/50"
        title="Rephrase the question as an aggregate that charts well"
      >
        ⚡ Re-ask as a chart
      </button>
    </div>
  );
}

function ThinkingFallback() {
  return (
    <div className="space-y-3 py-1 animate-fade-in">
      <div className="flex items-center gap-2">
        <span className="inline-block h-2.5 w-2.5 rounded-full bg-indigo-400 animate-pulse" />
        <p className="text-sm font-medium text-slate-400">Working on it</p>
      </div>
      <div className="space-y-1.5">
        <div className="h-1.5 w-3/4 rounded-full bg-white/10 animate-gentle-pulse" />
        <div className="h-1.5 w-2/3 rounded-full bg-white/10 animate-gentle-pulse" style={{ animationDelay: "150ms" }} />
        <div className="h-1.5 w-1/2 rounded-full bg-white/10 animate-gentle-pulse" style={{ animationDelay: "300ms" }} />
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
    <div className="space-y-2.5 animate-fade-in">
      <p className="text-sm font-medium text-slate-400 flex items-center gap-2">
        <span className="inline-block h-2 w-2 rounded-full bg-indigo-400 animate-pulse" />
        Working through the pipeline
      </p>
      <ol className="space-y-2 pl-2 border-l border-white/10">
        {STAGES.map((stage, idx) => (
          <li
            key={stage.status}
            className={`flex items-center gap-3 relative ${
              idx < activeIdx
                ? "text-slate-400"
                : idx === activeIdx
                  ? "text-white font-medium"
                  : "text-slate-600"
            }`}
          >
            <span
              className={`absolute -left-[1.5px] top-1/2 -translate-y-1/2 w-2 h-2 rounded-full border-2 ${
                idx < activeIdx
                  ? "bg-white/20 border-white/20"
                  : idx === activeIdx
                    ? "bg-indigo-400 border-indigo-400 animate-pulse"
                    : "bg-transparent border-white/10"
              }`}
            />
            <span className="px-1">{stage.label}</span>
            {idx < activeIdx && (
              <span className="ml-1 text-[10px] text-emerald-400" aria-hidden>
                ✓
              </span>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}

function ClarificationView({ questions }: { questions: string[] }) {
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-slate-300">
        I can answer this more accurately with a bit more detail:
      </p>
      {questions.length > 0 ? (
        <ul className="list-disc pl-5 text-sm text-slate-400 space-y-1">
          {questions.map((q, i) => (
            <li key={i}>{q}</li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-slate-400">
          The request was too ambiguous to write a safe query.
        </p>
      )}
      <p className="text-xs text-slate-500">
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
    <div className="flex items-end gap-2 p-3 rounded-2xl border border-white/10 bg-white/[0.04] shadow-sm focus-within:border-indigo-500/50 focus-within:ring-2 focus-within:ring-indigo-500/20 transition">
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
        className="flex-1 resize-none outline-none text-sm text-white bg-transparent placeholder:text-slate-600"
        style={{ maxHeight: 120 }}
      />
      <button
        type="button"
        disabled={disabled || !value.trim()}
        onClick={() => onSend(value)}
        aria-label="Send"
        className="px-4 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-semibold hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 disabled:opacity-50 transition active:scale-95"
      >
        Send
      </button>
    </div>
  );
}

/** Pinned widgets live in a horizontal strip under the transcript (Zyricon
 * shows supplementary panels under the chat; the rail keeps its slots). */
function PinnedStrip({
  widgets,
  onRemove,
}: {
  widgets: SessionWidget[];
  onRemove: (id: string) => void;
}) {
  return (
    <div className="px-5 pb-1 flex gap-2 overflow-x-auto flex-shrink-0">
      {widgets.map((w) => (
        <div
          key={w.id}
          className="relative w-56 h-40 shrink-0 rounded-xl border border-white/10 bg-[#0f101c] overflow-hidden group"
        >
          <p className="text-[10px] font-medium text-slate-400 px-3 pt-2 pr-7 truncate" title={w.title}>
            {w.title}
          </p>
          <div className="h-[calc(10rem-2rem)] overflow-hidden">
            <ResultView result={w.result} defaultView="auto" chartKind={w.chartKind} />
          </div>
          <button
            type="button"
            onClick={() => onRemove(w.id)}
            aria-label={`Remove "${w.title}" from the strip`}
            className="absolute top-2 right-2 h-6 w-6 rounded-md bg-[#0a0b12]/85 border border-white/10 text-slate-500 hover:text-red-400 opacity-0 group-hover:opacity-100 transition flex items-center justify-center text-[10px]"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
