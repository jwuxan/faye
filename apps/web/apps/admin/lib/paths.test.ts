/** The Paths tab's virtual list math. */
import assert from "node:assert/strict";
import { test } from "node:test";
import { budgetTone, evict, pageQuery, pagesOf, rowWindow, shownCount } from "./paths.ts";

test("rowWindow at the top overscans only below", () => {
  assert.deepEqual(rowWindow(0, 380, 5980), { start: 0, end: 13 });
});

test("rowWindow in the middle overscans on both sides", () => {
  assert.deepEqual(rowWindow(76 * 50, 380, 5980), { start: 42, end: 63 });
});

test("rowWindow at the end clamps to the total", () => {
  assert.deepEqual(rowWindow(76 * 100 - 380, 380, 100), { start: 87, end: 100 });
});

test("rowWindow of an empty list is empty, and a negative scroll is the top", () => {
  assert.deepEqual(rowWindow(0, 380, 0), { start: 0, end: 0 });
  assert.deepEqual(rowWindow(-50, 380, 100), { start: 0, end: 13 });
});

test("pagesOf spans a page boundary", () => {
  assert.deepEqual(pagesOf({ start: 0, end: 50 }), [0]);
  assert.deepEqual(pagesOf({ start: 90, end: 110 }), [0, 1]);
  assert.deepEqual(pagesOf({ start: 95, end: 205 }), [0, 1, 2]);
  assert.deepEqual(pagesOf({ start: 0, end: 0 }), []);
});

test("evict drops pages more than KEEP from the visible ones", () => {
  const cache = new Map([...Array(10).keys()].map((p) => [p, [p]]));
  const kept = evict(cache, [5]);
  assert.deepEqual([...kept.keys()].sort((a, b) => a - b), [2, 3, 4, 5, 6, 7, 8]);
});

test("evict returns the same map when nothing drops", () => {
  const cache = new Map([[4, ["a"]], [5, ["b"]], [6, ["c"]]]);
  assert.equal(evict(cache, [5]), cache);
});

test("pageQuery pages within a filter", () => {
  assert.equal(pageQuery(2, "all"), "offset=200&limit=100");
  assert.equal(pageQuery(2, "succeed"), "offset=200&limit=100&succeeds=true");
  assert.equal(pageQuery(2, "fail"), "offset=200&limit=100&succeeds=false");
});

test("shownCount counts the filtered paths", () => {
  const v = { pathCount: 10, succeeding: 4 };
  assert.equal(shownCount(v, "all"), 10);
  assert.equal(shownCount(v, "succeed"), 4);
  assert.equal(shownCount(v, "fail"), 6);
});

test("budgetTone is green under half, yellow to three quarters, red past that or over", () => {
  assert.equal(budgetTone(0, 10_000, false), "success");
  assert.equal(budgetTone(4_999, 10_000, false), "success");
  assert.equal(budgetTone(5_000, 10_000, false), "warning");
  assert.equal(budgetTone(7_500, 10_000, false), "warning");
  assert.equal(budgetTone(7_501, 10_000, false), "danger");
  assert.equal(budgetTone(10_000, 10_000, false), "danger");
  assert.equal(budgetTone(0, 10_000, true), "danger");
});
