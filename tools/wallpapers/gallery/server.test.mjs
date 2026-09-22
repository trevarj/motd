import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { buildGallery, resolveArtwork, themes } from "./server.mjs";

function testRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "motd-wallpaper-gallery-"));
}

function writeManifest(root, value) {
  const directory = path.join(root, "build/wallpaper-previews");
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, "manifest.json"), value);
  return directory;
}

test("gallery remains usable when the preview manifest is absent or malformed", () => {
  const root = testRoot();
  assert.equal(buildGallery(root).manifestError, null);
  assert.equal(buildGallery(root).themes.length, themes.length);
  writeManifest(root, "{");
  assert.match(buildGallery(root).manifestError, /not valid JSON/);
  assert.equal(buildGallery(root).themes.flatMap(theme => theme.assets).length, 0);
});

test("all seven shipped PNGs are available without a draft manifest", () => {
  const root = testRoot();
  const directory = path.join(root, "app/src/main/assets/chat-wallpapers");
  fs.mkdirSync(directory, { recursive: true });
  for (const theme of themes) fs.writeFileSync(path.join(directory, `${theme.id}.png`), "png");
  const gallery = buildGallery(root);
  assert.equal(gallery.themes.length, 7);
  assert.ok(gallery.themes.some(theme => theme.id === "motd"));
  for (const theme of gallery.themes) {
    assert.equal(theme.assets.length, 1);
    assert.equal(theme.assets[0].status, "Final");
    assert.equal(resolveArtwork(root, theme.assets[0].id).file, path.join(directory, `${theme.id}.png`));
  }
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
