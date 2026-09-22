import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { buildSoundGallery, resolveSound } from "./server-routes.mjs";
import { createServer } from "../../wallpapers/gallery/server.mjs";

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "motd-sound-gallery-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const directory = path.join(root, "build/sound-previews");
  fs.mkdirSync(directory, { recursive: true });
  return { root, directory, write: assets => fs.writeFileSync(path.join(directory, "manifest.json"), JSON.stringify({ version: 1, assets })) };
}

const asset = { id: "soft-glass-send-balanced-0", candidate: "soft-glass", cue: "send", tone: "balanced", take: 0, file: "soft-glass-send-balanced-0.wav" };

test("missing and malformed studies preserve the current reference", t => {
  const { root, directory } = fixture(t);
  fs.mkdirSync(path.join(root, "app/src/main/res/raw"), { recursive: true });
  for (const cue of ["send", "receive"]) fs.writeFileSync(path.join(root, `app/src/main/res/raw/chat_${cue}.wav`), "RIFF");
  assert.equal(buildSoundGallery(root).referenceAvailable, true);
  assert.match(buildSoundGallery(root).error, /not been generated/);
  fs.writeFileSync(path.join(directory, "manifest.json"), "{");
  assert.match(buildSoundGallery(root).error, /invalid/);
  assert.equal(buildSoundGallery(root).candidates.length, 4);
});

test("only canonical registered WAV ids are exposed and partial pairs remain unavailable", t => {
  const { root, directory, write } = fixture(t);
  fs.writeFileSync(path.join(directory, asset.file), "RIFF");
  write([asset, asset, { ...asset, id: "escape", file: "../outside.wav" }, { ...asset, candidate: ["soft-glass"] }, { ...asset, tone: "no" }]);
  const gallery = buildSoundGallery(root);
  assert.equal(gallery.assets.length, 1);
  assert.equal(gallery.candidates[0].available, false);
  assert.equal(resolveSound(root, asset.id).file, path.join(directory, asset.file));
  for (const id of ["escape", "../../outside", "soft-glass-send-balanced-1", [asset.id]]) assert.equal(resolveSound(root, id), null);
});

test("file and directory symlinks cannot expose outside content", t => {
  const { root, directory, write } = fixture(t);
  const outside = path.join(root, "outside.wav");
  fs.writeFileSync(outside, "private");
  fs.symlinkSync(outside, path.join(directory, asset.file));
  write([asset]);
  assert.equal(resolveSound(root, asset.id), null);
  const foreign = fs.mkdtempSync(path.join(os.tmpdir(), "motd-foreign-"));
  t.after(() => fs.rmSync(foreign, { recursive: true, force: true }));
  fs.writeFileSync(path.join(foreign, "chat_send.wav"), "private");
  fs.mkdirSync(path.join(root, "app/src/main/res"), { recursive: true });
  fs.symlinkSync(foreign, path.join(root, "app/src/main/res/raw"));
  assert.equal(resolveSound(root, "current-send"), null);
});

test("HTTP serves audio and metadata with GET/HEAD only and keeps wallpaper routes", async t => {
  const { root, directory, write } = fixture(t);
  fs.writeFileSync(path.join(directory, asset.file), "RIFF");
  write([asset]);
  const server = createServer(root);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const get = await fetch(`${base}/api/sound/${asset.id}`);
  assert.equal(get.status, 200);
  assert.equal(get.headers.get("content-type"), "audio/wav");
  assert.equal(get.headers.get("cache-control"), "no-store");
  assert.equal(await get.text(), "RIFF");
  const head = await fetch(`${base}/api/sound/${asset.id}`, { method: "HEAD" });
  assert.equal(head.headers.get("content-length"), "4");
  assert.equal(await head.text(), "");
  assert.equal((await fetch(`${base}/api/sounds`, { method: "POST" })).status, 405);
  assert.equal((await fetch(`${base}/api/sound/%zz`)).status, 400);
  assert.equal((await fetch(`${base}/api/sound/..%2Foutside`)).status, 404);
  assert.equal((await (await fetch(`${base}/api/sounds`)).json()).assets.length, 1);
  assert.equal((await (await fetch(`${base}/api/gallery`)).json()).themes.length, 7);
});
