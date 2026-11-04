import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const iconsDir = path.resolve(__dirname, '../public/icons');

// The committed app icons are all the same 1024x1024 owl artwork (RGB, no alpha).
const SOURCE = path.join(iconsDir, 'icon-192.png');

/* ---------- Minimal PNG decoder (8-bit, non-interlaced) ---------- */

function decodePng(filePath) {
  const buf = fs.readFileSync(filePath);
  let offset = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0;
  const idat = [];

  while (offset < buf.length) {
    const len = buf.readUInt32BE(offset);
    const type = buf.toString('ascii', offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + len);

    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      if (data[12] !== 0) throw new Error('Interlaced PNG not supported');
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + len;
  }

  if (bitDepth !== 8) throw new Error(`Only 8-bit PNGs supported, got ${bitDepth}`);

  // colorType: 0=gray, 2=RGB, 3=palette, 4=gray+alpha, 6=RGBA
  const channels = [1, 0, 3, 1, 2, 0, 4][colorType] ?? 3;
  const bpp = channels;
  const inflated = zlib.inflateSync(Buffer.concat(idat));
  const stride = 1 + width * channels;
  const pixels = Buffer.alloc(width * height * channels);

  for (let y = 0; y < height; y++) {
    const filter = inflated[y * stride];
    const rowStart = y * stride + 1;
    for (let x = 0; x < width * channels; x++) {
      const byte = inflated[rowStart + x];
      const idx = y * width * channels + x;
      const a = x >= bpp ? pixels[idx - bpp] : 0;
      const b = y > 0 ? pixels[idx - width * channels] : 0;
      const c = x >= bpp && y > 0 ? pixels[idx - width * channels - bpp] : 0;

      let val;
      switch (filter) {
        case 0: val = byte; break;
        case 1: val = byte + a; break;
        case 2: val = byte + b; break;
        case 3: val = byte + ((a + b) >> 1); break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          val = byte + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default: throw new Error(`Unknown PNG filter ${filter}`);
      }
      pixels[idx] = val & 0xFF;
    }
  }

  return { width, height, channels, colorType, pixels };
}

/* ---------- Silhouette extraction ---------- */

function buildAlphaMap(img) {
  const { width, height, channels, pixels } = img;

  // The dominant color is the flat dark background of the icon artwork.
  const freq = new Map();
  for (let i = 0; i < width * height; i++) {
    const o = i * channels;
    const key = (pixels[o] << 16) | (pixels[o + 1] << 8) | pixels[o + 2];
    freq.set(key, (freq.get(key) || 0) + 1);
  }
  let bgKey = 0, bgCount = 0;
  for (const [k, c] of freq) {
    if (c > bgCount) { bgCount = c; bgKey = k; }
  }
  const bgR = (bgKey >> 16) & 0xff, bgG = (bgKey >> 8) & 0xff, bgB = bgKey & 0xff;
  console.log(`Background: #${bgKey.toString(16).padStart(6, '0')} (${bgCount} px)`);

  const alpha = new Float32Array(width * height);
  for (let i = 0; i < width * height; i++) {
    const o = i * channels;
    const r = pixels[o], g = pixels[o + 1], b = pixels[o + 2];
    const dist = Math.max(Math.abs(r - bgR), Math.abs(g - bgG), Math.abs(b - bgB));
    // Soft threshold: background -> transparent, artwork -> white
    alpha[i] = Math.max(0, Math.min(255, (dist - 24) * 6));
  }
  return alpha;
}

/* ---------- Box downsample of the alpha map ---------- */

function downsample(alpha, sw, sh, dw, dh) {
  const out = new Float32Array(dw * dh);
  for (let dy = 0; dy < dh; dy++) {
    const y0 = Math.floor((dy * sh) / dh);
    const y1 = Math.max(y0 + 1, Math.floor(((dy + 1) * sh) / dh));
    for (let dx = 0; dx < dw; dx++) {
      const x0 = Math.floor((dx * sw) / dw);
      const x1 = Math.max(x0 + 1, Math.floor(((dx + 1) * sw) / dw));
      let sum = 0, n = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          sum += alpha[y * sw + x];
          n++;
        }
      }
      out[dy * dw + dx] = sum / n;
    }
  }
  return out;
}

