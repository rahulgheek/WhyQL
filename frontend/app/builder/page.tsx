"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  useBoards,
  useBoard,
  useCreateBoard,
  useDataSources,
  useRemoveBoardWidget,
  useSaveBoardWidget,
  useSchemaTables,
} from "@/lib/graphql";
import type { BoardWidgetReply, BoardsReply, BoardReply } from "@/lib/graphql";
import { DataSourcesReply } from "@/app/ask/page";
import ResultView from "@/app/components/ResultView";

/** A presentation board: up to 6 widgets, one per slot, each re-runnable. */
const MAX_WIDGETS = 6;

type Mode = "MANUAL" | "SQL" | "QUESTION";
type ChartKind = "TABLE" | "BAR" | "LINE" | "PIE";

interface EditorState {
  /** Slot being filled (a concrete slot number when the editor is open). */
  slot: number;
  /** Existing widget being edited, null when creating. */
  widget: BoardWidgetReply | null;
}

export default function BuilderPage() {
  const [boardId, setBoardId] = useState<string | null>(null);
  const boardsData = (useBoards().data as BoardsReply | undefined)?.boards ?? [];
  const { data: boardData, refetch: refetchBoard } = useBoard(boardId);
  const board = (boardData as BoardReply | undefined)?.board ?? null;

  // Adopt the newest board on first load; the builder can stay empty until
  // the user creates one deliberately.
  useEffect(() => {
    if (boardId === null && boardsData.length > 0) {
      setBoardId(boardsData[0].id);
    }
  }, [boardId, boardsData]);

  const [editor, setEditor] = useState<EditorState | null>(null);

  const slots = useMemo(() => {
    const byOrdinal = new Map<number, BoardWidgetReply>();
    for (const widget of board?.widgets ?? []) {
      byOrdinal.set(widget.ordinal, widget);
    }
    return Array.from({ length: MAX_WIDGETS }, (_, i) => byOrdinal.get(i) ?? null);
  }, [board]);

  return (
    <div className="min-h-screen bg-[#0a0b12]">
      <main className="max-w-7xl mx-auto p-6 animate-fade-in">
        <div className="flex items-center justify-between flex-wrap gap-3 mb-6">
          <div>
            <div className="flex items-center gap-3">
              <div className="inline-flex h-8 w-8 items-center justify-center rounded-xl bg-indigo-500 text-white font-bold text-sm">
                B
              </div>
              <h1 className="text-xl font-bold tracking-tight text-white">Board builder</h1>
              <div className="flex items-center gap-2 ml-2">
                <Link href="/dashboard" className="text-sm text-indigo-400 hover:underline">
                  Dashboard
                </Link>
                <Link href="/ask" className="text-sm text-indigo-400 hover:underline">
                  Ask
                </Link>
              </div>
            </div>
            <p className="text-sm text-slate-400">
              Compose up to {MAX_WIDGETS} widgets — a table pick, a SQL query, or a
              plain-English prompt — each rendered as its own chart.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <BoardSelect
              boards={boardsData}
              value={boardId}
              onChange={setBoardId}
              onCreated={setBoardId}
            />
          </div>
        </div>

        {!board && boardId === null ? (
          <BoardWelcome />
        ) : (
          <div className="grid gap-5 md:grid-cols-2 lg:grid-cols-3 animate-fade-in" style={{ animationDelay: "100ms" }}>
            {slots.map((widget, ordinal) =>
              widget ? (
                <WidgetCard
                  key={widget.id}
                  widget={widget}
                  boardId={boardId!}
                  onEdit={() => setEditor({ slot: ordinal, widget })}
                  onRemoved={refetchBoard}
                />
              ) : (
                <EmptySlot key={ordinal} onAdd={() => setEditor({ slot: ordinal, widget: null })} />
              ),
            )}
          </div>
        )}

        {editor && boardId !== null && (
          <WidgetEditor
            boardId={boardId}
            editor={editor}
            onClose={() => setEditor(null)}
            onSaved={async () => {
              setEditor(null);
              await refetchBoard();
            }}
          />
        )}
      </main>
    </div>
  );
}

function BoardWelcome() {
  return (
    <div className="p-12 border-2 border-dashed border-white/10 rounded-2xl bg-white/5 text-center animate-fade-in">
      <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-indigo-500/20 text-indigo-400 mb-4">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" />
        </svg>
      </div>
      <h2 className="text-lg font-bold tracking-tight text-white mb-2">No board open</h2>
      <p className="text-sm text-slate-400 mb-4 max-w-xs mx-auto">
        Pick a board in the selector above, or create a new one and start adding widgets.
      </p>
    </div>
  );
}

