import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const brand = path.join(root, "docs/assets/brand");
const res = path.join(root, "app/src/main/res");
function magick(args) {
  return execFileSync("magick", args, { maxBuffer: 64 * 1024 * 1024 });
}
function inspect(file, width, height, radius = null, holeY = .5) {
  assert.equal(magick([file, "-format", "%wx%h", "info:"]).toString(), `${width}x${height}`, file);
  const pixels = magick([file, "-depth", "8", "rgba:-"]);
  let visible = 0;
  let opaqueMin = 255;
  let opaqueMax = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    const alpha = pixels[i + 3];
    if (alpha <= 127) continue;
    visible++;
    opaqueMin = Math.min(opaqueMin, pixels[i]);
    opaqueMax = Math.max(opaqueMax, pixels[i]);
    if (radius !== null) {
      const x = (i / 4) % width + .5 - width / 2;
      const y = Math.floor(i / 4 / width) + .5 - height / 2;
      assert.ok(Math.hypot(x, y) <= radius, `${file}: artwork exceeds circular safe zone`);
    }
  }
  assert.ok(visible > 0, `${file}: empty artwork`);
  assert.equal(pixels[3], 0, `${file}: background is not transparent`);
  // At 24px a nominal sample can land on the antialiased edge. This central
  // neighborhood is inside the bubble, so transparent pixels must be the slash.
  const holeSamples = [];
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      holeSamples.push(pixels[((Math.floor(height * holeY) + dy) * width + Math.floor(width / 2) + dx) * 4 + 3]);
    }
  }
  assert.ok(holeSamples.includes(0), `${file}: slash is not transparent`);
  return { min: opaqueMin, max: opaqueMax };
}

const splashMaster = path.join(brand, "motd-splash-master.png");
const [splashWidth, splashHeight] = magick([splashMaster, "-format", "%w %h", "info:"]).toString().split(" ").map(Number);
assert.ok(Math.min(splashWidth, splashHeight) >= 1024, "Splash master must support downsampling at every Android density");
inspect(splashMaster, splashWidth, splashHeight);
const [visibleWidth, visibleHeight, visibleX, visibleY] = magick([splashMaster, "-alpha", "extract", "-threshold", "50%", "-format", "%@", "info:"]).toString().match(/\d+/g).map(Number);
const padding = Math.max(2, Math.round(Math.max(splashWidth, splashHeight) / 512));
const splashBounds = `${visibleWidth + 2 * padding}x${visibleHeight + 2 * padding}+${visibleX - padding}+${visibleY - padding}`;

