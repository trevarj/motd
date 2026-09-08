const catalog = await (await fetch('assets/catalog.json')).json();
const parts = catalog.components;
const images = new Map(), tinted = new Map();
const panelMasks = new Map();
await Promise.all(Object.values(parts).flat().map(async p => {
  const image = new Image(); image.src = 'assets/' + p.file; await image.decode(); images.set(p.file,image);
}));
const query = new URLSearchParams(window.location.search);
function queryIndex(group) {
  const id = query.get(group);
  const index = parts[group].findIndex(part => part.id === id);
  return index >= 0 ? index : 0;
}
const state = {body:0,head:queryIndex('head'),face:queryIndex('face'),accessory:queryIndex('accessory')};
const luminance=(data,index)=>Math.round(.2126*data[index]+.7152*data[index+1]+.0722*data[index+2]);
function panelMask(part, pixels, width, height) {
  const cached=panelMasks.get(part.file);if(cached)return cached;
  const head=parts.head.find(candidate=>candidate.file===part.file);if(!head)return null;
  const [headX,headY,headWidth,headHeight]=head.rect,[faceX,faceY,faceWidth,faceHeight]=head.faceRect;
  const left=Math.max(0,Math.floor((faceX-headX)/headWidth*width)),right=Math.min(width-1,Math.ceil((faceX+faceWidth-headX)/headWidth*width)-1);
  const top=Math.max(0,Math.floor((faceY-headY)/headHeight*height)),bottom=Math.min(height-1,Math.ceil((faceY+faceHeight-headY)/headHeight*height)-1);
  const centerX=(left+right)/2,centerY=(top+bottom)/2;let seed=-1,bestDistance=Infinity;
  for(let y=top;y<=bottom;y++)for(let x=left;x<=right;x++){
    const index=(y*width+x)*4,distance=(x-centerX)**2+(y-centerY)**2;
    if(pixels[index+3]>=192&&luminance(pixels,index)<=100&&distance<bestDistance){seed=y*width+x;bestDistance=distance;}
  }
  const mask=new Uint8Array(width*height);if(seed<0)return mask;
  const queue=[seed];mask[seed]=1;
  for(let offset=0;offset<queue.length;offset++){
    const point=queue[offset],x=point%width,y=Math.floor(point/width);
    for(const [nextX,nextY] of [[x-1,y],[x+1,y],[x,y-1],[x,y+1]]){
      if(nextX<left||nextX>right||nextY<top||nextY>bottom)continue;
      const next=nextY*width+nextX,index=next*4;
      if(mask[next]||pixels[index+3]<192||luminance(pixels,index)>100)continue;
      mask[next]=1;queue.push(next);
    }
  }
  panelMasks.set(part.file,mask);return mask;
}
function source(p, color, strength, treatment = 'current') {
  const key = p.file + color + strength + treatment;
  if(tinted.has(key)) return tinted.get(key);
  const image=images.get(p.file), c=document.createElement('canvas');c.width=image.width;c.height=image.height;
  const ctx=c.getContext('2d'), pixels=(ctx.drawImage(image,0,0),ctx.getImageData(0,0,c.width,c.height));
  const rgb=[1,3,5].map(i=>parseInt(color.slice(i,i+2),16));
  const faceLayer=parts.face.some(part=>part.file===p.file),screen=treatment==='light-preview'?panelMask(p,pixels.data,c.width,c.height):null;
  for(let i=0;i<pixels.data.length;i+=4){
    const L=luminance(pixels.data,i);
    if(treatment==='light-preview'&&faceLayer){
      const glyph=42+L*.11;pixels.data[i]=Math.round(glyph*.82);pixels.data[i+1]=Math.round(glyph*.95);pixels.data[i+2]=Math.round(glyph*1.1);
    }else if(treatment==='light-preview'&&screen?.[i/4]){
      const ivory=.95+L/255*.05;pixels.data[i]=Math.round(240*ivory);pixels.data[i+1]=Math.round(236*ivory);pixels.data[i+2]=Math.round(221*ivory);
    }else if(treatment==='light-preview'){
      const metal=Math.round(100+144*Math.pow(L/255,.55));
      for(let k=0;k<3;k++)pixels.data[i+k]=Math.round(metal*(.8+.2*rgb[k]/255));
    }else for(let k=0;k<3;k++)pixels.data[i+k]=Math.round(L*((1-strength)+strength*rgb[k]/255));
  }
  ctx.putImageData(pixels,0,0);tinted.set(key,c);
  if(tinted.size>128)tinted.delete(tinted.keys().next().value);
  return c;
}
function render(canvas, selection=state, color='#69b7de', theme='dark', detail=canvas.width>=24, treatment=theme==='dark'?'current':'light-preview') {
  const size=canvas.width,ctx=canvas.getContext('2d');ctx.clearRect(0,0,size,size);ctx.imageSmoothingEnabled=false;
  const head=parts.head[selection.head],accessory=parts.accessory[selection.accessory];
  ctx.save();ctx.beginPath();ctx.arc(size/2,size/2,size/2,0,Math.PI*2);ctx.clip();ctx.fillStyle=theme==='dark'?'#24262b':'#e8e9eb';ctx.fillRect(0,0,size,size);
  // Reframe the complete assembly together so attachments keep their registration.
  if(catalog.framing){
    const zoom=catalog.framing.zoom??1;
    ctx.translate(size/2,size/2);ctx.scale(zoom,zoom);
    ctx.translate(-(head.rect[0]+head.rect[2]/2)*size,-(head.rect[1]+head.rect[3]/2)*size);
  }
  const draw=(p,rect=p.rect,strength=.22)=>ctx.drawImage(source(p,color,strength,treatment),...rect.map(v=>v*size));
  draw(parts.body[selection.body]);
  if(detail&&accessory.behindHead)draw(accessory,head.accessoryRects[accessory.id]);
  draw(head);draw(parts.face[selection.face],head.faceRect);
  if(detail&&!accessory.behindHead)draw(accessory,head.accessoryRects[accessory.id]);
  draw(parts.accent[0]);ctx.restore();
  ctx.strokeStyle=theme==='dark'?'#52555c':'#a0a3a8';ctx.lineWidth=Math.max(1,size/32);ctx.beginPath();ctx.arc(size/2,size/2,size/2-ctx.lineWidth/2,0,Math.PI*2);ctx.stroke();
  return canvas;
}
function tile(selection,label,color,theme){
  const div=document.createElement('div');div.className='tile';const canvas=document.createElement('canvas');canvas.width=canvas.height=128;
  render(canvas,selection,color,theme);div.append(canvas,document.createTextNode(label));return div;
}
function refresh(){
  const color=document.querySelector('#accent').value,theme=document.querySelector('#theme').value;
  render(document.querySelector('#hero'),state,color,theme);
  const sizes=document.querySelector('#sizes');sizes.replaceChildren();
  for(const size of [20,24,32,48,64]){const c=document.createElement('canvas');c.width=c.height=size;c.title=size+'dp';sizes.append(render(c,state,color,theme));}
  for(const [group,target] of [['accessory','accessories'],['head','heads']]){
    document.getElementById(target).replaceChildren(...parts[group].map((p,i)=>tile({...state,[group]:i},p.id,color,theme)));
  }
}
function startGallery(){
  for(const [group,index] of Object.entries(state)){
    const label=document.createElement('label');label.textContent=group;
    const select=document.createElement('select');select.id=group;
    parts[group].forEach(p=>{const option=document.createElement('option');option.textContent=p.id;select.append(option);});
    select.selectedIndex=index;select.onchange=()=>{state[group]=select.selectedIndex;refresh();};label.append(select);document.querySelector('#controls').append(label);
  }
  for(const [group,items] of Object.entries(parts))for(const part of items){
    const div=document.createElement('div');div.className='tile';const img=images.get(part.file).cloneNode();img.alt=group+' '+part.id;div.append(img,document.createElement('br'),document.createTextNode(group+' / '+part.id));document.querySelector('#parts').append(div);
  }
  document.querySelector('#accent').oninput=refresh;document.querySelector('#theme').onchange=refresh;
  document.querySelector('#shuffle').onclick=()=>{for(const group of Object.keys(state)){state[group]=Math.floor(Math.random()*parts[group].length);document.getElementById(group).selectedIndex=state[group];}refresh();};
  refresh();
}
window.spriteV2={catalog,images,render,source,state};
if(!window.spriteV2PreviewMode)startGallery();
window.spriteV2Ready=true;window.dispatchEvent(new Event('spritev2ready'));
