#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const directory = path.join(root, 'tools/wallpapers/scenic');
const width = 1536, height = 1024, half = width / 2, count = 16;
const rect = (kind, bounds, night = false) => ({ kind, bounds, night });
const poly = (kind, points) => ({ kind, points });
// Kinds: 1 isolated light pixels, 2 water, 3 stars, 4 screen, 5 whole neon sign.
// Whole-sign blending retains the glow around the tubes as they dim and relight.
// Source-panel coordinates keep animation attached to the photographed features.
const regions = {
  'japanese-countryside': [rect(1,[.22,.55,.29,.75]),rect(1,[.02,.33,.21,.65],true),rect(1,[.88,.40,1,.7],true),poly(2,[[.77,.65],[.84,.65],[.86,.76],[.98,1],[.9,1],[.80,.82],[.74,.72]]),rect(3,[.43,0,.98,.25],true)],
  'rainy-cafe': [rect(1,[.04,.35,.48,.64]),rect(1,[.06,.08,.3,.25],true),poly(2,[[.62,.57],[.84,.6],[1,1],[0,1],[0,.78]]),rect(3,[.5,0,.98,.25],true)],
  'neon-arcade': [rect(5,[.47,.30,.74,.38]),rect(5,[.23,.21,.30,.27]),poly(2,[[.31,.66],[.57,.66],[1,1],[0,1],[0,.85]]),rect(3,[.45,0,.97,.22],true)],
  'retro-computer-room': [rect(4,[.28,.55,.49,.68]),rect(1,[.77,.48,.89,.56]),rect(1,[.3,.23,.75,.4],true),rect(2,[.29,.38,.66,.44]),rect(3,[.3,0,.83,.28],true)],
  'cyberpunk-canal': [rect(1,[0,.32,.30,.62],true),rect(1,[.72,.28,1,.64],true),rect(1,[.35,.14,.8,.49],true),poly(2,[[.45,.56],[.57,.56],[.7,.65],[.9,.78],[1,.85],[1,1],[0,1],[0,.83],[.26,.69]]),rect(3,[.18,0,.98,.26],true)],
};
const baseStudies = {'japanese-countryside':'summer-v1','rainy-cafe':'v1','neon-arcade':'v2','retro-computer-room':'v1','cyberpunk-canal':'v1'};
function command(args) {
  const result = spawnSync('magick', args, { maxBuffer: 128 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(result.stderr?.toString() || 'ImageMagick failed');
  return result.stdout;
}
function decode(file) {
  const pixels = command([file,'-alpha','on','-depth','8','rgba:-']);
  if (pixels.length !== width * height * 4) throw new Error(`Expected ${width}x${height}: ${file}`);
  return pixels;
}
function contains(region, x, y) {
  if(region.bounds) return x >= region.bounds[0] && x < region.bounds[2] && y >= region.bounds[1] && y < region.bounds[3];
  let inside = false;
  for(let i=0,j=region.points.length-1;i<region.points.length;j=i++) {
    const [a,b]=region.points[i], [c,d]=region.points[j];
    if ((b>y)!==(d>y) && x<(c-a)*(y-b)/(d-b)+a) inside=!inside;
  }
  return inside;
}
function luminous(pixels,index) {
  const r=pixels[index],g=pixels[index+1],b=pixels[index+2];
  return Math.max(r,g,b)>155 && (Math.max(r,g,b)-Math.min(r,g,b)>45 || Math.min(r,g,b)>195);
}
function star(pixels,x,y) {
  if(x<2 || y<2 || x>=width-2 || y>=height-2) return false;
  const i=(y*width+x)*4;
  if(Math.min(pixels[i],pixels[i+1],pixels[i+2])<125) return false;
  let dark=0;
  for(const [dx,dy] of [[-2,0],[2,0],[0,-2],[0,2],[-2,-2],[2,2]]) {
    const n=((y+dy)*width+x+dx)*4;
    if(Math.max(pixels[n],pixels[n+1],pixels[n+2])<115) dark++;
  }
  return dark>=4;
}
function selection(source, scene) {
  const kinds=new Uint8Array(width*height), groups=new Uint8Array(width*height);
  for(let y=0;y<height;y++) for(let x=0;x<width;x++) {
    const p=y*width+x, i=p*4, u=(x%half)/half, v=y/height;
    for(const [group,region] of regions[scene].entries()) {
      if(region.night && x<half || !contains(region,u,v)) continue;
      if(region.kind===1 && !luminous(source,i)) continue;
      if(region.kind===3 && !star(source,x,y)) continue;
      // Road reflections retain dark mortar and stone silhouettes.
      if(region.kind===2 && (scene==='rainy-cafe'||scene==='neon-arcade') && !luminous(source,i)) continue;
      kinds[p]=region.kind; groups[p]=group;
    }
  }
  return {kinds,groups};
}
const clamp=n=>Math.max(0,Math.min(255,Math.round(n)));
function render(source, alternate, map, frame) {
  const pixels=Buffer.from(source), t=frame/count*Math.PI*2;
  for(let p=0;p<map.kinds.length;p++) {
    const kind=map.kinds[p]; if(!kind) continue;
    const x=p%width,y=Math.floor(p/width),i=p*4;
    const feature=map.groups[p];
    let blend=(1-Math.cos(t+feature*.67))*.5;
    let from=i, gain=1;
    if(kind===2) {
      // Separate ripple patches move at different phases, avoiding full-width bands.
      const phase=Math.floor(x/64)*1.73+Math.floor(y/24)*.91;
      const dx=Math.round(Math.sin(t+phase)*7),dy=Math.round(Math.cos(t+phase)*3);
      const sx=x+dx,sy=y+dy,q=sy*width+sx;
      if(sx>=0 && sx<width && sy>=0 && sy<height && Math.floor(sx/half)===Math.floor(x/half) && map.kinds[q]===2) from=q*4;
      blend=(1-Math.cos(t+phase*.25))*.45;
    } else if(kind===3) {
      const phase=Math.floor(x/4)*1.93+Math.floor(y/4)*.71;
      gain=.12+1.45*Math.pow((1+Math.sin(t+phase))*.5,2); blend=0;
    } else if(kind===1 || kind===5) {
      const phase=(frame+feature*3)%count;
      // A short dim-and-relight event belongs to each lamp/sign, not the whole frame.
      gain=phase===6?.48:phase===7?.72:phase===8?.55:1;
    }
    for(let c=0;c<3;c++) {
      const a=source[from+c], b=alternate && kind!==3 ? alternate[from+c] : a;
      pixels[i+c]=clamp((a*(1-blend)+b*blend)*gain);
    }
  }
  return pixels;
}
function metadata(file) {
  const b=fs.readFileSync(file); let frames=0, duration=0, loops=-1,w=0,h=0;
  if(b.toString('ascii',0,4)!=='RIFF'||b.toString('ascii',8,12)!=='WEBP') throw new Error(`Not WebP: ${file}`);
  for(let o=12;o+8<=b.length;) {
    const type=b.toString('ascii',o,o+4),size=b.readUInt32LE(o+4),p=o+8;
    if(type==='VP8X'){w=b.readUIntLE(p+4,3)+1;h=b.readUIntLE(p+7,3)+1;}
    if(type==='ANIM') loops=b.readUInt16LE(p+4);
    if(type==='ANMF'){frames++;duration+=b.readUIntLE(p+12,3);}
    o=p+size+(size%2);
  }
  if(frames!==count||duration!==4000||loops!==0||w!==width||h!==height) throw new Error(`Invalid loop ${file}: ${JSON.stringify({frames,duration,loops,w,h})}`);
  return {frames,duration,loops,width:w,height:h};
}
const args=process.argv.slice(2), selected=args.includes('--scene')?args[args.indexOf('--scene')+1]:null, check=args.includes('--check');
const manifest=JSON.parse(fs.readFileSync(path.join(directory,'manifest.json'),'utf8'));
for(const scene of manifest.scenes) {
  if(selected && scene.id!==selected) continue;
  for(const version of scene.versions) {
    const output=path.join(directory,version.light.replace(/\.png$/,'-loop.webp'));
    if(check){console.log(path.basename(output),metadata(output));continue;}
    const source=decode(path.join(directory,version.light));
    const alternatePath=path.join(directory,`${scene.id}-motion-keyframe.png`);
    const alternate=version.id===baseStudies[scene.id]&&fs.existsSync(alternatePath)?decode(alternatePath):null;
    const map=selection(source,scene.id), temp=fs.mkdtempSync(path.join(os.tmpdir(),'motd-loop-'));
    try {
      const files=[];
      for(let frame=0;frame<count;frame++) {
        const file=path.join(temp,`${String(frame).padStart(2,'0')}.pam`);
        const pixels=render(source,alternate,map,frame);
        // Verify that the frame composer cannot change any unselected source pixel.
        for(let p=0;p<map.kinds.length;p++) if(!map.kinds[p]) for(let c=0;c<4;c++) if(pixels[p*4+c]!==source[p*4+c]) throw new Error('Static pixel changed');
        fs.writeFileSync(file,Buffer.concat([Buffer.from(`P7\nWIDTH ${width}\nHEIGHT ${height}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n`),pixels]));
        files.push(file);
      }
      const staging=path.join(temp,'loop.webp');
      command(['-delay','25',...files,'-loop','0','-define','webp:lossless=true','-define','webp:method=4',staging]);
      metadata(staging); fs.copyFileSync(staging,output);
      console.log(`Wrote ${path.basename(output)}: 16 frames / 4 seconds; ${alternate?'generated keyframe':'original seasonal artwork'}`);
    } finally {fs.rmSync(temp,{recursive:true,force:true});}
  }
}
