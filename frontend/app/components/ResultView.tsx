"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type {
  BarSeriesOption,
  LineSeriesOption,
  PieSeriesOption,
} from "echarts/charts";
import type { ComposeOption } from "echarts/core";
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
} from "echarts/components";

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

type ChartOption = ComposeOption<
  BarSeriesOption | LineSeriesOption | PieSeriesOption | GridComponentOption | TooltipComponentOption | LegendComponentOption
>;

export interface ResultColumns {
  name: string;
  typeName: string;
}

/** Shape shared by the job result and dashboard card result (see lib/graphql.ts). */
export interface ResultData {
  columns: ResultColumns[];
  rows: unknown[];
  truncated: boolean;
}

/** Widget/board-level preference: "TABLE" never charts; others seed the pick. */
export type ChartKind = "TABLE" | "BAR" | "LINE" | "PIE";

/** User-facing per-result chart settings. */
export interface ChartState {
  /** "auto" opens with the chart when one is possible, else the table. */
  view: "auto" | "table" | "chart";
  /** What chart shape to draw in chart view; AUTO infers from the data. */
  kind: "AUTO" | "BAR" | "LINE" | "PIE";
}

export function defaultChartState(
  defaultView: "table" | "auto",
  chartKind?: ChartKind,
): ChartState {
  return {
    view: defaultView,
    kind: chartKind === "TABLE" ? "AUTO" : chartKind ?? "AUTO",
  };
}

const NUMERIC = /int|decimal|numeric|float|double|real|number|money|bit/i;
const TEMPORAL = /date|time|timestamp|instant/i;
/** Refuse charting beyond this many rows — the table is the right view then. */
const MAX_CHART_ROWS = 500;
const MAX_CATEGORIES = 30;
const MAX_SERIES = 4;
const MAX_PIE_SLICES = 8;

const PALETTE = [
  "#3b82f6", "#14b8a6", "#f59e0b", "#ef4444",
  "#8b5cf6", "#06b6d4", "#f97316", "#22c55e",
];

/**
 * Table + chart viewer for SQL results with editable chart settings.
 * `value`/`onValueChange` make it controlled (chat messages capture the
 * chosen shape when pinning); without them it manages state internally and
 * `chartKind`/`defaultView` seed the defaults ("TABLE" = never chart).
 */
