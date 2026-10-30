import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { applyOverrides, eventsForSpec, materialTransfer } from "./build-pixels.mjs";

test("canal event frames use bounded unique opaque pixel replacements and restore rest", () => {
  const schedule = eventsForSpec(); const event = schedule.events[0];
  assert.deepEqual([schedule.width, schedule.height], [256, 448]);
  assert.deepEqual(schedule.initialDelayMs, [20000, 40000]); assert.deepEqual(schedule.quietIntervalMs, [45000, 90000]);
  assert.equal(event.frames.at(-1).durationMs, 140); assert.deepEqual(event.frames.at(-1).light, []); assert.deepEqual(event.frames.at(-1).dark, []);
  for (const frame of event.frames.slice(0, -1)) for (const side of ["light", "dark"]) {
    assert.ok(frame[side].length >= 20 && frame[side].length <= 50);
    const keys = new Set(frame[side].map(([x, y]) => `${x},${y}`)); assert.equal(keys.size, frame[side].length);
    for (const [x, y, color] of frame[side]) { assert.ok(x >= 0 && x < 256 && y >= 0 && y < 448); assert.match(color, /^#[0-9a-f]{6}$/i); }
  }
});
test("empty restoration frame leaves the canonical resting pixels exact", () => {
  const base = new Uint8Array(256 * 448 * 4).fill(19); const event = eventsForSpec().events[0];
  const changed = applyOverrides(base, event.frames[1].light); assert.notDeepEqual(changed, base);
  assert.deepEqual(applyOverrides(base, event.frames.at(-1).light), base);
});

test("lighting studies supply a palette without importing their changed geometry", () => {
  const source = new Uint8Array(256 * 448 * 4).fill(25);
  const study = new Uint8Array(source.length).fill(40);
  source.set([90, 90, 90, 255], 40); source.set([90, 90, 90, 255], 44);
  // Simulate a study moving an object edge between two identically colored source cells.
  study.set([180, 70, 45, 255], 40); study.set([20, 45, 100, 255], 44);
  const result = materialTransfer(source, study, []).pixels;
  assert.deepEqual(result.slice(40, 44), result.slice(44, 48));
  assert.notDeepEqual(result.slice(40, 48), study.slice(40, 48));
});

const asset = name => new URL(name, import.meta.url);
const eventFile = id => JSON.parse(readFileSync(asset(id + "-events.json")));
test("cafe splash frames expand in perspective rather than reusing a brightness pattern", () => {
  const frames = eventFile("rainy-cafe").events[0].frames.slice(0, -1);
  const spans = frames.map(frame => {
    const xs = frame.light.map(pixel => pixel[0]), ys = frame.light.map(pixel => pixel[1]);
    assert.ok(Math.max(...ys) - Math.min(...ys) <= 2);
    return Math.max(...xs) - Math.min(...xs);
  });
  assert.deepEqual(spans, [1, 4, 8, 10]);
  assert.equal(frames.reduce((total, frame) => total + frame.durationMs, 0), 740);
});

test("archived revision-02 MOTD neon retains its original short segment dip", () => {
  const event = eventFile("neon-arcade").events[0];
  assert.equal(event.frames.length, 2);
  assert.equal(event.frames[0].durationMs, 180);
  assert.equal(event.frames[0].light.length, 4);
  for (const mode of ["light", "dark"]) {
    const base = execFileSync("magick", [asset("neon-arcade-" + mode + ".png").pathname, "-depth", "8", "rgba:-"]);
    for (const [x, y, hex] of event.frames[0][mode]) {
      assert.equal(x, 64); assert.ok(y >= 110 && y <= 113);
      const replacement = hex.match(/\w\w/g).map(value => parseInt(value, 16));
      const original = [...base.subarray((y * 256 + x) * 4, (y * 256 + x) * 4 + 3)];
      assert.ok(replacement.every((value, index) => value <= original[index]));
      assert.ok(replacement.some((value, index) => value < original[index]));
    }
  }
});
