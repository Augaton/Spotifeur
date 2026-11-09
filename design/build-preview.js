// Run inside Sketch with sketchtool run-script and context.repository set to the root.
// This rebuilds the artwork from native layers and the website's original screenshots.
(() => {
const sketch = require('sketch/dom');
const root = context.repository;
if (!root) throw new Error('Pass the repository path in --context as {"repository":"/path/to/Spotifeur"}');
Mocha.sharedRuntime().loadFrameworkWithName_inDirectory('CoreText', '/System/Library/Frameworks');
CTFontManagerRegisterFontsForURL(NSURL.fileURLWithPath(root + '/app/src/main/res/font/gflex_variable.ttf'), 1, null);

const C = {
  background: '#0A100D', surface: '#141C17', raised: '#202A24',
  jade: '#6FE3A6', strong: '#0F9D63', bright: '#B4F7D1',
  container: '#0B4F35', deep: '#04281B', lavender: '#C9BEFF',
  text: '#EAF6EF', secondary: '#B9CABF', outline: '#3F4E45',
};
const doc = new sketch.Document();
doc.colorSpace = sketch.Document.ColorSpace.sRGB;
const page = doc.pages[0];
page.name = 'README artwork';
Object.keys(C).forEach(name => doc.colors.push({name, color: C[name]}));
const R = (x, y, width, height) => new sketch.Rectangle(x, y, width, height);

function rectangle(parent, name, x, y, w, h, radius, fill, extra) {
  return new sketch.ShapePath({parent, name, frame: R(x,y,w,h),
    style: Object.assign({fills: [fill], borders: [], corners: {radii: radius}}, extra || {})});
}
function gradient(a, b) {
  return {fillType: 'Gradient', gradient: {gradientType: 'Linear', from: {x:0,y:0},
    to: {x:1,y:1}, stops: [{position:0,color:a},{position:1,color:b}]}};
}
function cookie(parent, name, x, y, size, a, b) {
  // Continuous scalloped path, using cubic interpolation rather than polygon corners.
  const points = [];
  const count = 144;
  for (let i=0; i<count; i++) {
    const angle = i * Math.PI * 2 / count;
    const r = size * (0.45 + 0.043 * Math.cos(9*angle));
    points.push({x:size/2+r*Math.cos(angle), y:size/2+r*Math.sin(angle)});
  }
  let path = 'M '+points[0].x+' '+points[0].y;
  for (let i=0; i<count; i++) {
    const p0=points[(i-1+count)%count], p1=points[i], p2=points[(i+1)%count], p3=points[(i+2)%count];
    path += ' C '+(p1.x+(p2.x-p0.x)/6)+' '+(p1.y+(p2.y-p0.y)/6)+' '+
      (p2.x-(p3.x-p1.x)/6)+' '+(p2.y-(p3.y-p1.y)/6)+' '+p2.x+' '+p2.y;
  }
  const shape = sketch.ShapePath.fromSVGPath(path+' Z');
  shape.parent=parent; shape.name=name; shape.frame=R(x,y,size,size);
  shape.style={fills:[gradient(a,b)],borders:[],shadows:[{color:'#00000024',blur:44,x:0,y:20}]};
  return shape;
}
function text(parent, name, value, x, y, size, color, weight, width, align) {
  const layer = new sketch.Text({parent,name,text:value,frame:R(x,y,width || 1000,size*1.5),
    fixedWidth: Boolean(width),style:{fontFamily:'Google Sans Flex',fontSize:size,
      textColor:color,alignment:align || 'left',kerning: size>=70 ? -size*0.025 : 0}});
  const axes=layer.style.fontAxes;
  if (!axes) throw new Error('Google Sans Flex was not loaded');
  axes.Roundness.value=100; axes.Weight.value=weight || 500;
  axes['Optical Size'].value=Math.min(size,144);
  layer.style.fontAxes=axes;
  if (!width) layer.adjustToFit();
  layer.name=name;
  return layer;
}
function logo(parent,x,y,size) {
  const vector=sketch.createLayerFromData(NSData.dataWithContentsOfFile(root+'/design/logo.svg'),'svg');
  vector.parent=parent;vector.name='Logo / Original vector';vector.frame=R(x,y,size,size);
}
function board(name,w,h,x,y) {
  const b=new sketch.Group({parent:page,name,groupBehavior:sketch.GroupBehavior.Frame,
    frame:R(x,y,w,h),style:{fills:[C.background]},
    exportFormats:[{fileFormat:'png',size:'1x'},{fileFormat:'png',size:'2x'}]});
  const mask=rectangle(b,'Canvas mask',0,0,w,h,0,C.background);
  mask.masksSiblings=true;
  mask.maskMode=sketch.Layer.MaskMode.Outline;
  return b;
}
function phone(parent,name,file,x,y,screenWidth) {
  const scale=screenWidth/440, bezel=12*scale, h=screenWidth*1200/537;
  const g=new sketch.Group({parent,name:'Phone / '+name,frame:R(x,y,screenWidth+24*scale,h+24*scale)});
  rectangle(g,'Frame',0,0,screenWidth+24*scale,h+24*scale,58*scale,C.raised,{
    borders:[{color:'#526158',thickness:2*scale,position:'Inside'}],
    shadows:[{color:'#00000066',blur:38*scale,x:0,y:22*scale}]});
  rectangle(g,'Inner bezel',4*scale,4*scale,screenWidth+16*scale,h+16*scale,54*scale,'#080B09');
  const clip=rectangle(g,'Screen mask',bezel,bezel,screenWidth,h,46*scale,'#000000');
  clip.masksSiblings=true;clip.maskMode=sketch.Layer.MaskMode.Outline;
  new sketch.Image({parent:g,name:'Screenshot / '+file+'.jpg',
    image:root+'/docs/assets/img/screens/'+file+'.jpg',frame:R(bezel,bezel,screenWidth,h)});
  return g;
}
function preview(name,files,labels,x,y,variant) {
  const b=board(name,2400,1350,x,y);
  const bg=new sketch.Group({parent:b,name:'Background / Expressive shapes'});
  const pill=rectangle(bg,'Jade pill',-70,355,2480,790,395,gradient(C.container,C.deep));
  pill.transform.rotation=variant ? 9 : -9;
  cookie(bg,'Cookie / Top right',2110,-225,490,C.strong,C.container);
  cookie(bg,'Cookie / Bottom left',-185,1100,450,C.strong,C.container);
  const accent=rectangle(bg,'Lavender accent',2240,1200,300,70,35,C.lavender);
  accent.transform.rotation=-18;
  logo(b,88,60,76);
  text(b,'Name','Spotifeur',188,62,62,C.jade,750);
  const xs=[140,692,1244,1796], ys=variant ? [175,215,175,215] : [215,175,215,175];
  files.forEach((file,i)=>{
    phone(b,labels[i],file,xs[i],ys[i],440);
    rectangle(b,'Label background / '+labels[i],xs[i]+67,1250,330,64,32,C.jade);
    text(b,'Label / '+labels[i],labels[i],xs[i]+67,1258,34,C.background,600,330,'center');
  });
  return b;
}

const banner=board('banner',1280,640,0,0);
const bannerBG=new sketch.Group({parent:banner,name:'Background / Expressive shapes'});
rectangle(bannerBG,'Jade pill',-350,370,1000,470,235,gradient(C.container,C.deep));
cookie(bannerBG,'Cookie / Top right',1040,-210,550,C.container,C.deep);
rectangle(bannerBG,'Lavender accent',1110,470,170,42,21,C.lavender).transform.rotation=-24;
logo(banner,76,224,152);
text(banner,'Name','Spotifeur',268,216,96,C.text,780);
text(banner,'Description','Open-source music player for Android',270,344,27,C.secondary,500);
text(banner,'Sources','Spotify · YouTube Music · Local files',270,389,25,C.jade,500);
phone(banner,'Player','player',930,40,244);

preview('preview-listening',['home','player','lyrics','queue'],['Home','Player','Lyrics','Queue'],0,840,false);
preview('preview-library',['album','playlist','settings','appearance'],['Album','Playlist','Settings','Appearance'],2600,840,true);

doc.selectedLayers.clear();
doc.save(root+'/design/spotifeur-preview.sketch',err=>{
  if(err) throw err;
  page.canvasLevelFrames.forEach(b=>sketch.export(b,{output:root+'/docs/assets/img',formats:'png',scales:'1',overwriting:true}));
  console.log('Saved editable Sketch document and three PNG exports.');
});
})();
