import CloudOperation from "@/app/components/cloud-operation";
import Link from "next/link";
import { core, type Identity } from "@/lib/api";
import DestinationControl from "./destination-control";
import DestinationEditor from "./destination-editor";
import type { components } from "@/lib/api.generated";
type Destination = components["schemas"]["Destination"];
export default async function Destinations() {
  const [result, user] = await Promise.all([
    core<Destination[]>("/destinations"),
    core<Identity>("/me"),
  ]);
  return (
    <>
      <p className="eyebrow">DELIVERY RESOURCES</p>
      <h1>Cloud destinations</h1>
      <p className="intro">
        Versioned destinations with deployment-managed bucket permissions.
        Configuration is captured when a job is accepted; disabling a
        destination affects new submissions.
      </p>
      {user.data?.roles.includes("ROLE_REPORT_ADMIN") && (
        <section className="panel">
          <DestinationEditor
            token={user.data.csrfToken}
            header={user.data.csrfHeader}
          />
        </section>
      )}
      {!result.data ? (
        <section className="panel" role="alert">
          Destinations unavailable. Sign in or retry the connection.
        </section>
      ) : (
        result.data.map((d) => (
          <section className="panel" key={d.id}>
            <div className="title-row">
              <h2>{d.id}</h2>
              <span className="badge">
                {d.enabled ? "Enabled" : "Disabled"}
              </span>
            </div>
            <dl>
              <dt>Provider</dt>
              <dd>{d.provider}</dd>
              <dt>Bucket / prefix</dt>
              <dd>
                <code>
                  {d.bucket}/{d.prefix}
                </code>
              </dd>
              <dt>Version</dt>
              <dd>{d.version}</dd>
              <dt>Last verified delivery</dt>
              <dd>
                {d.last_verified_delivery
                  ? new Date(d.last_verified_delivery).toISOString()
                  : "No verified delivery recorded"}
              </dd>
            </dl>
            <Link href={`/destinations/${d.id}`}>View version history</Link>
            <p className="muted">
              Create-only objects · SHA-256 verification · Credentials remain in
              the delivery service.
            </p>
            {user.data?.roles.includes("ROLE_REPORT_ADMIN") && (
              <>
                <DestinationEditor
                  key={d.version}
                  destination={d}
                  token={user.data.csrfToken}
                  header={user.data.csrfHeader}
                />
                <CloudOperation
                  endpoint={`/destinations/${d.id}/checks`}
                  label="Check bucket access"
                  token={user.data.csrfToken}
                  header={user.data.csrfHeader}
                />
                <DestinationControl
                  id={d.id}
                  enabled={d.enabled}
                  token={user.data.csrfToken}
                  header={user.data.csrfHeader}
                />
              </>
            )}
          </section>
        ))
      )}
    </>
  );
}
