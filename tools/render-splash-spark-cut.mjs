#!/usr/bin/env node
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, rmSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = resolve(fileURLToPath(new URL(".", import.meta.url)));
const root = resolve(here, "..");
const output = resolve(root, "output/splash-laser-preview");
mkdirSync(output, { recursive: true });
const html = resolve(output, "laser-cut-preview.html");
const sourceHtml = resolve(here, "splash-spark-cut.html");
const logo = resolve(root, "app/src/main/res/drawable-xxxhdpi/ic_splash_logo.png");
const preview = readFileSync(sourceHtml, "utf8").replace(
  '../app/src/main/res/drawable-xxxhdpi/ic_splash_logo.png',
  'data:image/png;base64,' + readFileSync(logo).toString('base64'),
);
writeFileSync(html, preview);
const out = resolve(output, "rendered");
const frames = resolve(out, "frames");
const chrome = process.env.CHROMIUM || "chromium";
const magick = process.env.MAGICK || "magick";
const duration = 950;

function run(command, args) {
  execFileSync(command, args, { stdio: "inherit" });
}
function shot(time, size, target) {
  const url = pathToFileURL(html).href + "?export=1&t=" + time + "&s=" + size;
  run(chrome, [
    "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
    "--run-all-compositor-stages-before-draw", "--virtual-time-budget=1500",
    "--window-size=" + size + "," + size, "--screenshot=" + target, url,
  ]);
}

const assetDom = execFileSync(chrome, [
  "--headless=new", "--no-sandbox", "--disable-gpu", "--allow-file-access-from-files",
  "--virtual-time-budget=1500", "--dump-dom", pathToFileURL(html).href + "?asset=1",
], { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
const asset = JSON.parse(assetDom.match(/<body>([\s\S]*?)<\/body>/)[1]);
if (Math.abs(asset.cutMin + 277.432) > 0.001 || Math.abs(asset.cutMax - 63.362) > 0.001) {
  throw new Error("Aperture changed: update native SparkCutArtwork bounds before regenerating.");
}
writeFileSync(resolve(root, "app/src/main/res/drawable-nodpi/splash_spark_intact.png"), Buffer.from(asset.png.split(",")[1], "base64"));
rmSync(out, { recursive: true, force: true });
mkdirSync(frames, { recursive: true });
for (let time = 0; time <= duration; time += 50) {
  shot(time, 512, resolve(frames, "frame-" + String(time).padStart(4, "0") + ".png"));
}
const actionFrames = [];
for (let time = 0; time < duration; time += 50) actionFrames.push(resolve(frames, "frame-" + String(time).padStart(4, "0") + ".png"));
const finalFrame = resolve(frames, "frame-0950.png");
run(magick, ["-delay", "5", ...actionFrames, "-delay", "120", finalFrame, "-loop", "1", resolve(output, "laser-cut.gif")]);
run(magick, [resolve(frames, "frame-0300.png"), "-resize", "180x180!", resolve(output, "phone-size-proof.png")]);
run(magick, [
  resolve(frames, "frame-0000.png"), "-resize", "180x180!",
  resolve(frames, "frame-0250.png"), "-resize", "180x180!",
  resolve(frames, "frame-0500.png"), "-resize", "180x180!",
  resolve(frames, "frame-0750.png"), "-resize", "180x180!",
  resolve(frames, "frame-0950.png"), "-resize", "180x180!",
  "+append", resolve(output, "phone-contact-sheet.png"),
]);

// Native-size end frame is compared against the original PNG composited onto black.
const finalNative = resolve(out, "final-rendered-800.png");
const expected = resolve(out, "final-expected-800.png");
shot(duration, 800, finalNative);
run(magick, [
  logo,
  "-background", "black", "-alpha", "remove", "-alpha", "off", expected,
]);
const comparison = spawnSync(magick, ["compare", "-metric", "AE", finalNative, expected, "null:"], { encoding: "utf8" });
if (comparison.status > 1) throw new Error(String(comparison.stderr || comparison.stdout));
const ae = String(comparison.stderr || comparison.stdout).trim();
const hashes = ["frame-0000.png", "frame-0500.png", "frame-0950.png"].map((name) => {
  const data = execFileSync("sha256sum", [resolve(frames, name)], { encoding: "utf8" }).split(/\s+/)[0];
  return name + "=" + data;
});
console.log("Rendered " + (actionFrames.length + 1) + " GIF frames.");
console.log("Fixed-frame hashes: " + hashes.join(" "));
console.log("Final native black-composite AE comparison: " + ae);
if (hashes[0].slice(-64) === hashes[1].slice(-64) || hashes[1].slice(-64) === hashes[2].slice(-64)) {
  throw new Error("Expected start, mid, and final frames to differ.");
}
if (!/^0(?: \(0\))?$/.test(ae)) process.exitCode = 2;