/* ---------- PNG encoder (RGBA) — same approach as generate-icons.js ---------- */

const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  crcTable[n] = c;
}

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = crcTable[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function writeChunk(type, data) {
  const len = data.length;
  const buf = Buffer.alloc(8 + len + 4);
  buf.writeUInt32BE(len, 0);
  buf.write(type, 4, 4, 'ascii');
  data.copy(buf, 8);
  const crcVal = crc32(buf.subarray(4, 8 + len));
  buf.writeUInt32BE(crcVal, 8 + len);
  return buf;
}

function createPng(width, height, getPixelRGBA) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // bit depth
  ihdrData[9] = 6; // RGBA
  const ihdrChunk = writeChunk('IHDR', ihdrData);

  const scanlineLength = 1 + width * 4;
  const rawData = Buffer.alloc(height * scanlineLength);
  for (let y = 0; y < height; y++) {
    rawData[y * scanlineLength] = 0; // filter: None
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = getPixelRGBA(x, y, width, height);
      const o = y * scanlineLength + 1 + x * 4;
      rawData[o] = r;
      rawData[o + 1] = g;
      rawData[o + 2] = b;
      rawData[o + 3] = a;
    }
  }

  const idatChunk = writeChunk('IDAT', zlib.deflateSync(rawData, { level: 9 }));
  const iendChunk = writeChunk('IEND', Buffer.alloc(0));
  return Buffer.concat([signature, ihdrChunk, idatChunk, iendChunk]);
}

/* ---------- Main ---------- */

console.log('Decoding owl icon...');
const img = decodePng(SOURCE);
console.log(`Source: ${img.width}x${img.height} colorType:${img.colorType}`);

const alphaFull = buildAlphaMap(img);

// Auto-crop to the artwork's bounding box so the badge fills the frame nicely
let minX = img.width, minY = img.height, maxX = -1, maxY = -1;
for (let y = 0; y < img.height; y++) {
  for (let x = 0; x < img.width; x++) {
    if (alphaFull[y * img.width + x] > 16) {
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
}
const cropW = maxX - minX + 1;
const cropH = maxY - minY + 1;
console.log(`Artwork bbox: ${cropW}x${cropH} at (${minX},${minY})`);

const cropped = new Float32Array(cropW * cropH);
for (let y = 0; y < cropH; y++) {
  for (let x = 0; x < cropW; x++) {
    cropped[y * cropW + x] = alphaFull[(minY + y) * img.width + (minX + x)];
  }
}

for (const size of [96, 192]) {
  // Scale to ~88% of the badge with even margins, preserving aspect ratio
  const scale = (size * 0.88) / Math.max(cropW, cropH);
  const w = Math.max(1, Math.round(cropW * scale));
  const h = Math.max(1, Math.round(cropH * scale));
  const ox = Math.floor((size - w) / 2);
  const oy = Math.floor((size - h) / 2);
  const alpha = downsample(cropped, cropW, cropH, w, h);

  const png = createPng(size, size, (x, y) => {
    const a =
      x >= ox && x < ox + w && y >= oy && y < oy + h
        ? Math.round(alpha[(y - oy) * w + (x - ox)])
        : 0;
    return [255, 255, 255, a]; // white silhouette
  });
  const outPath = path.join(iconsDir, `badge-${size}.png`);
  fs.writeFileSync(outPath, png);
  const opaque = Array.from(alpha).filter((v) => v > 128).length;
  const pct = ((opaque / (size * size)) * 100).toFixed(1);
  console.log(`Wrote badge-${size}.png (${png.length} bytes, ${pct}% opaque)`);
}

console.log('Badge icons generated from the owl artwork.');
