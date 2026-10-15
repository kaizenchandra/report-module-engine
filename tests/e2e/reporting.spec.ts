import AxeBuilder from "@axe-core/playwright";
import { test, expect } from "@playwright/test";
import { inflateRawSync } from "node:zlib";
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import sample from "../../examples/request.json";
async function login(
  page: import("@playwright/test").Page,
  username = "reporter",
) {
  if (!process.env.REPORT_TEST_PASSWORD)
    throw new Error("REPORT_TEST_PASSWORD required");
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in securely" }).click();
  await page.getByLabel("Username or email").fill(username);
  await page
    .getByLabel("Password", { exact: true })
    .fill(
      username === "operator"
        ? process.env.REPORT_ADMIN_TEST_PASSWORD!
        : process.env.REPORT_TEST_PASSWORD,
    );
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
}
test("browser submits, observes verified S3 delivery and retrieves intact PDF", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page
    .getByLabel("Report title")
    .fill("Browser verification sales report");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const id = page.url().split("/").at(-1);
  const job = await (
    await page.request.get(`/backend/api/v1/jobs/${id}`)
  ).json();
  expect(job.deliveries[0].status).toBe("VERIFIED");
  expect(job.attempt).toBe(1);
  expect(job.deliveryAttempts.at(-1).status).toBe("VERIFIED");
  expect(job.deliveryAttempts.at(-1).phase).toBe("UPLOAD");
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download PDF" }).click();
  const download = await downloadPromise;
  const bytes = await readFile((await download.path())!);
  expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
  expect(bytes.length).toBe(job.artifacts[0].size_bytes);
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(download.suggestedFilename()).toBe(`report-${id}.pdf`);
  // S3Mock is deliberately unauthenticated and loopback-only. Compare actual stored bytes.
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(
    createHash("sha256")
      .update(await cloud.body())
      .digest("hex"),
  ).toBe(job.artifacts[0].sha256);
  await page
    .getByRole("button", { name: "Create temporary cloud link" })
    .click();
  const link = page.getByRole("link", {
    name: "Download from cloud",
    exact: true,
  });
  await expect(link).toBeVisible({ timeout: 30000 });
  const signed = await link.getAttribute("href");
  if (!signed) throw new Error("Signed URL missing");
  const parsed = new URL(signed);
  expect(Number(parsed.searchParams.get("X-Amz-Expires"))).toBeLessThanOrEqual(
    300,
  );
  const signedDownload = await page.request.get(signed);
  expect(signedDownload.status()).toBe(200);
  expect(
    createHash("sha256")
      .update(await signedDownload.body())
      .digest("hex"),
  ).toBe(job.artifacts[0].sha256);
});
test("CSRF, strict validation, duplicate acceptance and conflicting idempotency", async ({
  page,
}) => {
  await login(page);
  const identity = await (await page.request.get("/backend/api/v1/me")).json();
  const key = randomUUID();
  const headers = {
    [identity.csrfHeader]: identity.csrfToken,
    "Idempotency-Key": key,
  };
  const noCsrf = await page.request.post("/backend/api/v1/jobs", {
    data: sample,
    headers: { "Idempotency-Key": key },
  });
  expect(noCsrf.status()).toBe(403);
  const invalid = await page.request.post("/backend/api/v1/jobs", {
    data: { ...sample, injected: true },
    headers,
  });
  expect(invalid.status()).toBe(400);
  const first = await page.request.post("/backend/api/v1/jobs", {
    data: sample,
    headers,
  });
  expect(first.status()).toBe(202);
  const duplicate = await page.request.post("/backend/api/v1/jobs", {
    data: sample,
    headers,
  });
  expect(duplicate.status()).toBe(202);
  expect((await duplicate.json()).id).toBe((await first.json()).id);
  const conflict = await page.request.post("/backend/api/v1/jobs", {
    data: { ...sample, parameters: { title: "Changed" } },
    headers,
  });
  expect(conflict.status()).toBe(409);
  const forbidden = await page.request.get(
    "/backend/api/v1/internal/deliveries",
  );
  expect(forbidden.status()).toBe(403);
});

test("GCS destination uses generation receipt and protected output", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page.getByLabel("Cloud destination").selectOption("local-gcs");
  await page.getByLabel("Report title").fill("GCS browser verification");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const id = page.url().split("/").at(-1);
  const job = await (
    await page.request.get(`/backend/api/v1/jobs/${id}`)
  ).json();
  const receipt = JSON.parse(job.deliveries[0].receipt);
  expect(receipt.version).toMatch(/^[0-9]+$/);
  expect(job.deliveries[0].destination_ref).toBe("local-gcs");
  await page
    .getByRole("button", { name: "Create temporary cloud link" })
    .click();
  await expect(
    page.getByText(
      "A signing identity is not configured. Use the authenticated output download instead.",
    ),
  ).toBeVisible({ timeout: 30000 });
  expect(
    (await (await page.request.get(`/backend/api/v1/jobs/${id}`)).json())
      .status,
  ).toBe("SUCCEEDED");
  await page.goto("/destinations");
  await expect(
    page.getByRole("heading", { name: "local-gcs", exact: true }),
  ).toBeVisible();
  const denied = await page.request.patch(
    "/backend/api/v1/destinations/local-gcs",
    { data: { enabled: false } },
  );
  expect(denied.status()).toBe(403);
});

test("implemented screens pass automated accessibility and mobile layout checks", async ({
  page,
}) => {
  await login(page);
  for (const path of [
    "/",
    "/definitions",
    "/destinations",
    "/jobs",
    "/jobs/new",
  ]) {
    await page.goto(path);
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(result.violations, path).toEqual([]);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/jobs/new");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in to continue" }),
  ).toBeVisible();
});

test("destination history is readable while registration remains admin-only", async ({
  page,
}) => {
  await login(page);
  await page.goto("/destinations/local-s3");
  await expect(
    page.getByRole("heading", { name: "Destination history" }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "report-results/reports/", exact: true }),
  ).toBeVisible();
  const identity = await (await page.request.get("/backend/api/v1/me")).json();
  const response = await page.request.post("/backend/api/v1/destinations", {
    headers: { [identity.csrfHeader]: identity.csrfToken },
    data: {
      id: "forbidden-test",
      configuration: {
        provider: "S3",
        bucket: "report-results",
        prefix: "reports/",
        enabled: true,
      },
    },
  });
  expect(response.status()).toBe(403);
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
});

test("operator registers, verifies and versions a destination through OIDC", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/destinations");
  const registration = page.locator("details").filter({
    has: page.locator("summary", { hasText: "Register a destination" }),
  });
  await registration.locator("summary").click();
  const id = `browser-${randomUUID()}`;
  await registration.getByLabel("Reference ID").fill(id);
  await registration
    .getByLabel("Bucket", { exact: true })
    .fill("report-results");
  await registration.getByLabel("Object prefix").fill("browser/2026/");
  await registration.getByRole("button", { name: "Save destination" }).click();
  const card = page
    .locator("section")
    .filter({ has: page.getByRole("heading", { name: id, exact: true }) });
  await expect(
    card.getByRole("heading", { name: id, exact: true }),
  ).toBeVisible();
  for (const name of [id, "local-gcs"]) {
    const target = page
      .locator("section")
      .filter({ has: page.getByRole("heading", { name, exact: true }) });
    await target.getByRole("button", { name: "Check bucket access" }).click();
    await expect(
      target.getByText(
        "Bucket metadata is reachable with the delivery identity. Write permissions and encryption were not tested.",
      ),
    ).toBeVisible({ timeout: 30000 });
  }
  const editor = card.locator("details");
  await editor.locator("summary").click();
  await editor.getByLabel("Object prefix").fill("browser/2027/");
  await editor.getByRole("button", { name: "Save destination" }).click();
  await expect(card.locator("code")).toHaveText("report-results/browser/2027/");
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await card.getByRole("link", { name: "View version history" }).click();
  await expect(
    page.getByRole("cell", {
      name: "report-results/browser/2026/",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", {
      name: "report-results/browser/2027/",
      exact: true,
    }),
  ).toBeVisible();
  const me = await (await page.request.get("/backend/api/v1/me")).json();
  await page.request.patch(`/backend/api/v1/destinations/${id}`, {
    headers: { [me.csrfHeader]: me.csrfToken },
    data: { enabled: false },
  });
});

