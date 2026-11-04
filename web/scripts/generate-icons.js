import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Standard CRC32 table
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  crcTable[n] = c;
}

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = crcTable[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
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
  // PNG Signature
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

  // IHDR
  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // Bit depth: 8
  ihdrData[9] = 6; // Color type: RGBA (6)
  ihdrData[10] = 0; // Compression
  ihdrData[11] = 0; // Filter
  ihdrData[12] = 0; // Interlace
  const ihdrChunk = writeChunk('IHDR', ihdrData);

  // IDAT - Scanlines with filter byte 0
  const scanlineLength = 1 + width * 4;
  const rawData = Buffer.alloc(height * scanlineLength);

  for (let y = 0; y < height; y++) {
    const rowOffset = y * scanlineLength;
    rawData[rowOffset] = 0; // Filter 0 (None)
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = getPixelRGBA(x, y, width, height);
      const pixelOffset = rowOffset + 1 + x * 4;
      rawData[pixelOffset] = r;
      rawData[pixelOffset + 1] = g;
      rawData[pixelOffset + 2] = b;
      rawData[pixelOffset + 3] = a;
    }
  }

  const compressedData = zlib.deflateSync(rawData, { level: 9 });
  const idatChunk = writeChunk('IDAT', compressedData);

  // IEND
  const iendChunk = writeChunk('IEND', Buffer.alloc(0));

  return Buffer.concat([signature, ihdrChunk, idatChunk, iendChunk]);
}

// Point in polygon test
function pointInTriangle(px, py, ax, ay, bx, by, cx, cy) {
  const v0x = cx - ax, v0y = cy - ay;
  const v1x = bx - ax, v1y = by - ay;
  const v2x = px - ax, v2y = py - ay;

  const dot00 = v0x * v0x + v0y * v0y;
  const dot01 = v0x * v1x + v0y * v1y;
  const dot02 = v0x * v2x + v0y * v2y;
  const dot11 = v1x * v1x + v1y * v1y;
  const dot12 = v1x * v2x + v1y * v2y;

  const invDenom = 1 / (dot00 * dot11 - dot01 * dot01);
  const u = (dot11 * dot02 - dot01 * dot12) * invDenom;
  const v = (dot00 * dot12 - dot01 * dot02) * invDenom;

  return (u >= 0) && (v >= 0) && (u + v < 1);
}

function isInsideVectraArrow(nx, ny) {
  // Arrow coordinates matching SVG: (0.5, 0.234), (0.703, 0.703), (0.5, 0.586), (0.297, 0.703)
  const ax = 0.5, ay = 0.234;
  const bx = 0.703, by = 0.703;
  const cx = 0.5, cy = 0.586;
  const dx = 0.297, dy = 0.703;

  const t1 = pointInTriangle(nx, ny, ax, ay, bx, by, cx, cy);
  const t2 = pointInTriangle(nx, ny, ax, ay, cx, cy, dx, dy);
  return t1 || t2;
}

function renderVectraIcon(x, y, w, h, isMaskable = false) {
  const nx = x / w;
  const ny = y / h;

  // Background `#0a0a0f`
  let bgR = 10, bgG = 10, bgB = 15, bgA = 255;

  if (!isMaskable) {
    // Rounded rect mask (corner radius ~25%)
    const r = 0.25;
    let distCornerSq = 0;

    if (nx < r && ny < r) distCornerSq = Math.pow(r - nx, 2) + Math.pow(r - ny, 2);
    else if (nx > 1 - r && ny < r) distCornerSq = Math.pow(nx - (1 - r), 2) + Math.pow(r - ny, 2);
    else if (nx < r && ny > 1 - r) distCornerSq = Math.pow(r - nx, 2) + Math.pow(ny - (1 - r), 2);
    else if (nx > 1 - r && ny > 1 - r) distCornerSq = Math.pow(nx - (1 - r), 2) + Math.pow(ny - (1 - r), 2);

    if (distCornerSq > r * r) {
      return [0, 0, 0, 0]; // Transparent outside rounded rect
    }
  }

  // Radial glow gradient in center
  const distFromCenter = Math.sqrt(Math.pow(nx - 0.5, 2) + Math.pow(ny - 0.5, 2));
  const circleRadius = 0.35;

  let r = bgR, g = bgG, b = bgB, a = bgA;

  if (distFromCenter < circleRadius) {
    const glow = Math.pow(1 - distFromCenter / circleRadius, 2) * 0.35;
    // Gradient: #4d94ff -> #8c64ff
    const gr = 77 + (140 - 77) * (nx * 0.5 + ny * 0.5);
    const gg = 148 + (100 - 148) * (nx * 0.5 + ny * 0.5);
    const gb = 255;

    r = Math.min(255, Math.round(r + gr * glow));
    g = Math.min(255, Math.round(g + gg * glow));
    b = Math.min(255, Math.round(b + gb * glow));
  }

  // Arrow Shape
  if (isInsideVectraArrow(nx, ny)) {
    const gr = 77 + (140 - 77) * (nx * 0.5 + ny * 0.5);
    const gg = 148 + (100 - 148) * (nx * 0.5 + ny * 0.5);
    const gb = 255;
    r = Math.round(gr);
    g = Math.round(gg);
    b = Math.round(gb);
  }

  return [r, g, b, a];
}

const iconsDir = path.resolve(__dirname, '../public/icons');

console.log('Generating 192x192 PNG...');
const png192 = createPng(192, 192, (x, y, w, h) => renderVectraIcon(x, y, w, h, false));
fs.writeFileSync(path.join(iconsDir, 'icon-192.png'), png192);

console.log('Generating 512x512 PNG...');
const png512 = createPng(512, 512, (x, y, w, h) => renderVectraIcon(x, y, w, h, false));
fs.writeFileSync(path.join(iconsDir, 'icon-512.png'), png512);

console.log('Generating 512x512 Maskable PNG...');
const pngMaskable = createPng(512, 512, (x, y, w, h) => renderVectraIcon(x, y, w, h, true));
fs.writeFileSync(path.join(iconsDir, 'icon-maskable.png'), pngMaskable);

console.log('All icons generated successfully!');
