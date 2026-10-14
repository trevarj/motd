import assert from "node:assert/strict";
import test from "node:test";
import { createServer } from "./server.mjs";

test("sticker gallery exposes only local registered routes", async t => {
  const server = createServer();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;

  const model = await (await fetch(`${base}/api/stickers`)).json();
  const target = "https://github.com/trevarj/motd/releases";
  assert.equal(model.stickers.length, 6);
  assert.ok(model.stickers.every(sticker => /^\d{2}-[a-z0-9-]+$/.test(sticker.slug)));
  assert.equal(model.edition, "v3");
  assert.equal(model.target, target);
  assert.equal((await (await fetch(`${base}/prompts.json`)).json()).length, 6);
  assert.equal((await (await fetch(`${base}/prompts-v3.json`)).json()).length, 6);
  for (const route of ["exports", "exports-v2", "exports-v3"]) {
    const manifest = await (await fetch(`${base}/${route}/manifest.json`)).json();
    assert.equal(manifest.target, target);
    const assets = manifest.assets.filter(asset => asset.available);
    assert.equal(assets.length, 6);
    assert.ok(assets.every(asset => asset.qr.target === target && asset.qr.errorCorrection === "M" && asset.qr.quietZoneModules === 4 && asset.qr.dataModules === 29));
  }
  assert.equal((await fetch(`${base}/gallery.css`)).headers.get("content-type"), "text/css; charset=utf-8");
  assert.equal((await fetch(`${base}/exports/%2e%2e%2fprompts.json`)).status, 404);
  assert.equal((await fetch(`${base}/exports/not-a-sticker.svg`)).status, 404);
  assert.equal((await fetch(`${base}/api/stickers`, { method: "POST" })).status, 405);
});
