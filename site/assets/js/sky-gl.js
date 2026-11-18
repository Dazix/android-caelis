/* CAELIS hero sky on the GPU.
   Pass 1, at half resolution: a single-scattering atmosphere (Rayleigh, Mie, ozone, the Earth's shadow)
   lit by the Sun and the Moon, with the glare around each, tone-mapped into a texture. The sky has no
   sharp edges, so half resolution costs nothing visible and a quarter of the work.
   Pass 2, at screen resolution: that texture upscaled, plus the Sun's disc (limb darkening) and the
   Moon as a sphere textured with the NASA colour map (assets/img/moon-map.webp: NASA GSFC Scientific
   Visualization Studio, CGI Moon Kit, LRO data, public domain; downsized), lit with its phase. Both discs are seen through
   the air they shine through, so the low Sun turns red by itself rather than by a second gradient.
   main.js owns the clock and the positions; this file only draws what it is told. Without WebGL (or
   highp in fragment shaders) create() returns null and main.js keeps the SVG and CSS sky. */
(() => {
  'use strict';

  const VERT = 'attribute vec2 a;void main(){gl_Position=vec4(a,0.0,1.0);}';

  // Kilometres throughout, so float precision is not spent on Earth radii in metres.
  const ATMOS = `
const float PI = 3.14159265;
const float R0 = 6371.0;
const float RA = 6471.0;
const vec3 BR = vec3(5.802e-3, 13.558e-3, 33.1e-3);
const float BM = 3.996e-3;
const float BME = 4.40e-3;
const vec3 BO = vec3(0.650e-3, 1.881e-3, 0.085e-3);
vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
`;

  const FRAG_SKY = `
precision highp float;
${ATMOS}
uniform vec2 uRes;          // target size in px
uniform float uPxPerCss;    // target px per CSS px
uniform float uHorizon, uRadPerPx, uExposure, uNight; // horizon and angle scale in CSS px
uniform vec3 uSunDir, uMoonDir;
uniform float uSunI, uMoonI;
uniform vec3 uSunDisc, uMoonDisc; // x, y from the top, radius; CSS px
const int N = 12;
const int M = 4;

vec2 sphere(vec3 ro, vec3 rd, float r) {
  float b = dot(ro, rd);
  float c = dot(ro, ro) - r * r;
  float d = b * b - c;
  if (d < 0.0) return vec2(-1.0); // a miss: no ground in the way, and no atmosphere to cross
  d = sqrt(d);
  return vec2(-b - d, -b + d);
}
vec3 dens(vec3 p) {
  float h = max(length(p) - R0, 0.0);
  return vec3(exp(-h / 8.0), exp(-h / 1.2), max(0.0, 1.0 - abs(h - 25.0) / 15.0));
}
vec3 ext(vec3 od) { return BR * od.x + BME * od.y + BO * od.z; }
// Optical depth towards the light; x < 0 where the Earth is in the way (the Earth's shadow at dusk).
vec3 lightDepth(vec3 p, vec3 l) {
  if (sphere(p, l, R0).x > 0.0) return vec3(-1.0);
  float ds = sphere(p, l, RA).y / float(M);
  vec3 od = vec3(0.0);
  for (int j = 0; j < M; j++) od += dens(p + l * ((float(j) + 0.5) * ds)) * ds;
  return od;
}
float phaseR(float mu) { return 3.0 / (16.0 * PI) * (1.0 + mu * mu); }
float phaseM(float mu) {
  const float g = 0.76;
  float gg = g * g;
  return 3.0 / (8.0 * PI) * ((1.0 - gg) * (1.0 + mu * mu)) / ((2.0 + gg) * pow(1.0 + gg - 2.0 * g * mu, 1.5));
}

void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uPxPerCss;
  float el = (uHorizon - px.y) * uRadPerPx;
  // Under the ridges nothing shows; skip the march there.
  if (el < -0.12) { gl_FragColor = vec4(0.02, 0.03, 0.08, 1.0); return; }
  float az = (px.x - 0.5 * uRes.x / uPxPerCss) * uRadPerPx;
  vec3 rd = vec3(cos(el) * sin(az), sin(el), cos(el) * cos(az));

  vec3 ro = vec3(0.0, R0 + 0.4, 0.0);
  float tmax = sphere(ro, rd, RA).y;
  float tg = sphere(ro, rd, R0).x;
  if (tg > 0.0) tmax = min(tmax, tg);
  float ds = tmax / float(N);
  vec3 od = vec3(0.0);
  vec3 sR = vec3(0.0), sM = vec3(0.0), mR = vec3(0.0), mM = vec3(0.0);
  for (int i = 0; i < N; i++) {
    vec3 p = ro + rd * ((float(i) + 0.5) * ds);
    vec3 d = dens(p) * ds;
    od += d * 0.5;
    if (uSunI > 0.0) {
      vec3 ol = lightDepth(p, uSunDir);
      if (ol.x >= 0.0) { vec3 t = exp(-ext(od + ol)); sR += d.x * t; sM += d.y * t; }
    }
    if (uMoonI > 0.0) {
      vec3 ol = lightDepth(p, uMoonDir);
      if (ol.x >= 0.0) { vec3 t = exp(-ext(od + ol)); mR += d.x * t; mM += d.y * t; }
    }
    od += d * 0.5;
  }
  vec3 viewT = exp(-ext(od));
  float ms = dot(rd, uSunDir), mm = dot(rd, uMoonDir);
  vec3 c = uSunI * (sR * BR * phaseR(ms) + sM * BM * phaseM(ms))
         + uMoonI * (mR * BR * phaseR(mm) + mM * BM * phaseM(mm));

  // Night is never pure black: airglow and starlight, a deeper blue towards the zenith.
  c += uNight * mix(vec3(0.010, 0.012, 0.026), vec3(0.004, 0.006, 0.020), clamp(el, 0.0, 1.0));

  // The glare a lens and an eye add around a bright source; smooth, so it belongs to this pass.
  if (uSunI > 0.0) {
    float r = max(length(px - uSunDisc.xy) / uSunDisc.z - 1.0, 0.0);
    c += viewT * uSunI * (0.12 * exp(-3.0 * r) + 0.012 * exp(-0.5 * r));
  }
  if (uMoonI > 0.0) {
    float r = max(length(px - uMoonDisc.xy) / uMoonDisc.z - 1.0, 0.0);
    c += viewT * uMoonI * (0.03 * exp(-1.8 * r) + 0.004 * exp(-0.3 * r)) * vec3(0.85, 0.9, 1.0);
  }

  gl_FragColor = vec4(pow(aces(c * uExposure), vec3(1.0 / 2.2)), 1.0);
}`;

  const FRAG_COMP = `
precision highp float;
${ATMOS}
uniform vec2 uRes;
uniform float uPxPerCss;
uniform sampler2D uSky, uMoonTex;
uniform float uMoonTexReady;
uniform vec3 uSunDisc, uMoonDisc;  // CSS px
uniform vec3 uSunCol, uMoonCol;    // radiance reaching the eye, exposure included (0 = not shown)
uniform vec3 uMoonLight;           // in the Moon's view frame: x right, y up, z towards the viewer
uniform float uMoonTilt;
uniform vec4 uShade;              // the shade behind the copy: centre and radii, CSS px
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec3 sky = texture2D(uSky, uv).rgb;
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uPxPerCss;
  // A real sky is bright towards the horizon and around the Sun. A soft shade behind the copy keeps the
  // ivory text readable, the way a photographer's grad filter would; it darkens the sky only, so the
  // Sun and the Moon drawn below stay at full brightness even where they pass behind the text.
  float t = length((px - uShade.xy) / uShade.zw);
  float a = t < 0.42 ? mix(0.62, 0.36, t / 0.42) : 0.36 * (1.0 - smoothstep(0.42, 1.0, t));
  sky = mix(sky, vec3(0.031, 0.047, 0.133), a);
  vec3 disc = vec3(0.0);
  float aa = 1.2 / uPxPerCss;

  if (uSunCol.r + uSunCol.g + uSunCol.b > 0.0) {
    float r = length(px - uSunDisc.xy) / uSunDisc.z;
    if (r < 1.0) {
      float mu = sqrt(1.0 - r * r);
      float limb = 1.0 - 0.6 * (1.0 - mu) - 0.15 * (1.0 - mu) * (1.0 - mu);
      float edge = 1.0 - smoothstep(1.0 - aa / uSunDisc.z, 1.0, r);
      disc += uSunCol * limb * edge;
    }
  }

  // Lommel-Seeliger: the lunar soil stays bright up to the limb; faint earthshine on the dark side.
  if (uMoonCol.r + uMoonCol.g + uMoonCol.b > 0.0) {
    vec2 d = (px - uMoonDisc.xy) / uMoonDisc.z;
    d.y = -d.y;
    float r2 = dot(d, d);
    if (r2 < 1.0) {
      float cs = cos(uMoonTilt), sn = sin(uMoonTilt);
      vec2 q = vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
      vec3 n = vec3(q, sqrt(1.0 - r2));
      vec2 tc = vec2(0.5 + atan(n.x, n.z) / (2.0 * PI), 0.5 - asin(clamp(n.y, -1.0, 1.0)) / PI);
      vec3 albedo = uMoonTexReady > 0.5 ? pow(texture2D(uMoonTex, tc).rgb, vec3(2.2)) : vec3(0.15);
      vec3 nl = vec3(d, n.z);
      float mu0 = max(dot(nl, uMoonLight), 0.0);
      float lit = 2.0 * mu0 / (mu0 + nl.z + 1e-3);
      float edge = 1.0 - smoothstep(1.0 - aa / uMoonDisc.z, 1.0, sqrt(r2));
      disc += uMoonCol * albedo * (lit * 0.5 + 0.004) * edge;
    }
  }

  vec3 c = sky;
  if (disc.r + disc.g + disc.b > 0.0) {
    vec3 dtm = pow(aces(disc), vec3(1.0 / 2.2));
    c = 1.0 - (1.0 - sky) * (1.0 - dtm); // light adds to the sky behind it, it does not replace it
  }
  c += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
  gl_FragColor = vec4(c, 1.0);
}`;

  // Same air as the shader, on the CPU, for the colour of the two discs: the transmittance from the
  // observer to space at elevation `el` (radians). Writes into `out`, allocates nothing.
  const R0 = 6371, RA = 6471;
  const BR = [5.802e-3, 13.558e-3, 33.1e-3], BME = 4.40e-3, BO = [0.650e-3, 1.881e-3, 0.085e-3];
  function transmittance(el, out) {
    const ry = R0 + 0.4;
    const dy = Math.sin(el), dx = Math.cos(el);
    // Ray from (0, ry) along (dx, dy) to the top of the atmosphere; below the geometric horizon it
    // would hit the ground, so clamp just above it (the disc is behind the ridge by then anyway).
    const b = ry * Math.max(dy, -0.02);
    const c = ry * ry - RA * RA;
    const tmax = -b + Math.sqrt(b * b - c);
    const n = 24, ds = tmax / n;
    let r = 0, m = 0, o = 0;
    for (let i = 0; i < n; i++) {
      const t = (i + 0.5) * ds;
      const x = dx * t, y = ry + Math.max(dy, -0.02) * t;
      const h = Math.max(Math.hypot(x, y) - R0, 0);
      r += Math.exp(-h / 8) * ds;
      m += Math.exp(-h / 1.2) * ds;
      o += Math.max(0, 1 - Math.abs(h - 25) / 15) * ds;
    }
    for (let k = 0; k < 3; k++) out[k] = Math.exp(-(BR[k] * r + BME * m + BO[k] * o));
    return out;
  }

  function compile(gl, type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) || 'shader');
    return s;
  }
  function program(gl, frag, names) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, VERT));
    gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, frag));
    gl.bindAttribLocation(p, 0, 'a');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) || 'link');
    const u = {};
    names.forEach((n) => { u[n] = gl.getUniformLocation(p, n); });
    return { p, u };
  }
  function texture(gl) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return t;
  }

  /** Returns a renderer, or null when this browser cannot draw it. */
  function create(canvas, { moonUrl, onLost } = {}) {
    let gl = null;
    try {
      const opts = { alpha: false, antialias: false, depth: false, stencil: false, powerPreference: 'low-power' };
      gl = canvas.getContext('webgl', opts) || canvas.getContext('experimental-webgl', opts);
    } catch (e) { gl = null; }
    if (!gl) return null;
    const hp = gl.getShaderPrecisionFormat && gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
    if (!hp || hp.precision === 0) return null; // the atmosphere needs highp in the fragment shader

    let sky, comp;
    try {
      sky = program(gl, FRAG_SKY, ['uRes', 'uPxPerCss', 'uHorizon', 'uRadPerPx', 'uExposure', 'uNight',
        'uSunDir', 'uMoonDir', 'uSunI', 'uMoonI', 'uSunDisc', 'uMoonDisc']);
      comp = program(gl, FRAG_COMP, ['uRes', 'uPxPerCss', 'uSky', 'uMoonTex', 'uMoonTexReady', 'uSunDisc',
        'uMoonDisc', 'uSunCol', 'uMoonCol', 'uMoonLight', 'uMoonTilt', 'uShade']);
    } catch (e) {
      return null;
    }

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    const skyTex = texture(gl);
    const fbo = gl.createFramebuffer();
    let skyW = 1, skyH = 1;

    // A grey Moon until the map arrives; the map loads after the page has, it is not on the LCP path.
    const moonTex = texture(gl);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, 1, 1, 0, gl.RGB, gl.UNSIGNED_BYTE, new Uint8Array([107, 107, 107]));
    let texReady = 0;
    let lost = false;
    let last = null;
    const renderer = { ok: true, render, resize };

    if (moonUrl) {
      const img = new Image();
      img.decoding = 'async';
      img.onload = () => {
        if (lost) return;
        gl.bindTexture(gl.TEXTURE_2D, moonTex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, img);
        texReady = 1;
        if (last) render(last);
      };
      const load = () => { img.src = moonUrl; };
      if (document.readyState === 'complete') setTimeout(load, 0);
      else window.addEventListener('load', load, { once: true });
    }

    canvas.addEventListener('webglcontextlost', (e) => {
      e.preventDefault();
      lost = true;
      renderer.ok = false;
      if (onLost) onLost();
    });

    let pxPerCss = 1;
    let skyPxPerCss = 0.5;
    /** CSS size and the canvas resolution to draw at (px per CSS px). */
    function resize(cssW, cssH, density) {
      pxPerCss = density;
      canvas.width = Math.max(1, Math.round(cssW * density));
      canvas.height = Math.max(1, Math.round(cssH * density));
      skyPxPerCss = 0.5;
      skyW = Math.max(1, Math.round(cssW * skyPxPerCss));
      skyH = Math.max(1, Math.round(cssH * skyPxPerCss));
      gl.bindTexture(gl.TEXTURE_2D, skyTex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, skyW, skyH, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, skyTex, 0);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    }

    /** s: CSS px positions (y from the top), unit directions (x right, y up, z ahead), linear colours. */
    function render(s) {
      if (lost) return;
      last = s;
      // Pass 1: the sky, half resolution, into the texture.
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.viewport(0, 0, skyW, skyH);
      gl.useProgram(sky.p);
      const u = sky.u;
      gl.uniform2f(u.uRes, skyW, skyH);
      gl.uniform1f(u.uPxPerCss, skyPxPerCss);
      gl.uniform1f(u.uHorizon, s.horizon);
      gl.uniform1f(u.uRadPerPx, s.radPerPx);
      gl.uniform1f(u.uExposure, s.exposure);
      gl.uniform1f(u.uNight, s.night);
      gl.uniform3fv(u.uSunDir, s.sunDir);
      gl.uniform3fv(u.uMoonDir, s.moonDir);
      gl.uniform1f(u.uSunI, s.sunI);
      gl.uniform1f(u.uMoonI, s.moonI);
      gl.uniform3fv(u.uSunDisc, s.sunDisc);
      gl.uniform3fv(u.uMoonDisc, s.moonDisc);
      gl.drawArrays(gl.TRIANGLES, 0, 3);

      // Pass 2: upscale and draw the discs at full resolution.
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(comp.p);
      const v = comp.u;
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, skyTex);
      gl.uniform1i(v.uSky, 0);
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, moonTex);
      gl.uniform1i(v.uMoonTex, 1);
      gl.activeTexture(gl.TEXTURE0);
      gl.uniform2f(v.uRes, canvas.width, canvas.height);
      gl.uniform1f(v.uPxPerCss, pxPerCss);
      gl.uniform1f(v.uMoonTexReady, texReady);
      gl.uniform3fv(v.uSunDisc, s.sunDisc);
      gl.uniform3fv(v.uMoonDisc, s.moonDisc);
      gl.uniform3fv(v.uSunCol, s.sunCol);
      gl.uniform3fv(v.uMoonCol, s.moonCol);
      gl.uniform3fv(v.uMoonLight, s.moonLight);
      gl.uniform1f(v.uMoonTilt, s.moonTilt);
      gl.uniform4fv(v.uShade, s.shade);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }

    return renderer;
  }

  window.CaelisSkyGL = { create, transmittance };
})();
