import Link from "next/link";
import { core, type Identity } from "@/lib/api";
import type { components } from "@/lib/api.generated";
import TemplateUpload from "./template-upload";
export default async function Templates({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const query = await searchParams;
  const page = /^\d{1,5}$/.test(query.page ?? "")
    ? Math.min(Number(query.page), 10000)
    : 0;
  const [templates, policy, user] = await Promise.all([
    core<components["schemas"]["RegisteredTemplate"][]>(
      `/templates?page=${page}`,
    ),
    core<components["schemas"]["TemplatePolicy"]>("/templates/policy"),
    core<Identity>("/me"),
  ]);
  if (!templates.data || !policy.data || !user.data)
    return (
      <section className="panel" role="alert">
        <h1>Templates unavailable</h1>
        <p>Sign in or retry when the service is available.</p>
        <Link href="/">Return to overview</Link>
      </section>
    );
  const admin = user.data.roles.includes("ROLE_REPORT_ADMIN");
  return (
    <>
      <p className="eyebrow">TRUSTED REPORTING RESOURCES</p>
      <h1>Templates</h1>
      <p>
        Immutable source versions identified by their SHA-256. Registered
        templates remain available across worker restarts. Revised source
        creates another fingerprint; definitions select an exact fingerprint.
      </p>
      <section className="panel">
        <h2>Bundled sample</h2>
        <p>
          Sales summary · JasperReports {policy.data.compilerVersion} ·
          sample-v1 data
        </p>
        <a className="button" href="/backend/api/v1/templates/sample">
          Download sample JRXML
        </a>
      </section>
      {admin && policy.data.uploadsEnabled ? (
        <TemplateUpload
          identity={user.data}
          maxBytes={policy.data.maxSourceBytes}
        />
      ) : (
        <p>
          {admin
            ? "Trusted template registration is disabled by deployment policy."
            : "Only trusted administrators can register templates or download registered source."}
        </p>
      )}
      <h2>Registered content versions</h2>
      {templates.data.length === 0 && (
        <p>No registered templates on this page.</p>
      )}
      {templates.data.map((t) => (
        <article className="panel" key={t.sha256}>
          <h3>{t.name}</h3>
          <p>
            Compiled with JasperReports {t.compiler_version} ·{" "}
            {new Date(t.created_at).toISOString()}
          </p>
          <p>
            Reference: <code>sha256:{t.sha256}</code>
          </p>
          {admin && (
            <>
              <Link
                className="button"
                href={`/definitions?template=sha256:${t.sha256}`}
              >
                Use in a definition
              </Link>{" "}
              <a href={`/backend/api/v1/templates/${t.sha256}/source`}>
                Download registered JRXML
              </a>
            </>
          )}
        </article>
      ))}
      <nav aria-label="Template pages">
        {page > 0 && (
          <Link href={`?page=${page - 1}`}>Previous template page</Link>
        )}{" "}
        {templates.data.length === 20 && (
          <Link href={`?page=${page + 1}`}>Older templates</Link>
        )}
      </nav>
    </>
  );
}
