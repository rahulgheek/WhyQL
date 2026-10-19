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
    <div className="min-h-screen bg-slate-50">
      <main className="max-w-7xl mx-auto p-6">
        <div className="flex items-center justify-between flex-wrap gap-3 mb-6">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-xl font-bold text-slate-900">Board builder</h1>
              <Link href="/dashboard" className="text-sm text-blue-600 hover:underline">
                Dashboard
              </Link>
              <Link href="/ask" className="text-sm text-blue-600 hover:underline">
                Ask
              </Link>
            </div>
            <p className="text-sm text-slate-600">
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
          <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
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
    <div className="p-10 border border-dashed border-slate-300 rounded-xl bg-white text-center">
      <h2 className="text-lg font-bold text-slate-900 mb-2">No board open</h2>
      <p className="text-sm text-slate-600 mb-4">
        Pick a board in the selector above, or create a new one and start
        adding widgets.
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
        className="border border-slate-300 rounded px-3 py-2 text-sm bg-white"
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
        className="px-4 py-2 rounded bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50"
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
      className="border border-dashed border-slate-300 rounded-xl bg-white/50 p-8 text-center hover:border-blue-400 hover:bg-blue-50/40 transition flex flex-col items-center justify-center min-h-48"
    >
      <span className="text-3xl text-slate-300 mb-2">+</span>
      <span className="text-sm font-medium text-slate-600">Add widget</span>
      <span className="text-xs text-slate-400 mt-1">Pick data, SQL, or a prompt</span>
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
    <div className="border border-slate-200 rounded-xl bg-white flex flex-col min-h-64">
      <div className="p-3 border-b border-slate-100 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-900 truncate" title={widget.title}>
            {widget.title}
          </p>
          <p className="text-xs text-slate-500 mt-0.5 flex gap-2">
            <span className="px-1.5 rounded bg-slate-100">{widget.mode}</span>
            <span className="px-1.5 rounded bg-slate-100">{widget.chartKind}</span>
            {widget.status !== "OK" && (
              <span
                className={`px-1.5 rounded ${
                  widget.status === "OK"
                    ? "bg-green-100 text-green-700"
                    : "bg-red-100 text-red-700"
                }`}
              >
                {widget.status}
              </span>
            )}
          </p>
        </div>
        <div className="flex gap-1 shrink-0">
          <button
            type="button"
            onClick={onEdit}
            className="text-xs px-2 py-1 rounded border border-slate-200 text-slate-600 hover:bg-slate-50"
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
            className="text-xs px-2 py-1 rounded border border-slate-200 text-red-500 hover:bg-red-50 disabled:opacity-50"
            title="Remove widget"
          >
            {removing ? "…" : "Delete"}
          </button>
        </div>
      </div>

      <div className="p-4 flex-1">
        {widget.status !== "OK" ? (
          <div className="p-3 rounded bg-red-50 text-red-700 text-xs whitespace-pre-wrap">
            {widget.status === "NEEDS_CLARIFICATION" ? "Needs clarification: " : "Failed: "}
            {widget.errorMessage ?? "unknown error"}
          </div>
        ) : widget.result ? (
          <ResultView
            result={widget.result}
            defaultView="auto"
            chartKind={widget.chartKind}
          />
        ) : (
          <p className="text-sm text-slate-500">No result snapshot.</p>
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
    <div className="fixed inset-0 bg-slate-900/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl max-w-lg w-full p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="font-bold text-slate-900">
            {existing ? "Edit widget" : `New widget — slot ${editor.slot + 1}`}
          </h3>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-slate-700 text-xl leading-none">
            ×
          </button>
        </div>

        {formError && (
          <div className="p-3 rounded bg-red-50 text-red-700 text-sm">{formError}</div>
        )}

        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1">Title</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="e.g. Top products"
            className="w-full border border-slate-300 rounded px-3 py-2 text-sm"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1">Data source</label>
          <select
            required
            value={dataSourceId}
            onChange={(e) => setDataSourceId(e.target.value)}
            className="w-full border border-slate-300 rounded px-3 py-2 text-sm"
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
          <label className="block text-sm font-medium text-slate-700 mb-1">Input</label>
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
                className="w-full border border-slate-300 rounded px-3 py-2 text-sm"
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
                className="w-full border border-slate-300 rounded px-3 py-2 text-sm font-mono"
              />
            )}
            {mode === "QUESTION" && (
              <textarea
                required
                rows={3}
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="e.g. Monthly revenue for the last 12 months"
                className="w-full border border-slate-300 rounded px-3 py-2 text-sm"
              />
            )}
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1">Render as</label>
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
              className="px-4 py-2 rounded border border-slate-300 text-slate-700 text-sm hover:bg-slate-50"
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
              className="px-4 py-2 rounded bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50"
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
      className={`px-2 py-1.5 rounded text-xs font-medium ${
        active
          ? "bg-slate-800 text-white"
          : "border border-slate-300 text-slate-600 hover:bg-slate-50"
      }`}
    >
      {label}
    </button>
  );
}
