import Link from "next/link";
import { core, type Identity, type Definition } from "@/lib/api";
import type { components } from "@/lib/api.generated";
import SubmissionForm from "./submission-form";
export default async function NewJob({
  searchParams,
}: {
  searchParams: Promise<{ definition?: string; version?: string }>;
}) {
  const query = await searchParams;
  const [user, destinations, definitions] = await Promise.all([
    core<Identity>("/me"),

    core<components["schemas"]["Destination"][]>("/destinations"),
    core<Definition[]>("/definitions"),
  ]);
  const available = definitions.data?.filter((d) => d.enabled) ?? [];
  if (
    query.definition &&
    query.version &&
    /^[a-z][a-z0-9-]{2,63}$/.test(query.definition) &&
    /^[1-9][0-9]{0,6}$/.test(query.version)
  ) {
    const pinned = await core<Definition>(
      `/definitions/${query.definition}/versions/${query.version}`,
    );
    if (
      pinned.data?.enabled &&
      available.some((d) => d.id === pinned.data!.id) &&
      !available.some(
        (d) => d.id === pinned.data!.id && d.version === pinned.data!.version,
      )
    )
      available.push(pinned.data);
  }
  const selected =
    available.find(
      (d) => d.id === query.definition && String(d.version) === query.version,
    ) ??
    available.find((d) => d.id === "sales-summary") ??
    available[0];
  const unavailable = Boolean(
    query.definition &&
      !available.some(
        (d) => d.id === query.definition && String(d.version) === query.version,
      ),
  );
  return (
    <>
      <p className="eyebrow">NEW EXECUTION</p>
      <h1>Generate a report</h1>
      <p className="intro">
        Submit a durable request. The engine handles rendering and delivery.
      </p>
      {user.data &&
      destinations.data &&
      definitions.data &&
      selected &&
      !unavailable ? (
        <SubmissionForm
          key={`${selected.id}:${selected.version}`}
          definitions={available}
          initialDefinition={selected}
          destinations={destinations.data.filter((d) => d.enabled)}
          csrfToken={user.data.csrfToken}
          csrfHeader={user.data.csrfHeader}
        />
      ) : (
        <section className="panel" role="alert">
          <p>
            Sign in to submit reports. If signed in, the selected definition may
            be unavailable; return to the catalog or retry.
          </p>
          <Link href="/">Return to overview</Link>
        </section>
      )}
    </>
  );
}
