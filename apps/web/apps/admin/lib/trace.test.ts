/** The preview timeline's time formatting. */
import assert from "node:assert/strict";
import { test } from "node:test";
import { delta, duration, stamp, timeOf } from "./trace.ts";

test("timeOf is HH:MM:SS.mmm, local", () => {
  const at = new Date(2025, 10, 1, 21, 33, 5, 42).getTime();
  assert.equal(timeOf(at), "21:33:05.042");
  const midnight = new Date(2025, 0, 2, 0, 0, 0, 7).getTime();
  assert.equal(timeOf(midnight), "00:00:00.007");
});

test("stamp is a short local date-time", () => {
  const at = new Date(2025, 10, 1, 21, 33, 5).getTime();
  assert.equal(stamp(at), "Nov 1, 21:33:05");
});

test("delta between events: ms under a second, tenths of seconds, then whole minutes", () => {
  assert.equal(delta(0), "+0 ms");
  assert.equal(delta(12), "+12 ms");
  assert.equal(delta(999), "+999 ms");
  assert.equal(delta(1300), "+1.3 s");
  assert.equal(delta(59_900), "+59.9 s");
  assert.equal(delta(120_000), "+2 min");
});

test("duration of a run: ms under a second, hundredths of seconds, then whole minutes", () => {
  assert.equal(duration(240), "240 ms");
  assert.equal(duration(1240), "1.24 s");
  assert.equal(duration(1000), "1 s");
  assert.equal(duration(65_000), "1 min");
});
