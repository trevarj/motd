import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withBrowser } from './browser.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(here, '../../../../app/src/main/assets/irc-sprites-v2');
const catalog = JSON.parse(await readFile(path.join(here, 'layout.json'), 'utf8'));
const files = Object.values(catalog.components).flat().map(p => p.file);
const sources = [];
for (const file of files) {
  const raw = ({'accessory-headset.png':'accessory-headset-v2.png','face-curious.png':'face-curious-v2.png'})[file] ?? file;
  try { await access(path.join(here, 'raw', raw)); sources.push({file,raw}); }
  catch (e) { if (!process.argv.includes('--partial')) throw e; }
}

const exported = await withBrowser('http://127.0.0.1:8765/', async ({evaluate}) => {
  return evaluate(`(${exportRasterParts.toString()})(${JSON.stringify(sources)})`);
});

// Asset generation is deterministic from retained originals; each head keeps its own anchors.
const metadata = new Map(exported.map(p => [p.file,p]));
for (const body of catalog.components.body) {
  const p = metadata.get(body.file);
  if (p) body.rect = [(1-Math.min(.58,Math.max(.42,.32*p.width/p.height)))/2,.60,Math.min(.58,Math.max(.42,.32*p.width/p.height)),.32];
}
for (const head of catalog.components.head) {
  const p = metadata.get(head.file);
  if (!p) continue;
  const aspect = p.width / p.height;
  const w = Math.min(.54,.44*aspect), h = w/aspect;
  const x = (1-w)/2, y = .64-h;
  head.rect = [x,y,w,h];
  const [sx,sy,sw,sh] = p.screen;
  const fw = sw*w*.82, fh = Math.min(sh*h*.70,fw/2.5);
  head.faceRect = [x+sx*w+(sw*w-fw)/2,y+sy*h+(sh*h-fh)/2,fw,fh];
  const top = t => y+p.tops[Math.round(t*100)]*h;
  const pairTop = Math.max(top(.2),top(.8));
  const centerTop = top(.5);
  const earY = y+h*.58;
  const headset = metadata.get('accessory-headset.png');
  const opening = headset?.opening ?? .8;
  const headsetW = Math.min(.86,w/opening*.98), headsetH = h+.075;
  // Eyewear frames the eye row, leaving the lower screen free for the mouth.
  const [fx,fy,faceW,faceH] = head.faceRect;
  const gogglesW = faceW*1.28, gogglesH = faceH*.88;
  const capW = w*1.04, capH = Math.min(.16,capW/2.8);
  const rects = {
    headset:[(1-headsetW)/2,earY-headsetH*.66,headsetW,headsetH],
    antenna:[.5-w*.42,pairTop+.025-.16,w*.84,.16],
    cap:[.5-capW/2,Math.max(top(.25),top(.75))+.025-capH,capW,capH],
    goggles:[fx+faceW/2-gogglesW/2,fy-faceH*.18,gogglesW,gogglesH],
    periscope:[.47,centerTop+.035-.16,.12,.16],
    circuit:[x-.065,y+h*.28,w+.13,.17],
    earpods:[x-.06,earY-.07,w+.12,.14],
    fin:[.46,centerTop+.025-.15,.08,.15],
    handle:[.5-w*.29,Math.max(top(.25),top(.75))+.025-.14,w*.58,.14],
    horns:[.5-w*.62,pairTop+.04-.18,w*1.24,.18],
  };
  head.accessoryRects = rects;
}
catalog.components.accessory.find(p=>p.id==='goggles').rect = catalog.components.head[0].accessoryRects.goggles;

await mkdir(app, {recursive:true});
await mkdir(path.join(here,'assets'), {recursive:true});
for (const p of exported) {
  const bytes = Buffer.from(p.png.split(',')[1], 'base64');
  await writeFile(path.join(app,p.file),bytes);
  await writeFile(path.join(here,'assets',p.file),bytes);
  delete p.png;
}
await writeFile(path.join(app,'catalog.json'),JSON.stringify(catalog,null,2)+'\n');
await writeFile(path.join(here,'assets/catalog.json'),JSON.stringify(catalog,null,2)+'\n');
await writeFile(path.join(here,'asset-metadata.json'),JSON.stringify(exported,null,2)+'\n');
console.log(`Exported ${exported.length}/${files.length} alpha-cropped raster layers to ${app}`);

