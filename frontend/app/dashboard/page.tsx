"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  useDashboard,
  useRemoveDashboardCard,
  useDataSources,
  useChatSessions,
  useDeleteChatSession,
  useRenameChatSession,
} from "@/lib/graphql";
import {
  revealCredentials,
  updateCredentials,
  testConnection,
  DatasourceApiError,
} from "@/lib/datasourceApi";
import { adoptPassword, adoptPasswordInit, AuthApiError } from "@/lib/auth";
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

interface DataSourcesReply {
  dataSources: {
    id: string;
    name: string;
    engine: string;
    sandbox: boolean;
    createdAt: string;
  }[];
}

const TABS = [
  { key: "overview", label: "Overview" },
  { key: "chats", label: "Chats" },
  { key: "connections", label: "Connections" },
  { key: "saved", label: "Saved" },
] as const;
type TabKey = (typeof TABS)[number]["key"];

const SESSIONS_PAGE_SIZE = 10;

export default function DashboardPage() {
  const router = useRouter();
  const [tab, setTab] = useState<TabKey>("overview");
  const [sessionPage, setSessionPage] = useState(0);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameTitle, setRenameTitle] = useState("");
  const [busySessionId, setBusySessionId] = useState<string | null>(null);

  // Shared data: counts serve the overview, lists serve the tabs.
  const { data: cardsReply, loading: cardsLoading, refetch: refetchCards } = useDashboard();
  const { data: sourcesReply } = useDataSources();
  const { data: sessionsReply, loading: sessionsLoading, refetch: refetchSessions } =
    useChatSessions(sessionPage, SESSIONS_PAGE_SIZE);

  const [removeCard] = useRemoveDashboardCard();
  const [removeSession] = useDeleteChatSession();
  const [renameSession] = useRenameChatSession();

  const cards = (cardsReply as DashboardReply | undefined)?.dashboard ?? [];
  const sources = (sourcesReply as DataSourcesReply | undefined)?.dataSources ?? [];
  const sessionsPageData = (sessionsReply as
    | {
        chatSessions?: {
          items: {
            id: string;
            title?: string | null;
            dataSourceId?: string | null;
            messageCount: number;
            updatedAt: string;
          }[];
          total: number;
          size: number;
          hasNext: boolean;
        };
      }
    | undefined)?.chatSessions;
  const sessions = sessionsPageData?.items ?? [];

  async function handleRemove(cardId: string) {
    setRemovingId(cardId);
    try {
      await removeCard({ variables: { id: cardId } });
      await refetchCards();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Failed to remove card");
    } finally {
      setRemovingId(null);
    }
  }

  async function handleDeleteSession(id: string) {
    if (!window.confirm("Delete this chat and all of its messages?")) return;
    setBusySessionId(id);
    try {
      await removeSession({ variables: { id } });
      await refetchSessions();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Failed to delete chat");
    } finally {
      setBusySessionId(null);
    }
  }

  async function handleRename(id: string) {
    const title = renameTitle.trim();
    if (!title) {
      setRenamingId(null);
      return;
    }
    setBusySessionId(id);
    try {
      await renameSession({ variables: { id, title } });
      await refetchSessions();
      setRenamingId(null);
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Failed to rename chat");
    } finally {
      setBusySessionId(null);
    }
  }

  function openSession(sessionId: string) {
    router.push(`/ask?session=${sessionId}`);
  }

  return (
    <div className="min-h-screen bg-[#0a0b12]">
      <main className="max-w-6xl mx-auto p-6 animate-fade-in">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-xl font-bold tracking-tight text-white mb-1">Dashboard</h1>
            <p className="text-sm text-slate-400">
              Your workspace at a glance — chats, connections and pinned answers.
            </p>
          </div>
        </div>

        {/* ── Tab bar ── */}
        <div className="mb-6 inline-flex p-1 rounded-xl border border-white/10 bg-white/5">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              className={
                "px-4 py-1.5 rounded-lg text-sm font-medium transition " +
                (tab === t.key
                  ? "bg-indigo-600 text-white shadow shadow-indigo-500/30"
                  : "text-slate-400 hover:text-white")
              }
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab === "overview" && (
          <OverviewTab
            cards={cards}
            sources={sources}
            sessions={sessions}
            sessionsTotal={sessionsPageData?.total ?? 0}
            sessionsLoading={sessionsLoading}
            openSession={openSession}
          />
        )}

        {tab === "chats" && (
          <ChatsTab
            sessions={sessions}
            sessionsPageData={sessionsPageData}
            loading={sessionsLoading}
            page={sessionPage}
            onPage={setSessionPage}
            renamingId={renamingId}
            renameTitle={renameTitle}
            setRenameTitle={setRenameTitle}
            startRename={(s) => {
              setRenamingId(s.id);
              setRenameTitle(s.title ?? "");
            }}
            cancelRename={() => setRenamingId(null)}
            submitRename={handleRename}
            deleteSession={handleDeleteSession}
            openSession={openSession}
            busySessionId={busySessionId}
          />
        )}

        {tab === "connections" && <ConnectionsTab sources={sources} />}

        {tab === "saved" && (
          <SavedTab
            cards={cards}
            loading={cardsLoading}
            onHide={handleRemove}
            removingId={removingId}
          />
        )}
      </main>
    </div>
  );
}

