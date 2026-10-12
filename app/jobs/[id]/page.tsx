import Link from "next/link";
import { core, type Job, type Identity } from "@/lib/api";
import JobMonitor from "./job-monitor";
export default async function JobDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const [job, identity] = await Promise.all([
    core<Job>(`/jobs/${encodeURIComponent(id)}`),
    core<Identity>("/me"),
  ]);
  return (
    <>
      <p className="eyebrow">EXECUTION DETAIL</p>
      <h1>Report execution</h1>
      <p className="intro">
        <code>{id}</code>
      </p>
      {job.data && identity.data ? (
        <JobMonitor
          key={job.data.id}
          initial={job.data}
          csrfHeader={identity.data.csrfHeader}
          csrfToken={identity.data.csrfToken}
        />
      ) : (
        <section className="panel" role="alert">
          <h2>{job.status === 404 ? "Job not found" : "Job unavailable"}</h2>
          <p>Sign in with the owning identity or retry the connection.</p>
          <Link href="/jobs">Back to jobs</Link>
        </section>
      )}
    </>
  );
}
