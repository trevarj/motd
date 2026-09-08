function tile(sprite, selection, accent, theme, treatment) {
  const item=document.createElement('div');item.className='tile';
  const canvas=document.createElement('canvas');canvas.width=canvas.height=144;
  sprite.render(canvas,selection,accent,theme,true,treatment);
  const label=document.createElement('code');label.textContent=`${sprite.catalog.components.head[selection.head].id} · ${accent}`;
  item.append(canvas,label);return item;
}
function panel(sprite, title, detail, theme, treatment) {
  const section=document.createElement('section');section.className=`panel ${theme==='dark'?'dark':''}`;
  const heading=document.createElement('h2');heading.textContent=title;
  const text=document.createElement('p');text.textContent=detail;
  const grid=document.createElement('div');grid.className='sprite-grid';
  const accents=['#69b7de','#c58dff','#54bfa3','#e5926e','#d4b35e','#e577a8','#6ca7ef','#94bc67'];
  sprite.catalog.components.head.forEach((_,head)=>grid.append(tile(sprite,{
    body:head%3,head,face:head%7,accessory:head%sprite.catalog.components.accessory.length,
  },accents[head],theme,treatment)));
  section.append(heading,text,grid);return section;
}
function sampleCard(sprite, title, theme, treatment) {
  const section=document.createElement('section');section.className=`sample ${theme==='dark'?'dark':''}`;
  const heading=document.createElement('h2');heading.textContent=title;
  const row=document.createElement('div');row.className='sample-row';
  for(const size of [20,32,48,64]){
    const figure=document.createElement('figure');const canvas=document.createElement('canvas');canvas.width=canvas.height=size;
    sprite.render(canvas,{body:1,head:4,face:0,accessory:3},'#69b7de',theme,size>=24,treatment);
    const label=document.createElement('figcaption');label.textContent=`${size}px`;figure.append(canvas,label);row.append(figure);
  }
  section.append(heading,row);return section;
}
function start(){
  const sprite=window.spriteV2,comparison=document.querySelector('#comparison'),samples=document.querySelector('#samples');
  comparison.append(
    panel(sprite,'Dark · current','Current renderer on the existing dark surface.','dark','current'),
    panel(sprite,'Light · original','Original palette on the existing light surface.','light','current'),
    panel(sprite,'Light · approved','Automatic light palette: lighter metal, ivory panel, dark eyes.','light','light-preview'),
  );
  samples.append(
    sampleCard(sprite,'Dark · current pixel sizes','dark','current'),
    sampleCard(sprite,'Light · original pixel sizes','light','current'),
    sampleCard(sprite,'Light · approved pixel sizes','light','light-preview'),
  );
  window.spriteV2ThemePreviewReady=true;
}
if(window.spriteV2Ready)start();else window.addEventListener('spritev2ready',start,{once:true});
