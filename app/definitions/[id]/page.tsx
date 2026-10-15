import Link from "next/link";
import { core, type Definition, type Identity } from "@/lib/api";
import DefinitionForm from "../definition-form";
export default async function DefinitionDetail({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ page?: string }>;
}) {
  const { id } = await params;
  const query = await searchParams;
  const page = /^\d{1,5}$/.test(query.page ?? "")
    ? Math.min(Number(query.page), 10000)
    : 0;
  const [definition, versions, user] = await Promise.all([
    core<Definition>(`/definitions/${encodeURIComponent(id)}`),
    core<Definition[]>(
      `/definitions/${encodeURIComponent(id)}/versions?page=${page}`,
    ),
    core<Identity>("/me"),
  ]);
  if (!definition.data || !versions.data || !user.data)
    return (
      <section className="panel" role="alert">
        <h1>Definition unavailable</h1>
        <p>Sign in or reload. The definition may no longer exist.</p>
        <Link href="/definitions">Return to catalog</Link>
      </section>
    );
  const d = definition.data;
  return (
    <>
      <p className="eyebrow">REPORT DEFINITION</p>
      <h1>{d.name}</h1>
      <p>{d.description}</p>
      <p>
        <code>{d.id}</code> · Version {d.version} ·{" "}
        {d.enabled ? "Accepting jobs" : "Disabled"}
      </p>
      <p>
        Template: <code>{d.template_resource}</code> · Dataset:{" "}
        {d.data_source_ref} · Locale: en-US · Time zone: UTC
      </p>
      {d.data_source_ref.startsWith("jdbc:sales-jdbc:") && (
        <p>
          Each new job captures current JDBC rows. Accepted jobs, retries and
          reprocessing use the saved snapshot.
        </p>
      )}
      {user.data.roles.includes("ROLE_REPORT_ADMIN") && (
        <DefinitionForm key={d.version} current={d} identity={user.data} />
      )}
      <section className="panel">
        <h2>Version history</h2>
        <p>
          Versions are immutable. Disabled versions cannot be submitted.
          Disabling the definition blocks all versions.
        </p>
        {versions.data.map((v) => (
          <article key={v.version}>
            <h3>
              Version {v.version} · {v.name}
            </h3>
            <p>{v.description}</p>
            <p>
              {v.recorded_at ? new Date(v.recorded_at).toISOString() : ""} ·{" "}
              {v.enabled ? "Published" : "Disabled"}
            </p>
            {d.enabled && v.enabled && (
              <Link
                className="button"
                href={`/jobs/new?definition=${encodeURIComponent(v.id)}&version=${v.version}`}
              >
                Generate version {v.version}
              </Link>
            )}
          </article>
        ))}
        <nav aria-label="Version history pages">
          {page > 0 && (
            <Link href={`?page=${page - 1}`}>Previous versions page</Link>
          )}{" "}
          {versions.data.length === 20 && (
            <Link href={`?page=${page + 1}`}>Older versions</Link>
          )}
        </nav>
      </section>
    </>
  );
}