let checked = 0;
for (const [density, factor] of [["mdpi", 1], ["hdpi", 1.5], ["xhdpi", 2], ["xxhdpi", 3], ["xxxhdpi", 4]]) {
  const folder = path.join(res, `drawable-${density}`);
  const mark = inspect(path.join(folder, "motd_logo_mark.png"), Math.round(96 * factor), Math.round(92 * factor), null, .43);
  assert.ok(mark.max - mark.min > 15, "Ceramic shading was flattened");
  checked++;
  for (const name of ["ic_launcher_foreground", "ic_launcher_foreground_light", "ic_launcher_foreground_terminal", "ic_launcher_monochrome"]) {
    inspect(path.join(folder, `${name}.png`), Math.round(108 * factor), Math.round(108 * factor), 33 * factor);
    const alpha = magick([path.join(folder, `${name}.png`), "-alpha", "extract", "-depth", "8", "gray:-"]);
    const silhouette = magick([path.join(folder, "ic_launcher_monochrome.png"), "-alpha", "extract", "-depth", "8", "gray:-"]);
    assert.deepEqual(alpha, silhouette, `${name} must retain the authoritative silhouette`);
    checked++;
  }
  const splashFile = path.join(folder, "ic_splash_logo.png");
  const splashSize = Math.round(200 * factor);
  const splash = inspect(splashFile, splashSize, splashSize, 76 * factor);
  assert.ok(splash.max - splash.min > 15, "Splash ceramic shading was flattened");
  const unit = splashSize / 192;
  const width = Math.round(164 * unit * .66);
  const height = Math.round(158 * unit * .66);
  const x = Math.round((96 + (16 - 96) * .66) * unit);
  const y = Math.round((96 + (24 - 96) * .66) * unit);
  // Compare the exported contour to direct master downsampling. Reapplying the
  // old raster mask or thresholding alpha must fail even if safe zones pass.
  const expectedAlpha = magick([splashMaster, "-crop", splashBounds, "+repage", "-filter", "Lanczos", "-resize", `${width}x${height}!`, "-alpha", "extract", "-depth", "8", "gray:-"]);
  const actualAlpha = magick([splashFile, "-crop", `${width}x${height}+${x}+${y}`, "+repage", "-alpha", "extract", "-depth", "8", "gray:-"]);
  assert.deepEqual(actualAlpha, expectedAlpha, `${splashFile}: regenerated splash contour changed`);
  checked++;
  const notification = inspect(path.join(folder, "ic_notification_motd.png"), Math.round(24 * factor), Math.round(24 * factor), null, .46);
  assert.equal(notification.min, 255, "Notification artwork must be a flat white system-tinted mask");
  const wordmarkFile = path.join(folder, "motd_onboarding_wordmark.png");
  assert.equal(magick([wordmarkFile, "-format", "%wx%h", "info:"]).toString(), `${Math.round(91 * factor)}x${Math.round(31 * factor)}`);
  checked += 2;
}
for (const name of ["motd_logo_mark", "ic_splash_logo", "ic_launcher_foreground", "ic_launcher_foreground_terminal", "ic_launcher_monochrome", "ic_notification_motd", "motd_onboarding_wordmark"]) {
  assert.equal(existsSync(path.join(res, "drawable", `${name}.xml`)), false, `${name} vector still shadows raster resources`);
}
for (const name of ["motd-symbol", "motd-app-icon", "motd-wordmark", "motd-lockup-light", "motd-lockup-dark", "motd-lockup-stacked"]) {
  assert.equal(existsSync(path.join(brand, `${name}.svg`)), false, `${name} SVG still present`);
  assert.ok(existsSync(path.join(brand, `${name}.png`)), `${name} PNG missing`);
}
for (const file of ["README.md", "site/build.sh", ...["index", "installation", "getting-started", "configuration", "guides"].map((name) => `site/${name}.html`)]) {
  assert.doesNotMatch(readFileSync(path.join(root, file), "utf8"), /motd-[\w-]+\.svg/, `${file} still references a brand SVG`);
}
// README marks retain the smooth master contour at the existing layout bounds.
const markSize = Math.round(192 * .92 * 3);
const markWidth = Math.round(164 * markSize / 192);
const markHeight = Math.round(158 * markSize / 192);
const markX = Math.round(23.7 * 3) + Math.round(16 * markSize / 192);
const markY = 3 + Math.round(24 * markSize / 192);
const markBounds = `${markWidth}x${markHeight}+${markX}+${markY}`;
const expectedAlpha = magick([splashMaster, "-crop", splashBounds, "+repage", "-filter", "Lanczos", "-resize", `${markWidth}x${markHeight}!`, "-alpha", "extract", "-depth", "8", "gray:-"]);
for (const [name, inverse] of [["light", false], ["dark", true]]) {
  const file = path.join(brand, `motd-lockup-${name}.png`);
  assert.equal(magick([file, "-format", "%wx%h", "info:"]).toString(), "1470x540", file);
  const actualAlpha = magick([file, "-crop", markBounds, "+repage", "-alpha", "extract", "-depth", "8", "gray:-"]);
  assert.ok(actualAlpha.equals(expectedAlpha), `${file}: smoothed README contour changed`);
  const pixels = magick([file, "-crop", markBounds, "+repage", "-depth", "8", "rgba:-"]);
  let min = 255;
  let max = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    if (pixels[i + 3] <= 127) continue;
    min = Math.min(min, pixels[i]);
    max = Math.max(max, pixels[i]);
  }
  assert.ok(max - min > 15, `${file}: ceramic shading was flattened`);
  assert.ok(inverse ? min >= 128 : max < 128, `${file}: wrong light/dark contrast`);
  // Lettering is composited verbatim, without a font substitution or generation.
  // Compositing clears invisible RGB, so normalize fully transparent pixels.
  const text = magick([path.join(brand, "motd-lettering-master.png"), ...(inverse ? ["-channel", "RGB", "-negate", "+channel"] : []), "-filter", "Lanczos", "-resize", "1470x540!", "-crop", "780x540+690+0", "+repage", "-depth", "8", "rgba:-"]);
  const lockup = magick([file, "-crop", "780x540+690+0", "+repage", "-depth", "8", "rgba:-"]);
  for (let i = 0; i < text.length; i += 4) {
    if (text[i + 3] === 0) text.fill(0, i, i + 3);
    if (lockup[i + 3] === 0) lockup.fill(0, i, i + 3);
  }
  assert.ok(lockup.equals(text), `${file}: existing lettering pixels changed`);
}
console.log(`${checked} Android density assets and 2 README lockups verified; transparency, smooth contours, contrast, shading, safe zones, unchanged lettering, and raster references passed.`);
