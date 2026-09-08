import {mkdir,writeFile} from 'node:fs/promises';
import {withBrowser} from './browser.mjs';
const previews=new URL('./previews/',import.meta.url);await mkdir(previews,{recursive:true});
await withBrowser('http://127.0.0.1:8765/v2/index.html',async({evaluate,send})=>{
  const result=await evaluate(`(async()=>{
    if(!window.spriteV2Ready)throw new Error('Gallery did not load');
    const {catalog,render,images,source}=spriteV2,p=catalog.components;
    const c=document.createElement('canvas');c.width=1600;c.height=8*180;const ctx=c.getContext('2d');ctx.fillStyle='#101319';ctx.fillRect(0,0,c.width,c.height);
    ctx.font='13px sans-serif';ctx.textAlign='center';
    p.head.forEach((head,h)=>p.accessory.forEach((accessory,a)=>{
      const tile=document.createElement('canvas');tile.width=tile.height=144;render(tile,{body:h%3,head:h,face:h%7,accessory:a});ctx.drawImage(tile,a*160+8,h*180);ctx.fillStyle='#dce2ed';ctx.fillText(head.id+' / '+accessory.id,a*160+80,h*180+161);
    }));
    const layers=Object.values(p).flat();let checks=0;
    for(const part of layers){
      const image=images.get(part.file);if(Math.max(image.width,image.height)>256)throw new Error('Oversize '+part.file);
      const a=source(part,'#ff5555',.22),b=source(part,'#5555ff',.22),ad=a.getContext('2d').getImageData(0,0,a.width,a.height).data,bd=b.getContext('2d').getImageData(0,0,b.width,b.height).data;
      if(!ad.some((v,i)=>i%4===3&&v===0)||!ad.some((v,i)=>i%4===3&&v>128))throw new Error('Alpha '+part.file);
      if(!ad.some((v,i)=>i%4!==3&&v!==bd[i]))throw new Error('Tint '+part.file);
      for(let i=3;i<ad.length;i+=4)if(ad[i]!==bd[i])throw new Error('Tint changed alpha');checks++;
    }
    const one=document.createElement('canvas');one.width=one.height=64;const hashes=new Set();
    for(let body=0;body<p.body.length;body++)for(let head=0;head<p.head.length;head++)for(let face=0;face<p.face.length;face++)for(let accessory=0;accessory<p.accessory.length;accessory++){
      render(one,{body,head,face,accessory});hashes.add(one.toDataURL());
    }
    if(hashes.size!==1680)throw new Error('Only '+hashes.size+' distinct combinations');
    one.width=one.height=20;render(one,{body:0,head:0,face:0,accessory:0});const tiny=one.toDataURL();render(one,{body:0,head:0,face:0,accessory:3});if(one.toDataURL()!==tiny)throw new Error('Tiny accessory policy');
    return {atlas:c.toDataURL(),report:{alphaAndTintLayers:checks,distinctCombinations:hashes.size,headAccessoryPairs:80,tinyAccessoryPolicy:'pass'}};
  })()`);
  await writeFile(new URL('attachment-matrix.png',previews),Buffer.from(result.atlas.split(',')[1],'base64'));
  const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(new URL('gallery-desktop.png',previews),Buffer.from(screenshot.data,'base64'));
  await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  if(await evaluate('document.documentElement.scrollWidth>innerWidth'))throw new Error('Mobile horizontal overflow');
  const mobile=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(new URL('gallery-mobile.png',previews),Buffer.from(mobile.data,'base64'));
  result.report.mobileOverflow=false;await writeFile(new URL('verification.json',previews),JSON.stringify(result.report,null,2)+'\n');console.log(JSON.stringify(result.report));
});
