import { createScene, trackPointer } from './scene.js';

const canvas = document.getElementById('map');
const stage = document.getElementById('view-play');
const pointer = trackPointer(stage);
const gl = canvas.getContext('webgl2', { antialias: false, premultipliedAlpha: false });
const vertex = `#version 300 es
in vec2 p;out vec2 v;void main(){v=p*.5+.5;gl_Position=vec4(p,0,1);}`;
const fragment = `#version 300 es
precision highp float;
in vec2 v;out vec4 o;
uniform sampler2D scene,depth;uniform vec2 res,img,mouse,sun;uniform float time,rays;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
vec2 cover(vec2 uv){
  float s=max(res.x/img.x,res.y/img.y)*1.08;vec2 size=img*s/res;return (uv-.5)/size+.5;}
float D(vec2 uv){return texture(depth,uv).r;}
vec3 S(vec2 uv){return texture(scene,uv).rgb;}
void main(){
  vec2 uv=cover(v);uv.y=1.-uv.y;
  uv=(uv-.5)*(1.-.012*sin(time*.07))+.5;
  vec2 m=mouse*vec2(1,-1)*.028;
  float d=D(uv);uv+=m*(d-.35);d=D(uv);uv+=m*(d-.35)*.5;
  d=D(uv);
  float blur=d<.02?0.:smoothstep(.45,.1,d)*2.2;
  vec3 c=S(uv);
  if(blur>0.){vec3 acc=c;for(int i=0;i<8;i++){float a=float(i)*.785;acc+=S(uv+vec2(cos(a),sin(a))*blur/res);}c=acc/9.;}
  vec3 glow=vec3(0);for(int i=0;i<8;i++){float a=float(i)*.785+.4;vec3 s=S(uv+vec2(cos(a),sin(a))*9./res*res.y/540.);glow+=max(s-.72,0.);}
  c+=glow*.22;
  if(rays>0.){vec2 dir=(sun-uv)/40.;vec2 p=uv;float sum=0.,w=1.;
    for(int i=0;i<40;i++){p+=dir;vec3 s=S(p);float sky=step(D(p),.02);sum+=max(dot(s,vec3(.33))-.55,0.)*sky*w;w*=.96;}
    c+=vec3(1.,.72,.42)*sum*.09*rays*(.85+.15*sin(time*.6));}
  float haze=(1.-smoothstep(0.,.5,d))*(.5+.5*sin(v.x*6.+time*.15+v.y*3.));c=mix(c,vec3(1.,.78,.6),haze*.05);
  for(int l=0;l<3;l++){float z=float(l+1);vec2 q=v*res/res.y*vec2(1.)*(7.+z*5.)+vec2(time*.03*z,-time*.05*z)+mouse*z*.4;
    vec2 cell=floor(q),f=fract(q)-.5;float h=hash(cell+z);if(h>.82){vec2 off=vec2(hash(cell*1.7)-.5,hash(cell*2.3)-.5)*.6+vec2(sin(time*.5+h*20.)*.1,0);
      float r=length(f-off);c+=vec3(1.,.85,.6)*smoothstep(.06/z*1.6,0.,r)*(.35+.25*sin(time*1.3+h*40.))*.6;}}
  float lum=dot(c,vec3(.299,.587,.114));c=mix(vec3(lum),c,1.12);c=(c-.5)*1.08+.5;
  c=mix(c*vec3(.92,.95,1.08)+.015,c*vec3(1.06,1.,.9),smoothstep(.2,.8,lum));
  float vig=smoothstep(1.25,.35,length((v-.5)*vec2(1.2,1.)));c*=mix(.45,1.,vig);
  o=vec4(clamp(c,0.,1.),1);
}`;

let program, buffer, uniforms, textures;
let size = [16, 9],
  sunRays = 1,
  current = null,
  revision = 0,
  mx = 0,
  my = 0;
const scene = createScene(canvas, 'scene', (now, delta, { w, h, reducedMotion }) => {
  gl.viewport(0, 0, w, h);
  const smoothing = 1 - Math.exp(-delta / 325);
  mx = reducedMotion ? 0 : mx + (pointer.x - mx) * smoothing;
  my = reducedMotion ? 0 : my + (pointer.y - my) * smoothing;
  gl.uniform2f(uniforms.res, w, h);
  gl.uniform2f(uniforms.img, ...size);
  gl.uniform2f(uniforms.mouse, mx, my);
  gl.uniform1f(uniforms.time, now / 1000);
  gl.uniform1f(uniforms.rays, sunRays);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
});

function shader(type, source) {
  const result = gl.createShader(type);
  gl.shaderSource(result, source);
  gl.compileShader(result);
  if (!gl.getShaderParameter(result, gl.COMPILE_STATUS)) {
    const error = gl.getShaderInfoLog(result);
    gl.deleteShader(result);
    throw Error(error);
  }
  return result;
}

function initialize() {
  program = gl.createProgram();
  const shaders = [shader(gl.VERTEX_SHADER, vertex), shader(gl.FRAGMENT_SHADER, fragment)];
  for (const compiled of shaders) gl.attachShader(program, compiled);
  gl.linkProgram(program);
  for (const compiled of shaders) gl.deleteShader(compiled);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(program));
  gl.useProgram(program);
  buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, 'p');
  gl.enableVertexAttribArray(position);
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  uniforms = Object.fromEntries(
    ['scene', 'depth', 'res', 'img', 'mouse', 'sun', 'time', 'rays'].map((name) => [
      name,
      gl.getUniformLocation(program, name),
    ]),
  );
  textures = [gl.createTexture(), gl.createTexture()];
  gl.uniform1i(uniforms.scene, 0);
  gl.uniform1i(uniforms.depth, 1);
  gl.uniform2f(uniforms.sun, 0.885, 0.21);
}

function texture(unit, source) {
  gl.activeTexture(gl.TEXTURE0 + unit);
  gl.bindTexture(gl.TEXTURE_2D, textures[unit]);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  if (source) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
  else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([90, 90, 90, 255]));
}

const load = (source) =>
  new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(Error('Não foi possível carregar o plano de fundo. Escolha outra imagem.'));
    image.src = source;
  });

export async function setWallpaper(custom) {
  const request = ++revision;
  const source = custom || 'wallpaper/scene.jpg';
  const [image, depth] = await Promise.all([load(source), custom || !gl ? null : load('wallpaper/depth.png')]);
  if (request !== revision) return;
  current = custom;
  canvas.style.backgroundImage = `url(${JSON.stringify(source)})`;
  canvas.style.backgroundSize = 'cover';
  canvas.style.backgroundPosition = 'center';
  if (!gl || gl.isContextLost()) return;
  try {
    if (!program) initialize();
    texture(0, image);
    texture(1, depth);
    size = [image.naturalWidth, image.naturalHeight];
    sunRays = custom ? 0 : 1;
    scene.setReady(true);
  } catch {
    scene.setReady(false);
  }
}

canvas.addEventListener('webglcontextlost', (event) => {
  event.preventDefault();
  scene.setReady(false);
});
canvas.addEventListener('webglcontextrestored', () => {
  program = null;
  setWallpaper(current).catch(() => {});
});
window.addEventListener(
  'pagehide',
  () => {
    if (!gl || !program) return;
    textures.forEach((value) => gl.deleteTexture(value));
    gl.deleteBuffer(buffer);
    gl.deleteProgram(program);
  },
  { once: true },
);
