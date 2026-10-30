import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { buildGallery, buildScenicGallery, createServer, resolveArtwork, resolveScenicAsset, themes } from "./server.mjs";

function testRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "motd-wallpaper-gallery-"));
}

function writeManifest(root, value) {
  const directory = path.join(root, "build/wallpaper-previews");
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, "manifest.json"), value);
  return directory;
}

function writePng(root, file, width = 256, height = 448) {
  const target = path.join(root, "tools/wallpapers/scenic", file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const header = Buffer.alloc(24);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(header);
  header.writeUInt32BE(width, 16); header.writeUInt32BE(height, 20);
  fs.writeFileSync(target, header);
}

function revisionEventDocument(overrides = {}) {
  return {
    schemaVersion: 1, width: 256, height: 448, scheduleSeed: "canal-pilot", initialDelayMs: [20000, 40000], quietIntervalMs: [45000, 90000],
    events: [{ id: "ripple", label: "Reflection ripple", frames: [{ durationMs: 120, light: [[12, 42, "#abcdef"]], dark: [[12, 42, "#123456"]] }, { durationMs: 120, light: [], dark: [] }], }],
    ...overrides,
  };
}

test("gallery remains usable when the preview manifest is absent or malformed", () => {
  const root = testRoot();
  assert.equal(buildGallery(root).manifestError, null);
  assert.equal(buildGallery(root).themes.length, themes.length);
  writeManifest(root, "{");
  assert.match(buildGallery(root).manifestError, /not valid JSON/);
  assert.equal(buildGallery(root).themes.flatMap(theme => theme.assets).length, 0);
});

test("all registered shipped PNGs are available without a draft manifest", () => {
  const root = testRoot();
  const directory = path.join(root, "app/src/main/assets/chat-wallpapers");
  fs.mkdirSync(directory, { recursive: true });
  for (const theme of themes) fs.writeFileSync(path.join(directory, `${theme.id}.png`), "png");
  const gallery = buildGallery(root);
  assert.equal(gallery.themes.length, themes.length);
  assert.ok(gallery.themes.some(theme => theme.id === "motd"));
  for (const theme of gallery.themes) {
    assert.equal(theme.assets.length, 1);
    assert.equal(theme.assets[0].status, "Final");
    assert.equal(resolveArtwork(root, theme.assets[0].id).file, path.join(directory, `${theme.id}.png`));
  }
});

test("registered source atlases are exposed separately and cannot escape their directory", () => {
  const root = testRoot();
  const directory = path.join(root, "tools/wallpapers/atlases");
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, "linux.png"), "atlas");
  const gallery = buildGallery(root);
  const linux = gallery.themes.find(theme => theme.id === "linux");
  assert.deepEqual(linux.assets.map(asset => ({ id: asset.id, status: asset.status, source: asset.source })), [{
    id: "linux-atlas",
    status: "Atlas",
    source: "atlas",
  }]);
  assert.equal(resolveArtwork(root, "linux-atlas")?.file, path.join(directory, "linux.png"));

  const outside = path.join(root, "outside.png");
  fs.writeFileSync(outside, "private");
  fs.symlinkSync(outside, path.join(directory, "deep-space.png"));
  assert.equal(resolveArtwork(root, "deep-space-atlas"), null);
  assert.equal(buildGallery(root).themes.find(theme => theme.id === "deep-space").assets.length, 0);
});

test("only registered png studies can become artwork routes", () => {
  const root = testRoot();
  const directory = writeManifest(root, JSON.stringify({ assets: [
    { id: "retro-chat-v1", theme: "retro-chat", label: "Retro Chat · Study 01", file: "retro-chat-v1.png", status: "Draft" },
    { id: "escape", theme: "memes", file: "../outside.png" },
    { id: ["retro-chat-v2"], theme: "retro-chat", file: "retro-chat-v1.png" },
    { id: "memes-v2", theme: ["memes"], file: "retro-chat-v1.png" },
    { id: "memes-v3", theme: "memes", file: ["retro-chat-v1.png"] },
  ] }));
  fs.writeFileSync(path.join(directory, "retro-chat-v1.png"), "png");
  fs.writeFileSync(path.join(root, "outside.png"), "private");
  const gallery = buildGallery(root);
  assert.equal(gallery.themes.find(theme => theme.id === "retro-chat").assets.length, 1);
  assert.equal(resolveArtwork(root, "retro-chat-v1")?.file, path.join(directory, "retro-chat-v1.png"));
  assert.equal(resolveArtwork(root, "escape"), null);
  assert.equal(resolveArtwork(root, "../../outside"), null);
});