export default function ResultView({
  result,
  defaultView = "auto",
  chartKind,
  value,
  onValueChange,
}: {
  result: ResultData | null;
  /** "auto" opens with the chart when one is possible, else the table. */
  defaultView?: "table" | "auto";
  /** Rendering preference from the widget/board layer ("TABLE" = never). */
  chartKind?: ChartKind;
  /** Controlled chart state, if the caller wants to capture the choice. */
  value?: ChartState;
  onValueChange?: (state: ChartState) => void;
}) {
  // Reset the user's settings whenever a new result arrives (job updates
  // stream in) — adjustment during render, so no setState-in-effect churn.
  const [internal, setInternal] = useState<ChartState>(() =>
    defaultChartState(defaultView, chartKind),
  );
  const [lastResult, setLastResult] = useState<ResultData | null>(result);
  if (result !== lastResult) {
    setLastResult(result);
    setInternal(defaultChartState(defaultView, chartKind));
  }
  const state = value ?? internal;
  const update = (next: ChartState) => {
    if (onValueChange) {
      // Controlled callers receive the resolved view so their saved value is final.
      const resolved = resolveChartState(result, next);
      onValueChange({ ...next, view: resolved.view });
    } else {
      setInternal(next);
    }
  };

  if (!result || result.columns.length === 0) {
    return <p className="p-4 text-sm text-slate-500">No result snapshot.</p>;
  }

  const boardHardTable = chartKind === "TABLE";
  const chartsPossible = !boardHardTable && canChart(result);
  if (boardHardTable) {
    return <ResultTable result={result} />;
  }
  if (!chartsPossible) {
    const hasToggle = state.view !== "auto";
    return (
      <div>
        {hasToggle && (
          <div className="flex items-center gap-1 mb-3">
            <ViewToggle active label="Table" />
          </div>
        )}
        <ResultTable result={result} />
      </div>
    );
  }

  const resolved = resolveChartState(result, state);
  const showChart = resolved.view === "chart";

  return (
    <div>
      <div className="flex items-center justify-between gap-2 mb-3 flex-wrap">
        <div className="flex items-center gap-1">
          <ViewToggle
            active={!showChart}
            label="Table"
            onClick={() => update({ ...state, view: "table" })}
          />
          <ViewToggle
            active={showChart}
            label="Chart"
            onClick={() => update({ ...state, view: "chart" })}
          />
        </div>
        <div className="flex items-center gap-1">
          <span className="text-[10px] font-medium text-slate-400 uppercase tracking-wide mr-1">
            Chart
          </span>
          <KindToggle
            active={state.kind === "AUTO"}
            label="Auto"
            title="Pick the best shape from the data"
            onClick={() => update({ ...state, kind: "AUTO" })}
          />
          <KindToggle
            active={state.kind === "BAR"}
            label="Bar"
            disabled={pickChart(result) === null}
            onClick={() => update({ view: "chart", kind: "BAR" })}
          />
          <KindToggle
            active={state.kind === "LINE"}
            label="Line"
            disabled={pickChart(result) === null}
            onClick={() => update({ view: "chart", kind: "LINE" })}
          />
          <KindToggle
            active={state.kind === "PIE"}
            label="Pie"
            disabled={pickPie(result) === null}
            title={
              pickPie(result) === null
                ? "Pie needs one categorical column and one numeric column"
                : "Pie chart"
            }
            onClick={() => update({ view: "chart", kind: "PIE" })}
          />
        </div>
      </div>

      {showChart && resolved.renderable ? (
        <EChart result={result} kind={resolved.kind} />
      ) : (
        <>
          {showChart && !resolved.renderable && (
            <p className="mb-2 p-2.5 rounded bg-amber-50 text-amber-700 text-xs">
              {resolved.kind === "PIE"
                ? "A pie chart needs one categorical column and one numeric column — showing the table instead."
                : "This result cannot be drawn as that chart — showing the table instead."}
            </p>
          )}
          <ResultTable result={result} />
        </>
      )}
    </div>
  );
}

/**
 * Fully resolve a chart state against the data: AUTO picks LINE for temporal
 * x-axes and BAR for categorical ones; the view falls back to the table when
 * the requested shape cannot render.
 */
export function resolveChartState(
  result: ResultData | null,
  state: ChartState,
): {
  view: "table" | "chart";
  kind: "BAR" | "LINE" | "PIE";
  renderable: boolean;
} {
  if (result == null) {
    return { view: "table", kind: "BAR", renderable: false };
  }
  let kind: "BAR" | "LINE" | "PIE";
  let renderable: boolean;
  if (state.kind === "AUTO") {
    const spec = pickChart(result);
    if (spec) {
      kind = spec.kind === "line" ? "LINE" : "BAR";
      renderable = true;
    } else {
      kind = "BAR";
      renderable = false;
    }
  } else {
    kind = state.kind;
    renderable =
      kind === "PIE" ? pickPie(result) !== null : pickChart(result) !== null;
  }
  const view =
    state.view === "auto" ? (renderable ? "chart" : "table") : state.view;
  return { view, kind, renderable };
}

/** Any chart shape (bar/line/pie over the data) or none. */
function canChart(result: ResultData): boolean {
  return result.rows.length > 0 && result.rows.length <= MAX_CHART_ROWS &&
    (pickChart(result) !== null || pickPie(result) !== null);
}