async function exportRasterParts(sources) {
  const output=[];
  for(const source of sources){
    const image=new Image();image.src='/v2/raw/'+source.raw;await image.decode();
    const raw=document.createElement('canvas');raw.width=image.width;raw.height=image.height;
    const r=raw.getContext('2d',{willReadFrequently:true});r.drawImage(image,0,0);
    const d=r.getImageData(0,0,raw.width,raw.height).data;
    let x0=raw.width,y0=raw.height,x1=-1,y1=-1,transparent=0;
    for(let y=0;y<raw.height;y++)for(let x=0;x<raw.width;x++){
      const alpha=d[(y*raw.width+x)*4+3];if(alpha===0)transparent++;
      if(alpha<=8)continue;x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);
    }
    if(!transparent||x1<x0)throw new Error(source.file+' has no usable alpha');
    const bw=x1-x0+1,bh=y1-y0+1,scale=Math.min(1,256/Math.max(bw,bh));
    const c=document.createElement('canvas');c.width=Math.round(bw*scale);c.height=Math.round(bh*scale);
    const ctx=c.getContext('2d',{willReadFrequently:true});ctx.imageSmoothingEnabled=false;
    ctx.drawImage(image,x0,y0,bw,bh,0,0,c.width,c.height);
    const pixels=ctx.getImageData(0,0,c.width,c.height).data;
    const solid=(x,y)=>pixels[(y*c.width+x)*4+3]>128;
    const tops=Array.from({length:101},(_,t)=>{const x=Math.min(c.width-1,Math.round(t*c.width/100));for(let y=0;y<c.height;y++)if(solid(x,y))return y/c.height;return 1;});
    let screen=[.2,.3,.6,.45];
    if(source.file.startsWith('head-')){
      // Largest opaque dark rectangle through the center is the blank terminal screen.
      const hist=Array(c.width).fill(0);let area=0;
      for(let y=0;y<c.height;y++){
        for(let x=0;x<c.width;x++){
          const i=(y*c.width+x)*4,L=.2126*pixels[i]+.7152*pixels[i+1]+.0722*pixels[i+2];
          hist[x]=solid(x,y)&&L<58 ? hist[x]+1:0;
        }
        const stack=[];
        for(let x=0;x<=c.width;x++){
          const v=x===c.width?0:hist[x];
          while(stack.length&&hist[stack.at(-1)]>v){
            const height=hist[stack.pop()],left=stack.length?stack.at(-1)+1:0,width=x-left,top=y-height+1;
            if(width*height>area&&left<c.width*.5&&x>c.width*.5&&top<c.height*.6&&y>c.height*.4&&width>c.width*.35){area=width*height;screen=[left/c.width,top/c.height,width/c.width,height/c.height];}
          }
          stack.push(x);
        }
      }
      if(!area)throw new Error('Cannot locate screen for '+source.file);
    }
    let opening;
    if(['accessory-headset.png','accessory-hood.png'].includes(source.file)){
      const gaps=[];
      for(let y=Math.floor(c.height*.50);y<c.height*.78;y++){
        let l=-1,rr=c.width;
        for(let x=0;x<c.width/2;x++)if(solid(x,y))l=x;
        for(let x=Math.ceil(c.width/2);x<c.width;x++)if(solid(x,y)){rr=x;break;}
        if(l>=0&&rr<c.width)gaps.push((rr-l)/c.width);
      }
      gaps.sort((a,b)=>a-b);opening=gaps[Math.floor(gaps.length/2)];
      if(!opening||opening<.35)throw new Error('Unusable accessory opening '+source.file);
    }
    output.push({file:source.file,width:c.width,height:c.height,sourceBounds:[x0,y0,bw,bh],tops,screen,opening,png:c.toDataURL('image/png')});
  }
  return output;
}