test("registered artwork cannot escape its approved directory through a symlink", () => {
  const root = testRoot();
  const directory = writeManifest(root, JSON.stringify({ assets: [
    { id: "memes-v1", theme: "memes", file: "memes-v1.png" },
  ] }));
  const outside = path.join(root, "outside.png");
  fs.writeFileSync(outside, "private");
  fs.symlinkSync(outside, path.join(directory, "memes-v1.png"));
  assert.equal(resolveArtwork(root, "memes-v1"), null);
});

test("scenic studies expose only complete allowlisted paired versions", () => {
  const root = testRoot();
  const directory = path.join(root, "tools/wallpapers/scenic");
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, "village.png"), "day-night");
  fs.writeFileSync(path.join(directory, "cafe-day.png"), "day");
  fs.writeFileSync(path.join(directory, "cafe-night.png"), "night");
  fs.writeFileSync(path.join(directory, "manifest.json"), JSON.stringify({ scenes: [
    { id: "japanese-countryside", title: "Japanese countryside", description: "Railway village", versions: [
      { id: "summer", label: "Summer", season: "Summer", light: "village.png", dark: "village.png", paired: true, motion: "pixel-shimmer" },
      { id: "broken", label: "Broken", light: "missing.png", dark: "missing.png" },
    ] },
    { id: "rainy-cafe", title: "Rainy café", versions: [{ id: "v1", label: "Study 01", light: "cafe-day.png", dark: "cafe-night.png", motion: "pixel-shimmer" }] },
    { id: "japanese-countryside", title: "Duplicate", versions: [{ id: "v2", label: "Nope", light: "village.png", dark: "village.png" }] },
  ] }));
  const scenic = buildScenicGallery(root);
  assert.deepEqual(scenic.scenes.map(scene => [scene.id, scene.versions.map(version => version.id)]), [["japanese-countryside", ["summer"]], ["rainy-cafe", ["v1"]]]);
  const summer = scenic.scenes[0].versions[0];
  assert.equal(summer.paired, true);
  assert.match(summer.lightUrl, /^\/scenic-assets\/village\.png\?v=/);
  assert.equal(resolveScenicAsset(root, "village.png")?.file, path.join(directory, "village.png"));
  assert.equal(resolveScenicAsset(root, "missing.png"), null);
  assert.equal(resolveScenicAsset(root, "../village.png"), null);
});

test("revision-02 pixel events require exact masters and expose only their declared nested assets", async t => {
  const root = testRoot();
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  writePng(root, "revision-02/electric-canal-day.png");
  writePng(root, "revision-02/electric-canal-night.png");
  const directory = path.join(root, "tools/wallpapers/scenic/revision-02");
  fs.writeFileSync(path.join(directory, "electric-canal-events.json"), JSON.stringify(revisionEventDocument()));
  fs.writeFileSync(path.join(root, "tools/wallpapers/scenic/manifest.json"), JSON.stringify({ scenes: [{
    id: "cyberpunk-canal", title: "Electric canal", versions: [{ id: "revision-02", label: "Revision 02", motion: "pixel-events", paired: false, light: "revision-02/electric-canal-day.png", dark: "revision-02/electric-canal-night.png", events: "revision-02/electric-canal-events.json" }],
  }] }));
  const model = buildScenicGallery(root);
  const version = model.scenes[0].versions[0];
  assert.equal(version.eventCount, 1);
  assert.match(version.eventsUrl, /^\/scenic-assets\/revision-02\/electric-canal-events\.json\?v=/);
  assert.equal(resolveScenicAsset(root, "revision-02/electric-canal-events.json")?.file, path.join(directory, "electric-canal-events.json"));
  assert.equal(resolveScenicAsset(root, "revision-02/private.json"), null);
  assert.equal(resolveScenicAsset(root, "../revision-02/electric-canal-events.json"), null);
  const server = createServer(root);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(`${base}${version.eventsUrl}`)).headers.get("content-type"), "application/json; charset=utf-8");
  assert.equal((await fetch(`${base}/scenic-assets/revision-02/private.json`)).status, 404);
});

test("revision-02 rejects non-restoring frames, duplicate pixels, and wrong master dimensions", () => {
  const root = testRoot();
  const directory = path.join(root, "tools/wallpapers/scenic/revision-02");
  fs.mkdirSync(directory, { recursive: true });
  const manifest = eventName => JSON.stringify({ scenes: [{ id: "rainy-cafe", title: "Rainy café", versions: [{ id: "revision-02", label: "Revision 02", motion: "pixel-events", paired: false, light: "revision-02/day.png", dark: "revision-02/night.png", events: `revision-02/${eventName}` }] }] });
  writePng(root, "revision-02/day.png", 256, 448); writePng(root, "revision-02/night.png", 256, 448);
  fs.writeFileSync(path.join(directory, "bad-events.json"), JSON.stringify(revisionEventDocument({ events: [{ id: "bad", label: "Bad", frames: [{ durationMs: 10, light: [[1, 1, "#ffffff"], [1, 1, "#000000"]], dark: [] }, { durationMs: 10, light: [[2, 2, "#ffffff"]], dark: [] }] }]})));
  fs.writeFileSync(path.join(root, "tools/wallpapers/scenic/manifest.json"), manifest("bad-events.json"));
  assert.equal(buildScenicGallery(root).scenes.length, 0);
  fs.writeFileSync(path.join(directory, "good-events.json"), JSON.stringify(revisionEventDocument()));
  writePng(root, "revision-02/day.png", 255, 448);
  fs.writeFileSync(path.join(root, "tools/wallpapers/scenic/manifest.json"), manifest("good-events.json"));
  assert.equal(buildScenicGallery(root).scenes.length, 0);
});