function BoardSelect({
  boards,
  value,
  onChange,
  onCreated,
}: {
  boards: { id: string; title: string }[];
  value: string | null;
  onChange: (id: string | null) => void;
  onCreated: (id: string) => void;
}) {
  const [createBoard, { loading }] = useCreateBoard();

  return (
    <>
      <select
        value={value ?? ""}
        onChange={(e) => onChange(e.target.value || null)}
        className="input-refined text-sm"
      >
        <option value="">— no board —</option>
        {boards.map((board) => (
          <option key={board.id} value={board.id}>
            {board.title}
          </option>
        ))}
      </select>
      <button
        type="button"
        disabled={loading}
        onClick={async () => {
          const res = await createBoard({
            variables: { title: `Presentation ${boards.length + 1}` },
          });
          const id = (res.data as any)?.createBoard?.id as string | undefined;
          if (id) {
            onChange(id);
            onCreated(id);
          }
        }}
        className="px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 disabled:opacity-50 transition active:scale-95"
      >
        {loading ? "Creating…" : "+ New board"}
      </button>
    </>
  );
}

function EmptySlot({ onAdd }: { onAdd: () => void }) {
  return (
    <button
      type="button"
      onClick={onAdd}
      className="border-2 border-dashed border-white/10 rounded-2xl bg-white/5 p-8 text-center hover:border-indigo-500/50 hover:bg-white/10 transition flex flex-col items-center justify-center min-h-48 group"
    >
      <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-dashed border-white/20 text-slate-400 group-hover:border-indigo-500 group-hover:text-indigo-400 transition mb-2">
        <span className="text-xl">+</span>
      </div>
      <span className="text-sm font-medium text-slate-300">Add widget</span>
      <span className="text-xs text-slate-500 mt-1">Pick data, SQL, or a prompt</span>
    </button>
  );
}

