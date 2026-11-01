// Review sheet of existing source artwork; these are not application renders.
const sharp = require(process.argv[2]);

async function main() {
  const width = 1440, margin = 32, gap = 24, card = (width - margin * 2 - gap) / 2;
  const imageHeight = 387;
  const images = [
    ['assets/candidates/free-humans/rain-official-preview.jpg', 'Rain', 'Blender Studio / CC BY 4.0'],
    ['assets/candidates/free-humans/snow-official-preview.png', 'Snow', 'Blender Studio / CC BY 4.0']
  ];
  const composites = [];
  const labels = [];
  for (let i = 0; i < images.length; i++) {
    const [source, title, credit] = images[i];
    const x = margin + i * (card + gap);
    composites.push({ input: await sharp(source).resize(card, imageHeight, { fit: 'contain', background: '#131c28' }).png().toBuffer(), left: x, top: 122 });
    labels.push(`<text x="${x}" y="555" class="title">${title}</text><text x="${x}" y="581" class="small">${credit}</text>`);
  }
  const overlay = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="660">
    <style>text{font-family:Segoe UI,Arial,sans-serif;fill:#f0f5fc}.title{font-size:28px;font-weight:600}.small{font-size:17px;fill:#a6b9cf}</style>
    <text x="32" y="49" class="title">Free sculpted face candidates</text>
    <text x="32" y="84" class="small">Official source artwork for style review. These models have not been integrated into the app.</text>
    ${labels.join('')}
    <text x="32" y="632" class="small">Rain and Snow Rig © Blender Foundation | studio.blender.org | CC BY 4.0</text>
  </svg>`);
  composites.push({ input: overlay, left: 0, top: 0 });
  const output = 'assets/previews/free-face-candidates.png';
  await sharp({ create: { width, height: 660, channels: 4, background: '#0a111b' } }).composite(composites).png().toFile(output);
  console.log(output);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
