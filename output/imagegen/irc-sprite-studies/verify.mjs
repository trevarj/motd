import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';

// Drive the real gallery with the pinned browser; keep diagnostic artifacts separate.
const artifacts = await mkdtemp('/tmp/irc-sprite-verification-');
const browser = spawn('chromium', [
  '--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${artifacts}/profile`, 'about:blank',
], { stdio: ['ignore', 'ignore', 'pipe'] });
let socket;
try {
  const endpoint = await new Promise((resolve, reject) => {
    let logs = '';
    const deadline = setTimeout(() => reject(new Error(`Browser startup timed out: ${logs.slice(-1200)}`)), 30000);
    browser.once('error', reject);
    browser.once('exit', code => { clearTimeout(deadline); reject(new Error(`Browser exited ${code}: ${logs.slice(-1200)}`)); });
    browser.stderr.on('data', data => {
      logs += data;
      const match = logs.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) { clearTimeout(deadline); resolve(match[1]); }
    });
  });
  const targets = await (await fetch(`http://${new URL(endpoint).host}/json/list`)).json();
  socket = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once:true}); socket.addEventListener('error', reject, {once:true}); });
  let nextId = 0;
  const calls = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const callback = calls.get(message.id);
    if (callback) { calls.delete(message.id); message.error ? callback.reject(new Error(JSON.stringify(message.error))) : callback.resolve(message.result); }
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    calls.set(id, {resolve,reject});
    socket.send(JSON.stringify({id,method,params}));
  });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, awaitPromise:true, returnByValue:true});
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', {width:1600,height:1100,deviceScaleFactor:1,mobile:false});
  await send('Page.navigate', {url:'http://127.0.0.1:8765/'});
  await evaluate(`new Promise((resolve,reject) => {
    const start=Date.now(); const poll=()=>{
      if (document.querySelectorAll('.style-card').length===5 && [...document.querySelectorAll('.style-card select')].every(s=>!s.disabled)) return resolve(true);
      if(Date.now()-start>60000) return reject(new Error('Gallery did not load all five kits'));
      setTimeout(poll,200);
    };poll();
  })`);
  const report = await evaluate(`(async()=>{
    const manifest=await (await fetch('manifest.json')).json();
    const report={styles:[],assets:0};
    for(const style of manifest.styles){
      const assets=[style.reference,...Object.values(style.components).flat().map(a=>a.src)];
      for(const src of assets){
        const image=new Image();image.src=src;await image.decode();
        const c=document.createElement('canvas');c.width=image.width;c.height=image.height;
        const x=c.getContext('2d',{willReadFrequently:true});x.drawImage(image,0,0);
        const data=x.getImageData(0,0,c.width,c.height).data;
        let transparent=0,solid=0;for(let i=3;i<data.length;i+=4){if(data[i]===0)transparent++;if(data[i]>200)solid++;}
        if(transparent===0||solid===0)throw new Error(src+' lacks usable alpha or content');
        report.assets++;
      }
    }
    for(const root of document.querySelectorAll('.style-card')){
      const selects=[...root.querySelectorAll('select')];
      const canvas=root.querySelector('.hero-canvas');
      const sheet=document.createElement('canvas');sheet.width=9*144;sheet.height=6*164;
      const ctx=sheet.getContext('2d');ctx.fillStyle='#20232a';ctx.fillRect(0,0,sheet.width,sheet.height);
      let count=0;const hashes=new Set();
      const walk=(i)=>{if(i===selects.length){
        const pixels=canvas.getContext('2d').getImageData(512,552,1,160).data;
        for(let y=0;y<160;y++)if(pixels[y*4+3]<200)throw new Error('Visible head/neck gap in '+root.querySelector('h2').textContent+' at '+(552+y));
        const src=canvas.toDataURL();hashes.add(src);
        const col=count%9,row=Math.floor(count/9);ctx.drawImage(canvas,col*144,row*164,144,144);
        ctx.fillStyle='#f0f0f0';ctx.font='10px sans-serif';ctx.fillText(selects.map(s=>s.selectedIndex).join('/'),col*144+8,row*164+156);
        count++;return;
      }const select=selects[i];for(const option of select.options){select.value=option.value;select.dispatchEvent(new Event('change',{bubbles:true}));walk(i+1);}};
      walk(0);
      if(count!==54||hashes.size!==54)throw new Error(root.querySelector('h2').textContent+': duplicate/missing combinations '+count+'/'+hashes.size);
      const color=root.querySelector('input[type=color]');const before=canvas.toDataURL();
      color.value='#ff3030';color.dispatchEvent(new Event('input',{bubbles:true}));
      if(before===canvas.toDataURL())throw new Error('Tint control did not alter image');
      root.querySelector('.preset').click();
      report.styles.push({name:root.querySelector('h2').textContent,combinations:count,unique:hashes.size,sheet:sheet.toDataURL()});
    }
    return report;
  })()`);
  assert.equal(report.assets,60);
  await mkdir(`${artifacts}/sheets`);
  for (let i=0;i<report.styles.length;i++) {
    await writeFile(`${artifacts}/sheets/${i+1}.png`,Buffer.from(report.styles[i].sheet.split(',')[1],'base64'));
    delete report.styles[i].sheet;
  }
  for(const surface of ['dark','light','checker']) {
    await evaluate(`document.querySelector('[data-surface-choice="${surface}"]').click()`);
    assert.equal(await evaluate('document.body.dataset.surface'),surface);
    const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
    await writeFile(`${artifacts}/${surface}.png`,Buffer.from(screenshot.data,'base64'));
  }
  await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  const overflow=await evaluate('document.documentElement.scrollWidth > window.innerWidth');
  assert.equal(overflow,false,'Mobile gallery overflows horizontally');
  const mobile=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
  await writeFile(`${artifacts}/mobile.png`,Buffer.from(mobile.data,'base64'));
  await writeFile(`${artifacts}/report.json`,JSON.stringify(report,null,2));
  console.log(JSON.stringify({artifacts,...report},null,2));
} finally {
  socket?.close();
  browser.kill('SIGTERM');
}
