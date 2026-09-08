import { spawn } from 'node:child_process';
import { mkdtemp } from 'node:fs/promises';

// Use the project's pinned headless browser for the same canvas pipeline as the gallery.
export async function withBrowser(url, run) {
  const profile = await mkdtemp('/tmp/motd-sprite-browser-');
  const browser = spawn('chromium', ['--headless', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
    { stdio: ['ignore', 'ignore', 'pipe'] });
  let socket;
  try {
    const endpoint = await new Promise((resolve, reject) => {
      let logs = '';
      const timer = setTimeout(() => reject(new Error(`Browser startup timeout: ${logs.slice(-1000)}`)), 30000);
      browser.once('error', reject);
      browser.once('exit', code => { clearTimeout(timer); reject(new Error(`Browser exited ${code}: ${logs.slice(-1000)}`)); });
      browser.stderr.on('data', data => {
        logs += data;
        const match = logs.match(/DevTools listening on (ws:\/\/[^\s]+)/);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
    });
    const targets = await (await fetch(`http://${new URL(endpoint).host}/json/list`)).json();
    socket = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, {once:true});
      socket.addEventListener('error', reject, {once:true});
    });
    let next = 0;
    const pending = new Map();
    socket.addEventListener('message', e => {
      const m = JSON.parse(e.data), call = pending.get(m.id);
      if (!call) return;
      pending.delete(m.id);
      clearTimeout(call.timer);
      m.error ? call.reject(new Error(JSON.stringify(m.error))) : call.resolve(m.result);
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++next;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 180000);
      pending.set(id, {resolve,reject,timer});
      socket.send(JSON.stringify({id,method,params}));
    });
    const evaluate = async expression => {
      const result = await send('Runtime.evaluate', {expression,awaitPromise:true,returnByValue:true});
      if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
      return result.result.value;
    };
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', {width:1600,height:1100,deviceScaleFactor:1,mobile:false});
    await send('Page.navigate', {url});
    await new Promise(resolve => setTimeout(resolve, 500));
    return await run({send,evaluate});
  } finally {
    socket?.close();
    browser.kill('SIGTERM');
  }
}
