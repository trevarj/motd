#!/usr/bin/env node
// Re-encode the approved scenic PNGs losslessly for Android assets.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "../..");
const scenicRoot = path.join(root, "tools/wallpapers/scenic");
const assetRoot = path.join(root, "app/src/main/assets/chat-wallpapers/scenic");
const manifest = JSON.parse(fs.readFileSync(path.join(scenicRoot, "manifest.json"), "utf8"));
const flags = new Set(process.argv.slice(2));
if ([...flags].some(flag => flag !== "--check")) throw new Error("Usage: optimize-scenic-assets.mjs [--check]");

// This is intentionally a fixed release catalog, rather than "latest" manifest discovery.
// Adding or superseding a production study requires an explicit review of this list.
const approvedVersions = new Map([
  ["japanese-countryside", ["summer-pixel-v3", "spring-pixel-v3", "autumn-pixel-v3", "winter-pixel-v3"]],
  ["rainy-cafe", ["pixel-events-v2"]],
  ["neon-arcade", ["pixel-events-v3"]],
  ["retro-computer-room", ["pixel-events-v3"]],
  ["cyberpunk-canal", ["pixel-events-v2"]],
]);

function command(name, args, input) {
  const result = spawnSync(name, args, { input, maxBuffer: 64 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${name} failed: ${String(result.stderr || result.error?.message).trim()}`);
  return result.stdout;
}

function magick(args, input) {
  return command("magick", args, input);
}

function sourcePath(relative) {
  const absolute = path.resolve(scenicRoot, relative);
  if (!absolute.startsWith(`${scenicRoot}${path.sep}`)) throw new Error(`Manifest asset escapes scenic directory: ${relative}`);
  return absolute;
}

function approvedAssets() {
  const scenes = new Map(manifest.scenes.map(scene => [scene.id, scene]));
  if (scenes.size !== approvedVersions.size || [...approvedVersions].some(([id]) => !scenes.has(id))) {
    throw new Error("Scenic manifest scene set no longer matches the reviewed app catalog");
  }
  const assets = [];
  for (const [sceneId, versionIds] of approvedVersions) {
    const versions = new Map(scenes.get(sceneId).versions.map(version => [version.id, version]));
    for (const versionId of versionIds) {
      const version = versions.get(versionId);
      if (!version || version.motion !== "pixel-events" || !version.light || !version.dark || !version.events) {
        throw new Error(`${sceneId}/${versionId} is not a complete pixel-event release study`);
      }
      for (const field of ["light", "dark", "events"]) {
        const source = sourcePath(version[field]);
        const name = path.basename(source);
        if (!/^(?:[a-z0-9-]+)-(?:light|dark)\.png$/.test(name) && !/^[a-z0-9-]+-events\.json$/.test(name)) {
          throw new Error(`Unexpected production asset name: ${name}`);
        }
        assets.push({ name, source, type: field === "events" ? "events" : "png" });
      }
    }
  }
  const names = new Set(assets.map(asset => asset.name));
  if (names.size !== assets.length) throw new Error("Approved scenic catalog contains duplicate asset names");
  return assets.sort((left, right) => left.name.localeCompare(right.name));
}

function rgba(source) {
  const dimensions = magick([source, "-format", "%w %h", "info:"]).toString().trim().split(" ").map(Number);
  if (dimensions[0] !== 256 || dimensions[1] !== 448) throw new Error(`${path.basename(source)} must remain 256 by 448`);
  const pixels = magick([source, "-depth", "8", "rgba:-"]);
  if (pixels.length !== 256 * 448 * 4) throw new Error(`${path.basename(source)} did not decode to an RGBA scenic frame`);
  for (let index = 3; index < pixels.length; index += 4) if (pixels[index] !== 255) throw new Error(`${path.basename(source)} has transparent pixels and cannot use the opaque release encoder`);
  return pixels;
}

function colorCount(source) {
  const count = Number(magick([source, "-format", "%k", "info:"]).toString().trim());
  if (!Number.isInteger(count) || count < 1) throw new Error(`Could not count colors for ${path.basename(source)}`);
  return count;
}

function optimizedPng(source) {
  const colors = colorCount(source);
  const colorType = colors <= 256 ? "3" : "2";
  const png = magick([
    source,
    "-strip",
    "-depth", "8",
    "-define", `png:color-type=${colorType}`,
    "-define", "png:compression-level=9",
    // Filter 1 was the smallest exact result in the checked 0..5 filter sweep.
    "-define", "png:compression-filter=1",
    "png:-",
  ]);
  return { png, colors, encoding: colorType === "3" ? "indexed" : "rgb24" };
}

function writeOrCheck(target, expected) {
  const current = fs.existsSync(target) ? fs.readFileSync(target) : null;
  if (flags.has("--check")) {
    if (!current || !current.equals(expected)) throw new Error(`Stale scenic export: ${path.relative(root, target)}`);
  } else if (!current || !current.equals(expected)) {
    fs.writeFileSync(target, expected);
  }
}

const assets = approvedAssets();
const expectedNames = new Set(assets.map(asset => asset.name));
const actualNames = new Set(fs.readdirSync(assetRoot).filter(name => fs.statSync(path.join(assetRoot, name)).isFile()));
if (actualNames.size !== expectedNames.size || [...actualNames].some(name => !expectedNames.has(name))) {
  throw new Error("App scenic directory must contain exactly the reviewed release catalog");
}

let before = 0;
let after = 0;
for (const asset of assets) {
  const target = path.join(assetRoot, asset.name);
  const original = fs.readFileSync(asset.source);
  before += original.length;
  if (asset.type === "events") {
    // Android already DEFLATE-compresses JSON inside the APK. Keep its reviewed bytes verbatim.
    writeOrCheck(target, original);
    after += original.length;
    continue;
  }
  const originalPixels = rgba(asset.source);
  const optimized = optimizedPng(asset.source);
  const optimizedPixels = magick(["png:-", "-depth", "8", "rgba:-"], optimized.png);
  if (!originalPixels.equals(optimizedPixels)) throw new Error(`${asset.name} optimization changed decoded RGBA pixels`);
  writeOrCheck(target, optimized.png);
  after += optimized.png.length;
  console.log(`${asset.name}: ${optimized.encoding}, ${optimized.colors} colors, ${original.length} -> ${optimized.png.length} bytes`);
}
console.log(`Scenic release catalog: ${assets.length} files, ${before} -> ${after} bytes (${before - after} bytes saved)`);