// Read one bounded ZIP entry from an actual generated workbook, using its central directory.
function workbookEntry(bytes: Buffer, name: string): string {
  return zipEntryBytes(bytes, name).toString("utf8");
}
function zipEntryBytes(bytes: Buffer, name: string): Buffer {
  const end = bytes.lastIndexOf(Buffer.from("504b0506", "hex"));
  if (end < 0) throw new Error("Workbook ZIP directory missing");
  let offset = bytes.readUInt32LE(end + 16);
  for (let i = 0; i < bytes.readUInt16LE(end + 10); i++) {
    if (bytes.readUInt32LE(offset) !== 0x02014b50)
      throw new Error("Invalid workbook ZIP directory");
    const length = bytes.readUInt16LE(offset + 28);
    const entry = bytes.subarray(offset + 46, offset + 46 + length).toString();
    if (entry === name) {
      const size = bytes.readUInt32LE(offset + 20);
      const local = bytes.readUInt32LE(offset + 42);
      const start =
        local +
        30 +
        bytes.readUInt16LE(local + 26) +
        bytes.readUInt16LE(local + 28);
      const compressed = bytes.subarray(start, start + size);
      const result =
        bytes.readUInt16LE(offset + 10) === 8
          ? inflateRawSync(compressed, { maxOutputLength: 10 * 1024 * 1024 })
          : compressed;
      return result;
    }
    offset +=
      46 +
      length +
      bytes.readUInt16LE(offset + 30) +
      bytes.readUInt16LE(offset + 32);
  }
  throw new Error("Workbook entry missing");
}
for (const format of ["CSV", "XLSX", "DOCX", "HTML"] as const) {
  test(`${format} report is generated, delivered and downloaded intact`, async ({
    page,
  }) => {
    test.setTimeout(90000);
    await login(page);
    await page.goto("/jobs/new");
    await page.getByLabel("Output format").selectOption(format);
    await page
      .getByLabel("Cloud destination")
      .selectOption(
        format === "CSV" || format === "HTML" ? "local-gcs" : "local-s3",
      );
    await page
      .getByLabel("Report title")
      .fill(format === "HTML" ? "<script>alert(1)</script> & review" : "=1+1");
    await page
      .getByRole("button", { name: "Submit report", exact: true })
      .click();
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
      timeout: 60000,
    });
    const id = page.url().split("/").at(-1);
    const job = await (
      await page.request.get(`/backend/api/v1/jobs/${id}`)
    ).json();
    expect(job.artifacts[0].format).toBe(format);
    await expect(
      page.getByRole("link", { name: `Download ${format}`, exact: true }),
    ).toBeVisible();
    const response = await page.request.get(
      `/backend/api/v1/jobs/${id}/output`,
    );
    expect(response.status()).toBe(200);
    const bytes = await response.body();
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(
      job.artifacts[0].sha256,
    );
    if (format === "CSV") {
      expect(response.headers()["content-type"]).toContain("text/csv");
      expect(bytes.toString()).toContain("'=1+1");
      expect(bytes.toString()).toContain("Atlas notebook");
    } else if (format === "XLSX") {
      expect(response.headers()["content-type"]).toBe(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      );
      const strings = workbookEntry(bytes, "xl/sharedStrings.xml");
      expect(strings).toContain("=1+1");
      expect(strings).toContain("Atlas notebook");
      expect(workbookEntry(bytes, "xl/worksheets/sheet1.xml")).not.toContain(
        "<f>",
      );
    }
    if (format === "DOCX") {
      expect(response.headers()["content-type"]).toBe(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      );
      const document = workbookEntry(bytes, "word/document.xml");
      expect(document).toContain("=1+1");
      expect(document).toContain("Atlas notebook");
      expect(document).toContain("240.00");
      expect(workbookEntry(bytes, "[Content_Types].xml")).toContain(
        "wordprocessingml",
      );
    }
    if (format === "HTML") {
      expect(response.headers()["content-type"]).toBe("application/zip");
      expect(response.headers()["content-disposition"]).toBe(
        `attachment; filename=report-${id}.html.zip`,
      );
      expect(response.headers()["x-content-type-options"]).toBe("nosniff");
      const html = workbookEntry(bytes, "index.html");
      expect(html).toContain("&lt;script&gt;");
      expect(html).toContain("Atlas notebook");
      expect(html).toContain("default-src 'none'");
      expect(html).not.toContain("<script");
      // Parse in an inert document, never attach report HTML to the trusted application DOM.
      const text = await page.evaluate(
        (html) =>
          new DOMParser().parseFromString(html, "text/html").body.textContent,
        html,
      );
      expect(text).toContain("<script>alert(1)</script> & review");
      expect(text).toContain("240.00");
    }
    const key = job.deliveries[0].object_key;
    const cloud = await page.request.get(
      format === "CSV" || format === "HTML"
        ? `http://localhost:4443/storage/v1/b/report-results/o/${encodeURIComponent(key)}?alt=media`
        : `http://localhost:9000/report-results/${key}`,
    );
    expect(cloud.status()).toBe(200);
    expect(
      createHash("sha256")
        .update(await cloud.body())
        .digest("hex"),
    ).toBe(job.artifacts[0].sha256);
  });
}

test("version 2 form and editor deliver all five formats through both providers", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page
    .getByLabel("Report title")
    .fill("Multiple outputs browser verification");
  await page
    .getByLabel("Multiple outputs and destinations", { exact: true })
    .check();
  await page
    .getByRole("combobox", { name: "XLSX output", exact: true })
    .selectOption("required");
  await page
    .getByRole("combobox", { name: "CSV output", exact: true })
    .selectOption("optional");
  await page
    .getByRole("combobox", { name: "DOCX output", exact: true })
    .selectOption("required");
  await page
    .getByRole("combobox", { name: "HTML output", exact: true })
    .selectOption("optional");
  await page.getByLabel("XLSX to mapping 1", { exact: true }).check();
  await page.getByLabel("DOCX to mapping 1", { exact: true }).check();
  await page
    .getByRole("button", { name: "Add delivery mapping", exact: true })
    .click();
  await page.getByLabel("Destination for mapping 2").selectOption("local-gcs");
  const accessibility = await new AxeBuilder({ page }).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  const configuration = JSON.parse(
    await page.getByLabel("Declarative configuration").inputValue(),
  );
  expect(configuration.schemaVersion).toBe("2");
  expect(configuration.outputs).toEqual(["PDF", "XLSX", "CSV", "DOCX", "HTML"]);
  expect(configuration.optionalOutputs).toEqual(["CSV", "HTML"]);
  expect(configuration.deliveries).toEqual([
    {
      destinationRef: "local-s3",
      outputs: ["PDF", "XLSX", "DOCX"],
      required: true,
    },
    {
      destinationRef: "local-gcs",
      outputs: ["PDF", "XLSX", "CSV", "DOCX", "HTML"],
      required: false,
    },
  ]);
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(
    page.getByRole("combobox", { name: "CSV output", exact: true }),
  ).toHaveValue("optional");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const id = page.url().split("/").at(-1);
  const job = await (
    await page.request.get(`/backend/api/v1/jobs/${id}`)
  ).json();
  expect(job.attempt).toBe(1);
  expect(job.artifacts).toHaveLength(5);
  expect(job.outputs.map((o: { status: string }) => o.status)).toEqual([
    "AVAILABLE",
    "AVAILABLE",
    "AVAILABLE",
    "AVAILABLE",
    "AVAILABLE",
  ]);
  expect(job.deliveries).toHaveLength(8);
  for (const artifact of job.artifacts) {
    const link = page.getByRole("link", {
      name: `Download ${artifact.format}`,
      exact: true,
    });
    await expect(link).toHaveAttribute(
      "href",
      `/backend/api/v1/jobs/${id}/outputs/${artifact.id}`,
    );
    const response = await page.request.get((await link.getAttribute("href"))!);
    expect(response.status()).toBe(200);
    const bytes = await response.body();
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(
      artifact.sha256,
    );
    if (artifact.format === "CSV")
      expect(bytes.toString()).toContain("Atlas notebook");
    if (artifact.format === "XLSX")
      expect(workbookEntry(bytes, "xl/sharedStrings.xml")).toContain(
        "Multiple outputs browser verification",
      );
    if (artifact.format === "PDF")
      expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
    for (const delivery of job.deliveries.filter(
      (d: { artifact_id: string }) => d.artifact_id === artifact.id,
    )) {
      expect(delivery.status).toBe("VERIFIED");
      expect(delivery.id).not.toBe(artifact.id);
      const cloud = await page.request.get(
        delivery.destination_ref === "local-s3"
          ? `http://localhost:9000/report-results/${delivery.object_key}`
          : `http://localhost:4443/storage/v1/b/report-results/o/${encodeURIComponent(delivery.object_key)}?alt=media`,
      );
      expect(cloud.status()).toBe(200);
      expect(
        createHash("sha256")
          .update(await cloud.body())
          .digest("hex"),
      ).toBe(artifact.sha256);
    }
  }
  expect(
    (await page.request.get(`/backend/api/v1/jobs/${id}/output`)).status(),
  ).toBe(409);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test("YAML editor validates safely and generates a real delivered CSV", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page.getByLabel("Report title").fill("YAML browser verification");
  await page.getByLabel("Output format").selectOption("CSV");
  await page.getByLabel("Cloud destination").selectOption("local-gcs");
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  await page.getByLabel("Configuration syntax").selectOption("yaml");
  await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
  await expect(page.getByLabel("Declarative configuration")).toContainText(
    "schemaVersion:",
  );
  const editor = page.getByLabel("Declarative configuration");
  const valid = await editor.inputValue();
  await editor.fill(valid + "\ndestinationRef: local-s3\n");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(
    page
      .getByRole("group", { name: "Report configuration" })
      .getByRole("alert"),
  ).toContainText("Malformed configuration");
  await expect(page).toHaveURL(/\/jobs\/new$/);
  await expect(editor).toHaveValue(valid + "\ndestinationRef: local-s3\n");
  await editor.fill("# Human-authored YAML\n" + valid);
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(page.getByLabel("Report title")).toHaveValue(
    "YAML browser verification",
  );
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  await page.getByLabel("Configuration syntax").selectOption("yaml");
  await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
  await expect(
    page.getByRole("button", { name: "Submit report", exact: true }),
  ).toBeEnabled();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  const accepted = page.waitForRequest(
    (r) => r.url().endsWith("/api/v1/jobs") && r.method() === "POST",
  );
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  expect((await accepted).headers()["content-type"]).toBe("application/yaml");
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const id = page.url().split("/").at(-1);
  const job = await (
    await page.request.get(`/backend/api/v1/jobs/${id}`)
  ).json();
  const bytes = await (
    await page.request.get(`/backend/api/v1/jobs/${id}/output`)
  ).body();
  expect(bytes.toString()).toContain("YAML browser verification");
  expect(bytes.toString()).toContain("Atlas notebook");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  const cloud = await page.request.get(
    `http://localhost:4443/storage/v1/b/report-results/o/${encodeURIComponent(job.deliveries[0].object_key)}?alt=media`,
  );
  expect(cloud.status()).toBe(200);
  expect(
    createHash("sha256")
      .update(await cloud.body())
      .digest("hex"),
  ).toBe(job.artifacts[0].sha256);
});

