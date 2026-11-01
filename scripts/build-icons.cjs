// Package the generated artwork as Android launcher resources and a Windows ICO.
// Usage: node scripts/build-icons.cjs [path/to/sharp]
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require(process.argv[2] || 'sharp');
const root = path.resolve(__dirname, '..');
const background = '#EAF1F4';

async function save(file, data) {
  const target = path.join(root, file);
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.writeFile(target, data);
}

function roundedMask(size, radius) {
  return Buffer.from(`<svg width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${radius}" fill="white"/></svg>`);
}

async function main() {
  const source = path.join(root, 'assets/icons/alterego-source.png');
  // Trim transparent margins only; keep the generated artwork unchanged.
  const mark = await sharp(source).trim().png().toBuffer();

  async function render(size, ratio, shape) {
    const symbol = await sharp(mark).resize({ width: Math.round(size * ratio) }).png().toBuffer();
    const icon = await sharp({ create: { width: size, height: size, channels: 4, background } })
      .composite([{ input: symbol, gravity: 'centre' }]).png().toBuffer();
    return shape ? sharp(icon).composite([{ input: roundedMask(size, shape === 'circle' ? size / 2 : size * .22), blend: 'dest-in' }]).png().toBuffer() : icon;
  }

  const desktop = await render(1024, .80, 'rounded');
  await save('assets/icons/alterego.png', desktop);

  // 108dp adaptive canvas: the mark occupies 62dp, inside launcher safe bounds.
  const symbol = await sharp(mark).resize({ width: 248 }).png().toBuffer();
  const foreground = await sharp({ create: { width: 432, height: 432, channels: 4, background: '#00000000' } })
    .composite([{ input: symbol, gravity: 'centre' }]).png().toBuffer();
  await save('app/src/main/res/drawable-nodpi/alterego_launcher_foreground.png', foreground);

  for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
    for (const round of [false, true]) {
      const image = await render(size, .84, round ? 'circle' : 'rounded');
      const webp = await sharp(image).webp({ lossless: true }).toBuffer();
      await save(`app/src/main/res/mipmap-${density}/ic_launcher${round ? '_round' : ''}.webp`, webp);
    }
  }

  // PNG-compressed 32-bit entries are supported by Windows Vista and later.
  const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256];
  const frames = await Promise.all(sizes.map(size => sharp(desktop).resize(size, size).png().toBuffer()));
  const header = Buffer.alloc(6 + 16 * sizes.length);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  sizes.forEach((size, index) => {
    const entry = 6 + index * 16;
    header[entry] = header[entry + 1] = size === 256 ? 0 : size;
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(frames[index].length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += frames[index].length;
  });
  await save('windows/assets/alterego.ico', Buffer.concat([header, ...frames]));
  console.log('Created Android adaptive/legacy launcher assets, 1024px PNG, and Windows ICO (16–256px).');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
