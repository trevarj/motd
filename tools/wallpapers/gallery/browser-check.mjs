// With the gallery running: nix develop .#sprite-studies -c node tools/wallpapers/gallery/browser-check.mjs
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const out = path.resolve('build/wallpaper-previews/qa');
await mkdir(out, { recursive: true });
const profile = await mkdtemp(path.join(out, 'chromium-'));
const browser = spawn('chromium', ['--headless', '--disable-gpu', '--disable-dev-shm-usage', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
let stderr = '';
browser.stderr.on('data', data => { stderr += data; });
let socket;
const timeout = setTimeout(() => { console.error(stderr.slice(-1500)); browser.kill(); process.exit(1); }, 60000);
try {
  for (let i = 0; i < 100 && !stderr.includes('DevTools listening'); i++) await delay(100);
  const port = /DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//.exec(stderr)?.[1];
  assert.ok(port, stderr.slice(-2000));
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  const exceptions = [];
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
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
    const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const capture = async name => {
    await delay(200);
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(path.join(out, `${name}.png`), Buffer.from(shot.data, 'base64'));
  };
  await call('Runtime.enable');
  await call('Page.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: 'http://127.0.0.1:4173' });
  for (let i = 0; i < 100; i++) {
    if (await evaluate(`document.querySelectorAll('.theme-card').length === 7 && document.querySelector('#inspector-heading').textContent === 'MOTD'`)) break;
    await delay(100);
  }
  assert.equal(await evaluate(`document.querySelectorAll('.theme-card').length`), 7);
  assert.equal(await evaluate(`document.querySelector('#inspector-heading').textContent`), 'MOTD');
  assert.equal(await evaluate(`document.querySelectorAll('.theme-card.has-art').length`), 7);
  await capture('desktop-original');
  await evaluate(`[...document.querySelectorAll('.theme-card')].find(el => el.querySelector('.card-name').textContent === 'Retro Chat').click()`);
  await click('#tab-repeat');
  assert.ok(await evaluate(`Math.abs(document.querySelector('#repeat-stage').getBoundingClientRect().width - document.querySelector('#repeat-stage').getBoundingClientRect().height) < 2`), 'Repeat preview must preserve square tiles');
  assert.match(await evaluate(`document.querySelector('#repeat-stage').style.getPropertyValue('--repeat-tile-size')`), /^\d+px$/, 'Repeat preview must use a whole-pixel tile size');
  await evaluate(`document.querySelector('#repeat-stage').style.width = '799px'`);
  await delay(50);
  assert.equal(
    await evaluate(`document.querySelector('#repeat-stage').style.getPropertyValue('--repeat-tile-size')`),
    await evaluate(`Math.floor(document.querySelector('#repeat-stage').clientWidth / 2) + 'px'`),
    'Odd-width repeat previews must snap the mask tile to a whole CSS pixel',
  );
  await evaluate(`document.querySelector('#repeat-stage').style.removeProperty('width')`);
  await delay(50);
  await click('#seam-guides');
  assert.ok(await evaluate(`document.querySelector('#repeat-stage').classList.contains('show-seams')`));
  await evaluate(`document.querySelector('.inspector').scrollIntoView()`);
  await capture('retro-chat-repeat');
  await click('#tab-chat');
  for (const [mode, expected] of [['light', 0.8], ['dark', 0.8], ['amoled', 0.8]]) {
    await click(`button[data-appearance="${mode}"]`);
    const opacity = await evaluate(`Number(getComputedStyle(document.querySelector('#chat-wallpaper')).opacity)`);
    assert.ok(Math.abs(opacity - expected) < 0.0001, `${mode} opacity ${opacity}`);
    if (mode === 'amoled') {
      assert.equal(await evaluate(`getComputedStyle(document.querySelector('#phone-frame')).backgroundColor`), 'rgb(0, 0, 0)');
      assert.equal(await evaluate(`getComputedStyle(document.querySelector('#phone-frame')).backgroundImage`), 'none');
    }
    for (const intensity of [0, 50, 80, 100]) {
      await evaluate(`document.querySelector('#intensity').value='${intensity}'; document.querySelector('#intensity').dispatchEvent(new Event('input', {bubbles:true}))`);
      assert.equal(await evaluate(`Number(getComputedStyle(document.querySelector('#chat-wallpaper')).opacity)`), intensity / 100, `${mode} at ${intensity}%`);
    }
    await evaluate(`document.querySelector('#intensity').value='80'; document.querySelector('#intensity').dispatchEvent(new Event('input', {bubbles:true}))`);
    await capture(`chat-${mode}`);
  }
  await evaluate(`document.querySelector('#intensity').value='0'; document.querySelector('#intensity').dispatchEvent(new Event('input', {bubbles:true}))`);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('#chat-wallpaper')).opacity`), '0');
  await evaluate(`document.querySelector('#intensity').value='100'; document.querySelector('#intensity').dispatchEvent(new Event('input', {bubbles:true}))`);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('#chat-wallpaper')).opacity`), '1');
  await evaluate(`document.querySelector('#intensity').value='80'; document.querySelector('#intensity').dispatchEvent(new Event('input', {bubbles:true}))`);
  await evaluate(`[...document.querySelectorAll('.theme-card')].find(el => el.querySelector('.card-name').textContent === 'Memes').click()`);
  await click('#tab-repeat');
  await capture('memes-repeat');
  await click('#refresh');
  await delay(400);
  assert.ok(await evaluate(`document.querySelector('#inspector-heading').textContent.includes('Memes')`));
  // Historical drafts are optional in a clean checkout; exercise them when available.
  if (await evaluate(`Boolean(document.querySelector('#variant option[value="memes-v1"]'))`)) {
    await evaluate(`document.querySelector('#variant').value='memes-v1'; document.querySelector('#variant').dispatchEvent(new Event('change', {bubbles:true}))`);
    await click('#refresh');
    await delay(300);
    assert.equal(await evaluate(`document.querySelector('#variant').value`), 'memes-v1');
    await evaluate(`document.querySelector('#variant').selectedIndex=0; document.querySelector('#variant').dispatchEvent(new Event('change', {bubbles:true}))`);
  }
  await click('#tab-original');
  await click('[data-zoom="1"]');
  assert.ok(await evaluate(`document.querySelector('#original-stage').classList.contains('zoom-1')`));
  await click('[data-zoom="2"]');
  assert.ok(await evaluate(`document.querySelector('#original-stage').classList.contains('zoom-2')`));
  await click('[data-zoom="fit"]');
  await evaluate(`[...document.querySelectorAll('.theme-card')].find(el => el.querySelector('.card-name').textContent === 'MOTD').click()`);
  assert.equal(await evaluate(`document.querySelector('#original-empty').hidden`), true);
  assert.notEqual(await evaluate(`getComputedStyle(document.querySelector('#original-art')).display`), 'none');
  await click('#tab-repeat');
  assert.notEqual(await evaluate(`getComputedStyle(document.querySelector('#repeat-art')).display`), 'none');
  await evaluate(`[...document.querySelectorAll('.theme-card')].find(el => el.querySelector('.card-name').textContent === 'Memes').click()`);
  await evaluate(`document.querySelector('#tab-original').focus(); document.querySelector('#tab-original').dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowRight', bubbles:true}))`);
  assert.equal(await evaluate(`document.activeElement.id`), 'tab-repeat');
  await click('#tab-original');
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: false });
  await evaluate('window.scrollTo(0, 0)');
  await capture('mobile-original');
  assert.ok(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), 'Page overflows mobile viewport');
  await click('#tab-chat');
  await evaluate(`document.querySelector('#panel-chat').scrollIntoView()`);
  await capture('mobile-chat');
  assert.deepEqual(exceptions, []);
  console.log('PASS: seven artworks, tabs, seam guides, three appearance modes, pure-black AMOLED, linear intensity, refresh, zoom, mobile layout, and no browser exceptions.');
} finally {
  clearTimeout(timeout);
  socket?.close();
  browser.kill();
}
