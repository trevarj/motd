import {readFile,writeFile,readdir} from 'node:fs/promises';
import {withBrowser} from './browser.mjs';
const assets=new URL('../../../../app/src/main/assets/irc-sprites-v2/',import.meta.url);
const files=await readdir(assets);
if(files.includes('accessory-hood.png')||files.filter(f=>f.endsWith('.png')).length!==29)throw new Error('Unexpected packaged layer set');
for(const file of ['face-curious.png','accessory-goggles.png']){
  if(!(await readFile(new URL(file,assets))).equals(await readFile(new URL('assets/'+file,import.meta.url))))throw new Error('Preview/package mismatch');
}
await withBrowser('http://127.0.0.1:8765/v2/index.html?face=curious&accessory=goggles',async({evaluate,send})=>{
  const result=await evaluate(`(()=>{
    const {catalog,images,render,state}=spriteV2,p=catalog.components;
    if(p.face[state.face].id!=='curious'||p.accessory[state.accessory].id!=='goggles')throw new Error('Preview query selection');
    const g=p.accessory.findIndex(p=>p.id==='goggles');
    if(p.accessory[g].behindHead||p.accessory.some(p=>p.id==='hood'))throw new Error('Eyewear catalog');
    const image=images.get('accessory-goggles.png'),lens=document.createElement('canvas');lens.width=image.width;lens.height=image.height;
    const l=lens.getContext('2d');l.drawImage(image,0,0);
    for(const x of [.25,.75])if(l.getImageData(Math.floor(x*lens.width),Math.floor(lens.height/2),1,1).data[3]!==0)throw new Error('Opaque lens');
    const c=document.createElement('canvas');c.width=1280;c.height=7*160;const ctx=c.getContext('2d');ctx.fillStyle='#101319';ctx.fillRect(0,0,c.width,c.height);ctx.font='12px sans-serif';ctx.textAlign='center';
    p.face.forEach((face,f)=>p.head.forEach((head,h)=>{
      const tile=document.createElement('canvas');tile.width=tile.height=136;render(tile,{body:h%3,head:h,face:f,accessory:g});ctx.drawImage(tile,h*160+12,f*160);ctx.fillStyle='#dce2ed';ctx.fillText(head.id+' / '+face.id,h*160+80,f*160+151);
    }));return c.toDataURL();
  })()`);
  await writeFile(new URL('previews/goggles-fit.png',import.meta.url),Buffer.from(result.split(',')[1],'base64'));
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
  await writeFile(new URL('previews/refinements.png',import.meta.url),Buffer.from(shot.data,'base64'));
});
console.log('PASS: 29 packaged layers; hood replaced; transparent lenses; deep link; matching preview PNGs; 56 eyewear combinations rendered.');
