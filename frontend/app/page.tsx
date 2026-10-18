import Link from "next/link";

export default function HomePage() {
  return (
    <div className="min-h-screen bg-slate-50">
      <main className="max-w-4xl mx-auto py-12 px-6">
        <h1 className="text-3xl font-bold text-slate-900 mb-6">Ask your data</h1>
        <p className="text-slate-600 mb-8">
          Connect a data source, ask questions in plain English, and get
          validated read-only queries, results, and charts.
        </p>
        <div className="grid gap-4 md:grid-cols-2">
          <Link
            href="/datasources"
            className="p-6 border border-slate-200 rounded-xl hover:border-slate-300 hover:bg-white transition"
          >
            <h3 className="font-semibold text-slate-900 mb-2">Data Sources</h3>
            <p className="text-sm text-slate-500">
              Add and test PostgreSQL/MySQL connections or upload SQL schemas
            </p>
          </Link>
          <Link
            href="/ask"
            className="p-6 border border-slate-200 rounded-xl hover:border-slate-300 hover:bg-white transition"
          >
            <h3 className="font-semibold text-slate-900 mb-2">Ask a Question</h3>
            <p className="text-sm text-slate-500">
              Submit a natural-language question and view generated SQL, results,
              analysis, and charts
            </p>
          </Link>
        </div>
      </main>
    </div>
  );
}