function WidgetCard({
  widget,
  boardId,
  onEdit,
  onRemoved,
}: {
  widget: BoardWidgetReply;
  boardId: string;
  onEdit: () => void;
  onRemoved: () => Promise<unknown>;
}) {
  const [removeWidget] = useRemoveBoardWidget();
  const [removing, setRemoving] = useState(false);

  return (
    <div className="border border-white/10 rounded-2xl bg-white/5 flex flex-col min-h-64 shadow-sm hover:border-indigo-500/50 hover:bg-white/10 transition">
      <div className="p-4 border-b border-white/10 rounded-t-2xl flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-white truncate" title={widget.title}>
            {widget.title}
          </p>
          <p className="text-xs text-slate-400 mt-1 flex gap-2">
            <span className="px-2 py-0.5 rounded bg-white/10 text-[10px] font-medium text-slate-400">
              {widget.mode}
            </span>
            <span className="px-2 py-0.5 rounded bg-white/10 text-[10px] font-medium text-slate-400">
              {widget.chartKind}
            </span>
            {widget.status !== "OK" && (
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-medium ${
                  widget.status === "OK"
                    ? "bg-emerald-500/20 text-emerald-400"
                    : "bg-red-500/20 text-red-400"
                }`}
              >
                {widget.status}
              </span>
            )}
          </p>
        </div>
        <div className="flex gap-1.5 shrink-0">
          <button
            type="button"
            onClick={onEdit}
            className="text-xs font-medium px-2.5 py-1.5 rounded border border-white/20 text-slate-300 hover:bg-white/10 hover:border-white/30 transition"
            title="Edit widget"
          >
            Edit
          </button>
          <button
            type="button"
            disabled={removing}
            onClick={async () => {
              setRemoving(true);
              try {
                await removeWidget({ variables: { boardId, widgetId: widget.id } });
                await onRemoved();
              } finally {
                setRemoving(false);
              }
            }}
            className="text-xs font-medium px-2.5 py-1.5 rounded border border-white/20 text-red-400 hover:bg-red-500/10 hover:text-red-300 hover:border-red-500/50 disabled:opacity-50 transition"
            title="Remove widget"
          >
            {removing ? "…" : "Delete"}
          </button>
        </div>
      </div>

      <div className="p-4 flex-1">
        {widget.status !== "OK" ? (
          <div className="p-3 rounded-xl bg-red-500/10 text-red-400 text-xs whitespace-pre-wrap border border-red-500/20">
            {widget.status === "NEEDS_CLARIFICATION" ? "Needs clarification: " : "Failed: "}
            {widget.errorMessage ?? "unknown error"}
          </div>
        ) : widget.result ? (
          <div className="h-48 overflow-hidden rounded-xl border border-white/10">
            <ResultView
              result={widget.result}
              defaultView="auto"
              chartKind={widget.chartKind}
            />
          </div>
        ) : (
          <p className="text-sm text-slate-400">No result snapshot.</p>
        )}
      </div>
    </div>
  );
}

function WidgetEditor({
  boardId,
  editor,
  onClose,
  onSaved,
}: {
  boardId: string;
  editor: EditorState;
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const existing = editor.widget;
  const [title, setTitle] = useState(existing?.title ?? "");
  const [dataSourceId, setDataSourceId] = useState(existing?.dataSourceId ?? "");
  const [mode, setMode] = useState<Mode>(existing?.mode ?? "MANUAL");
  const [tableName, setTableName] = useState(existing?.tableName ?? "");
  const [sqlText, setSqlText] = useState(existing?.sqlText ?? "");
  const [question, setQuestion] = useState(existing?.question ?? "");
  const [chartKind, setChartKind] = useState<ChartKind>(existing?.chartKind ?? "BAR");
  const [formError, setFormError] = useState("");

  const { data: dsData, loading: dsLoading } = useDataSources();
  const datasources = (dsData as DataSourcesReply | undefined)?.dataSources ?? [];
  const { data: tablesData } = useSchemaTables(mode === "MANUAL" ? dataSourceId : null);
  const tables = (tablesData as { schemaTables?: string[] } | undefined)?.schemaTables ?? [];

  const [saveBoardWidget, { loading: saving }] = useSaveBoardWidget();

  const canSave = Boolean(title.trim()) && Boolean(dataSourceId) && (
    mode === "MANUAL" ? Boolean(tableName) : mode === "SQL" ? Boolean(sqlText.trim()) : Boolean(question.trim())
  );

  return (
    <div className="fixed inset-0 bg-[#0a0b12]/80 flex items-center justify-center z-50 p-4 backdrop-blur-sm animate-fade-in">
      <div className="bg-white/5 rounded-2xl shadow-2xl max-w-lg w-full p-6 space-y-5 border border-white/10">
        <div className="flex items-center justify-between">
          <h3 className="font-bold tracking-tight text-white">
            {existing ? "Edit widget" : `New widget — slot ${editor.slot + 1}`}
          </h3>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-white text-xl leading-none rounded-full p-1 hover:bg-white/10 transition">
            ×
          </button>
        </div>

        {formError && (
          <div className="p-3 rounded-xl bg-red-500/10 text-red-400 text-sm border border-red-500/20">{formError}</div>
        )}

        <div>
          <label className="block text-sm font-medium text-slate-300 mb-1">Title</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="e.g. Top products"
            className="w-full border border-white/20 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-slate-300 mb-1">Data source</label>
          <select
            required
            value={dataSourceId}
            onChange={(e) => setDataSourceId(e.target.value)}
            className="w-full border border-white/20 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
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
          <label className="block text-sm font-medium text-slate-300 mb-1">Input</label>
          <div className="grid grid-cols-3 gap-2">
            <ModeButton active={mode === "MANUAL"} label="Pick a table" onClick={() => setMode("MANUAL")} />
            <ModeButton active={mode === "SQL"} label="Write SQL" onClick={() => setMode("SQL")} />
            <ModeButton active={mode === "QUESTION"} label="Ask a prompt" onClick={() => setMode("QUESTION")} />
          </div>
          <div className="mt-2">
            {mode === "MANUAL" && (
              <select
                required
                value={tableName}
                onChange={(e) => setTableName(e.target.value)}
                className="w-full border border-white/20 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
              >
                <option value="" disabled>
                  Select a table
                </option>
                {tables.map((table) => (
                  <option key={table} value={table}>
                    {table}
                  </option>
                ))}
              </select>
            )}
            {mode === "SQL" && (
              <textarea
                required
                rows={4}
                value={sqlText}
                onChange={(e) => setSqlText(e.target.value)}
                placeholder="SELECT * FROM users LIMIT 100"
                className="w-full border border-white/20 rounded-xl px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
              />
            )}
            {mode === "QUESTION" && (
              <textarea
                required
                rows={3}
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="e.g. Monthly revenue for the last 12 months"
                className="w-full border border-white/20 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
              />
            )}
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-slate-300 mb-1">Render as</label>
          <div className="grid grid-cols-4 gap-2">
            {(["TABLE", "BAR", "LINE", "PIE"] as const).map((kind) => (
              <ModeButton
                key={kind}
                active={chartKind === kind}
                label={kind.charAt(0) + kind.slice(1).toLowerCase()}
                onClick={() => setChartKind(kind)}
              />
            ))}
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 pt-1">
          {mode === "QUESTION" && (
            <span className="text-xs text-slate-400">
              Runs the AI pipeline — may take a few seconds.
            </span>
          )}
          <div className="flex gap-2 ml-auto">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl border border-white/20 text-slate-300 text-sm font-medium hover:bg-white/10 hover:border-white/30 transition"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={!canSave || saving}
              onClick={async () => {
                setFormError("");
                try {
                  await saveBoardWidget({
                    variables: {
                      boardId,
                      input: {
                        id: existing?.id ?? null,
                        title: title.trim(),
                        dataSourceId,
                        mode,
                        tableName: mode === "MANUAL" ? tableName : null,
                        sqlText: mode === "SQL" ? sqlText : null,
                        question: mode === "QUESTION" ? question : null,
                        chartKind,
                        ordinal: editor.slot,
                      },
                    },
                  });
                  await onSaved();
                } catch (err) {
                  setFormError(
                    err instanceof Error
                      ? err.message.replace(/^GraphQL execution error: /, "")
                      : "Failed to save widget",
                  );
                }
              }}
              className="px-4 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-500 hover:shadow-lg shadow-indigo-500/30 disabled:opacity-50 transition active:scale-95"
            >
              {saving ? "Running…" : existing ? "Update & run" : "Save & run"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function ModeButton({
  active,
  label,
  onClick,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${
        active
          ? "bg-indigo-600 text-white shadow-sm shadow-indigo-500/30"
          : "border border-white/20 text-slate-400 hover:bg-white/10 hover:border-white/30"
      }`}
    >
      {label}
    </button>
  );
}