test("HTTP serves exact PNG finals and studies without exposing unregistered or escaped files", async t => {
  const root = testRoot();
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const finals = path.join(root, "app/src/main/assets/chat-wallpapers");
  const atlases = path.join(root, "tools/wallpapers/atlases");
  fs.mkdirSync(finals, { recursive: true });
  fs.mkdirSync(atlases, { recursive: true });
  const draftDir = writeManifest(root, JSON.stringify({ assets: [
    { id: "linux-draft", theme: "linux", file: "linux-draft.png" },
    { id: "unsafe", theme: "linux", file: "../outside.png" },
    { id: "webp-draft", theme: "linux", file: "unregistered.webp" },
  ] }));
  const webp = Buffer.from("RIFF\u0004\u0000\u0000\u0000WEBP", "binary");
  const png = Buffer.from("\u0089PNG\r\n\u001a\n", "binary");
  fs.writeFileSync(path.join(finals, "linux.png"), png);
  fs.writeFileSync(path.join(atlases, "linux.png"), png);
  fs.writeFileSync(path.join(draftDir, "linux-draft.png"), png);
  fs.writeFileSync(path.join(draftDir, "unregistered.webp"), webp);
  const outside = path.join(root, "outside.png");
  fs.writeFileSync(outside, png);
  fs.symlinkSync(outside, path.join(finals, "deep-space.png"));
  fs.writeFileSync(path.join(finals, "unregistered.png"), png);
  fs.writeFileSync(path.join(finals, "memes.webp"), webp);
  const server = createServer(root);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  for (const [id, type, bytes] of [
    ["linux-final", "image/png", png],
    ["linux-atlas", "image/png", png],
    ["linux-draft", "image/png", png],
  ]) {
    const response = await fetch(`${base}/api/artwork/${id}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("content-type"), type);
    assert.equal(response.headers.get("x-content-type-options"), "nosniff");
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
    const head = await fetch(`${base}/api/artwork/${id}`, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.equal(head.headers.get("content-length"), String(bytes.length));
    assert.equal((await head.arrayBuffer()).byteLength, 0);
  }
  for (const id of ["unsafe", "webp-draft", "unregistered-final", "deep-space-final", "memes-final", "..%2Foutside.png"]) {
    assert.equal((await fetch(`${base}/api/artwork/${id}`)).status, 404);
  }
  assert.equal((await fetch(`${base}/api/artwork/linux-final`, { method: "POST" })).status, 405);
});

test("HTTP serves only scenic manifest assets", async t => {
  const root = testRoot();
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const directory = path.join(root, "tools/wallpapers/scenic");
  fs.mkdirSync(directory, { recursive: true });
  const png = Buffer.from("\u0089PNG\r\n\u001a\n", "binary");
  const webp = Buffer.from("RIFF\u0004\u0000\u0000\u0000WEBP", "binary");
  fs.writeFileSync(path.join(directory, "scene.png"), png);
  fs.writeFileSync(path.join(directory, "scene-loop.webp"), webp);
  fs.writeFileSync(path.join(directory, "private.png"), png);
  fs.writeFileSync(path.join(directory, "manifest.json"), JSON.stringify({ scenes: [{
    id: "neon-arcade", title: "Neon arcade", versions: [{ id: "v1", label: "Study 01", light: "scene.png", dark: "scene.png", loop: "scene-loop.webp", paired: true }],
  }] }));
  const server = createServer(root);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const model = await (await fetch(`${base}/api/scenic`)).json();
  assert.equal(model.scenes[0].id, "neon-arcade");
  const asset = await fetch(`${base}/scenic-assets/scene.png`);
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get("content-type"), "image/png");
  assert.deepEqual(Buffer.from(await asset.arrayBuffer()), png);
  const loop = await fetch(`${base}/scenic-assets/scene-loop.webp`);
  assert.equal(loop.status, 200);
  assert.equal(loop.headers.get("content-type"), "image/webp");
  assert.deepEqual(Buffer.from(await loop.arrayBuffer()), webp);
  for (const pathName of ["private.png", "..%2Foutside.png", "scene.webp"]) assert.equal((await fetch(`${base}/scenic-assets/${pathName}`)).status, 404);
});
