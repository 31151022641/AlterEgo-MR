// Software QA render of the actual shared Kotlin mesh, with smooth per-vertex colors,
// app-equivalent per-vertex lighting and optional CPU painter-order mode.
// Usage: node scripts/render-portrait-preview.cjs [mesh.json] [output.png] [sharp-module] [--gallery] [--painter]
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require(process.argv[4] || process.env.SHARP_MODULE || 'sharp');

const WIDTH = 400, HEIGHT = 408, SCALE = 2;
const COLS = Number(process.argv.find(arg => arg.startsWith('--cols='))?.split('=')[1] || 4);
const GAP = 16, MARGIN = 32, HEADER = 118, FOOTER = 64;
const BACKGROUND = [17, 24, 35], KEY = [-.35, .45, .82];
const painter = process.argv.includes('--painter');

function escapeXml(text) {
  return String(text).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]));
}

function render(item) {
  if (process.argv.includes('--use-app-raster')) {
    if (!item.applicationRaster) throw new Error(`No actual application raster for ${item.title}`);
    return sharp(item.applicationRaster).resize(WIDTH, HEIGHT).png().toBuffer();
  }
  const width = WIDTH * SCALE, height = HEIGHT * SCALE;
  const pixels = Buffer.alloc(width * height * 4);
  const depth = new Float32Array(width * height).fill(-Infinity);
  // Neutral reference bounds are fixed per character family, including mane/horns.
  // Expression cases and rotations never trigger a geometry-dependent fit.
  const creature = item.character && item.character !== 'human';
  const headWidth = creature ? .40 : .28, headHeight = creature ? .44 : .38;
  const viewWidth = (WIDTH - 28) * SCALE, viewHeight = (HEIGHT - 62) * SCALE;
  const unit = Math.min(viewWidth / headWidth, viewHeight / headHeight);
  const centerX = width / 2, centerY = 22 * SCALE + viewHeight / 2;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const pos = (y * width + x) * 4;
    const glow = Math.max(0, 1 - Math.hypot((x - width / 2) / width, (y - height * .45) / height) * 1.45);
    pixels[pos] = BACKGROUND[0] + glow * 9;
    pixels[pos + 1] = BACKGROUND[1] + glow * 11;
    pixels[pos + 2] = BACKGROUND[2] + glow * 16;
    pixels[pos + 3] = 255;
  }
  const yaw = item.yaw * Math.PI / 180, co = Math.cos(yaw), si = Math.sin(yaw);
  const vertices = new Array(item.colors.length);
  for (let i = 0; i < vertices.length; i++) {
    const k = i * 3, p = item.positions, n = item.normals;
    const x = co * p[k] + si * p[k + 2], y = p[k + 1], z = -si * p[k] + co * p[k + 2];
    const nx = co * n[k] + si * n[k + 2], ny = n[k + 1], nz = -si * n[k] + co * n[k + 2];
    const rgb = (item.shadedColors ? item.shadedColors[i] : item.colors[i]) >>> 0;
    const diffuse = Math.max(0, Math.min(1, nx * KEY[0] + ny * KEY[1] + nz * KEY[2]));
    const illumination = .6 + diffuse * .36;
    const specular = (item.metallic ? .18 : .035) * Math.pow(Math.max(nz, 0), 22);
    const shade = (value, shine) => item.shadedColors ? value : Math.max(0, Math.min(255, Math.floor(value * illumination + shine * specular)));
    vertices[i] = [centerX + x * unit, centerY - y * unit, z,
      shade((rgb >>> 16) & 255, 255), shade((rgb >>> 8) & 255, 244), shade(rgb & 255, 229)];
  }
  const indices = item.indices;
  const triangles = Array.from({ length: indices.length / 3 }, (_, i) => i * 3);
  if (painter) triangles.sort((a, b) =>
    vertices[indices[a]][2] + vertices[indices[a + 1]][2] + vertices[indices[a + 2]][2] -
    vertices[indices[b]][2] - vertices[indices[b + 1]][2] - vertices[indices[b + 2]][2]);
  for (const i of triangles) {
    const a = vertices[indices[i]], b = vertices[indices[i + 1]], c = vertices[indices[i + 2]];
    const area = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
    // Android/OpenGL uses outward CCW triangles; +Y-up becomes clockwise on screen.
    if (area >= -1e-8) continue;
    const minX = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0]))), maxX = Math.min(width - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
    const minY = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1]))), maxY = Math.min(height - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
    for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
      const px = x + .5, py = y + .5;
      const u = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / area;
      const v = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / area;
      const w = 1 - u - v;
      if (u < -1e-6 || v < -1e-6 || w < -1e-6) continue;
      const z = a[2] * u + b[2] * v + c[2] * w, at = y * width + x;
      if (!painter && z < depth[at]) continue;
      depth[at] = z;
      for (let channel = 0; channel < 3; channel++) {
        const color = a[channel + 3] * u + b[channel + 3] * v + c[channel + 3] * w;
        pixels[at * 4 + channel] = Math.max(0, Math.min(255, Math.round(color)));
      }
    }
  }
  return sharp(pixels, { raw: { width, height, channels: 4 } }).resize(WIDTH, HEIGHT).png().toBuffer();
}

