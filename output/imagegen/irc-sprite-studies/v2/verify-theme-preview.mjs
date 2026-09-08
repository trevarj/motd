import {writeFile} from 'node:fs/promises';
import {withBrowser} from './browser.mjs';
await withBrowser('http://127.0.0.1:8765/v2/theme-preview.html',async({evaluate,send})=>{
  const result=await evaluate(`(()=>{
    if(!window.spriteV2ThemePreviewReady)throw new Error('Theme preview did not load');
    const {catalog,images,source,render}=spriteV2,parts=Object.values(catalog.components).flat();
    let alphaChecked=0,proposedChanged=0;
    for(const part of parts){
      const current=source(part,'#69b7de',.22,'current'),darkDefault=source(part,'#69b7de',.22),proposed=source(part,'#69b7de',.22,'light-preview');
      const a=current.getContext('2d').getImageData(0,0,current.width,current.height).data;
      const d=darkDefault.getContext('2d').getImageData(0,0,darkDefault.width,darkDefault.height).data;
      const p=proposed.getContext('2d').getImageData(0,0,proposed.width,proposed.height).data;
      const reference=document.createElement('canvas');reference.width=current.width;reference.height=current.height;const referenceCtx=reference.getContext('2d');referenceCtx.drawImage(images.get(part.file),0,0);const referencePixels=referenceCtx.getImageData(0,0,reference.width,reference.height);
      for(let i=0;i<referencePixels.data.length;i+=4){const luminance=Math.round(.2126*referencePixels.data[i]+.7152*referencePixels.data[i+1]+.0722*referencePixels.data[i+2]);for(const [channel,accent] of [[0,0x69],[1,0xb7],[2,0xde]])referencePixels.data[i+channel]=Math.round(luminance*((1-.22)+.22*accent/255));}
      referenceCtx.putImageData(referencePixels,0,0);const expected=referenceCtx.getImageData(0,0,reference.width,reference.height).data;
      for(let i=0;i<a.length;i+=4){
        if(a[i+3]!==p[i+3])throw new Error('Alpha changed '+part.file);
        if(a[i]!==d[i]||a[i+1]!==d[i+1]||a[i+2]!==d[i+2]||a[i+3]!==d[i+3])throw new Error('Current treatment changed '+part.file);
        for(let channel=0;channel<4;channel++)if(a[i+channel]!==expected[i+channel])throw new Error('Dark tint changed '+part.file);
      }
      if(a.some((value,index)=>index%4!==3&&value!==p[index]))proposedChanged++;alphaChecked++;
    }
    if(alphaChecked!==29||proposedChanged!==29)throw new Error('Unexpected layer treatment coverage');
    let ivoryPanels=0;
    for(const head of catalog.components.head){
      const image=images.get(head.file),treated=source(head,'#69b7de',.22,'light-preview'),data=treated.getContext('2d').getImageData(0,0,image.width,image.height).data;
      const [headX,headY,headWidth,headHeight]=head.rect,[faceX,faceY,faceWidth,faceHeight]=head.faceRect;
      const x=Math.round(((faceX+faceWidth/2-headX)/headWidth)*(image.width-1)),y=Math.round(((faceY+faceHeight/2-headY)/headHeight)*(image.height-1)),index=(y*image.width+x)*4;
      if(data[index]<220||data[index+1]<215||data[index+2]<200)throw new Error('Panel not ivory '+head.file);ivoryPanels++;
    }
    let darkFaceLayers=0;
    for(const face of catalog.components.face){
      const treated=source(face,'#69b7de',.22,'light-preview'),data=treated.getContext('2d').getImageData(0,0,treated.width,treated.height).data;
      if(!data.some((value,index)=>index%4===3&&value>128&&data[index-3]<90&&data[index-2]<90&&data[index-1]<100))throw new Error('Face glyph not dark '+face.file);darkFaceLayers++;
    }
    const avatar=document.createElement('canvas');avatar.width=avatar.height=64;
    const selected={body:1,head:4,face:0,accessory:3};
    const snapshot=(theme,treatment)=>render(avatar,selected,'#69b7de',theme,true,treatment).toDataURL();
    const dark=snapshot('dark'),light=snapshot('light');
    if(dark===light||dark!==snapshot('dark','current')||light!==snapshot('light','light-preview')||dark!==snapshot('dark'))throw new Error('Automatic theme switching failed');
    const c=document.createElement('canvas');c.width=1560;c.height=470;const ctx=c.getContext('2d');ctx.fillStyle='#f6f7f9';ctx.fillRect(0,0,c.width,c.height);
    const columns=[['Dark · current','dark','current'],['Light · original','light','current'],['Light · approved','light','light-preview']];const accents=['#69b7de','#c58dff','#54bfa3','#e5926e','#d4b35e','#e577a8','#6ca7ef','#94bc67'];
    ctx.font='16px sans-serif';ctx.textAlign='center';
    for(const [column,[title,theme,treatment]] of columns.entries()){
      const x=column*520;ctx.fillStyle=theme==='dark'?'#101319':'#ffffff';ctx.fillRect(x,0,500,470);ctx.fillStyle=theme==='dark'?'#e9edf4':'#20242b';ctx.fillText(title,x+250,28);
      catalog.components.head.forEach((_,head)=>{const tile=document.createElement('canvas');tile.width=tile.height=112;render(tile,{body:head%3,head,face:head%7,accessory:head%10},accents[head],theme,true,treatment);const tx=x+(head%4)*125,ty=42+Math.floor(head/4)*170;ctx.drawImage(tile,tx+6,ty);ctx.fillStyle=theme==='dark'?'#cbd3df':'#56606e';ctx.font='11px sans-serif';ctx.fillText(catalog.components.head[head].id,tx+62,ty+128);});
      ctx.font='13px sans-serif';ctx.fillStyle=theme==='dark'?'#e9edf4':'#20242b';ctx.fillText('Actual pixel sizes',x+250,355);
      for(const [index,size] of [20,32,48,64].entries()){const tile=document.createElement('canvas');tile.width=tile.height=size;render(tile,{body:1,head:4,face:0,accessory:3},'#69b7de',theme,size>=24,treatment);const tx=x+112+index*85;ctx.drawImage(tile,tx,378);ctx.font='11px sans-serif';ctx.fillStyle=theme==='dark'?'#cbd3df':'#56606e';ctx.fillText(size+'px',tx+size/2,459);}
    }
    return {png:c.toDataURL(),alphaLayers:alphaChecked,proposedLayersChanged:proposedChanged,ivoryPanels,darkFaceLayers,automaticThemeSwitch:true};
  })()`);
  await writeFile(new URL('previews/theme-comparison.png',import.meta.url),Buffer.from(result.png.split(',')[1],'base64'));
  await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  if(await evaluate('document.documentElement.scrollWidth>innerWidth'))throw new Error('Mobile horizontal overflow');
  console.log(JSON.stringify({...result,png:undefined,mobileOverflow:false}));
});