test("lost acceptance acknowledgement recovers the same job after switching JSON to YAML", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page.getByLabel("Report title").fill("Lost acknowledgement recovery");
  const acceptedIds: string[] = [];
  const keys: string[] = [];
  await page.route("**/backend/api/v1/jobs", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    keys.push(route.request().headers()["idempotency-key"]);
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    acceptedIds.push((await response.json()).id);
    if (acceptedIds.length === 1) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(
    page
      .getByRole("group", { name: "Report configuration" })
      .getByRole("alert"),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/jobs\/new$/);
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  await page.getByLabel("Configuration syntax").selectOption("yaml");
  await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
  await expect(
    page.getByRole("button", { name: "Submit report", exact: true }),
  ).toBeEnabled();
  const editor = page.getByLabel("Declarative configuration");
  await editor.fill(
    "# Only encoding changed; retry must not generate another job\n" +
      (await editor.inputValue()),
  );
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  expect(acceptedIds).toHaveLength(2);
  expect(acceptedIds[1]).toBe(acceptedIds[0]);
  expect(keys[1]).toBe(keys[0]);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(`/backend/api/v1/jobs/${acceptedIds[0]}`)
  ).json();
  expect(job.attempt).toBe(1);
  expect(job.executions).toHaveLength(1);
});

test("administrator versions a definition and browser generates its pinned revision", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  const id = `browser-definition-${randomUUID()}`;
  await page.goto("/definitions");
  const form = page.getByRole("group", { name: "Definition configuration" });
  await form.getByLabel("Definition ID").fill(id);
  await form.getByLabel("Definition name").fill("Versioned browser sales");
  await form
    .getByLabel("Description", { exact: true })
    .fill("Original published description");
  await form
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await expect(
    page.getByRole("heading", { name: "Version 1 · Versioned browser sales" }),
  ).toBeVisible();
  await page.getByLabel("Definition name").fill("Updated browser sales");
  await page
    .getByRole("button", { name: "Publish version", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Version 2 · Updated browser sales" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Version 1 · Versioned browser sales" }),
  ).toBeVisible();
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await expect(
    page.getByRole("combobox", { name: "Report definition", exact: true }),
  ).toHaveValue(`${id}:1`);
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  let configuration = JSON.parse(
    await page.getByLabel("Declarative configuration").inputValue(),
  );
  expect(configuration.definitionId).toBe(id);
  expect(configuration.definitionVersion).toBe(1);
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(
    page.getByRole("combobox", { name: "Report definition", exact: true }),
  ).toHaveValue(`${id}:1`);
  await page.getByLabel("Report title").fill("Pinned browser revision");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  configuration = JSON.parse(job.configuration);
  expect(configuration.definition.name).toBe("Versioned browser sales");
  expect(configuration.definition.version).toBe(1);
  const result = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  const bytes = await result.body();
  expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(job.deliveries[0].status).toBe("VERIFIED");
  await page.goto(`/definitions/${id}`);
  await page.getByLabel("Accept new jobs").uncheck();
  await page
    .getByRole("button", { name: "Publish version", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Version 3 · Updated browser sales" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Generate version 1", exact: true }),
  ).toHaveCount(0);
  await page.goto(`/jobs/new?definition=${id}&version=1`);
  await expect(
    page.getByRole("button", { name: "Submit report", exact: true }),
  ).toHaveCount(0);
});

test("reprocessing recovers a lost acknowledgement and creates one linked execution", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page.getByLabel("Report title").fill("Linked reprocessing report");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const parentId = page.url().split("/").at(-1)!;
  const parent = await (
    await page.request.get(`/backend/api/v1/jobs/${parentId}`)
  ).json();
  const parentBytes = await (
    await page.request.get(
      `/backend/api/v1/jobs/${parentId}/outputs/${parent.artifacts[0].id}`,
    )
  ).body();
  const ids: string[] = [];
  const keys: string[] = [];
  await page.route(
    `**/backend/api/v1/jobs/${parentId}/reprocess`,
    async (route) => {
      keys.push(route.request().headers()["idempotency-key"]);
      const response = await route.fetch();
      expect(response.status()).toBe(202);
      ids.push((await response.json()).id);
      if (ids.length === 1) await route.abort("failed");
      else await route.fulfill({ response });
    },
  );
  const region = page.getByRole("region", {
    name: "Reprocessing",
    exact: true,
  });
  await region
    .getByRole("button", { name: "Reprocess as new job", exact: true })
    .click();
  await expect(region.getByRole("alert")).toBeVisible();
  await expect(
    region.getByRole("link", { name: ids[0], exact: true }),
  ).toBeVisible();
  await region
    .getByRole("button", { name: "Reprocess as new job", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/jobs/${ids[0]}$`));
  expect(ids).toHaveLength(2);
  expect(ids[1]).toBe(ids[0]);
  expect(keys[1]).toBe(keys[0]);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  await expect(
    page.getByRole("link", { name: parentId, exact: true }),
  ).toBeVisible();
  const child = await (
    await page.request.get(`/backend/api/v1/jobs/${ids[0]}`)
  ).json();
  expect(child.parent_job_id).toBe(parentId);
  expect(child.id).not.toBe(parent.id);
  expect(child.batch_execution_id).not.toBe(parent.batch_execution_id);
  expect(child.attempt).toBe(1);
  expect(child.executions).toHaveLength(1);
  expect(child.artifacts[0].id).not.toBe(parent.artifacts[0].id);
  expect(child.deliveries[0].object_key).not.toBe(
    parent.deliveries[0].object_key,
  );
  expect(child.deliveries[0].status).toBe("VERIFIED");
  expect(JSON.parse(child.configuration).request).toEqual(
    JSON.parse(parent.configuration).request,
  );
  const childBytes = await (
    await page.request.get(
      `/backend/api/v1/jobs/${child.id}/outputs/${child.artifacts[0].id}`,
    )
  ).body();
  expect(childBytes.subarray(0, 5).toString()).toBe("%PDF-");
  expect(createHash("sha256").update(childBytes).digest("hex")).toBe(
    child.artifacts[0].sha256,
  );
  const retained = await (
    await page.request.get(
      `/backend/api/v1/jobs/${parentId}/outputs/${parent.artifacts[0].id}`,
    )
  ).body();
  expect(retained.equals(parentBytes)).toBe(true);
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.getByRole("link", { name: parentId, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/jobs/${parentId}$`));
  await expect(
    page
      .getByRole("region", { name: "Reprocessing", exact: true })
      .getByRole("link", { name: child.id, exact: true }),
  ).toBeVisible();
  const history = await (
    await page.request.get(`/backend/api/v1/jobs/${parentId}/reprocessings`)
  ).json();
  expect(history.total).toBe(1);
});

test("trusted administrator registers JRXML and generates its actual branded output", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/templates");
  const form = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await form.getByLabel("Template name").fill("Browser trusted branding");
  await form.getByLabel("JRXML file").setInputFiles({
    name: "invalid.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from("<jasperReport/>"),
  });
  await form
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await form
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(form.getByRole("alert")).toContainText(
    "validation or compilation failed",
  );
  const marker = `Trusted branding ${randomUUID()}`;
  const source = (
    await readFile(
      "services/report-core/src/main/resources/templates/sales-summary.jrxml",
      "utf8",
    )
  ).replace("Sales summary | Synthetic demonstration data | USD | UTC", marker);
  const sha = createHash("sha256").update(source).digest("hex");
  await form.getByLabel("JRXML file").setInputFiles({
    name: "reviewed.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await form
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(
    new RegExp(`/definitions\\?template=sha256:${sha}$`),
  );
  const id = `browser-template-${randomUUID()}`;
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await expect(definition.getByLabel("Template reference")).toHaveValue(
    `sha256:${sha}`,
  );
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Branded browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Report title").fill("Trusted template report");
  await page.getByLabel("Output format").selectOption("CSV");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  expect(JSON.parse(job.configuration).definition.template_resource).toBe(
    `sha256:${sha}`,
  );
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  const bytes = await output.body();
  expect(bytes.toString()).toContain(marker);
  expect(bytes.toString()).toContain("Atlas notebook");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(job.deliveries[0].status).toBe("VERIFIED");
  await page.goto("/templates");
  const article = page
    .getByRole("article")
    .filter({ hasText: `sha256:${sha}` });
  await expect(article).toBeVisible();
  const download = await page.request.get(
    `/backend/api/v1/templates/${sha}/source`,
  );
  expect(download.headers()["x-content-type-options"]).toBe("nosniff");
  expect(await download.text()).toBe(source);
});

test("registered structured rows drive a delivered report through the browser", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/data-sources");
  const form = page.getByRole("group", {
    name: "Structured source configuration",
  });
  await form.getByLabel("Source name").fill("Browser structured data");
  await form.getByLabel("Rows (JSON)").fill("[]");
  await form
    .getByRole("button", { name: "Validate and register source", exact: true })
    .click();
  await expect(form.getByRole("alert")).toContainText(
    "Invalid structured data",
  );
  const product = `Orchid ${randomUUID().slice(0, 8)}`;
  const rows = [
    { product, quantity: 9, amount: 432.1 },
    { product: "  =1+1", quantity: 2, amount: 12.34 },
  ];
  await form.getByLabel("Rows (JSON)").fill(JSON.stringify(rows, null, 2));
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await form
    .getByRole("button", { name: "Validate and register source", exact: true })
    .click();
  await expect(page).toHaveURL(/\/definitions\?dataSource=data:[0-9a-f]{64}$/);
  const reference = new URL(page.url()).searchParams.get("dataSource")!;
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await expect(definition.getByLabel("Data source reference")).toHaveValue(
    reference,
  );
  const id = `browser-data-${randomUUID()}`;
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Browser custom rows");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Output format").selectOption("CSV");
  await page.getByLabel("Cloud destination").selectOption("local-gcs");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).datasetVersion).toBe(reference);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  const bytes = await output.body();
  expect(bytes.toString()).toContain(product);
  expect(bytes.toString()).toContain("432.10");
  expect(bytes.toString()).toContain("'  =1+1");
  expect(bytes.toString()).not.toContain("Atlas notebook");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(job.deliveries[0].status).toBe("VERIFIED");
  await page.goto("/data-sources");
  await expect(
    page.getByRole("article").filter({ hasText: reference }),
  ).toContainText("2 rows");
});

