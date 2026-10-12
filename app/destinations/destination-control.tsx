"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
export default function DestinationControl({
  id,
  enabled,
  token,
  header,
}: {
  id: string;
  enabled: boolean;
  token: string;
  header: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const router = useRouter();
  async function update() {
    setBusy(true);
    setError("");
    try {
      const r = await fetch(`/backend/api/v1/destinations/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", [header]: token },
        body: JSON.stringify({ enabled: !enabled }),
      });
      if (!r.ok) throw new Error(`Update rejected (${r.status})`);
      router.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Update failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <button className="secondary" disabled={busy} onClick={update}>
        {busy
          ? "Saving…"
          : enabled
            ? "Disable new submissions"
            : "Enable new submissions"}
      </button>
      {error && <p role="alert">{error}</p>}
    </>
  );
}
