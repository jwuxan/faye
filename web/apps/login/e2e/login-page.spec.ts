import { expect, type APIRequestContext, type Page, test } from "@playwright/test";

/**
 * The Login UI's ADR 0033 controls in a browser: "keep me signed in", the show password button,
 * the sign up placeholder and the footer. The screens come from e2e/stub-faye.ts, which records
 * what the browser submits.
 */

const STUB = "http://127.0.0.1:18080";
const FOOTER = "© Acme. All rights reserved.";

/** Opens a new flow of a stub scenario at /login; returns its id. */
async function open(page: Page, scenario: string): Promise<string> {
  const flowId = `f_${scenario}_${Math.random().toString(36).slice(2)}`;
  await page.goto(`/login?flow=${flowId}`);
  return flowId;
}

/** The payloads the browser submitted for a flow, once there are this many. */
async function submitted(request: APIRequestContext, flowId: string, count = 1): Promise<unknown[]> {
  let list: unknown[] = [];
  await expect
    .poll(async () => {
      list = (await (await request.get(`${STUB}/__submissions/${flowId}`)).json()) as unknown[];
      return list.length;
    })
    .toBe(count);
  return list;
}

test.describe("keep me signed in", () => {
  test("is offered unticked, and an unticked box is sent as false", async ({ page, request }) => {
    const flowId = await open(page, "remember");
    const box = page.getByRole("checkbox", { name: "Keep me signed in" });
    await expect(box).toBeVisible();
    await expect(box).not.toBeChecked();
    await page.getByLabel("Email").fill("ada@example.com");
    await page.getByRole("button", { name: "Continue" }).click();
    expect(await submitted(request, flowId)).toEqual([{ identifier: "ada@example.com", remember: false }]);
  });

  test("a ticked box is sent as true", async ({ page, request }) => {
    const flowId = await open(page, "remember");
    await page.getByLabel("Email").fill("ada@example.com");
    await page.getByRole("checkbox", { name: "Keep me signed in" }).click();
    await expect(page.getByRole("checkbox", { name: "Keep me signed in" })).toBeChecked();
    await page.getByRole("button", { name: "Continue" }).click();
    expect(await submitted(request, flowId)).toEqual([{ identifier: "ada@example.com", remember: true }]);
  });

  test("ticking the label ticks the box", async ({ page }) => {
    await open(page, "remember");
    await page.getByText("Keep me signed in").click();
    await expect(page.getByRole("checkbox", { name: "Keep me signed in" })).toBeChecked();
  });

  test("is not shown, and not sent, when the tenant does not offer it", async ({ page, request }) => {
    const flowId = await open(page, "plain");
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await page.getByLabel("Email").fill("ada@example.com");
    await page.getByRole("button", { name: "Continue" }).click();
    expect(await submitted(request, flowId)).toEqual([{ identifier: "ada@example.com" }]);
  });

  test("rides with the identifier and password asked together", async ({ page, request }) => {
    const flowId = await open(page, "idpw");
    await page.getByLabel("Email").fill("ada@example.com");
    await page.getByLabel("Password", { exact: true }).fill("correct horse");
    await page.getByRole("checkbox", { name: "Keep me signed in" }).click();
    await page.getByRole("button", { name: "Sign in" }).click();
    expect(await submitted(request, flowId)).toEqual([
      { identifier: "ada@example.com", password: "correct horse", remember: true },
    ]);
  });
});

test.describe("show password", () => {
  test("shows what was typed, and hides it again", async ({ page }) => {
    await open(page, "password");
    const field = page.getByLabel("Password", { exact: true });
    await field.fill("correct horse");
    await expect(field).toHaveAttribute("type", "password");
    await page.getByRole("button", { name: "Show password" }).click();
    await expect(field).toHaveAttribute("type", "text");
    await expect(field).toHaveValue("correct horse");
    const hide = page.getByRole("button", { name: "Hide password" });
    await expect(hide).toHaveAttribute("aria-pressed", "true");
    await hide.click();
    await expect(field).toHaveAttribute("type", "password");
    await expect(page.getByRole("button", { name: "Show password" })).toHaveAttribute("aria-pressed", "false");
  });

  test("is on the password of the identifier and password form too", async ({ page }) => {
    await open(page, "idpw");
    await page.getByRole("button", { name: "Show password" }).click();
    await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute("type", "text");
  });

  test("is absent when the tenant does not turn it on", async ({ page }) => {
    await open(page, "nopassword");
    await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Show password" })).toHaveCount(0);
  });
});

test.describe("sign up", () => {
  test("opens a dialog saying sign up is not available yet, which OK closes", async ({ page, request }) => {
    const flowId = await open(page, "signup");
    await page.getByRole("button", { name: "Sign up" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("heading", { name: "Sign up isn't available yet" })).toBeVisible();
    await expect(dialog).toContainText("You can't create an account here yet.");
    await dialog.getByRole("button", { name: "OK" }).click();
    await expect(dialog).toHaveCount(0);
    // Nothing reached the server: the button is a placeholder
    expect(await (await request.get(`${STUB}/__submissions/${flowId}`)).json()).toEqual([]);
  });

  test("is absent when the tenant does not turn it on", async ({ page }) => {
    await open(page, "remember");
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign up" })).toHaveCount(0);
  });
});

test.describe("footer", () => {
  test("names the tenant on the first screen and on the next", async ({ page }) => {
    await open(page, "remember");
    await expect(page.getByText(FOOTER)).toBeVisible();
    await page.getByLabel("Email").fill("ada@example.com");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
    await expect(page.getByText(FOOTER)).toBeVisible();
  });

  test("is absent for a tenant with no name", async ({ page }) => {
    await open(page, "plain");
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByText("All rights reserved")).toHaveCount(0);
  });

  test("the identifier screen's Sign in heading is for screen readers only", async ({ page }) => {
    await open(page, "remember");
    await expect(page.getByLabel("Email")).toBeVisible();
    // Kept in the accessibility tree, but drawn in a 1px clipped box: no visible heading
    const box = await page.getByRole("heading", { name: "Sign in" }).boundingBox();
    expect(box?.width ?? 0).toBeLessThanOrEqual(1);
    expect(box?.height ?? 0).toBeLessThanOrEqual(1);
  });
});