test("locale settings survive editor round trips and format delivered output", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.goto("/jobs/new");
  await page
    .getByRole("combobox", { name: "Report locale", exact: true })
    .selectOption("de-DE");
  await page
    .getByRole("combobox", { name: "Report time zone", exact: true })
    .selectOption("Europe/Berlin");
  await page.getByLabel("Output format").selectOption("CSV");
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  const editor = page.getByLabel("Declarative configuration");
  const request = JSON.parse(await editor.inputValue());
  expect(request.parameters.locale).toBe("de-DE");
  expect(request.parameters.timeZone).toBe("Europe/Berlin");
  await editor.fill(
    JSON.stringify({
      ...request,
      parameters: { ...request.parameters, locale: "unsupported" },
    }),
  );
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(
    page
      .getByRole("group", { name: "Report configuration" })
      .getByRole("alert"),
  ).toContainText("locale");
  await editor.fill(JSON.stringify(request));
  await page.getByLabel("Configuration syntax").selectOption("yaml");
  await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(
    page.getByRole("combobox", { name: "Report locale", exact: true }),
  ).toHaveValue("de-DE");
  await expect(
    page.getByRole("combobox", { name: "Report time zone", exact: true }),
  ).toHaveValue("Europe/Berlin");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  const snapshot = JSON.parse(job.configuration);
  expect(snapshot.locale).toBe("de-DE");
  expect(snapshot.timezone).toBe("Europe/Berlin");
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  const bytes = await output.body();
  expect(bytes.toString()).toContain("240,00");
  expect(bytes.toString()).toContain("90,00");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(job.deliveries[0].status).toBe("VERIFIED");
});

test("job history filters persist in URLs and reject invalid date ranges", async ({
  page,
}) => {
  await login(page);
  await page.goto("/jobs");
  await page.getByLabel("Definition ID", { exact: true }).fill("sales-summary");
  await page.getByLabel("Accepted from (UTC)").fill("2000-01-01");
  await page.getByLabel("Accepted before (UTC)").fill("2099-01-01");
  await page
    .getByRole("combobox", { name: "Sort", exact: true })
    .selectOption("oldest");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(page).toHaveURL(/definitionId=sales-summary/);
  await expect(page).toHaveURL(/sort=oldest/);
  const shared = page.url();
  await page.goto(shared);
  await expect(page.getByLabel("Accepted from (UTC)")).toHaveValue(
    "2000-01-01",
  );
  await expect(
    page.getByRole("combobox", { name: "Sort", exact: true }),
  ).toHaveValue("oldest");
  const response = await page.request.get(
    "/backend/api/v1/jobs?definitionId=sales-summary&acceptedFrom=2000-01-01&acceptedBefore=2099-01-01&sort=oldest",
  );
  expect(response.ok()).toBeTruthy();
  const history = await response.json();
  expect(history.total).toBeGreaterThan(0);
  const first = history.items[0];
  await expect(page.getByRole("row").nth(1)).toContainText(
    first.id.slice(0, 8),
  );
  if (history.total > 20) {
    await page.getByRole("link", { name: "Next page", exact: true }).click();
    await expect(page).toHaveURL(/page=1/);
    await expect(page).toHaveURL(/definitionId=sales-summary/);
    await expect(page).toHaveURL(/acceptedFrom=2000-01-01/);
    await expect(page).toHaveURL(/sort=oldest/);
  }
  await page
    .getByLabel("Definition ID", { exact: true })
    .fill("missing-history-definition");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(page.getByText("No jobs match these filters.")).toBeVisible();
  await page.getByLabel("Accepted before (UTC)").fill("1999-01-01");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(
    page.getByText("Unable to load jobs. Check filters or retry."),
  ).toBeVisible();
  const invalid = await page.request.get(
    "/backend/api/v1/jobs?acceptedFrom=2025-02-29",
  );
  expect(invalid.status()).toBe(400);
  await page.getByRole("link", { name: "Clear filters" }).click();
  await expect(page).toHaveURL(/\/jobs$/);
  await expect(
    page.getByRole("heading", { name: "Report jobs" }),
  ).toBeVisible();
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test("JDBC connection check and snapshot import drive a verified report", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/data-sources");
  const profileResponse = await page.request.get("/backend/api/v1/jdbc-source");
  expect(profileResponse.status()).toBe(200);
  const profile = await profileResponse.json();
  expect(profile.adapter).toBe("postgres-sales-v2");
  expect(profile.mappingSha256).toMatch(/^[0-9a-f]{64}$/);
  await expect(page.getByLabel("JDBC mapping fingerprint")).toHaveText(
    `sha256:${profile.mappingSha256}`,
  );
  const form = page.getByRole("group", { name: "JDBC snapshot configuration" });
  await form.getByRole("button", { name: "Test JDBC connection" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Connection and supported columns verified",
  );
  await form.getByLabel("Snapshot name").fill("Browser JDBC snapshot");
  await form.getByLabel("Sales category").fill("missing-category");
  await form
    .getByRole("button", { name: "Import snapshot", exact: true })
    .click();
  await expect(page.getByText(/Invalid structured data/)).toBeVisible();
  await form.getByLabel("Sales category").fill("stationery");
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await form
    .getByRole("button", { name: "Import snapshot", exact: true })
    .click();
  await expect(page).toHaveURL(/\/definitions\?dataSource=data:[0-9a-f]{64}$/);
  const reference = new URL(page.url()).searchParams.get("dataSource")!;
  const imports = await (
    await page.request.get("/backend/api/v1/jdbc-source/imports")
  ).json();
  expect(
    imports.some(
      (item: {
        source_sha256: string;
        mapping_sha256: string;
        adapter_version: string;
      }) =>
        item.source_sha256 === reference.substring(5) &&
        item.mapping_sha256 === profile.mappingSha256 &&
        item.adapter_version === "postgres-sales-v2",
    ),
  ).toBe(true);

  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await expect(definition.getByLabel("Data source reference")).toHaveValue(
    reference,
  );
  const id = `browser-jdbc-${randomUUID()}`;
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Browser JDBC rows");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Output format").selectOption("CSV");
  await page.getByLabel("Cloud destination").selectOption("local-gcs");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).datasetVersion).toBe(reference);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  const bytes = await output.body();
  expect(bytes.toString()).toContain("JDBC amber journal");
  expect(bytes.toString()).toContain("84.25");
  expect(bytes.toString()).toContain("JDBC cobalt pencil");
  expect(bytes.toString()).not.toContain("JDBC desk lamp");
  expect(bytes.toString()).not.toContain("Atlas notebook");
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(
    job.artifacts[0].sha256,
  );
  expect(job.deliveries[0].status).toBe("VERIFIED");
  await page.goto("/data-sources");
  await expect(
    page.getByRole("article").filter({ hasText: reference }),
  ).toContainText("2 rows");
  await expect(
    page.getByRole("heading", { name: "JDBC import history" }),
  ).toBeVisible();
  await expect(
    page.getByRole("list").filter({ hasText: reference }),
  ).toContainText("stationery");
});