function ViewToggle({
  active,
  label,
  onClick,
}: {
  active: boolean;
  label: string;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-1 rounded text-xs font-medium ${
        active
          ? "bg-slate-800 text-white"
          : "border border-slate-300 text-slate-600 hover:bg-slate-50"
      }`}
    >
      {label}
    </button>
  );
}

function KindToggle({
  active,
  label,
  disabled,
  title,
  onClick,
}: {
  active: boolean;
  label: string;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title ?? label}
      onClick={onClick}
      className={`px-2.5 py-1 rounded text-xs font-medium ${
        active
          ? "bg-blue-600 text-white"
          : "border border-slate-200 text-slate-600 hover:bg-slate-50"
      } disabled:opacity-40 disabled:cursor-not-allowed`}
    >
      {label}
    </button>
  );
}

// ─── Table ────────────────────────────────────────────────────────────────────

function ResultTable({ result }: { result: ResultData }) {
  const rows = result.rows ?? [];
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr>
            {result.columns.map((col) => (
              <th
                key={col.name}
                className="text-left font-medium text-slate-500 pb-2 pr-4 border-b border-slate-200"
              >
                {col.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIdx) => (
            <tr key={rowIdx} className="border-b border-slate-50 last:border-0">
              {result.columns.map((col, colIdx) => {
                const value = (row as unknown[])[colIdx];
                return (
                  <td
                    key={col.name}
                    title={formatCell(value)}
                    className="py-1.5 pr-4 text-slate-700 max-w-xs truncate"
                  >
                    {formatCell(value)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {result.truncated && (
        <p className="text-xs text-slate-400 mt-2">Preview truncated.</p>
      )}
    </div>
  );
}

function formatCell(value: unknown): string {
  return displayValue(value);
}

/**
 * Cells are usually plain scalars, but legacy stored results can carry
 * driver-wrapper objects ({"type":…,"value":…}); unwrap the classic shape and
 * JSON-stringify anything else so a cell never renders as "[object Object]".
 */
function displayValue(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const inner = record["value"];
    if (inner != null && typeof inner !== "object" && "type" in record) {
      return String(inner);
    }
    try {
      return JSON.stringify(value);
    } catch {
      return "";
    }
  }
  return String(value);
}

// ─── Chart ───────────────────────────────────────────────────────────────────

type ChartSpec = {
  kind: "line" | "bar";
  categories: string[];
  /** One entry per numeric column rendered as a series. */
  series: { name: string; values: number[] }[];
};

/** Infers a chart from column types and values, or null when none applies. */
function pickChart(result: ResultData): ChartSpec | null {
  const rows = result.rows ?? [];
  const cols = result.columns;
  if (rows.length === 0 || rows.length > MAX_CHART_ROWS) return null;

  const temporalIdx = cols.findIndex((c) => TEMPORAL.test(c.typeName));
  const categoricalIdx = cols.findIndex(
    (c, i) => i !== temporalIdx && !NUMERIC.test(c.typeName),
  );
  const numericIdxs = cols
    .map((c, i) => (NUMERIC.test(c.typeName) ? i : -1))
    .filter((i) => i >= 0 && i !== temporalIdx)
    .slice(0, MAX_SERIES);
  if (numericIdxs.length === 0) return null;

  // A single count-like row (SELECT COUNT(*), totals) is better shown as a
  // plain table than as a single lonely bar.
  if (rows.length === 1 && temporalIdx < 0 && categoricalIdx < 0) return null;

  const cell = (row: unknown, idx: number) => (row as unknown[])[idx];

  // Temporal x-axis → line chart, rows in query order.
  if (temporalIdx >= 0) {
    return {
      kind: "line",
      categories: rows.map((row) => formatAxis(cell(row, temporalIdx)) ?? ""),
      series: numericIdxs.map((idx) => ({
        name: cols[idx].name,
        values: rows.map((row) => toNumber(cell(row, idx))),
      })),
    };
  }

  // Categorical x-axis → one bar per row, preserving query order. Rows are
  // never silently merged — "last 5 students" must show 5 bars even when two
  // share a name (duplicates get disambiguated labels). Aggregation only
  // happens when the raw row count is too large to read.
  if (categoricalIdx >= 0) {
    if (rows.length <= MAX_CATEGORIES) {
      const labels = withDuplicatesMarked(
        rows.map((row) => formatAxis(cell(row, categoricalIdx)) ?? "—"),
      );
      return {
        kind: "bar",
        categories: labels,
        series: numericIdxs.map((idx) => ({
          name: cols[idx].name,
          values: rows.map((row) => toNumber(cell(row, idx))),
        })),
      };
    }
    const counts = new Map<string, number[]>();
    for (const row of rows) {
      const category = formatAxis(cell(row, categoricalIdx)) ?? "—";
      const entry = counts.get(category) ?? new Array(numericIdxs.length).fill(0);
      numericIdxs.forEach((colIdx, k) => {
        entry[k] += toNumber(cell(row, colIdx));
      });
      counts.set(category, entry);
    }
    let entries = [...counts.entries()];
    // Keep the biggest categories so the chart stays readable.
    entries = entries
      .sort(
        (a, b) =>
          b[1].reduce((s, v) => s + v, 0) - a[1].reduce((s, v) => s + v, 0),
      )
      .slice(0, MAX_CATEGORIES);
    return {
      kind: "bar",
      categories: entries.map(([category]) => category),
      series: numericIdxs.map((_, k) => ({
        name: cols[numericIdxs[k]].name,
        values: entries.map(([, values]) => values[k]),
      })),
    };
  }

  return null;
}

/** Labels duplicate occurrences as "Name (2)", "Name (3)" — row-per-bar/pie
 * charting relies on unique keys, and ECharts dedupes/confuses same names. */
function withDuplicatesMarked(labels: string[]): string[] {
  const seen = new Map<string, number>();
  return labels.map((label) => {
    const n = (seen.get(label) ?? 0) + 1;
    seen.set(label, n);
    return n === 1 ? label : `${label} (${n})`;
  });
}

/** Category/axis label; null for values that cannot be labelled. */
function formatAxis(value: unknown): string | null {
  if (value == null) return null;
  const label = displayValue(value);
  return label === "" ? null : label;
}

function toNumber(value: unknown): number {
  if (typeof value === "number") return value;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** Part-to-whole slices, one per row when the dataset is small (zeros kept —
 * a "last N students" pie must not lose rows). Aggregates by category (top
 * slices + "Other") only when there are more rows than displayable slices. */
function pickPie(result: ResultData): { name: string; value: number }[] | null {
  const rows = result.rows ?? [];
  const cols = result.columns;
  if (rows.length === 0 || rows.length > MAX_CHART_ROWS) return null;

  const numericIdxs = cols
    .map((c, i) => (NUMERIC.test(c.typeName) ? i : -1))
    .filter((i) => i >= 0);
  if (numericIdxs.length === 0) return null;

  const categoricalIdx = cols.findIndex(
    (c, i) => i !== numericIdxs[0] && !NUMERIC.test(c.typeName),
  );
  const valueIdx = numericIdxs[0];

  const named = rows.map((row, rowIdx) => {
    const rowArr = row as unknown[];
    const name = categoricalIdx >= 0
      ? formatAxis(rowArr[categoricalIdx]) ?? `Row ${rowIdx + 1}`
      : `Row ${rowIdx + 1}`;
    return { name, value: toNumber(rowArr[valueIdx]) };
  });

  let slices = named;
  if (named.length > MAX_PIE_SLICES) {
    // Too many rows for a pie: aggregate duplicates and keep the biggest
    // slices, rolling the rest into "Other".
    const counts = new Map<string, number>();
    for (const slice of named) {
      counts.set(slice.name, (counts.get(slice.name) ?? 0) + slice.value);
    }
    let entries = [...counts.entries()];
    entries.sort((a, b) => b[1] - a[1]);
    const rest = entries.slice(MAX_PIE_SLICES - 1);
    slices = [
      ...withDuplicatesMarked(
        entries.slice(0, MAX_PIE_SLICES - 1).map(([name]) => name),
      ).map((name, k) => ({ name, value: entries[k][1] })),
      { name: "Other", value: rest.reduce((sum, [, value]) => sum + value, 0) },
    ];
  } else {
    // Every row shows as its own slice, even zero-valued ones (the label
    // tells the truth about the table).
    slices = named.map((slice) => ({
      name: slice.name,
      value: slice.value === 0 ? 0 : slice.value,
    }));
    slices = withDuplicatesMarked(slices.map((s) => s.name)).map((name, k) => ({
      name,
      value: slices[k].value,
    }));
  }

  if (slices.length === 0) return null;
  return slices;
}

/** Pie parts are always chartable when pickPie succeeds, so kind drives the shape. */
function EChart({ result, kind }: { result: ResultData; kind: "BAR" | "LINE" | "PIE" }) {
  const spec = useMemo(
    () => (kind === "PIE" ? null : pickChart(result)),
    [result, kind],
  );
  const slices = useMemo(
    () => (kind === "PIE" ? pickPie(result) : null),
    [result, kind],
  );
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  const option = useMemo<ChartOption | null>(() => {
    if (kind === "PIE") {
      if (!slices) return null;
      return {
        color: PALETTE,
        tooltip: { trigger: "item" },
        legend: { top: 0, type: "scroll", textStyle: { fontSize: 11 } },
        series: [{
          type: "pie",
          radius: ["42%", "70%"],
          center: ["50%", "56%"],
          data: slices,
          label: { formatter: "{b}: {d}%", fontSize: 11, color: "#475569" },
          itemStyle: { borderRadius: 4, borderColor: "#fff", borderWidth: 2 },
        }],
      } as ChartOption;
    }
    if (!spec) return null;
    const rotate = spec.categories.length > 12 ? 35 : spec.categories.length > 8 ? 30 : 0;
    return {
      color: PALETTE,
      grid: { left: 8, right: 12, top: 34, bottom: rotate ? 6 : 8, containLabel: true },
      tooltip: { trigger: "axis" },
      legend: { top: 0, type: "scroll", textStyle: { fontSize: 11 } },
      xAxis: {
        type: "category",
        data: spec.categories,
        axisLabel: {
          interval: 0,
          rotate,
          fontSize: 10,
          width: 96,
          overflow: "truncate",
          color: "#64748b",
        },
        axisLine: { lineStyle: { color: "#e2e8f0" } },
        axisTick: { show: false },
      },
      yAxis: {
        type: "value",
        splitLine: { lineStyle: { color: "#f1f5f9" } },
        axisLabel: { color: "#64748b", fontSize: 10 },
      },
      series: spec.series.map((s) =>
        spec.kind === "line" || kind === "LINE"
          ? {
              name: s.name,
              type: "line" as const,
              data: s.values,
              smooth: true,
              symbolSize: 5,
              lineStyle: { width: 2 },
              areaStyle: spec.series.length === 1 ? { opacity: 0.1 } : undefined,
            }
          : {
              name: s.name,
              type: "bar" as const,
              data: s.values,
              barMaxWidth: 40,
              itemStyle: { borderRadius: [4, 4, 0, 0] },
            },
      ),
    } as ChartOption;
  }, [spec, slices, kind]);

  // echarts touches the DOM, so the chart lifecycle lives entirely inside effects.
  useEffect(() => {
    if (!containerRef.current || !option) return;
    chartRef.current = echarts.init(containerRef.current);
    chartRef.current.setOption(option);
    const observer = new ResizeObserver(() => chartRef.current?.resize());
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      chartRef.current?.dispose();
      chartRef.current = null;
    };
    // Re-create on option change: the data may arrive through a subscription,
    // so the safest cycle is dispose → init → setOption.
  }, [option]);

  if (!option) return <ResultTable result={result} />;
  return (
    <div
      ref={containerRef}
      className="w-full h-72"
      aria-label="Result chart"
    />
  );
}
