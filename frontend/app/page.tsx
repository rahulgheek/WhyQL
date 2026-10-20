import Link from "next/link";
import { SheenButton, SpotlightCard, TiltFrame } from "./components/landing/Motion";

/**
 * Marketing landing page. Deliberately dark: the marketing wrapper looks like
 * an AI product; the app shell behind /ask, /datasources, /dashboard stays
 * light — the two never blur because this page ships its own background.
 * Server component: no state, no auth calls — the SiteHeader covers identity.
 * Motion (tilt / spotlight / sheen) lives in the small client Motion module.
 */

const ACCENT = "from-indigo-400 via-violet-400 to-fuchsia-400";

function Glow({
  className,
  color,
}: {
  className: string;
  color: string;
}) {
  return (
    <div
      aria-hidden
      className={`pointer-events-none absolute rounded-full blur-[120px] ${className}`}
      style={{ background: color, opacity: 0.18 }}
    />
  );
}

function Badge() {
  return (
    <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium text-slate-300">
      <span className="relative flex h-2 w-2">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-indigo-400 opacity-60" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-indigo-400" />
      </span>
      AI-powered pipeline — route, validate, execute, explain
    </span>
  );
}

/** Mock of the ask flow: question → SQL → result + chart, all decorative. */
function AskFlowMock() {
  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.04] shadow-2xl shadow-black/40 backdrop-blur">
      {/* window chrome */}
      <div className="flex items-center gap-1.5 border-b border-white/10 px-4 py-3">
        <span className="h-2.5 w-2.5 rounded-full bg-rose-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-amber-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/70" />
        <span className="ml-3 text-xs text-slate-500">whyql · ask</span>
      </div>
      <div className="space-y-4 p-5 text-left">
        {/* user bubble */}
        <div className="flex justify-end">
          <div className="max-w-[80%] rounded-2xl rounded-br-md bg-indigo-500/90 px-4 py-2.5 text-sm text-white">
            why did revenue drop in March and how are our top regions doing?
          </div>
        </div>
        {/* assistant bubble */}
        <div className="flex flex-col gap-3">
          <div className="max-w-[92%] rounded-2xl rounded-bl-md border border-white/10 bg-white/[0.04] px-4 py-3">
            <div className="mb-2 flex items-center gap-2 text-[11px] uppercase tracking-wide text-slate-500">
              <span className="rounded border border-emerald-400/30 bg-emerald-400/10 px-1.5 py-0.5 text-emerald-300">
                validated
              </span>
              <span>postgres · read-only · limit 30</span>
            </div>
            <pre className="overflow-x-auto rounded-lg bg-black/40 p-3 text-[11px] leading-relaxed text-indigo-200">
              {`SELECT region, SUM(total) AS revenue
FROM orders
WHERE created_at >= '2026-03-01'
GROUP BY region
ORDER BY revenue DESC
LIMIT 30`}
            </pre>
          </div>
          {/* result table + chart strip */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="rounded-xl border border-white/10 bg-white/[0.03] p-3">
              <table className="w-full text-[11px] text-slate-400">
                <thead>
                  <tr className="text-left text-slate-500">
                    <th className="pb-1.5 font-medium">region</th>
                    <th className="pb-1.5 text-right font-medium">revenue</th>
                  </tr>
                </thead>
                <tbody className="text-slate-200">
                  <tr className="border-t border-white/5">
                    <td className="py-1">north</td>
                    <td className="py-1 text-right">428,190</td>
                  </tr>
                  <tr className="border-t border-white/5">
                    <td className="py-1">south</td>
                    <td className="py-1 text-right">316,042</td>
                  </tr>
                  <tr className="border-t border-white/5">
                    <td className="py-1">east</td>
                    <td className="py-1 text-right">291,775</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div className="flex items-end justify-around gap-2 rounded-xl border border-white/10 bg-white/[0.03] p-3">
              {[88, 62, 55, 41, 34, 26].map((h, i) => (
                <div
                  key={i}
                  className={`w-6 rounded-t bg-gradient-to-t ${i === 0 ? "from-violet-500/80 to-fuchsia-400/80" : "from-indigo-500/50 to-violet-400/30"}`}
                  style={{ height: `${h}%` }}
                />
              ))}
            </div>
          </div>
          {/* evidence analysis */}
          <div className="max-w-[92%] rounded-2xl rounded-bl-md border border-indigo-400/20 bg-indigo-400/[0.06] px-4 py-3 text-sm text-slate-300">
            <span className="text-[11px] uppercase tracking-wide text-indigo-300">
              Why — from the evidence
            </span>
            <p className="mt-1 leading-relaxed">
              March revenue fell in the <em>south</em> region (~40% of the
              decline): 118 orders lost their customer link in the profile
              migration on Mar 14.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

/** The real pipeline, in order, as the page's product story. */
const PIPELINE = [
  {
    step: "1",
    title: "Route",
    desc: "Your question decides its own path — data query, data + explanation, or general knowledge.",
  },
  {
    step: "2",
    title: "Generate",
    desc: "Schema-grounded SQL for your engine, aware of today's date and similar past questions.",
  },
  {
    step: "3",
    title: "Validate",
    desc: "A hard parser gate: read-only SELECTs only, against your allowlisted tables and columns.",
  },
  {
    step: "4",
    title: "Execute",
    desc: "Timeouts, row caps and a 200-row display policy — millions of rows never hit your browser.",
  },
  {
    step: "5",
    title: "Analyze",
    desc: "Why-questions come back as evidence-backed narratives built from the actual result rows.",
  },
] as const;

const SAFETY = [
  "Read-only SELECT only",
  "Schema allowlist per source",
  "SSRF-guarded connections",
  "Size-aware results",
  "Isolated Kafka stages",
] as const;

const FEATURES = [
  {
    title: "Plain-English queries",
    desc: "Ask like you'd ask a colleague. Ambiguity turns into clarifying questions, not wrong queries.",
    icon: (
      <path d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
    ),
  },
  {
    title: "Postgres & MySQL, SQL files too",
    desc: "Connect live databases or upload a schema file — every source is tested before it's trusted.",
    icon: (
      <path d="M4 7c0 1.66 3.13 3 7 3s7-1.34 7-3-3.13-3-7-3-7 1.34-7 3zM4 7v10c0 1.66 3.13 3 7 3s7-1.34 7-3V7M4 12c0 1.66 3.13 3 7 3s7-1.34 7-3" />
    ),
  },
  {
    title: "Answers that explain themselves",
    desc: "Why and how questions return evidence-backed narratives: the rows that make the pattern visible.",
    icon: <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0114 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />,
  },
  {
    title: "Charts that pick themselves",
    desc: "Bar, line or pie — inferred from your question, switchable per result, aggregated so nothing floods the page.",
    icon: <path d="M11 3v18m-7-8v8m14-14v14M3 21h18" />,
  },
  {
    title: "Boards & dashboards",
    desc: "Pin results to a session rail, compose boards, or save a question straight to your dashboard.",
    icon: <path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />,
  },
  {
    title: "Multiple views per question",
    desc: "\"Summary and monthly trend\" returns stacked sections — each its own table or chart.",
    icon: <path d="M8 7v8m0 0v3m0-3h4c2.21 0 4-1.79 4-4V5m-8 0h9a1 1 0 011 1v1M3 3h1m0 18h18M3 21h1" />,
  },
] as const;

const STEPS = [
  { n: "1", t: "Connect a data source", d: "Postgres or MySQL, tested on connection. SQL schema uploads work too." },
  { n: "2", t: "Ask in plain English", d: "The pipeline routes, generates, validates and explains — you watch it stage by stage." },
  { n: "3", t: "Keep what matters", d: "Pin results for the session, save them to your dashboard, or compose them into boards." },
] as const;

function CtaButtons() {
  return (
    <div className="flex flex-wrap items-center justify-center gap-3">
      <SheenButton href="/ask" variant="gradient">
        Start asking →
      </SheenButton>
      <SheenButton href="/datasources" variant="ghost">
        Connect your data
      </SheenButton>
    </div>
  );
}

export default function HomePage() {
  return (
    <div className="relative overflow-hidden bg-[#0a0b12] text-slate-200">
      <Glow className="-top-40 left-1/2 h-[480px] w-[720px] -translate-x-1/2" color="rgb(99 102 241)" />
      <Glow className="top-[420px] -left-40 h-[420px] w-[420px]" color="rgb(192 38 211)" />
      <Glow className="top-[900px] -right-40 h-[420px] w-[420px]" color="rgb(56 189 248)" />

      {/* ── Hero ─────────────────────────────────────────────── */}
      <main className="relative mx-auto max-w-6xl px-6">
        <section className="flex flex-col items-center pb-16 pt-20 text-center md:pb-24 md:pt-28 animate-fade-in">
          <Badge />
          <h1 className="mt-6 max-w-3xl text-4xl font-semibold tracking-tight text-white sm:text-6xl">
            Ask your data anything.
            <span className={`block bg-gradient-to-r ${ACCENT} bg-clip-text text-transparent`}>
              Get verified answers.
            </span>
          </h1>
          <p className="mt-6 max-w-2xl text-base leading-relaxed text-slate-400 sm:text-lg">
            WhyQL turns questions in plain English into validated, read-only
            SQL — runs them safely against your database, returns the result,
            the chart, and the reasoning behind the numbers.
          </p>
          <div className="mt-8">
            <CtaButtons />
          </div>
          <div className="mt-16 w-full max-w-3xl">
            <TiltFrame>
              <AskFlowMock />
            </TiltFrame>
          </div>
        </section>

        {/* ── Pipeline ───────────────────────────────────────── */}
        <section className="py-16 md:py-20 animate-slide-in-up" style={{ animationDelay: "100ms" }}>
          <div className="mx-auto max-w-2xl text-center">
            <h2 className="text-2xl font-semibold tracking-tight text-white sm:text-3xl">
              One question. Five guarded stages.
            </h2>
            <p className="mt-3 text-sm leading-relaxed text-slate-400 sm:text-base">
              Every answer travels a stage-isolated pipeline. Each stage does
              one job and can be trusted alone — a Kafka event hand-off keeps
              them that way.
            </p>
          </div>
          <ol className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            {PIPELINE.map((p, i) => (
              <li key={p.step} className="h-full animate-fade-in" style={{ animationDelay: `${150 + i * 90}ms` }}>
                <SpotlightCard className="h-full rounded-2xl border border-white/10 bg-white/[0.03] p-5">
                  <span
                    className={`inline-flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br ${ACCENT} text-sm font-bold text-white transition-transform duration-300 group-hover:-rotate-6 group-hover:scale-110`}
                  >
                    {p.step}
                  </span>
                  <h3 className="mt-3 font-semibold text-white">{p.title}</h3>
                  <p className="mt-1.5 text-[13px] leading-relaxed text-slate-400">{p.desc}</p>
                  <span
                    aria-hidden
                    className="absolute -right-3 top-8 hidden h-px w-6 bg-gradient-to-r from-indigo-400/60 to-transparent md:block"
                  />
                </SpotlightCard>
              </li>
            ))}
          </ol>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-2.5">
            {SAFETY.map((s) => (
              <span
                key={s}
                className="rounded-full border border-emerald-400/20 bg-emerald-400/[0.07] px-3 py-1 text-xs font-medium text-emerald-300/90"
              >
                {s}
              </span>
            ))}
          </div>
        </section>

        {/* ── Features ───────────────────────────────────────── */}
        <section className="py-16 md:py-20 animate-slide-in-up" style={{ animationDelay: "200ms" }}>
          <div className="mx-auto max-w-2xl text-center">
            <h2 className="text-2xl font-semibold tracking-tight text-white sm:text-3xl">
              Everything after the query
            </h2>
            <p className="mt-3 text-sm text-slate-400 sm:text-base">
              The pipeline is the engine. This is the car seat.
            </p>
          </div>
          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map((f, i) => (
              <div key={f.title} className="animate-fade-in" style={{ animationDelay: `${150 + i * 80}ms` }}>
                <SpotlightCard className="h-full rounded-2xl border border-white/10 bg-white/[0.03] p-6">
                  <div className="flex h-10 w-10 transform-gpu items-center justify-center rounded-xl border border-indigo-400/20 bg-indigo-400/10 transition duration-300 group-hover:scale-110 group-hover:border-indigo-400/50 group-hover:shadow-[0_0_24px_rgba(99,102,241,0.4)]">
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      className="h-5 w-5 text-indigo-300"
                    >
                      {f.icon}
                    </svg>
                  </div>
                  <h3 className="mt-4 font-semibold text-white">{f.title}</h3>
                  <p className="mt-1.5 text-[13px] leading-relaxed text-slate-400">{f.desc}</p>
                </SpotlightCard>
              </div>
            ))}
          </div>
        </section>

        {/* ── Getting started ────────────────────────────────── */}
        <section className="py-16 md:py-20 animate-slide-in-up" style={{ animationDelay: "300ms" }}>
          <div className="space-y-12 lg:space-y-0 lg:grid lg:grid-cols-[1fr_2fr] lg:gap-10 lg:items-center">
            <div>
              <h2 className="text-2xl font-semibold tracking-tight text-white sm:text-3xl">
                Two minutes to your first answer
              </h2>
            </div>
            <ol className="grid gap-4 sm:grid-cols-3">
              {STEPS.map((s, i) => (
                <li key={s.n} className="h-full animate-fade-in" style={{ animationDelay: `${150 + i * 100}ms` }}>
                  <SpotlightCard className="h-full rounded-2xl border border-white/10 bg-white/[0.03] p-5">
                    <span className="text-xs font-semibold uppercase tracking-widest text-indigo-300">
                      Step {s.n}
                    </span>
                    <h3 className="mt-2 font-semibold text-white">{s.t}</h3>
                    <p className="mt-1.5 text-[13px] leading-relaxed text-slate-400">{s.d}</p>
                  </SpotlightCard>
                </li>
              ))}
            </ol>
          </div>
        </section>

        {/* ── Final CTA ──────────────────────────────────────── */}
        <section className="pb-24 pt-8 animate-slide-in-up" style={{ animationDelay: "400ms" }}>
          <div
            className={`relative overflow-hidden rounded-3xl border border-white/10 p-6 text-center md:p-16`}
          >
            <div
              aria-hidden
              className={`absolute inset-0 bg-gradient-to-br from-indigo-500/15 via-violet-500/10 to-fuchsia-500/15`}
            />
            <div className="relative">
              <h2 className="text-3xl font-semibold tracking-tight text-white sm:text-4xl">
                Your data already has the answer.
              </h2>
              <p className="mx-auto mt-3 max-w-xl text-sm text-slate-400 sm:text-base">
                All that was missing was someone to translate the question.
                That part takes two seconds now.
              </p>
              <div className="mt-8 flex justify-center">
                <CtaButtons />
              </div>
            </div>
          </div>
        </section>
      </main>

      {/* ── Footer ───────────────────────────────────────────── */}
      <footer className="relative border-t border-white/[0.06]">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-6 py-10 text-sm text-slate-500 sm:flex-row">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-300">WhyQL</span>
            <span>© 2026 — ask, don't write SQL</span>
          </div>
          <nav className="flex gap-6">
            <Link href="/datasources" className="transition hover:text-slate-300">
              Data sources
            </Link>
            <Link href="/ask" className="transition hover:text-slate-300">
              Ask
            </Link>
            <Link href="/dashboard" className="transition hover:text-slate-300">
              Dashboards
            </Link>
            <Link href="/builder" className="transition hover:text-slate-300">
              Boards
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