test("catalog searches, paginates and preserves definition creation references", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  const me = await (await page.request.get("/backend/api/v1/me")).json();
  const token = `catalog-${randomUUID().slice(0, 8)}`;
  for (let n = 0; n < 23; n++) {
    const response = await page.request.post("/backend/api/v1/definitions", {
      headers: { [me.csrfHeader]: me.csrfToken },
      data: {
        id: `${token}-${String(n).padStart(2, "0")}`,
        configuration: {
          name: `${token} equal`,
          description: "Catalog browser fixture",
          templateResource: "templates/sales-summary.jrxml",
          enabled: n < 21,
        },
      },
    });
    expect(response.status()).toBe(201);
  }
  // A validated-shape reference remains an editable draft; resource existence is checked on save.
  const template = `sha256:${"a".repeat(64)}`;
  const source = `data:${"b".repeat(64)}`;
  await page.goto(
    `/definitions?${new URLSearchParams({ template, dataSource: source })}`,
  );
  await page
    .getByRole("searchbox", { name: "Search definitions" })
    .fill(token.toUpperCase());
  await page
    .getByRole("combobox", { name: "Definition status", exact: true })
    .selectOption("enabled");
  await page
    .getByRole("button", { name: "Search catalog", exact: true })
    .click();
  await expect(
    page.getByText("21 matching definitions", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("article")).toHaveCount(20);
  await page
    .getByRole("link", { name: "Next catalog page", exact: true })
    .click();
  await expect(page).toHaveURL(/page=1/);
  expect(new URL(page.url()).searchParams.get("template")).toBe(template);
  expect(new URL(page.url()).searchParams.get("dataSource")).toBe(source);
  await expect(page.getByRole("article")).toHaveCount(1);
  await expect(page.getByRole("article")).toContainText(`${token}-20`);
  await page.reload();
  await expect(
    page.getByRole("searchbox", { name: "Search definitions" }),
  ).toHaveValue(token.toUpperCase());
  const form = page.getByRole("group", { name: "Definition configuration" });
  await expect(form.getByLabel("Data source reference")).toHaveValue(source);
  await expect(form.getByLabel("Template reference")).toHaveValue(template);
  await page
    .getByRole("combobox", { name: "Definition status", exact: true })
    .selectOption("disabled");
  await page
    .getByRole("combobox", { name: "Catalog order", exact: true })
    .selectOption("updated");
  await page
    .getByRole("button", { name: "Search catalog", exact: true })
    .click();
  await expect(
    page.getByText("2 matching definitions", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("article").first()).toContainText(`${token}-22`);
  await expect(
    page.getByRole("link", { name: "Generate report →", exact: true }),
  ).toHaveCount(0);
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page
    .getByRole("searchbox", { name: "Search definitions" })
    .fill(`${token}-missing`);
  await page
    .getByRole("button", { name: "Search catalog", exact: true })
    .click();
  await expect(
    page.getByText("No report definitions match these filters."),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Clear catalog filters", exact: true })
    .click();
  await expect(
    page.getByRole("searchbox", { name: "Search definitions" }),
  ).toHaveValue("");
  expect(new URL(page.url()).searchParams.get("dataSource")).toBe(source);
  await page.goto("/definitions?sort=invalid");
  await expect(
    page.getByRole("heading", { name: "Catalog unavailable" }),
  ).toBeVisible();
});

test("owner previews retained PDF pages and retries a preview failure without regeneration", async ({
  page,
  browser,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  const me = await (await page.request.get("/backend/api/v1/me")).json();
  const sourceResponse = await page.request.post(
    "/backend/api/v1/data-sources",
    {
      headers: { [me.csrfHeader]: me.csrfToken },
      data: {
        name: "Preview pages",
        rows: Array.from({ length: 120 }, (_, n) => ({
          product: `Preview item ${n + 1}`,
          quantity: 1,
          amount: 2.5,
        })),
      },
    },
  );
  expect(sourceResponse.ok()).toBeTruthy();
  const source = await sourceResponse.json();
  const id = `preview-${randomUUID()}`;
  const definition = await page.request.post("/backend/api/v1/definitions", {
    headers: { [me.csrfHeader]: me.csrfToken },
    data: {
      id,
      configuration: {
        name: "PDF preview fixture",
        description: "Synthetic multi-page input",
        templateResource: "templates/sales-summary.jrxml",
        dataSourceRef: `data:${source.sha256}`,
        enabled: true,
      },
    },
  });
  expect(definition.status()).toBe(201);
  await page.goto(`/jobs/new?definition=${id}&version=1`);
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const jobId = page.url().split("/").at(-1)!;
  const before = await (
    await page.request.get(`/backend/api/v1/jobs/${jobId}`)
  ).json();
  const artifact = before.artifacts[0];
  const base = `/backend/api/v1/jobs/${jobId}/outputs/${artifact.id}/preview`;
  const metadata = await (await page.request.get(base)).json();
  expect(metadata.pageCount).toBeGreaterThan(1);
  await page.getByRole("button", { name: "Preview PDF", exact: true }).click();
  const region = page.getByRole("region", {
    name: "PDF page preview",
    exact: true,
  });
  const firstImage = region.getByRole("img", {
    name: `Report PDF preview, page 1 of ${metadata.pageCount}`,
    exact: true,
  });
  await expect(firstImage).toBeVisible();
  await expect
    .poll(() =>
      firstImage.evaluate(
        (element) => (element as HTMLImageElement).naturalWidth,
      ),
    )
    .toBeGreaterThan(700);
  await expect(
    region.getByRole("button", { name: "Previous PDF page" }),
  ).toBeDisabled();
  let failed = false;
  await page.route(
    `**/outputs/${artifact.id}/preview/pages/1`,
    async (route) => {
      if (!failed) {
        failed = true;
        await route.fulfill({
          status: 503,
          contentType: "application/problem+json",
          body: JSON.stringify({ detail: "Synthetic preview interruption" }),
        });
      } else await route.continue();
    },
  );
  await region.getByRole("button", { name: "Next PDF page" }).click();
  await expect(region.getByRole("alert")).toContainText(
    "Synthetic preview interruption",
  );
  await region.getByRole("button", { name: "Retry PDF preview" }).click();
  await expect(
    region.getByRole("img", {
      name: `Report PDF preview, page 2 of ${metadata.pageCount}`,
      exact: true,
    }),
  ).toBeVisible();
  const png = await page.request.get(`${base}/pages/1`);
  expect(png.headers()["content-type"]).toBe("image/png");
  expect(png.headers()["cache-control"]).toBe("no-store");
  expect((await png.body()).subarray(0, 8).toString("hex")).toBe(
    "89504e470d0a1a0a",
  );
  await expect(page).toHaveTitle("Report Engine");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page
    .getByRole("button", { name: "Close PDF preview", exact: true })
    .click();
  await expect(region).toHaveCount(0);
  const after = await (
    await page.request.get(`/backend/api/v1/jobs/${jobId}`)
  ).json();
  expect(after.attempt).toBe(before.attempt);
  expect(after.artifacts).toEqual(before.artifacts);
  expect(after.deliveries).toEqual(before.deliveries);
  const other = await browser.newPage();
  try {
    await login(other);
    expect((await other.request.get(base)).status()).toBe(404);
    expect((await other.request.get(`${base}/pages/0`)).status()).toBe(404);
  } finally {
    await other.close();
  }
});

for (const variant of ["CSV", "XLSX", "CSV with BOM"] as const) {
  test(`output settings survive form JSON YAML and govern delivered ${variant}`, async ({
    page,
  }) => {
    test.setTimeout(90000);
    const outputFormat = variant === "XLSX" ? "XLSX" : "CSV";
    const includeBom = variant === "CSV with BOM";
    const title = includeBom ? "=Résumé;東京" : "=Output;settings";
    await login(page);
    await page.goto("/jobs/new");
    await page.getByLabel("Report title", { exact: true }).fill(title);
    await page.getByLabel("Output format").selectOption("CSV");
    await page.getByLabel("CSV field delimiter").selectOption("SEMICOLON");
    if (includeBom)
      await page.getByLabel("CSV UTF-8 byte-order mark").selectOption("false");
    await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
    const editor = page.getByLabel("Declarative configuration");
    const configured = JSON.parse(await editor.inputValue());
    expect(configured.outputOptions).toEqual(
      includeBom
        ? { csvDelimiter: "SEMICOLON", csvIncludeBom: false }
        : { csvDelimiter: "SEMICOLON" },
    );
    await editor.fill(
      JSON.stringify({
        ...configured,
        outputOptions: { csvDelimiter: "PIPE" },
      }),
    );
    await page.getByRole("button", { name: "Use parameter form" }).click();
    await expect(
      page
        .getByRole("group", { name: "Report configuration" })
        .getByRole("alert"),
    ).toContainText("csvDelimiter");
    if (includeBom) {
      await editor.fill(
        JSON.stringify({
          ...configured,
          outputOptions: { ...configured.outputOptions, csvIncludeBom: "true" },
        }),
      );
      await page.getByRole("button", { name: "Use parameter form" }).click();
      await expect(
        page
          .getByRole("group", { name: "Report configuration" })
          .getByRole("alert"),
      ).toContainText("Malformed configuration");
    }
    await editor.fill(JSON.stringify(configured));
    await page.getByLabel("Configuration syntax").selectOption("yaml");
    await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
    await page.getByRole("button", { name: "Use parameter form" }).click();
    await expect(page.getByLabel("CSV field delimiter")).toHaveValue(
      "SEMICOLON",
    );
    if (includeBom)
      await expect(page.getByLabel("CSV UTF-8 byte-order mark")).toHaveValue(
        "false",
      );
    // Settings for deselected formats do not leak into another output request.
    await page.getByLabel("Output format").selectOption("XLSX");
    await page.getByLabel("XLSX worksheet layout").selectOption("true");
    await page
      .getByLabel("XLSX worksheet name (optional)")
      .fill("Quarterly Sales");
    await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
    expect(JSON.parse(await editor.inputValue()).outputOptions).toEqual({
      xlsxOnePagePerSheet: true,
      xlsxSheetName: "Quarterly Sales",
    });
    const xlsxRequest = JSON.parse(await editor.inputValue());
    await editor.fill(
      JSON.stringify({
        ...xlsxRequest,
        outputOptions: {
          ...xlsxRequest.outputOptions,
          xlsxSheetName: "Bad/Name",
        },
      }),
    );
    await page.getByRole("button", { name: "Use parameter form" }).click();
    await expect(
      page
        .getByRole("group", { name: "Report configuration" })
        .getByRole("alert"),
    ).toContainText("xlsxSheetName");
    await editor.fill(JSON.stringify(xlsxRequest));
    await page.getByLabel("Configuration syntax").selectOption("yaml");
    await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
    await page.getByRole("button", { name: "Use parameter form" }).click();
    await expect(page.getByLabel("XLSX worksheet layout")).toHaveValue("true");
    await expect(page.getByLabel("XLSX worksheet name (optional)")).toHaveValue(
      "Quarterly Sales",
    );
    if (outputFormat === "CSV") {
      await page.getByLabel("Output format").selectOption("CSV");
      // Returning from an XLSX-only declarative request restores only that request's options.
      await expect(page.getByLabel("CSV field delimiter")).toHaveValue("");
      await page.getByLabel("CSV field delimiter").selectOption("SEMICOLON");
      await expect(page.getByLabel("CSV UTF-8 byte-order mark")).toHaveValue(
        "",
      );
      if (includeBom) {
        await page.getByLabel("CSV UTF-8 byte-order mark").selectOption("true");
        await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
        await page.getByLabel("Configuration syntax").selectOption("yaml");
        await expect(page.getByLabel("Configuration syntax")).toHaveValue(
          "yaml",
        );
        await page.getByRole("button", { name: "Use parameter form" }).click();
        await expect(page.getByLabel("CSV UTF-8 byte-order mark")).toHaveValue(
          "true",
        );
      }
    }
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    await page
      .getByRole("button", { name: "Submit report", exact: true })
      .click();
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
      timeout: 60000,
    });
    const job = await (
      await page.request.get(
        `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
      )
    ).json();
    expect(JSON.parse(job.configuration).request.outputOptions).toEqual(
      outputFormat === "CSV"
        ? {
            csvDelimiter: "SEMICOLON",
            ...(includeBom ? { csvIncludeBom: true } : {}),
          }
        : { xlsxOnePagePerSheet: true, xlsxSheetName: "Quarterly Sales" },
    );
    const output = await page.request.get(
      `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
    );
    expect(output.status()).toBe(200);
    const bytes = await output.body();
    if (outputFormat === "CSV") {
      const bom = Buffer.from([0xef, 0xbb, 0xbf]);
      expect(bytes.subarray(0, 3).equals(bom)).toBe(includeBom);
      expect(bytes.toString()).toContain("\"'" + title + '"');
      expect(bytes.toString()).toContain(";240.00");
      expect(bytes.toString()).toContain("\r\n");
    } else {
      expect(workbookEntry(bytes, "xl/workbook.xml")).toContain(
        'name="Quarterly Sales"',
      );
      expect(workbookEntry(bytes, "xl/sharedStrings.xml")).toContain(
        "=Output;settings",
      );
      expect(workbookEntry(bytes, "xl/worksheets/sheet1.xml")).not.toContain(
        "<f>",
      );
    }
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(
      job.artifacts[0].sha256,
    );
    expect(job.deliveries[0].status).toBe("VERIFIED");
    const cloud = await page.request.get(
      `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
    );
    expect(cloud.status()).toBe(200);
    expect(await cloud.body()).toEqual(bytes);
  });
}

test("typed template parameters load recover and survive JSON YAML before real delivery", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  const id = `browser-typed-${randomUUID()}`;
  const source = (
    await readFile("examples/typed-parameters.jrxml", "utf8")
  ).replace("Sales summary | Synthetic demonstration data | USD | UTC", id);
  await page.goto("/templates");
  const upload = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await upload.getByLabel("Template name").fill("Typed browser fixture");
  await upload.getByLabel("JRXML file").setInputFiles({
    name: "typed.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await upload
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  const sha = createHash("sha256").update(source).digest("hex");
  await expect(page).toHaveURL(
    new RegExp(`/definitions\\?template=sha256:${sha}$`),
  );
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Typed browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  const metadata = `**/backend/api/v1/definitions/${id}/versions/1/parameters`;
  await page.route(metadata, (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/problem+json",
      body: '{"status":503}',
    }),
  );
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await expect(
    page.getByText("Template parameters unavailable. Retry to load the form."),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Submit report", exact: true }),
  ).toBeDisabled();
  await page.unroute(metadata);
  await page.getByRole("button", { name: "Retry parameter loading" }).click();
  const fields = page.getByRole("group", {
    name: "Template parameters",
    exact: true,
  });
  await expect(fields).toBeVisible();
  await fields.getByLabel("P_NOTE (TEXT)").fill("=Typed;note");
  await fields.getByLabel("P_COUNT (INTEGER)").fill("7");
  await fields.getByLabel("P_RATE (DECIMAL)").fill("12.3400");
  await fields.getByLabel("P_APPROVED (BOOLEAN)").selectOption("false");
  await fields.getByLabel("P_AS_OF (DATE)").fill("2024-03-10");
  await page.getByLabel("Report time zone").selectOption("America/New_York");
  await page.getByLabel("Output format").selectOption("CSV");
  await page.getByLabel("CSV field delimiter").selectOption("SEMICOLON");
  await page.getByRole("button", { name: "Edit JSON / YAML" }).click();
  const editor = page.getByLabel("Declarative configuration");
  const request = JSON.parse(await editor.inputValue());
  expect(request.parameters.values.P_APPROVED).toEqual({
    type: "BOOLEAN",
    value: false,
  });
  expect(request.parameters.values.P_COUNT).toEqual({
    type: "INTEGER",
    value: 7,
  });
  expect(request.parameters.values.P_RATE).toEqual({
    type: "DECIMAL",
    value: "12.3400",
  });
  await editor.fill(
    JSON.stringify({
      ...request,
      parameters: {
        ...request.parameters,
        values: {
          ...request.parameters.values,
          P_EXTRA: { type: "TEXT", value: "undeclared" },
        },
      },
    }),
  );
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(
    page
      .getByRole("group", { name: "Report configuration" })
      .getByRole("alert"),
  ).toContainText("do not match the selected template");
  await editor.fill(JSON.stringify(request));
  await page.getByLabel("Configuration syntax").selectOption("yaml");
  await expect(page.getByLabel("Configuration syntax")).toHaveValue("yaml");
  await page.getByRole("button", { name: "Use parameter form" }).click();
  await expect(fields.getByLabel("P_RATE (DECIMAL)")).toHaveValue("12.3400");
  await expect(fields.getByLabel("P_APPROVED (BOOLEAN)")).toHaveValue("false");
  await expect(fields.getByLabel("P_COUNT (INTEGER)")).toHaveValue("7");
  await expect(fields.getByLabel("P_AS_OF (DATE)")).toHaveValue("2024-03-10");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).request.parameters.values).toEqual(
    request.parameters.values,
  );
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  const download = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  expect(download.status()).toBe(200);
  const bytes = await download.body();
  expect(bytes.toString()).toContain('"\'=Typed;note"');
  expect(bytes.toString()).toContain("Count=7:Integer");
  expect(bytes.toString()).toContain("Rate=12.3400:BigDecimal");
  expect(bytes.toString()).toContain("Approved=false:Boolean");
  expect(bytes.toString()).toContain("2024-03-10 00:00 -05:00");
  expect(job.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
});

test("PNG dependency closure registers downloads and renders real HTML image assets", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/templates");
  const id = `browser-png-${randomUUID()}`;
  const source = (await readFile("examples/png-assets.jrxml", "utf8")).replace(
    "Sales summary | Synthetic demonstration data | USD | UTC",
    id,
  );
  const image = await readFile("examples/logo.png");
  const upload = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await upload.getByLabel("Template name").fill("PNG browser closure");
  await upload.getByLabel("JRXML file").setInputFiles({
    name: "logo-report.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await upload
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(upload.getByRole("alert")).toContainText(
    "validation or compilation failed",
  );
  await upload
    .getByLabel("Template assets (PNG / JRTX / JRXML / TTF, optional)")
    .setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: image });
  await expect(
    page.getByRole("list", { name: "Selected template assets" }),
  ).toContainText("assets/logo.png");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(/\/definitions\?template=sha256:[0-9a-f]{64}$/);
  const sha = new URL(page.url()).searchParams.get("template")!.substring(7);
  expect(sha).not.toBe(createHash("sha256").update(source).digest("hex"));
  const bundle = await page.request.get(
    `/backend/api/v1/templates/${sha}/bundle`,
  );
  expect(bundle.status()).toBe(200);
  expect(bundle.headers()["content-type"]).toBe("application/zip");
  const archive = await bundle.body();
  expect(zipEntryBytes(archive, "report.jrxml")).toEqual(Buffer.from(source));
  expect(zipEntryBytes(archive, "assets/logo.png")).toEqual(image);
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("PNG browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Output format").selectOption("HTML");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  expect(output.status()).toBe(200);
  const bytes = await output.body();
  const html = workbookEntry(bytes, "index.html");
  expect(html).toContain(id);
  const names = [...html.matchAll(/src="(assets\/[^\"]+)"/g)].map((m) => m[1]);
  expect(names.length).toBeGreaterThan(0);
  const emitted = zipEntryBytes(bytes, names[0]);
  const pixel = await page.evaluate(async (data) => {
    const url = URL.createObjectURL(
      new Blob([new Uint8Array(data)], { type: "image/png" }),
    );
    try {
      const image = new Image();
      image.src = url;
      await image.decode();
      const canvas = document.createElement("canvas");
      canvas.width = image.width;
      canvas.height = image.height;
      const context = canvas.getContext("2d")!;
      context.drawImage(image, 0, 0);
      return Array.from(context.getImageData(0, 0, 1, 1).data);
    } finally {
      URL.revokeObjectURL(url);
    }
  }, Array.from(emitted));
  expect(pixel).toEqual([255, 0, 0, 255]);
  expect(job.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
});

test("lost JDBC import acknowledgement recovers one immutable import with the same key", async ({
  page,
}) => {
  await login(page, "operator");
  await page.goto("/data-sources");
  const previous = await (
    await page.request.get("/backend/api/v1/jdbc-source/imports")
  ).json();
  const previousIds = new Set(previous.map((item: { id: string }) => item.id));
  const form = page.getByRole("group", { name: "JDBC snapshot configuration" });
  await form.getByLabel("Snapshot name").fill(`Recovered JDBC ${randomUUID()}`);
  await form.getByLabel("Sales category").fill("stationery");
  let accepted: { sha256: string; importId: string } | undefined;
  let key: string | undefined;
  const routePattern = "**/backend/api/v1/jdbc-source/imports";
  await page.route(routePattern, async (route) => {
    key = route.request().headers()["idempotency-key"];
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    const response = await route.fetch();
    expect(response.ok()).toBeTruthy();
    expect(response.headers()["idempotency-replayed"]).toBe("false");
    accepted = await response.json();
    await route.fulfill({
      status: 503,
      contentType: "application/problem+json",
      body: JSON.stringify({
        detail: "Import acknowledgement interrupted. Retry unchanged fields.",
      }),
    });
  });
  await form
    .getByRole("button", { name: "Import snapshot", exact: true })
    .click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "Import acknowledgement interrupted" }),
  ).toBeVisible();
  expect(accepted?.importId).toMatch(/^[0-9a-f-]{36}$/);
  await page.unroute(routePattern);
  await page.route(routePattern, async (route) => {
    expect(route.request().headers()["idempotency-key"]).toBe(key);
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    expect(response.headers()["idempotency-replayed"]).toBe("true");
    const recovered = await response.json();
    expect(recovered.importId).toBe(accepted!.importId);
    expect(recovered.sha256).toBe(accepted!.sha256);
    await route.fulfill({ response });
  });
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await form
    .getByRole("button", { name: "Import snapshot", exact: true })
    .click();
  await expect(page).toHaveURL(
    new RegExp(`/definitions\\?dataSource=data:${accepted!.sha256}$`),
  );
  await expect(page.getByLabel("Data source reference")).toHaveValue(
    `data:${accepted!.sha256}`,
  );
  const history = await (
    await page.request.get("/backend/api/v1/jdbc-source/imports")
  ).json();
  const newImports = history.filter(
    (item: { id: string }) => !previousIds.has(item.id),
  );
  expect(newImports.map((item: { id: string }) => item.id)).toEqual([
    accepted!.importId,
  ]);
});

test("named JRTX styles register with complete closure and render delivered HTML", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/templates");
  const id = `browser-style-${randomUUID()}`;
  const source = (
    await readFile("examples/style-assets.jrxml", "utf8")
  ).replace("Sales summary | Synthetic demonstration data | USD | UTC", id);
  const styles = await readFile("examples/brand.jrtx");
  const upload = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await upload.getByLabel("Template name").fill("Named browser styles");
  await upload.getByLabel("JRXML file").setInputFiles({
    name: "styled.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await upload
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(upload.getByRole("alert")).toContainText(
    "validation or compilation failed",
  );
  await upload
    .getByLabel("Template assets (PNG / JRTX / JRXML / TTF, optional)")
    .setInputFiles({
      name: "brand.jrtx",
      mimeType: "application/xml",
      buffer: styles,
    });
  await expect(
    page.getByRole("list", { name: "Selected template assets" }),
  ).toContainText("assets/brand.jrtx");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(/\/definitions\?template=sha256:[0-9a-f]{64}$/);
  const sha = new URL(page.url()).searchParams.get("template")!.substring(7);
  const bundle = await page.request.get(
    `/backend/api/v1/templates/${sha}/bundle`,
  );
  expect(bundle.status()).toBe(200);
  const archive = await bundle.body();
  expect(zipEntryBytes(archive, "report.jrxml")).toEqual(Buffer.from(source));
  expect(zipEntryBytes(archive, "assets/brand.jrtx")).toEqual(styles);
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Style browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Report title").fill("Styled browser heading");
  await page.getByLabel("Output format").selectOption("HTML");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  expect(output.status()).toBe(200);
  const bytes = await output.body();
  const html = workbookEntry(bytes, "index.html");
  expect(html).toContain("Styled browser heading");
  expect(html.toLowerCase()).toContain("#ff0000");
  expect(job.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
});

test("row-scoped JRXML subreports register with closure and render delivered HTML", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/templates");
  const id = `browser-subreport-${randomUUID()}`;
  const source = (
    await readFile("examples/subreport-assets.jrxml", "utf8")
  ).replace("Sales summary | Synthetic demonstration data | USD | UTC", id);
  const child = await readFile("examples/subreport-row.jrxml");
  const upload = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await upload.getByLabel("Template name").fill("Named browser subreports");
  await upload.getByLabel("JRXML file").setInputFiles({
    name: "parent.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await upload
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(upload.getByRole("alert")).toContainText(
    "validation or compilation failed",
  );
  await upload
    .getByLabel("Template assets (PNG / JRTX / JRXML / TTF, optional)")
    .setInputFiles({
      name: "subreport-row.jrxml",
      mimeType: "application/xml",
      buffer: child,
    });
  await expect(
    page.getByRole("list", { name: "Selected template assets" }),
  ).toContainText("assets/subreport-row.jrxml");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(/\/definitions\?template=sha256:[0-9a-f]{64}$/);
  const sha = new URL(page.url()).searchParams.get("template")!.substring(7);
  const bundle = await page.request.get(
    `/backend/api/v1/templates/${sha}/bundle`,
  );
  expect(bundle.status()).toBe(200);
  const archive = await bundle.body();
  expect(zipEntryBytes(archive, "report.jrxml")).toEqual(Buffer.from(source));
  expect(zipEntryBytes(archive, "assets/subreport-row.jrxml")).toEqual(child);
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await definition.getByLabel("Definition ID").fill(id);
  await definition
    .getByLabel("Definition name")
    .fill("Subreport browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Report title").fill("Subreport browser heading");
  await page.getByLabel("Output format").selectOption("HTML");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  expect(output.status()).toBe(200);
  const bytes = await output.body();
  const html = workbookEntry(bytes, "index.html");
  expect(html).toContain("Subreport browser heading");
  expect(html).toContain("Child: Atlas notebook");
  expect(html).toContain("Child: Cedar pen");
  expect(html).toContain("Child: Harbor planner");
  expect(job.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
});

test("JDBC-backed jobs recover acceptance and reprocess the same saved snapshot", async ({
  page,
}) => {
  test.setTimeout(120000);
  await login(page, "operator");
  await page.goto("/data-sources");
  const imports = async () =>
    (await page.request.get("/backend/api/v1/jdbc-source/imports")).json();
  const previous = new Set(
    (await imports()).map((item: { id: string }) => item.id),
  );
  const jdbc = page.getByRole("group", { name: "JDBC snapshot configuration" });
  await jdbc.getByLabel("Sales category").fill("equipment");
  await jdbc
    .getByRole("button", { name: "Use JDBC for new jobs", exact: true })
    .click();
  await expect(page).toHaveURL(/\/definitions\?dataSource=/);
  expect(new URL(page.url()).searchParams.get("dataSource")).toBe(
    "jdbc:sales-jdbc:equipment",
  );
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await expect(definition.getByLabel("Data source reference")).toHaveValue(
    "jdbc:sales-jdbc:equipment",
  );
  const id = `browser-current-${randomUUID()}`;
  await definition.getByLabel("Definition ID").fill(id);
  await definition
    .getByLabel("Definition name")
    .fill("Capture current JDBC rows");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  expect(
    (await imports()).filter((item: { id: string }) => !previous.has(item.id)),
  ).toEqual([]);
  await expect(
    page.getByText("Each new job captures current JDBC rows.", {
      exact: false,
    }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Report title").fill("Fresh JDBC acceptance");
  await page.getByLabel("Output format").selectOption("CSV");
  const acknowledgements: { id: string; source: string; key: string }[] = [];
  await page.route("**/backend/api/v1/jobs", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    const accepted = await response.json();
    acknowledgements.push({
      id: accepted.id,
      source: JSON.parse(accepted.configuration).sourceImportId,
      key: route.request().headers()["idempotency-key"],
    });
    if (acknowledgements.length === 1)
      await route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          detail:
            "JDBC job acknowledgement interrupted. Retry unchanged configuration.",
        }),
      });
    else await route.fulfill({ response });
  });
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "JDBC job acknowledgement interrupted" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  expect(acknowledgements).toHaveLength(2);
  expect(acknowledgements[1]).toEqual(acknowledgements[0]);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const parent = await (
    await page.request.get(`/backend/api/v1/jobs/${acknowledgements[0].id}`)
  ).json();
  const config = JSON.parse(parent.configuration);
  expect(config.definition.data_source_ref).toBe("jdbc:sales-jdbc:equipment");
  expect(config.datasetVersion).toMatch(/^data:[0-9a-f]{64}$/);
  expect(config.sourceImportId).toBe(acknowledgements[0].source);
  const bytes = await (
    await page.request.get(
      `/backend/api/v1/jobs/${parent.id}/outputs/${parent.artifacts[0].id}`,
    )
  ).body();
  expect(bytes.toString()).toContain("JDBC desk lamp");
  expect(bytes.toString()).toContain("96.50");
  expect(bytes.toString()).not.toContain("JDBC amber journal");
  expect(parent.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${parent.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
  await page
    .getByRole("region", { name: "Reprocessing", exact: true })
    .getByRole("button", { name: "Reprocess as new job", exact: true })
    .click();
  await expect(page).not.toHaveURL(new RegExp(`/jobs/${parent.id}$`));
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const child = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(child.parent_job_id).toBe(parent.id);
  expect(JSON.parse(child.configuration).sourceImportId).toBe(
    config.sourceImportId,
  );
  expect(JSON.parse(child.configuration).datasetVersion).toBe(
    config.datasetVersion,
  );
  const childBytes = await (
    await page.request.get(
      `/backend/api/v1/jobs/${child.id}/outputs/${child.artifacts[0].id}`,
    )
  ).body();
  expect(childBytes).toEqual(bytes);
  const created = (await imports()).filter(
    (item: { id: string }) => !previous.has(item.id),
  );
  expect(created.map((item: { id: string }) => item.id)).toEqual([
    config.sourceImportId,
  ]);
});

for (const binding of ["nested", "dataset"] as const) {
  test(`${binding} JRXML subreports register with complete closure and render grandchildren`, async ({
    page,
  }) => {
    test.setTimeout(90000);
    await login(page, "operator");
    await page.goto("/templates");
    const id = `browser-${binding}-${randomUUID()}`;
    const source = (
      await readFile(
        binding === "dataset"
          ? "examples/dataset-subreport.jrxml"
          : "examples/nested-subreports.jrxml",
        "utf8",
      )
    )
      .replace("Sales summary | Synthetic demonstration data | USD | UTC", id)
      .replace("assets/subreport-row.jrxml", "assets/nested-section.jrxml");
    const child = await readFile("examples/subreport-row.jrxml");
    const middle = await readFile("examples/nested-section.jrxml");
    const upload = page.getByRole("group", {
      name: "Trusted template registration",
    });
    await upload.getByLabel("Template name").fill("Nested browser reports");
    await upload.getByLabel("JRXML file").setInputFiles({
      name: "parent.jrxml",
      mimeType: "application/xml",
      buffer: Buffer.from(source),
    });
    await upload
      .getByLabel("I reviewed this executable template and trust its code.")
      .check();
    await upload
      .getByRole("button", {
        name: "Validate and register template",
        exact: true,
      })
      .click();
    await expect(upload.getByRole("alert")).toContainText(
      "validation or compilation failed",
    );
    await upload
      .getByLabel("Template assets (PNG / JRTX / JRXML / TTF, optional)")
      .setInputFiles([
        {
          name: "nested-section.jrxml",
          mimeType: "application/xml",
          buffer: middle,
        },
        {
          name: "subreport-row.jrxml",
          mimeType: "application/xml",
          buffer: child,
        },
      ]);
    await expect(
      page.getByRole("list", { name: "Selected template assets" }),
    ).toContainText("assets/subreport-row.jrxml");
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    await upload
      .getByRole("button", {
        name: "Validate and register template",
        exact: true,
      })
      .click();
    await expect(page).toHaveURL(
      /\/definitions\?template=sha256:[0-9a-f]{64}$/,
    );
    const sha = new URL(page.url()).searchParams.get("template")!.substring(7);
    const bundle = await page.request.get(
      `/backend/api/v1/templates/${sha}/bundle`,
    );
    expect(bundle.status()).toBe(200);
    const archive = await bundle.body();
    expect(zipEntryBytes(archive, "report.jrxml")).toEqual(Buffer.from(source));
    expect(zipEntryBytes(archive, "assets/subreport-row.jrxml")).toEqual(child);
    expect(zipEntryBytes(archive, "assets/nested-section.jrxml")).toEqual(
      middle,
    );
    const policy = await (
      await page.request.get("/backend/api/v1/templates/policy")
    ).json();
    expect(policy.maxSubreportDepth).toBe(2);
    expect(policy.maxExpandedSubreports).toBe(8);
    expect(policy.maxSubreportsPerReport).toBe(4);
    const definition = page.getByRole("group", {
      name: "Definition configuration",
    });
    await definition.getByLabel("Definition ID").fill(id);
    await definition
      .getByLabel("Definition name")
      .fill("Subreport browser report");
    await definition
      .getByRole("button", { name: "Register definition", exact: true })
      .click();
    await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
    await page
      .getByRole("link", { name: "Generate version 1", exact: true })
      .click();
    await page.getByLabel("Report title").fill("Subreport browser heading");
    await page.getByLabel("Output format").selectOption("HTML");
    await page
      .getByRole("button", { name: "Submit report", exact: true })
      .click();
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
      timeout: 60000,
    });
    const job = await (
      await page.request.get(
        `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
      )
    ).json();
    expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
    const output = await page.request.get(
      `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
    );
    expect(output.status()).toBe(200);
    const bytes = await output.body();
    const html = workbookEntry(bytes, "index.html");
    expect(html).toContain("Subreport browser heading");
    expect(html).toContain("Child: Atlas notebook");
    expect(html).toContain("Child: Cedar pen");
    expect(html).toContain("Child: Harbor planner");
    expect(html.split("Child: Atlas notebook")).toHaveLength(2);
    expect(html.split("Child: Cedar pen")).toHaveLength(2);
    expect(html.split("Child: Harbor planner")).toHaveLength(2);
    expect(job.deliveries[0].status).toBe("VERIFIED");
    const cloud = await page.request.get(
      `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
    );
    expect(cloud.status()).toBe(200);
    expect(await cloud.body()).toEqual(bytes);
  });
}

test("TrueType subsets register and render packaged fonts without scripts", async ({
  page,
}) => {
  test.setTimeout(90000);
  await login(page, "operator");
  await page.goto("/templates");
  const id = `browser-font-${randomUUID()}`;
  const source = (await readFile("examples/font-assets.jrxml", "utf8")).replace(
    "Sales summary | Synthetic demonstration data | USD | UTC",
    id,
  );
  const child = await readFile("examples/digits.ttf");
  const upload = page.getByRole("group", {
    name: "Trusted template registration",
  });
  await upload.getByLabel("Template name").fill("Embedded browser font");
  await upload.getByLabel("JRXML file").setInputFiles({
    name: "parent.jrxml",
    mimeType: "application/xml",
    buffer: Buffer.from(source),
  });
  await upload
    .getByLabel("I reviewed this executable template and trust its code.")
    .check();
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(upload.getByRole("alert")).toContainText(
    "validation or compilation failed",
  );
  await upload
    .getByLabel("Template assets (PNG / JRTX / JRXML / TTF, optional)")
    .setInputFiles({
      name: "digits.ttf",
      mimeType: "font/ttf",
      buffer: child,
    });
  await expect(
    page.getByRole("list", { name: "Selected template assets" }),
  ).toContainText("assets/digits.ttf");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await upload
    .getByRole("button", {
      name: "Validate and register template",
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(/\/definitions\?template=sha256:[0-9a-f]{64}$/);
  const sha = new URL(page.url()).searchParams.get("template")!.substring(7);
  const bundle = await page.request.get(
    `/backend/api/v1/templates/${sha}/bundle`,
  );
  expect(bundle.status()).toBe(200);
  const archive = await bundle.body();
  expect(zipEntryBytes(archive, "report.jrxml")).toEqual(Buffer.from(source));
  expect(zipEntryBytes(archive, "assets/digits.ttf")).toEqual(child);
  const definition = page.getByRole("group", {
    name: "Definition configuration",
  });
  await definition.getByLabel("Definition ID").fill(id);
  await definition.getByLabel("Definition name").fill("Font browser report");
  await definition
    .getByRole("button", { name: "Register definition", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/definitions/${id}$`));
  await page
    .getByRole("link", { name: "Generate version 1", exact: true })
    .click();
  await page.getByLabel("Report title").fill("Font browser heading");
  await page.getByLabel("Output format").selectOption("HTML");
  await page
    .getByRole("button", { name: "Submit report", exact: true })
    .click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("status")).toHaveText("SUCCEEDED", {
    timeout: 60000,
  });
  const job = await (
    await page.request.get(
      `/backend/api/v1/jobs/${page.url().split("/").at(-1)}`,
    )
  ).json();
  expect(JSON.parse(job.configuration).templateSha256).toBe(sha);
  const output = await page.request.get(
    `/backend/api/v1/jobs/${job.id}/outputs/${job.artifacts[0].id}`,
  );
  expect(output.status()).toBe(200);
  const bytes = await output.body();
  const html = workbookEntry(bytes, "index.html");
  expect(html).toContain("Font browser heading");
  expect(html).toContain("240.00");
  expect(html.toLowerCase()).not.toContain("<script");
  const fontPath = [
    ...html.matchAll(/href="(assets\/font-[a-f0-9]+\.css)"/g),
  ].map((m) => m[1]);
  expect(fontPath).toHaveLength(1);
  const css = workbookEntry(bytes, fontPath[0]);
  const ttfName = /url\('([^']+\.ttf)'\)/.exec(css)![1];
  expect(zipEntryBytes(bytes, `assets/${ttfName}`)).toEqual(child);
  const preview = await page.context().newPage();
  let fontRequests = 0;
  await preview.route("http://font-preview.invalid/**", async (route) => {
    const path =
      new URL(route.request().url()).pathname.slice(1) || "index.html";
    const body = zipEntryBytes(bytes, path);
    if (path.endsWith(".ttf")) fontRequests++;
    await route.fulfill({
      status: 200,
      contentType: path.endsWith(".css")
        ? "text/css"
        : path.endsWith(".ttf")
          ? "font/ttf"
          : "text/html",
      body,
    });
  });
  try {
    await preview.goto("http://font-preview.invalid/index.html");
    const loaded = await preview.evaluate(async () => {
      await document.fonts.ready;
      return [...document.fonts].map((font) => font.status);
    });
    expect(loaded).toContain("loaded");
    expect(fontRequests).toBe(1);
  } finally {
    await preview.close();
  }
  expect(job.deliveries[0].status).toBe("VERIFIED");
  const cloud = await page.request.get(
    `http://localhost:9000/report-results/${job.deliveries[0].object_key}`,
  );
  expect(cloud.status()).toBe(200);
  expect(await cloud.body()).toEqual(bytes);
});