async function main() {
  const input = path.resolve(process.argv[2] || 'scratch/portrait-mesh-preview.json');
  const output = path.resolve(process.argv[3] || 'scratch/portrait-improvements.png');
  const model = JSON.parse(await fs.readFile(input, 'utf8'));
  if (!Array.isArray(model.cases) || model.cases.length === 0) throw new Error('No geometry cases found.');
  const filter = process.argv.find(arg => arg.startsWith('--filter='))?.split('=')[1];
  if (filter) model.cases = model.cases.filter(item => item.title === filter || item.title.startsWith(filter + ' /'));
  if (model.cases.length === 0) throw new Error(`No cases match filter: ${filter}`);
  const humanGallery = process.argv.includes('--human-gallery');
  const gallery = process.argv.includes('--gallery') || humanGallery;
  const art = process.argv.includes('--art');
  const authored = process.argv.includes('--authored');
  const animalHeads = process.argv.includes('--animal-heads');
  if (animalHeads) {
    model.cases = ['Lion', 'Dragon', 'Unicorn'].flatMap(title =>
      ['', ' / right', ' / profile'].map(suffix => {
        const item = model.cases.find(item => item.title === title + suffix);
        if (!item) throw new Error(`Missing animal view: ${title + suffix}`);
        return item;
      }));
  }
  if (gallery) {
    let choices = [['Everyday character', 'Natural'], ['Bearded', 'Bearded'], ['Golden sculpture', 'Gold'],
      ['Cyber synth', 'Cyber'], ['Holographic', 'Holo'], ['Lion', 'Lion'], ['Dragon', 'Dragon'], ['Unicorn', 'Unicorn']];
    if (authored) choices.splice(0, 2, ['Rain Portrait','Rain Portrait'], ['Snow Portrait','Snow Portrait']);
    if (humanGallery) choices = choices.slice(0, 5);
    model.cases = choices.map(([title, label]) => {
      const item = model.cases.find(item => item.title === title);
      if (!item) throw new Error(`Missing gallery mesh: ${title}`);
      return { ...item, title: label };
    });
  }
  const rows = Math.ceil(model.cases.length / COLS);
  const width = MARGIN * 2 + WIDTH * COLS + GAP * (COLS - 1);
  const height = HEADER + rows * HEIGHT + GAP * (rows - 1) + FOOTER;
  const composites = [];
  const captions = [];
  for (let i = 0; i < model.cases.length; i++) {
    const item = model.cases[i], x = MARGIN + (i % COLS) * (WIDTH + GAP), y = HEADER + Math.floor(i / COLS) * (HEIGHT + GAP);
    composites.push({ input: await render(item), left: x, top: y });
    captions.push(`<text x="${x + 20}" y="${y + HEIGHT - 39}" class="label">${escapeXml(item.title)}</text>`);
    captions.push(`<text x="${x + 20}" y="${y + HEIGHT - 18}" class="sub">${escapeXml(item.subtitle)}</text>`);
  }
  const labels = Buffer.from(`<svg width="${width}" height="${height}" xmlns="http://www.w3.org/2000/svg">
    <style>text{font-family:Segoe UI,Arial,sans-serif}.label{fill:#ecf3fa;font-size:19px;font-weight:600}.sub{fill:#8fa3b8;font-size:13px}</style>
    <text x="${MARGIN}" y="48" fill="#f5f8fc" font-family="Segoe UI,Arial,sans-serif" font-size="30" font-weight="600">${authored && humanGallery ? 'Rain and Snow / animated 3D portraits' : humanGallery ? 'Reference-inspired human portraits' : art ? 'Sculpted character collection' : gallery ? 'Cartoon face collection' : animalHeads ? 'Animal heads / front, turn and profile' : '3D portrait / geometry &amp; expression preview'}</text>
    <text x="${MARGIN}" y="79" class="sub">${gallery ? (humanGallery ? 'Five human presets' : 'Eight cartoon characters') + ' · actual application ' + (process.argv.includes('--use-app-raster') ? 'geometry and software renderer' : 'geometry') : 'Actual application mesh · fixed camera scale · vertex lighting · ' + (painter ? 'CPU painter order' : 'per-pixel depth buffer')}</text>
    ${captions.join('\n')}
    ${authored ? `<text x="${MARGIN}" y="${height-44}" class="sub">Rain Rig and Snow Rig © Blender Foundation · studio.blender.org · CC BY 4.0 · adapted</text>` : ''}
    <text x="${MARGIN}" y="${height - 25}" class="sub">${gallery ? `Offline preview · ${painter ? 'CPU painter order' : 'depth tested'} · application vertex lighting. Headset perspective may differ.` : `Offline geometry QA · ${model.cases.length} poses · detail ${model.detail} · Headset perspective may differ.`}</text>
  </svg>`);
  composites.push({ input: labels, left: 0, top: 0 });
  await fs.mkdir(path.dirname(output), { recursive: true });
  await sharp({ create: { width, height, channels: 4, background: '#0a1019' } }).composite(composites).png().toFile(output);
  console.log(`Rendered ${model.cases.length} actual mesh poses: ${output} (${width} × ${height})`);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