// ─── Overview ────────────────────────────────────────────────────────────────

function OverviewTab({
  cards,
  sources,
  sessions,
  sessionsTotal,
  sessionsLoading,
  openSession,
}: {
  cards: DashboardReply["dashboard"];
  sources: DataSourcesReply["dataSources"];
  sessions: { id: string; title?: string | null; messageCount: number; updatedAt: string }[];
  sessionsTotal: number;
  sessionsLoading: boolean;
  openSession: (id: string) => void;
}) {
  const stats = [
    {
      label: "Data sources",
      value: sources.length,
      hint: "connected databases",
    },
    {
      label: "Chats",
      value: sessionsTotal,
      hint: "saved conversations",
    },
    {
      label: "Saved cards",
      value: cards.length,
      hint: "pinned answers",
    },
  ];

  return (
    <div className="space-y-7">
      <div className="grid gap-4 md:grid-cols-3">
        {stats.map((s) => (
          <div
            key={s.label}
            className="p-5 rounded-2xl border border-white/10 bg-white/5 hover:border-indigo-500/40 transition"
          >
            <p className="text-xs uppercase tracking-wider text-slate-500">{s.label}</p>
            <p className="text-3xl font-bold text-white mt-2">{s.value}</p>
            <p className="text-xs text-slate-500 mt-1">{s.hint}</p>
          </div>
        ))}
      </div>

      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-white">Recent chats</h2>
          <Link href="/ask" className="text-xs text-indigo-400 hover:text-indigo-300">
            Start a new one →
          </Link>
        </div>
        {sessionsLoading ? (
          <div className="h-16 flex items-center text-slate-400 text-sm animate-gentle-pulse">
            Loading chats…
          </div>
        ) : sessions.length === 0 ? (
          <p className="text-sm text-slate-400 p-5 rounded-2xl border border-dashed border-white/10 bg-white/5">
            No conversations yet — ask your first question from{" "}
            <Link href="/ask" className="text-indigo-400 hover:underline">
              Ask
            </Link>
            .
          </p>
        ) : (
          <ul className="space-y-2">
            {sessions.slice(0, 3).map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  onClick={() => openSession(s.id)}
                  className="w-full text-left px-4 py-3 rounded-xl border border-white/10 bg-white/5 hover:border-indigo-500/50 hover:bg-white/10 transition flex items-center justify-between gap-3"
                >
                  <span className="text-sm text-white font-medium truncate">
                    {s.title || "Untitled chat"}
                  </span>
                  <span className="text-xs text-slate-500 shrink-0">
                    {s.messageCount} messages • {formatWhen(s.updatedAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-white">Connections</h2>
          <Link
            href="/datasources"
            className="text-xs text-indigo-400 hover:text-indigo-300"
          >
            Manage credentials →
          </Link>
        </div>
        {sources.length === 0 ? (
          <p className="text-sm text-slate-400 p-5 rounded-2xl border border-dashed border-white/10 bg-white/5">
            No databases yet.{" "}
            <Link href="/datasources" className="text-indigo-400 hover:underline">
              Add a data source
            </Link>
            .
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {sources.map((ds) => (
              <span
                key={ds.id}
                className="px-3 py-1.5 text-xs rounded-full border border-white/10 bg-white/5 text-slate-300"
              >
                {ds.name}
                <span className="text-slate-500 ml-1.5">({ds.engine})</span>
                {ds.sandbox && (
                  <span className="text-emerald-400/80 ml-1.5">sandbox</span>
                )}
              </span>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

// ─── Chats ───────────────────────────────────────────────────────────────────

function ChatsTab({
  sessions,
  sessionsPageData,
  loading,
  page,
  onPage,
  renamingId,
  renameTitle,
  setRenameTitle,
  startRename,
  cancelRename,
  submitRename,
  deleteSession,
  openSession,
  busySessionId,
}: {
  sessions: { id: string; title?: string | null; dataSourceId?: string | null; messageCount: number; updatedAt: string }[];
  sessionsPageData?: { hasNext: boolean; total: number; size: number } | null;
  loading: boolean;
  page: number;
  onPage: (p: number) => void;
  renamingId: string | null;
  renameTitle: string;
  setRenameTitle: (t: string) => void;
  startRename: (s: { id: string; title?: string | null }) => void;
  cancelRename: () => void;
  submitRename: (id: string) => Promise<void>;
  deleteSession: (id: string) => Promise<void>;
  openSession: (id: string) => void;
  busySessionId: string | null;
}) {
  const total = sessionsPageData?.total ?? 0;
  const size = sessionsPageData?.size ?? SESSIONS_PAGE_SIZE;
  const lastPage = size > 0 ? Math.max(0, Math.ceil(total / size) - 1) : 0;

  return (
    <div className="space-y-4">
      {loading ? (
        <div className="h-40 flex items-center justify-center text-slate-400 animate-gentle-pulse">
          Loading chats…
        </div>
      ) : sessions.length === 0 ? (
        <div className="p-10 border border-dashed border-white/10 rounded-2xl bg-white/5 text-center">
          <p className="text-sm text-slate-400 mb-5">
            Nothing saved yet. Every conversation continues automatically — even
            after a refresh or restart.
          </p>
          <Link
            href="/ask"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 transition active:scale-95"
          >
            Start your first chat
          </Link>
        </div>
      ) : (
        <ul className="space-y-2">
          {sessions.map((s) => (
            <li
              key={s.id}
              className="px-4 py-3 rounded-xl border border-white/10 bg-white/5 hover:border-indigo-500/40 transition"
            >
              {renamingId === s.id ? (
                <div className="flex items-center gap-2">
                  <input
                    autoFocus
                    value={renameTitle}
                    onChange={(e) => setRenameTitle(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") void submitRename(s.id);
                      if (e.key === "Escape") cancelRename();
                    }}
                    className="input-refined text-sm flex-1"
                    placeholder="Chat name"
                  />
                  <button
                    type="button"
                    onClick={() => void submitRename(s.id)}
                    className="text-xs font-medium px-3 py-1.5 rounded bg-indigo-600 text-white hover:bg-indigo-500 transition"
                  >
                    Save
                  </button>
                  <button
                    type="button"
                    onClick={cancelRename}
                    className="text-xs text-slate-400 hover:text-white px-2"
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <div className="flex items-center justify-between gap-3">
                  <button
                    type="button"
                    onClick={() => openSession(s.id)}
                    className="min-w-0 text-left flex-1"
                  >
                    <p className="text-sm font-medium text-white truncate">
                      {s.title || "Untitled chat"}
                    </p>
                    <p className="text-xs text-slate-500 mt-0.5">
                      {s.messageCount} messages • {formatWhen(s.updatedAt)}
                    </p>
                  </button>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <button
                      type="button"
                      onClick={() => openSession(s.id)}
                      className="text-xs font-medium px-3 py-1.5 rounded border border-white/15 text-slate-300 hover:bg-white/10 hover:text-white transition"
                    >
                      Open
                    </button>
                    <button
                      type="button"
                      onClick={() => startRename(s)}
                      className="text-xs font-medium px-3 py-1.5 rounded border border-white/15 text-slate-300 hover:bg-white/10 hover:text-white transition"
                    >
                      Rename
                    </button>
                    <button
                      type="button"
                      onClick={() => void deleteSession(s.id)}
                      disabled={busySessionId === s.id}
                      className="text-xs font-medium px-3 py-1.5 rounded border border-white/15 text-slate-400 hover:bg-red-500/10 hover:text-red-400 hover:border-red-500/50 disabled:opacity-50 transition"
                    >
                      {busySessionId === s.id ? "…" : "Delete"}
                    </button>
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {/* Paging controls — one bounded window per request. */}
      {total > size && (
        <div className="flex items-center justify-center gap-4 text-xs text-slate-400">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => onPage(page - 1)}
            className="px-3 py-1.5 rounded-lg border border-white/15 disabled:opacity-40 hover:bg-white/10 transition"
          >
            ← Newer
          </button>
          <span>
            Page {page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            disabled={page >= lastPage || !(sessionsPageData?.hasNext ?? false)}
            onClick={() => onPage(page + 1)}
            className="px-3 py-1.5 rounded-lg border border-white/15 disabled:opacity-40 hover:bg-white/10 transition"
          >
            Older →
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Connections: password manager ──────────────────────────────────────────

function ConnectionsTab({ sources }: { sources: DataSourcesReply["dataSources"] }) {
  if (sources.length === 0) {
    return (
      <div className="p-10 border border-dashed border-white/10 rounded-2xl bg-white/5 text-center">
        <p className="text-sm text-slate-400 mb-5">
          No connections yet — add one from the data sources page.
        </p>
        <Link
          href="/datasources"
          className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 shadow-lg shadow-indigo-500/30 transition active:scale-95"
        >
          Add a data source
        </Link>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <p className="text-xs text-slate-500 mb-2">
        Passwords are stored encrypted (AES-GCM) and stay hidden behind
        <span className="text-slate-400"> ••••••••</span>. Revealing one requires
        re-entering your account password.
      </p>
      {sources.map((ds) => (
        <ConnectionRow key={ds.id} ds={ds} />
      ))}
    </div>
  );
}

type ConnectionPanel = null | "edit" | "reveal";

function ConnectionRow({ ds }: { ds: DataSourcesReply["dataSources"][number] }) {
  const [panel, setPanel] = useState<ConnectionPanel>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [jdbcUrl, setJdbcUrl] = useState("");
  const [updateAccountPassword, setUpdateAccountPassword] = useState("");
  const [accountPassword, setAccountPassword] = useState("");
  const [revealed, setRevealed] = useState<{
    jdbcUrl: string;
    username: string;
    password: string;
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  // Adopt flow: OAuth-only accounts must set a local password (email OTP)
  // before the reveal gate can ever match one. After adoption we reveal
  // immediately with the freshly chosen password.
  const [adoptPhase, setAdoptPhase] = useState<"none" | "ready" | "sent">("none");
  const [adoptCode, setAdoptCode] = useState("");
  const [adoptNewPassword, setAdoptNewPassword] = useState("");
  const [adoptConfirm, setAdoptConfirm] = useState("");

  const isSandbox = ds.sandbox;

  async function runTest() {
    setBusy(true);
    setFeedback(null);
    try {
      const res = await testConnection(ds.id);
      setFeedback({
        ok: true,
        text:
          res.warnings.length > 0
            ? `Connection OK — ${res.warnings.join(" ")}`
            : "Connection OK",
      });
    } catch (err) {
      setFeedback({ ok: false, text: errorText(err, "Connection test failed") });
    } finally {
      setBusy(false);
    }
  }

  async function runUpdate() {
    if (!updateAccountPassword) {
      setFeedback({ ok: false, text: "Enter your account password to confirm." });
      return;
    }
    setBusy(true);
    setFeedback(null);
    try {
      const res = await updateCredentials(
        ds.id,
        {
          username: username.trim() || null,
          password: password.trim() || null,
          jdbcUrl: jdbcUrl.trim() || null,
        },
        updateAccountPassword,
      );
      setFeedback({
        ok: true,
        text:
          res.warnings.length > 0
            ? `Credentials updated — ${res.warnings.join(" ")}`
            : "Credentials updated and connection re-tested OK",
      });
      setPanel(null);
      setUsername("");
      setPassword("");
      setJdbcUrl("");
      setUpdateAccountPassword("");
    } catch (err) {
      setFeedback({ ok: false, text: errorText(err, "Credential update failed") });
    } finally {
      setBusy(false);
    }
  }

  async function runReveal() {
    setBusy(true);
    setFeedback(null);
    try {
      const res = await revealCredentials(ds.id, accountPassword);
      setRevealed({
        jdbcUrl: res.jdbcUrl ?? "—",
        username: res.username ?? "—",
        password: res.password ?? "(none stored)",
      });
      setAccountPassword("");
      setAdoptPhase("none");
    } catch (err) {
      if (err instanceof DatasourceApiError && err.code === "NO_ACCOUNT_PASSWORD") {
        // OAuth-only account: offer the adopt-a-password flow right here.
        setAdoptPhase("ready");
        setFeedback({
          ok: false,
          text: "Your account signs in via Google/GitHub — set a local password below, then reveal works.",
        });
      } else {
        setFeedback({ ok: false, text: errorText(err, "Reveal failed") });
      }
      setRevealed(null);
    } finally {
      setBusy(false);
    }
  }

  async function runAdoptInit() {
    setBusy(true);
    setFeedback(null);
    try {
      await adoptPasswordInit();
      setAdoptPhase("sent");
      setFeedback(null);
    } catch (err) {
      setFeedback({ ok: false, text: errorText(err, "Could not send the code") });
    } finally {
      setBusy(false);
    }
  }

  async function runAdoptSubmit() {
    if (adoptNewPassword.length < 8) {
      setFeedback({ ok: false, text: "Password must be at least 8 characters." });
      return;
    }
    if (adoptNewPassword !== adoptConfirm) {
      setFeedback({ ok: false, text: "Passwords don't match." });
      return;
    }
    setBusy(true);
    setFeedback(null);
    try {
      await adoptPassword(adoptCode.trim(), adoptNewPassword);
      // Adopted! Reveal right away with the fresh password.
      const res = await revealCredentials(ds.id, adoptNewPassword);
      setRevealed({
        jdbcUrl: res.jdbcUrl ?? "—",
        username: res.username ?? "—",
        password: res.password ?? "(none stored)",
      });
      setAdoptPhase("none");
      setAdoptCode("");
      setAdoptNewPassword("");
      setAdoptConfirm("");
      setAccountPassword("");
      setFeedback({ ok: true, text: "Password set — revealed below." });
    } catch (err) {
      const msg =
        err instanceof AuthApiError && err.code === "PASSWORD_ALREADY_SET"
          ? "You already have a password — use Reveal directly."
          : err instanceof AuthApiError && err.code === "RESEND_LIMIT_EXCEEDED"
            ? "Too many code requests — try again later."
            : err instanceof AuthApiError && err.code === "INVALID_OTP"
              ? "The code doesn't match — check the email."
              : err instanceof AuthApiError && err.code === "OTP_EXPIRED"
                ? "Code expired — request a new one."
                : errorText(err, "Could not set the password");
      setFeedback({ ok: false, text: msg });
    } finally {
      setBusy(false);
    }
  }

  function switchPanel(target: ConnectionPanel) {
    setPanel((prev) => (prev === target ? null : target));
    setRevealed(null);
    setFeedback(null);
    setAccountPassword("");
    setUpdateAccountPassword("");
    setAdoptPhase("none");
    setAdoptCode("");
    setAdoptNewPassword("");
    setAdoptConfirm("");
  }

  return (
    <div className="rounded-2xl border border-white/10 bg-white/5 overflow-hidden">
      <div className="px-4 py-3 flex items-center justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <p className="text-sm font-medium text-white truncate">
            {ds.name}
            <span className="text-xs text-slate-500 ml-2">({ds.engine})</span>
            {isSandbox && (
              <span className="text-xs text-emerald-400/80 ml-2">sandbox</span>
            )}
          </p>
          <p className="text-xs text-slate-500 mt-0.5 font-mono">
            password: <span className="tracking-widest">••••••••</span>
          </p>
        </div>
        <div className="flex items-center gap-1.5 shrink-0 flex-wrap">
          <button
            type="button"
            onClick={() => void runTest()}
            disabled={busy}
            className="text-xs font-medium px-3 py-1.5 rounded border border-white/15 text-slate-300 hover:bg-white/10 hover:text-white disabled:opacity-50 transition"
          >
            {busy ? "…" : "Test"}
          </button>
          <button
            type="button"
            onClick={() => switchPanel("edit")}
            disabled={isSandbox}
            title={isSandbox ? "Sandbox sources have no stored credentials" : undefined}
            className="text-xs font-medium px-3 py-1.5 rounded border border-white/15 text-slate-300 hover:bg-white/10 hover:text-white disabled:opacity-40 transition"
          >
            Update
          </button>
          <button
            type="button"
            onClick={() => switchPanel("reveal")}
            disabled={isSandbox}
            title={isSandbox ? "Sandbox sources have no stored credentials" : undefined}
            className="text-xs font-medium px-3 py-1.5 rounded border border-amber-500/40 text-amber-300/90 hover:bg-amber-500/10 disabled:opacity-40 transition"
          >
            Reveal
          </button>
        </div>
      </div>

      {panel === "edit" && (
        <div className="px-4 pb-4 pt-1 border-t border-white/10 space-y-2">
          <p className="text-xs text-slate-500 pt-2">
            Confirm with your WhyQL account password, then edit what should
            change — blanks keep the stored values, and the merged connection
            is tested before anything is saved.
          </p>
          <input
            type="password"
            value={updateAccountPassword}
            onChange={(e) => setUpdateAccountPassword(e.target.value)}
            className="input-refined text-sm"
            placeholder="Account password (required)"
          />
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="input-refined text-sm"
            placeholder="Username (optional)"
          />
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="input-refined text-sm"
            placeholder="New database password (optional, stored encrypted)"
          />
          <input
            value={jdbcUrl}
            onChange={(e) => setJdbcUrl(e.target.value)}
            className="input-refined text-sm font-mono"
            placeholder="JDBC URL (optional)"
          />
          <div className="flex gap-2 pt-1">
            <button
              type="button"
              onClick={() => void runUpdate()}
              disabled={busy || !updateAccountPassword}
              className="text-xs font-medium px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-500 disabled:opacity-50 transition"
            >
              {busy ? "Testing & saving…" : "Save credentials"}
            </button>
            <button
              type="button"
              onClick={() => switchPanel(null)}
              className="text-xs text-slate-400 hover:text-white px-3 py-2"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {panel === "reveal" && (
        <div className="px-4 pb-4 pt-1 border-t border-white/10 space-y-2">
          {adoptPhase === "none" && (
            <>
              <p className="text-xs text-slate-500 pt-2">
                Confirm it&apos;s you — enter your WhyQL account password.
              </p>
              <input
                type="password"
                autoFocus
                value={accountPassword}
                onChange={(e) => setAccountPassword(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && void runReveal()}
                className="input-refined text-sm"
                placeholder="Account password"
              />
              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => void runReveal()}
                  disabled={busy || !accountPassword}
                  className="text-xs font-medium px-4 py-2 rounded-lg bg-amber-600/90 text-white hover:bg-amber-500 disabled:opacity-50 transition"
                >
                  {busy ? "Verifying…" : "Reveal"}
                </button>
                <button
                  type="button"
                  onClick={() => switchPanel(null)}
                  className="text-xs text-slate-400 hover:text-white px-3 py-2"
                >
                  Cancel
                </button>
              </div>
            </>
          )}

          {adoptPhase === "ready" && (
            <div className="space-y-2 pt-2">
              <p className="text-xs text-slate-400">
                We&apos;ll email a 6-digit code to your account address. Verify
                it below to choose a local password — after that, reveal works
                with it (and you can also sign in with email + password).
              </p>
              <button
                type="button"
                onClick={() => void runAdoptInit()}
                disabled={busy}
                className="text-xs font-medium px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-500 disabled:opacity-50 transition"
              >
                {busy ? "Sending…" : "Email me a code"}
              </button>
            </div>
          )}

          {adoptPhase === "sent" && (
            <div className="space-y-2 pt-2">
              <p className="text-xs text-slate-400">
                Code sent to your email. Enter it with the password you want to
                adopt.
              </p>
              <input
                inputMode="numeric"
                maxLength={6}
                value={adoptCode}
                onChange={(e) => setAdoptCode(e.target.value.replace(/\D/g, ""))}
                className="input-refined text-sm tracking-widest"
                placeholder="6-digit code"
              />
              <input
                type="password"
                value={adoptNewPassword}
                onChange={(e) => setAdoptNewPassword(e.target.value)}
                className="input-refined text-sm"
                placeholder="New password (min 8 characters)"
              />
              <input
                type="password"
                value={adoptConfirm}
                onChange={(e) => setAdoptConfirm(e.target.value)}
                className="input-refined text-sm"
                placeholder="Confirm new password"
              />
              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => void runAdoptSubmit()}
                  disabled={busy || adoptCode.length !== 6 || !adoptNewPassword}
                  className="text-xs font-medium px-4 py-2 rounded-lg bg-amber-600/90 text-white hover:bg-amber-500 disabled:opacity-50 transition"
                >
                  {busy ? "Setting…" : "Set password & reveal"}
                </button>
                <button
                  type="button"
                  onClick={() => switchPanel("reveal")}
                  className="text-xs text-slate-400 hover:text-white px-3 py-2"
                >
                  Back
                </button>
              </div>
            </div>
          )}
          {revealed && (
            <div className="mt-2 p-3 rounded-xl border border-amber-500/30 bg-amber-500/10 text-xs font-mono space-y-1">
              <p>
                <span className="text-amber-300/70">url:</span>{" "}
                <span className="break-all text-slate-200">{revealed.jdbcUrl}</span>
              </p>
              <p>
                <span className="text-amber-300/70">username:</span>{" "}
                <span className="text-slate-200">{revealed.username}</span>
              </p>
              <p className="flex items-center gap-2 flex-wrap">
                <span className="text-amber-300/70">password:</span>{" "}
                <span className="text-slate-200 break-all">{revealed.password}</span>
                <button
                  type="button"
                  onClick={() => void navigator.clipboard?.writeText(revealed.password)}
                  className="text-indigo-400 hover:text-indigo-300 font-sans"
                >
                  copy
                </button>
              </p>
            </div>
          )}
        </div>
      )}

      {feedback && (
        <div
          className={
            "px-4 pb-3 text-xs border-t border-white/10 pt-2 " +
            (feedback.ok ? "text-emerald-400" : "text-red-400")
          }
        >
          {feedback.text}
        </div>
      )}
    </div>
  );
}

function errorText(err: unknown, fallback: string): string {
  if (err instanceof DatasourceApiError) {
    if (err.code === "WRONG_ACCOUNT_PASSWORD") return "Account password is incorrect.";
    if (err.code === "REVEAL_RATE_LIMITED")
      return "Too many reveal attempts — wait a few minutes.";
    if (err.code === "NO_ACCOUNT_PASSWORD")
      return "Your account signs in via OAuth and has no local password yet.";
    return err.message;
  }
  return err instanceof Error ? err.message : fallback;
}

// ─── Saved cards ─────────────────────────────────────────────────────────────

function SavedTab({
  cards,
  loading,
  onHide,
  removingId,
}: {
  cards: DashboardReply["dashboard"];
  loading: boolean;
  onHide: (cardId: string) => Promise<void>;
  removingId: string | null;
}) {
  if (loading) {
    return (
      <div className="h-64 flex items-center justify-center text-slate-400 animate-gentle-pulse">
        Loading your cards…
      </div>
    );
  }
  if (cards.length === 0) {
    return (
      <div className="p-12 border border-dashed border-white/10 rounded-2xl bg-white/5 text-center">
        <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-indigo-500/20 text-indigo-400 mb-4">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><rect x="7" y="7" width="10" height="10" rx="1" ry="1" />
          </svg>
        </div>
        <p className="text-sm text-slate-400 mb-5 max-w-xs mx-auto">
          No saved cards yet. Ask a question and hit
          <span className="mx-1 font-medium text-white">Save to dashboard</span>
          on a completed result to pin it here.
        </p>
        <Link
          href="/ask"
          className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 transition active:scale-95"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 12h-4l3 3 4-4-8-9-8 9 3 4 4-4" />
          </svg>
          Ask a question
        </Link>
      </div>
    );
  }
  return (
    <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
      {cards.map((card) => (
        <div
          key={card.id}
          className="border border-white/10 rounded-2xl bg-white/5 flex flex-col hover:border-indigo-500/50 hover:bg-white/10 transition"
        >
          <div className="p-4 border-b border-white/10 flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-white leading-snug">
                {card.question}
              </p>
              <p className="text-xs text-slate-400 mt-1.5">
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
              onClick={() => void onHide(card.id)}
              disabled={removingId === card.id}
              className="text-xs font-medium px-3 py-1.5 rounded border border-white/20 text-slate-400 hover:bg-red-500/10 hover:text-red-400 hover:border-red-500/50 disabled:opacity-50 transition"
              title="Remove from dashboard"
            >
              {removingId === card.id ? "…" : "Remove"}
            </button>
          </div>
          <div className="p-4 flex-1">
            <ResultView result={card.result} defaultView="table" />
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Shared bits ─────────────────────────────────────────────────────────────

function formatWhen(iso: string): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const minutes = Math.floor((Date.now() - then) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}
