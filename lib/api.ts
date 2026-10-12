import "server-only";
import { headers } from "next/headers";
import type { components } from "./api.generated";
export type Identity = components["schemas"]["Identity"];
export type Definition = components["schemas"]["Definition"];
export type Summary = components["schemas"]["Summary"];
export type Job = components["schemas"]["Job"];
export type JobPage = components["schemas"]["JobPage"];
export async function core<T>(
  path: string,
): Promise<{ data?: T; status: number }> {
  const incoming = await headers();
  try {
    const response = await fetch(
      `${process.env.CORE_URL ?? "http://localhost:8085"}/backend/api/v1${path}`,
      {
        headers: { cookie: incoming.get("cookie") ?? "" },
        cache: "no-store",
        redirect: "manual",
        signal: AbortSignal.timeout(5000),
      },
    );
    if (!response.ok) return { status: response.status };
    return { data: (await response.json()) as T, status: response.status };
  } catch {
    return { status: 503 };
  }
}
