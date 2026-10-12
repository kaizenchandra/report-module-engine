import Link from "next/link";
import { core, type Identity, type Summary } from "@/lib/api";
export default async function Home() {
  const identity = await core<Identity>("/me");
  if (!identity.data)
    return (
      <>
        <p className="eyebrow">REPORT OPERATIONS</p>
        <h1>Your reporting workspace</h1>
        <p className="intro">
          Generate, deliver, and track reports through one secured platform.
        </p>
        <section className="panel">
          <h2>
            {identity.status === 401
              ? "Sign in to continue"
              : identity.status === 403
                ? "Permission required"
                : "Core service unavailable"}
          </h2>
          <p>
            {identity.status === 401
              ? "Use your organization identity to access reporting resources."
              : identity.status === 403
                ? "Your identity needs the REPORT_USER or REPORT_ADMIN role."
                : "Start the Java service, database, and identity provider, then retry."}
          </p>
          {identity.status === 401 ? (
            <a
              className="button"
              href="/backend/oauth2/authorization/reporting"
            >
              Sign in securely →
            </a>
          ) : (
            <Link className="button" href="/">
              Retry connection
            </Link>
          )}
        </section>
      </>
    );
  const summary = await core<Summary>("/summary");
  return (
    <>
      <p className="eyebrow">REPORT OPERATIONS</p>
      <h1>Workspace overview</h1>
      <p className="intro">
        Workload and outcomes for your authenticated identity.
      </p>
      <section className="metrics" aria-label="Job totals">
        {[
          "QUEUED",
          "RUNNING",
          "DELIVERING",
          "SUCCEEDED",
          "PARTIAL_SUCCESS",
          "FAILED",
          "CANCELLED",
        ].map((status) => (
          <article className="panel" key={status}>
            <span>{status.replaceAll("_", " ")}</span>
            <strong>
              {summary.data
                ? (summary.data.counts.find((c) => c.status === status)
                    ?.count ?? 0)
                : "Unavailable"}
            </strong>
          </article>
        ))}
      </section>
      <section className="panel">
        <h2>Connected to report core</h2>
        <p>
          Authenticated subject: <code>{identity.data.subject}</code>
        </p>
        <p>{identity.data.roles.join(", ")}</p>
        <Link className="button" href="/definitions">
          Browse report catalog →
        </Link>
      </section>
      {!summary.data && (
        <p role="alert">
          Workload summary could not be loaded. Refresh to retry.
        </p>
      )}
    </>
  );
}
