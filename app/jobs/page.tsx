import Link from "next/link";
import { core, type JobPage } from "@/lib/api";
export default async function Jobs({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const query = Object.fromEntries(
    Object.entries(raw).map(([key, value]) => [
      key,
      Array.isArray(value) ? value[0] : value,
    ]),
  );
  const page = Number(query.page ?? 0);
  const params = new URLSearchParams({ page: String(page) });
  for (const key of [
    "status",
    "definitionId",
    "acceptedFrom",
    "acceptedBefore",
    "sort",
  ]) {
    if (query[key]) params.set(key, query[key]);
  }
  const result = await core<JobPage>(`/jobs?${params}`);
  return (
    <>
      <p className="eyebrow">EXECUTION HISTORY</p>
      <div className="title-row">
        <h1>Report jobs</h1>
        <Link className="button" href="/jobs/new">
          New report
        </Link>
      </div>
      <p className="intro">
        Your durable report requests. Date filters use midnight UTC; the end
        date is exclusive.
      </p>
      <form className="filters">
        <label>
          Status
          <select name="status" defaultValue={query.status ?? ""}>
            <option value="">All statuses</option>
            {[
              "QUEUED",
              "RUNNING",
              "DELIVERING",
              "SUCCEEDED",
              "PARTIAL_SUCCESS",
              "FAILED",
              "CANCELLED",
            ].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          Definition ID
          <input
            name="definitionId"
            maxLength={64}
            defaultValue={query.definitionId ?? ""}
            placeholder="All definitions"
          />
        </label>
        <label>
          Accepted from (UTC)
          <input
            type="date"
            name="acceptedFrom"
            defaultValue={query.acceptedFrom ?? ""}
          />
        </label>
        <label>
          Accepted before (UTC)
          <input
            type="date"
            name="acceptedBefore"
            defaultValue={query.acceptedBefore ?? ""}
          />
        </label>
        <label>
          Sort
          <select name="sort" defaultValue={query.sort ?? "newest"}>
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
          </select>
        </label>
        <button>Apply filters</button>
        <Link href="/jobs">Clear filters</Link>
      </form>
      {!result.data ? (
        <section className="panel" role="alert">
          <p>
            {result.status === 401
              ? "Sign in to view your jobs."
              : "Unable to load jobs. Check filters or retry."}
          </p>
          <Link href="/">Overview</Link>
        </section>
      ) : (
        <section className="panel">
          <p>{result.data.total} matching jobs</p>
          {result.data.items.length === 0 ? (
            <p>No jobs match these filters.</p>
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Job</th>
                    <th>Status</th>
                    <th>Stage</th>
                    <th>Attempts</th>
                    <th>Accepted (UTC)</th>
                  </tr>
                </thead>
                <tbody>
                  {result.data.items.map((job) => (
                    <tr key={job.id}>
                      <td>
                        <Link href={`/jobs/${job.id}`}>
                          {job.id.slice(0, 8)}
                        </Link>
                      </td>
                      <td>
                        <span className="badge">{job.status}</span>
                      </td>
                      <td>{job.stage}</td>
                      <td>{job.attempt}</td>
                      <td>
                        {new Date(job.created_at)
                          .toISOString()
                          .replace("T", " ")
                          .slice(0, 19)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="title-row">
            {page > 0 && (
              <Link
                href={`/jobs?${new URLSearchParams({ ...Object.fromEntries(params), page: String(page - 1) })}`}
              >
                Previous page
              </Link>
            )}
            {(page + 1) * 20 < result.data.total && (
              <Link
                href={`/jobs?${new URLSearchParams({ ...Object.fromEntries(params), page: String(page + 1) })}`}
              >
                Next page
              </Link>
            )}
          </div>
        </section>
      )}
    </>
  );
}
