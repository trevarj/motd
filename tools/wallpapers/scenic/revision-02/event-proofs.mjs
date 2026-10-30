// Magnified, lossless inspection strips: rest then each authored frame; day above night.
import { execFileSync, spawnSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const directory = path.dirname(fileURLToPath(import.meta.url));
for (const file of readdirSync(directory).filter(name => name.endsWith("-events.json"))) {
  const id = file.slice(0, -"-events.json".length);
  const document = JSON.parse(readFileSync(path.join(directory, file)));
  const bases = ["light", "dark"].map(mode => execFileSync("magick", [path.join(directory, id + "-" + mode + ".png"), "-depth", "8", "rgba:-"]));
  for (const event of document.events) {
    const coordinates = event.frames.flatMap(frame => [...frame.light, ...frame.dark]);
    const x0 = Math.max(0, Math.min(...coordinates.map(p => p[0])) - 8);
    const y0 = Math.max(0, Math.min(...coordinates.map(p => p[1])) - 8);
    const x1 = Math.min(256, Math.max(...coordinates.map(p => p[0])) + 9);
    const y1 = Math.min(448, Math.max(...coordinates.map(p => p[1])) + 9);
    const tileWidth = x1 - x0, tileHeight = y1 - y0;
    const frames = [{ light: [], dark: [] }, ...event.frames];
    const proofWidth = tileWidth * frames.length, proofHeight = tileHeight * 2;
    const output = Buffer.alloc(proofWidth * proofHeight * 4, 255);
    for (const [side, mode] of ["light", "dark"].entries()) {
      for (const [column, frame] of frames.entries()) {
        const pixels = Buffer.from(bases[side]);
        for (const [x, y, color] of frame[mode]) {
          pixels.set([...color.match(/\w\w/g).map(value => parseInt(value, 16)), 255], (y * 256 + x) * 4);
        }
        for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
          const target = ((side * tileHeight + y - y0) * proofWidth + column * tileWidth + x - x0) * 4;
          pixels.copy(output, target, (y * 256 + x) * 4, (y * 256 + x) * 4 + 4);
        }
      }
    }
    const result = spawnSync("magick", ["-size", proofWidth + "x" + proofHeight, "-depth", "8", "rgba:-", "-scale", "600%", path.join(directory, "proofs", id + "-" + event.id + "-frames.png")], { input: output });
    assert.equal(result.status, 0, result.stderr?.toString());
  }
}
