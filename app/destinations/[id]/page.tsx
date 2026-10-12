import Link from "next/link";
import { core } from "@/lib/api";
import type { components } from "@/lib/api.generated";
export default async function DestinationHistory({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const result = await core<components["schemas"]["DestinationVersion"][]>(
    `/destinations/${encodeURIComponent(id)}/versions`,
  );
  return (
    <>
      <Link href="/destinations">All destinations</Link>
      <h1>Destination history</h1>
      <p className="intro">{id} · Latest 100 versions · UTC</p>
      <section className="panel">
        {!result.data ? (
          <p role="alert">History unavailable. Sign in or retry.</p>
        ) : !result.data.length ? (
          <p>No history is available for this reference.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Provider</th>
                  <th>Bucket / prefix</th>
                  <th>Enabled</th>
                  <th>Recorded (UTC)</th>
                </tr>
              </thead>
              <tbody>
                {result.data.map((v) => (
                  <tr key={v.version}>
                    <td>{v.version}</td>
                    <td>{v.provider}</td>
                    <td>
                      <code>
                        {v.bucket}/{v.prefix}
                      </code>
                    </td>
                    <td>{v.enabled ? "Yes" : "No"}</td>
                    <td>{new Date(v.recorded_at).toISOString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
