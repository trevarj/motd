// With the local gallery running: nix develop .#sprite-studies -c node stickers/browser-check.mjs
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const baseUrl = "http://127.0.0.1:4174/gallery/";
const output = path.resolve("build/sticker-previews-release/qa");
await mkdir(output, { recursive: true });
const profile = await mkdtemp(path.join(os.tmpdir(), "motd-sticker-gallery-"));
const browser = spawn("chromium", ["--headless", "--disable-gpu", "--disable-dev-shm-usage", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
let stderr = "";
browser.stderr.on("data", data => { stderr += data; });

let socket;
const done = async () => {
  socket?.close();
  if (browser.exitCode === null) {
    const exited = new Promise(resolve => browser.once("exit", resolve));
    browser.kill();
    await exited;
  }
  await rm(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }).catch(() => {});
};

try {
  for (let index = 0; index < 100 && !stderr.includes("DevTools listening"); index++) await delay(100);
  const port = /DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//.exec(stderr)?.[1];
  assert.ok(port, `Chromium did not start:\n${stderr.slice(-1500)}`);
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === "page").webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let id = 0;
  const waiting = new Map();
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (!message.id || !waiting.has(message.id)) return;
    const [resolve, reject] = waiting.get(message.id);
    waiting.delete(message.id);
    message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
  };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const requestId = ++id;
    waiting.set(requestId, [resolve, reject]);
    socket.send(JSON.stringify({ id: requestId, method, params }));
  });
  const evaluate = async expression => {
    const result = await call("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true, timeout: 30000 });
    assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const shot = async name => {
    const image = await call("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(path.join(output, name), Buffer.from(image.data, "base64"));
  };

  await call("Runtime.enable");
  await call("Page.enable");
  for (const [name, width, height, mobile] of [["release-desktop.png", 1440, 1200, false], ["release-mobile.png", 390, 844, true]]) {
    await call("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile });
    await call("Page.navigate", { url: baseUrl });
    for (let tries = 0; tries < 100 && await evaluate("document.querySelectorAll('.card img').length !== 6"); tries++) await delay(100);
    assert.equal(await evaluate("document.querySelectorAll('.card').length"), 6);
    assert.equal(await evaluate("document.querySelectorAll('.card img').length"), 6);
    await evaluate("[...document.querySelectorAll('.card img')].forEach(image => { image.loading = 'eager'; }); true");
    assert.equal(await evaluate("Promise.all([...document.querySelectorAll('.card img')].map(image => image.decode())).then(() => [...document.querySelectorAll('.card img')].every(image => image.naturalWidth > 0 && image.naturalHeight > 0))"), true);
    assert.match(await evaluate("document.querySelector('#notice').textContent"), /6\/6 proofs/);
    assert.equal(await evaluate("document.querySelectorAll('.palette').length"), 6);
    await shot(name);
  }
  await call("Page.navigate", { url: `${baseUrl}?edition=v2` });
  for (let tries = 0; tries < 100 && await evaluate("document.querySelectorAll('.card img').length !== 6"); tries++) await delay(100);
  assert.equal(await evaluate("document.querySelector('.edition-link[data-edition=\"v2\"]').getAttribute('aria-current')"), "page");
  assert.equal(await evaluate("[...document.querySelectorAll('.card img')].every(image => image.src.includes('/exports-v2/'))"), true);
  await call("Page.navigate", { url: baseUrl });
  for (let tries = 0; tries < 100 && await evaluate("document.querySelectorAll('.card img').length !== 6"); tries++) await delay(100);
  assert.equal(await evaluate("document.querySelector('.edition-link[data-edition=\"v3\"]').getAttribute('aria-current')"), "page");
  assert.equal(await evaluate("[...document.querySelectorAll('.card img')].every(image => image.src.includes('/exports-v3/'))"), true);
  await evaluate("document.querySelector('.art-button').click()");
  assert.equal(await evaluate("document.querySelector('#lightbox').open"), true);
  assert.equal(await evaluate("document.querySelectorAll('#lightbox-actions .download').length"), 2);
  assert.equal(await evaluate("document.querySelector('#print-sheet').textContent"), "PRINT A4 SHEET");
  console.log(`Browser QA passed; captures: ${output}/release-desktop.png and ${output}/release-mobile.png`);
} finally {
  await done();
}
