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
      return result.toString("utf8");
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
