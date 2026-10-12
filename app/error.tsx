"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <section className="panel" role="alert">
      <h1>Unable to load this view</h1>
      <p>
        Please retry. If the problem persists, contact your platform operator.
      </p>
      <button onClick={reset}>Retry</button>
    </section>
  );
}
