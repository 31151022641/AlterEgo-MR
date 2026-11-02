// Adapt the existing AlterEgo brand mark to Meta Horizon Store image layouts.
// Usage: node scripts/build-store-art.cjs [path/to/sharp]
// This preserves the logo artwork; these are branded covers, not app screenshots.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require(process.argv[2] || 'sharp');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'assets/store/v1');
const pale = '#EAF1F4';

const layouts = [
  { name: 'cover-landscape', width: 2560, height: 1440, tile: 620, x: 430, y: 410,
    textX: 1190, textY: 768, font: 152, anchor: 'start' },
  { name: 'cover-square', width: 1440, height: 1440, tile: 550, x: 445, y: 310,
    textX: 720, textY: 1050, font: 126, anchor: 'middle' },
  { name: 'cover-portrait', width: 1008, height: 1440, tile: 520, x: 244, y: 330,
    textX: 504, textY: 1030, font: 98, anchor: 'middle' },
  { name: 'hero', width: 3000, height: 900, tile: 430, x: 665, y: 235,
    textX: 1245, textY: 508, font: 182, anchor: 'start' },
  { name: 'mini', width: 1080, height: 360, tile: 180, x: 175, y: 90,
    textX: 415, textY: 204, font: 76, anchor: 'start' },
  // Compact central lockup survives Meta's landscape, portrait and hero crops.
  { name: 'universal', width: 3000, height: 3000, tile: 500, x: 750, y: 1250,
    textX: 1390, textY: 1555, font: 160, anchor: 'start' },
];

function coverSvg(layout, markUri) {
  const { width: w, height: h, tile, x, y, textX, textY, font, anchor } = layout;
  const cx = x + tile / 2, cy = y + tile / 2;
  const symbolWidth = tile * 0.79;
  // The icon's original transparent mark has a wide aspect ratio. Its embedded
  // viewBox maintains the complete mark with no distortion or clipped elements.
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
  <defs>
    <linearGradient id="bg" x1="0" y1="1" x2="1" y2="0">
      <stop stop-color="#071D29"/><stop offset="0.55" stop-color="#10343E"/><stop offset="1" stop-color="#0B2633"/>
    </linearGradient>
    <radialGradient id="halo"><stop stop-color="#0DBBA4" stop-opacity="0.16"/><stop offset="1" stop-color="#0DBBA4" stop-opacity="0"/></radialGradient>
    <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#F5FBFD"/><stop offset="1" stop-color="${pale}"/></linearGradient>
  </defs>
  <rect width="${w}" height="${h}" fill="url(#bg)"/>
  <ellipse cx="${cx}" cy="${cy}" rx="${tile * 1.08}" ry="${tile * 1.08}" fill="url(#halo)"/>
  <g fill="none" stroke="#55CCCB">
    <circle cx="${cx}" cy="${cy}" r="${tile * 0.79}" stroke-width="${Math.max(1, tile / 370)}" opacity="0.13"/>
    <circle cx="${cx}" cy="${cy}" r="${tile * 1.18}" stroke-width="${Math.max(1, tile / 500)}" opacity="0.07"/>
  </g>
  <path d="M ${w * 0.68} ${-h * 0.38} C ${w * 0.49} ${h * 0.42}, ${w * 1.02} ${h * 0.23}, ${w * 1.07} ${h * 1.25}" fill="none" stroke="#9EDCD9" stroke-opacity="0.055" stroke-width="${Math.max(1, w / 1700)}"/>
  <rect x="${x}" y="${y + tile * 0.025}" width="${tile}" height="${tile}" rx="${tile * 0.22}" fill="#001019" opacity="0.2"/>
  <rect x="${x}" y="${y}" width="${tile}" height="${tile}" rx="${tile * 0.22}" fill="url(#tile)"/>
  <rect x="${x + 1}" y="${y + 1}" width="${tile - 2}" height="${tile - 2}" rx="${tile * 0.22}" fill="none" stroke="#FFFFFF" stroke-opacity="0.3" stroke-width="2"/>
  <image x="${cx - symbolWidth / 2}" y="${cy - tile * 0.31}" width="${symbolWidth}" height="${tile * 0.62}" preserveAspectRatio="xMidYMid meet" xlink:href="${markUri}"/>
  <text x="${textX}" y="${textY}" text-anchor="${anchor}" fill="#F2FBFC" font-family="Segoe UI, Arial, sans-serif" font-size="${font}" font-weight="600" letter-spacing="${-font * 0.026}">AlterEgo MR</text>
  </svg>`;
}

async function main() {
  await fs.mkdir(output, { recursive: true });
  const mark = await sharp(path.join(root, 'assets/icons/alterego-source.png')).trim().png().toBuffer();
  const markUri = `data:image/png;base64,${mark.toString('base64')}`;

  // 24-bit opaque PNG. Keep the established icon proportions and pale ground.
  const iconMark = await sharp(mark).resize({ width: 410 }).png().toBuffer();
  await sharp({ create: { width: 512, height: 512, channels: 3, background: pale } })
    .composite([{ input: iconMark, gravity: 'centre' }])
    .removeAlpha().png().toFile(path.join(output, 'icon-512.png'));

  for (const layout of layouts) {
    const svg = coverSvg(layout, markUri);
    const stem = `${layout.name}-${layout.width}x${layout.height}`;
    await fs.writeFile(path.join(output, `${stem}.svg`), svg);
    await sharp(Buffer.from(svg)).removeAlpha().png().toFile(path.join(output, `${stem}.png`));
  }

  const files = ['icon-512.png', ...layouts.map(l => `${l.name}-${l.width}x${l.height}.png`)];
  for (const file of files) {
    const info = await sharp(path.join(output, file)).metadata();
    if (info.channels !== 3 || info.hasAlpha) throw new Error(`${file} is not 24-bit RGB`);
    console.log(`${file}: ${info.width} x ${info.height}, RGB PNG`);
  }

  // Review sheet is only for local visual inspection, not a Store upload asset.
  const cellW = 800, cellH = 520;
  const cells = [];
  for (let i = 0; i < files.length; i++) {
    const cell = await sharp(path.join(output, files[i]))
      .resize(cellW - 40, cellH - 60, { fit: 'contain', background: '#D8E3E6' })
      .removeAlpha().png().toBuffer();
    const label = Buffer.from(`<svg width="${cellW}" height="40"><text x="20" y="27" fill="#18333E" font-family="Segoe UI, Arial, sans-serif" font-size="20">${files[i]}</text></svg>`);
    const left = (i % 2) * cellW, top = Math.floor(i / 2) * cellH;
    cells.push({ input: cell, left: left + 20, top: top + 10 });
    cells.push({ input: label, left, top: top + cellH - 40 });
  }
  await sharp({ create: { width: cellW * 2, height: cellH * Math.ceil(files.length / 2), channels: 3, background: '#D8E3E6' } })
    .composite(cells).removeAlpha().png().toFile(path.join(output, 'review-sheet.png'));
}

main().catch(error => { console.error(error); process.exitCode = 1; });
