const catalog = await (await fetch('assets/catalog.json')).json();
const parts = catalog.components;
const images = new Map(), tinted = new Map();
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
function source(p, color, strength) {
  const key = p.file + color + strength;
  if(tinted.has(key)) return tinted.get(key);
  const image=images.get(p.file), c=document.createElement('canvas');c.width=image.width;c.height=image.height;
  const ctx=c.getContext('2d'), pixels=(ctx.drawImage(image,0,0),ctx.getImageData(0,0,c.width,c.height));
  const rgb=[1,3,5].map(i=>parseInt(color.slice(i,i+2),16));
  for(let i=0;i<pixels.data.length;i+=4){
    const L=Math.round(.2126*pixels.data[i]+.7152*pixels.data[i+1]+.0722*pixels.data[i+2]);
    for(let k=0;k<3;k++)pixels.data[i+k]=Math.round(L*((1-strength)+strength*rgb[k]/255));
  }
  ctx.putImageData(pixels,0,0);tinted.set(key,c);
  if(tinted.size>128)tinted.delete(tinted.keys().next().value);
  return c;
}
function render(canvas, selection=state, color='#69b7de', theme='dark', detail=canvas.width>=24) {
  const size=canvas.width,ctx=canvas.getContext('2d');ctx.clearRect(0,0,size,size);ctx.imageSmoothingEnabled=false;
  const head=parts.head[selection.head],accessory=parts.accessory[selection.accessory];
  ctx.save();ctx.beginPath();ctx.arc(size/2,size/2,size/2,0,Math.PI*2);ctx.clip();ctx.fillStyle=theme==='dark'?'#24262b':'#e8e9eb';ctx.fillRect(0,0,size,size);
  const draw=(p,rect=p.rect,strength=.22)=>ctx.drawImage(source(p,color,strength),...rect.map(v=>v*size));
  draw(parts.body[selection.body]);
  if(detail&&accessory.behindHead)draw(accessory,head.accessoryRects[accessory.id]);
  draw(head);draw(parts.face[selection.face],head.faceRect);
  if(detail&&!accessory.behindHead)draw(accessory,head.accessoryRects[accessory.id]);
  draw(parts.accent[0]);ctx.restore();
  ctx.strokeStyle=theme==='dark'?'#52555c':'#a0a3a8';ctx.lineWidth=Math.max(1,size/32);ctx.beginPath();ctx.arc(size/2,size/2,size/2-ctx.lineWidth/2,0,Math.PI*2);ctx.stroke();
  return canvas;
}
for(const [group,index] of Object.entries(state)){
  const label=document.createElement('label');label.textContent=group;
  const select=document.createElement('select');select.id=group;
  parts[group].forEach(p=>{const option=document.createElement('option');option.textContent=p.id;select.append(option);});
  select.selectedIndex=index;select.onchange=()=>{state[group]=select.selectedIndex;refresh();};label.append(select);document.querySelector('#controls').append(label);
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
for(const [group,items] of Object.entries(parts))for(const part of items){
  const div=document.createElement('div');div.className='tile';const img=images.get(part.file).cloneNode();img.alt=group+' '+part.id;div.append(img,document.createElement('br'),document.createTextNode(group+' / '+part.id));document.querySelector('#parts').append(div);
}
document.querySelector('#accent').oninput=refresh;document.querySelector('#theme').onchange=refresh;
document.querySelector('#shuffle').onclick=()=>{for(const group of Object.keys(state)){state[group]=Math.floor(Math.random()*parts[group].length);document.getElementById(group).selectedIndex=state[group];}refresh();};
window.spriteV2={catalog,images,render,source,state};refresh();window.spriteV2Ready=true;
