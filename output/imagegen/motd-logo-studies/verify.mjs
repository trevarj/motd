import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { access, readFile, writeFile } from "node:fs/promises";
import { once } from "node:events";
import { withBrowser } from "../irc-sprite-studies/v2/browser.mjs";

const root = new URL("./", import.meta.url);
const required = [
  "index.html", "gallery.js", "server.mjs", "prompts.json",
  "original-black.png", "original-white.png", "wordmark-black.png", "wordmark-white.png",
  "01-crisp-flat.png", "02-satin-ink.png", "03-matte-ceramic.png", "04-soft-bevel.png", "05-satin-metal.png",
];
for (const file of required) await access(new URL(file, root));

const html = await readFile(new URL("index.html", root), "utf8");
assert.match(html, /24 · 48 · 96 px checks/);
const prompts = JSON.parse(await readFile(new URL("prompts.json", root), "utf8"));
assert.deepEqual(prompts.variants.map(({ id }) => id), [
  "01-crisp-flat", "02-satin-ink", "03-matte-ceramic", "04-soft-bevel", "05-satin-metal",
]);

const server = spawn("node", ["server.mjs"], { cwd: new URL(".", root), stdio: ["ignore", "pipe", "pipe"] });
let output = "";
server.stdout.on("data", (data) => { output += data; });
server.stderr.on("data", (data) => { output += data; });
try {
  await Promise.race([
    once(server.stdout, "data"),
    once(server, "exit").then(([code]) => { throw new Error(`Server exited ${code}: ${output}`); }),
  ]);
  const page = await fetch("http://127.0.0.1:8766/");
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type") ?? "", /^text\/html/);
  assert.match(await page.text(), /Comparison board/);
  const source = await fetch("http://127.0.0.1:8766/01-crisp-flat.png");
  assert.equal(source.status, 200);
  assert.equal(source.headers.get("content-type"), "image/png");
  const blocked = await fetch("http://127.0.0.1:8766/%2e%2e/AGENTS.md");
  assert.equal(blocked.status, 404);
  await withBrowser("http://127.0.0.1:8766/", async ({ evaluate, send }) => {
    const result = await evaluate(`(async () => {
      await Promise.all([...document.images].map((image) => image.decode()));
      const initial = document.body.dataset.theme;
      document.querySelector('#theme-toggle').click();
      await new Promise(requestAnimationFrame);
      await new Promise(requestAnimationFrame);
      await new Promise((resolve) => setTimeout(resolve, 300));
      const toggled = document.body.dataset.theme;
      return {
        ready: window.motdLogoStudiesReady,
        cards: document.querySelectorAll('.study').length,
        images: document.images.length,
        broken: [...document.images].filter((image) => !image.complete || image.naturalWidth === 0).length,
        initial,
        toggled,
      };
    })()`);
    assert.equal(result.ready, true);
    assert.equal(result.cards, 6);
    assert.equal(result.broken, 0);
    assert.notEqual(result.initial, result.toggled);
    assert.equal(await evaluate("getComputedStyle(document.body).backgroundColor"), "rgb(22, 22, 22)");
    assert.equal(await evaluate("getComputedStyle(document.body).color"), "rgb(242, 242, 237)");
    const desktop = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(new URL("gallery-desktop.png", root), Buffer.from(desktop.data, "base64"));
    await send("Emulation.setDeviceMetricsOverride", {
      width: 390, height: 844, deviceScaleFactor: 1, mobile: true,
    });
    assert.equal(await evaluate("document.documentElement.scrollWidth > innerWidth"), false);
    assert.equal(await evaluate("[...document.querySelectorAll('.surface .sizes')].every((sizes) => sizes.scrollWidth <= sizes.clientWidth)"), true);
    const mobile = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(new URL("gallery-mobile.png", root), Buffer.from(mobile.data, "base64"));
  });
  console.log("gallery HTTP and traversal checks passed");
} finally {
  server.kill("SIGTERM");
  await once(server, "exit");
}
