import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { buildArcadeSign } from "./build-arcade-sign.mjs";

const rgba = file => execFileSync("magick", [new URL(file, import.meta.url).pathname, "-depth", "8", "rgba:-"]);
const hex = value => value.match(/\w\w/g).map(part => parseInt(part, 16));
const pixel = (pixels, x, y) => [...pixels.subarray((y * 256 + x) * 4, (y * 256 + x) * 4 + 3)];
const built = buildArcadeSign();

test("full branded MOTD sign, halo, and reflection turn off through declared exact replacements", () => {
  const event = built.document.events[0];
  assert.deepEqual(built.document.initialDelayMs, [20000, 40000]);
  assert.deepEqual(built.document.quietIntervalMs, [45000, 90000]);
  assert.equal(event.frames[0].durationMs, 180);
  assert.deepEqual(event.frames.at(-1).light, []);
  assert.deepEqual(event.frames.at(-1).dark, []);
  assert.ok(built.allOwned.size > 100 && built.allOwned.size < 4096);
  for (const mode of ["light", "dark"]) {
    const rest = mode === "light" ? built.light : built.dark;
    const off = mode === "light" ? built.offLight : built.offDark;
    const replacements = new Map(event.frames[0][mode].map(([x, y, color]) => [x + "," + y, color]));
    assert.deepEqual(new Set(replacements.keys()), built.allOwned);
    for (const key of built.allOwned) {
      const [x, y] = key.split(",").map(Number);
      assert.deepEqual(hex(replacements.get(key)), pixel(off, x, y));
      assert.notDeepEqual(pixel(rest, x, y), pixel(off, x, y));
    }
  }
  for (const glyph of ["symbol", "m", "o", "t", "d"]) assert.ok(built.tube.get ? [...built.tube.values()].includes(glyph) : false);
});

test("OFF restores all glow and reflection pixels to the clean background and all tubes to unlit hardware", () => {
  for (const mode of ["light", "dark"]) {
    const rest = rgba("./neon-arcade-v3-" + mode + ".png");
    const off = rgba("./neon-arcade-v3-off-" + mode + ".png");
    const document = JSON.parse(readFileSync(new URL("./neon-arcade-v3-events.json", import.meta.url)));
    const mask = new Set(document.events[0].frames[0][mode].map(([x, y]) => x + "," + y));
    for (let y = 0; y < 448; y++) for (let x = 0; x < 256; x++) {
      if (!mask.has(x + "," + y)) assert.deepEqual(pixel(rest, x, y), pixel(off, x, y));
    }
    const clean = mode === "light" ? built.cleanLight : built.cleanDark;
    for (const [x, y, color] of document.events[0].frames[0][mode]) {
      const [red, green, blue] = hex(color);
      if (built.tube.has(x + "," + y)) {
        assert.ok(Math.max(red, green, blue) < 100, "Unlit hardware must remain dim");
        assert.ok(Math.max(red, green, blue) - Math.min(red, green, blue) < 20, "Unlit tube hardware must be neutral");
        assert.ok(red + green + blue < pixel(rest, x, y).reduce((sum, value) => sum + value, 0));
      } else {
        assert.deepEqual([red, green, blue], pixel(clean, x, y), "Sign-owned light must disappear completely");
      }
      assert.deepEqual([red, green, blue], pixel(off, x, y));
    }
  }
});
