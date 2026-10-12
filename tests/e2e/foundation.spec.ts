import { test, expect } from "@playwright/test";
test("anonymous API requests are rejected", async ({ request }) => {
  const response = await request.get("/backend/api/v1/me");
  expect(response.status()).toBe(401);
});
test("OIDC browser login reaches protected database-backed catalog", async ({
  page,
}) => {
  if (!process.env.REPORT_TEST_PASSWORD)
    throw new Error("REPORT_TEST_PASSWORD is required");
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in securely" }).click();
  await page.getByLabel("Username or email").fill("reporter");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.REPORT_TEST_PASSWORD);
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Browse report catalog" }).click();
  await expect(
    page.getByRole("heading", { name: "Sales summary" }),
  ).toBeVisible();
  const cookies = await page.context().cookies();
  const session = cookies.find((c) => c.name === "REPORT_SESSION");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");
});
