// Run with the gallery already serving: nix develop .#sprite-studies -c node tools/wallpapers/gallery/scenic-browser-check.mjs --port 4173
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { spawn } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const portIndex = process.argv.indexOf("--port");
const port = portIndex === -1 ? 4173 : Number(process.argv[portIndex + 1]);
assert.ok(Number.isInteger(port) && port > 0 && port < 65536, "--port must be a valid port");
const studioUrl = `http://127.0.0.1:${port}`;
const argument = name => process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : null;
const requestedScene = argument("--scene");
const requestedVersion = argument("--version");
const profile = await mkdtemp(path.join(os.tmpdir(), "motd-scenic-events-browser-"));
const browser = spawn("chromium", ["--headless", "--disable-gpu", "--disable-dev-shm-usage", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
let stderr = "";
browser.stderr.on("data", chunk => { stderr += chunk; });
let socket;

try {
  for (let attempt = 0; attempt < 100 && !stderr.includes("DevTools listening"); attempt++) await delay(100);
  const debugPort = /DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//.exec(stderr)?.[1];
  assert.ok(debugPort, stderr.slice(-2000));
  const pages = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === "page").webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  const exceptions = [];
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") exceptions.push(message.params.exceptionDetails);
    if (message.id && pending.has(message.id)) {
      const [resolve, reject] = pending.get(message.id);
      pending.delete(message.id);
      message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
    }
  };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId; pending.set(id, [resolve, reject]); socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await call("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const waitFor = async (expression, context = expression) => {
    for (let attempt = 0; attempt < 160; attempt++) { if (await evaluate(expression)) return; await delay(50); }
    assert.fail(`Timed out: ${context}`);
  };
  const canvasState = () => evaluate(`(() => {
    const canvases = [...document.querySelectorAll('.scenic-art canvas')];
    const result = canvases.map(canvas => {
      const context = canvas.getContext('2d');
      return { width: canvas.width, height: canvas.height, data: Array.from(context.getImageData(0, 0, canvas.width, canvas.height).data) };
    });
    window.scenicTestBaseline = result.map(canvas => canvas.data);
    return result;
  })()`);

  await call("Runtime.enable"); await call("Page.enable");
  await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1050, deviceScaleFactor: 1, mobile: false });
  await call("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "light" }, { name: "prefers-reduced-motion", value: "no-preference" }] });
  await call("Page.navigate", { url: `${studioUrl}/scenic${requestedScene ? "?scene=" + encodeURIComponent(requestedScene) : ""}` });
  await waitFor("document.querySelectorAll('.scene-card').length === 5", "studio collection");
  const model = await (await fetch(`${studioUrl}/api/scenic`)).json();
  const studies = model.scenes.flatMap(currentScene => currentScene.versions.filter(version => version.motion === "pixel-events").map(version => ({ ...version, sceneId: currentScene.id })))
    .filter(study => (!requestedScene || study.sceneId === requestedScene) && (!requestedVersion || study.id === requestedVersion));
  assert.ok(studies.length > 0, "Expected matching pixel-event studies");
  if (requestedScene) await waitFor(`document.querySelector("#preview-pair").dataset.scene === ${JSON.stringify(requestedScene)}`, "linked scene selection");

  for (const study of studies) {
    const eventDocument = await (await fetch(`${studioUrl}${study.eventsUrl}`)).json();
    assert.deepEqual(eventDocument.initialDelayMs, [20000, 40000], `${study.sceneId}/${study.id} initial cadence`);
    assert.deepEqual(eventDocument.quietIntervalMs, [45000, 90000], `${study.sceneId}/${study.id} quiet cadence`);
    const sceneIndex = model.scenes.findIndex(currentScene => currentScene.id === study.sceneId);
    await evaluate(`(() => {
      document.querySelectorAll('.scene-card')[${sceneIndex}].click();
      const select = document.querySelector('#version');
      select.value = ${JSON.stringify(study.id)};
      select.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
    await waitFor(`!document.querySelector('#event-control').hidden && document.querySelector('#version').value === ${JSON.stringify(study.id)} && document.querySelectorAll('.scenic-art canvas:not([hidden])').length === 2 && [...document.querySelectorAll('.scenic-art canvas')].every(canvas => canvas.toDataURL().length > 10000) && /^\\d+$/.test(document.querySelector('#preview-pair').dataset.scheduleDue ?? '')`, `${study.sceneId}/${study.id} resting master and production schedule`);
    assert.deepEqual(await evaluate("[...document.querySelectorAll('.scenic-art canvas')].map(canvas => [canvas.width, canvas.height])"), [[256, 448], [256, 448]]);
    const remaining = Number(await evaluate("document.querySelector('#preview-pair').dataset.scheduleDue")) - Date.now();
    assert.ok(remaining > 15000 && remaining <= 40000, `${study.sceneId}/${study.id} must retain a 20–40s production initial delay, got ${remaining}ms`);
    const baseline = await canvasState();

    for (const declaredEvent of eventDocument.events) {
      await evaluate(`(() => {
        const select = document.querySelector('#event-select');
        select.value = ${JSON.stringify(declaredEvent.id)};
        select.dispatchEvent(new Event('change', { bubbles: true }));
        document.querySelector('#preview-motion').click();
      })()`);
      await waitFor(`document.querySelector('#preview-pair').dataset.eventId === ${JSON.stringify(declaredEvent.id)} && document.querySelector('#preview-pair').dataset.eventFrame === '0'`, `${study.sceneId}/${study.id}/${declaredEvent.id} first event frame`);
      assert.match(await evaluate("document.querySelector('#motion-status').textContent"), /^Triggered preview · /);
      const firstFrame = declaredEvent.frames[0];
      const proof = await evaluate(`(() => {
        const masks = ${JSON.stringify([firstFrame.light, firstFrame.dark])};
        const baseline = window.scenicTestBaseline;
        return [...document.querySelectorAll('.scenic-art canvas')].map((canvas, mode) => {
          const data = canvas.getContext('2d').getImageData(0, 0, 256, 448).data;
          const mask = new Map(masks[mode].map(([x, y, color]) => [x + ',' + y, color]));
          let outsideChanged = 0; let declaredMismatch = 0; let declaredChanged = 0;
          for (let y = 0; y < 448; y++) for (let x = 0; x < 256; x++) {
            const offset = (y * 256 + x) * 4; const color = mask.get(x + ',' + y);
            if (!color) {
              if (data[offset] !== baseline[mode][offset] || data[offset + 1] !== baseline[mode][offset + 1] || data[offset + 2] !== baseline[mode][offset + 2] || data[offset + 3] !== baseline[mode][offset + 3]) outsideChanged++;
            } else {
              const expected = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)];
              if (data[offset] !== expected[0] || data[offset + 1] !== expected[1] || data[offset + 2] !== expected[2]) declaredMismatch++;
              if (data[offset] !== baseline[mode][offset] || data[offset + 1] !== baseline[mode][offset + 1] || data[offset + 2] !== baseline[mode][offset + 2]) declaredChanged++;
            }
          }
          return { outsideChanged, declaredMismatch, declaredChanged, maskSize: mask.size };
        });
      })()`);
      proof.forEach((mode, index) => {
        assert.equal(mode.outsideChanged, 0, `${study.sceneId}/${study.id}/${declaredEvent.id} mode ${index}: pixels outside declared mask changed`);
        assert.equal(mode.declaredMismatch, 0, `${study.sceneId}/${study.id}/${declaredEvent.id} mode ${index}: declared replacements were not exact`);
        if (mode.maskSize) assert.ok(mode.declaredChanged > 0, `${study.sceneId}/${study.id}/${declaredEvent.id} mode ${index}: declared mask did not visibly change`);
      });
      const eventDuration = declaredEvent.frames.reduce((total, frame) => total + frame.durationMs, 0);
      await delay(eventDuration + 120);
      const after = await canvasState();
      assert.deepEqual(after.map(canvas => canvas.data), baseline.map(canvas => canvas.data), `${study.sceneId}/${study.id}/${declaredEvent.id} final empty frame must restore resting pixels exactly`);
    }
  }

  await click("button[data-display='single']");
  await call("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "dark" }, { name: "prefers-reduced-motion", value: "no-preference" }] });
  await waitFor("document.querySelector('#preview-pair').dataset.activeSide === 'dark'", "Follow System dark");
  assert.equal(await evaluate("getComputedStyle(document.querySelector('.phone[data-side=light]')).display"), "none");
  assert.notEqual(await evaluate("getComputedStyle(document.querySelector('.phone[data-side=dark]')).display"), "none");
  assert.deepEqual(exceptions, []);
  console.log(`PASS: ${studies.length} revision-02 variants and ${studies.reduce((total, study) => total + study.eventCount, 0)} declared events preserve production timing, authored masks, exact pixels, resting frames, and Follow System.`);
} finally {
  socket?.close();
  if (browser.exitCode === null) await new Promise(resolve => { browser.once("exit", resolve); browser.kill(); });
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}
