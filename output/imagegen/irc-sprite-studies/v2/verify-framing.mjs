import {readFile,writeFile} from 'node:fs/promises';
import {withBrowser} from './browser.mjs';
const local=JSON.parse(await readFile(new URL('assets/catalog.json',import.meta.url)));
const app=JSON.parse(await readFile(new URL('../../../../app/src/main/assets/irc-sprites-v2/catalog.json',import.meta.url)));
if(JSON.stringify(local)!==JSON.stringify(app))throw new Error('App and preview catalogs differ');
await withBrowser('http://127.0.0.1:8765/v2/index.html',async({evaluate})=>{
  const result=await evaluate(`(()=>{
    const {catalog,render}=spriteV2,p=catalog.components,framing=catalog.framing;
    if(framing.zoom!==1.4)throw new Error('Unexpected framing');
    const c=document.createElement('canvas');c.width=1280;c.height=356;const ctx=c.getContext('2d');ctx.fillStyle='#101319';ctx.fillRect(0,0,c.width,c.height);ctx.font='13px sans-serif';ctx.textAlign='center';
    let cropped=0;
    p.head.forEach((head,h)=>{
      const [x,y,w,height]=head.rect,cx=x+w/2,cy=y+height/2,z=framing.zoom;
      if(Math.abs((.5+(x-cx)*z)+w*z/2-.5)>1e-6||Math.abs((.5+(y-cy)*z)+height*z/2-.5)>1e-6)throw new Error('Head center '+head.id);
      for(const body of p.body){if(.5+(body.rect[1]+body.rect[3]-cy)*z<=1)throw new Error('Body not cropped '+head.id);cropped++;}
      for(let row=0;row<2;row++){
        if(row===0)delete catalog.framing;else catalog.framing=framing;
        const tile=document.createElement('canvas');tile.width=tile.height=144;render(tile,{body:0,head:h,face:0,accessory:3});ctx.drawImage(tile,h*160+8,row*178);ctx.fillStyle='#dce2ed';ctx.fillText(head.id+' / '+(row?'1.4×':'original'),h*160+80,row*178+162);
      }
    });catalog.framing=framing;
    return {png:c.toDataURL(),heads:p.head.length,croppedBodies:cropped};
  })()`);
  await writeFile(new URL('previews/framing-comparison.png',import.meta.url),Buffer.from(result.png.split(',')[1],'base64'));
  console.log(JSON.stringify({centeredHeads:result.heads,croppedHeadBodyPairs:result.croppedBodies,catalogsMatch:true}));
});
