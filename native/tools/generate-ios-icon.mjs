// Generates the iOS app icon (iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png) from
// tools/assets/vectra.svg, matching the Android adaptive icon: the white owl on #0c0c0e, framed as
// Android frames it (the visible 72dp of the 108dp canvas, i.e. the middle two thirds of the SVG).
//   node tools/generate-ios-icon.mjs   (macOS: rasterises with Quick Look)
import { readFileSync, writeFileSync, mkdtempSync, renameSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const svg = readFileSync(join(here, 'assets', 'vectra.svg'), 'utf8');
const paths = [...svg.matchAll(/<path[^>]*\sd="([^"]+)"/g)].map((m) => m[1].replace(/\s+/g, ' ').trim());
const visible = 1024 * (72 / 108);
const inset = (1024 - visible) / 2;
const icon = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="${inset} ${inset} ${visible} ${visible}">
<rect x="0" y="0" width="1024" height="1024" fill="#0c0c0e"/>
${paths.map((d) => `<path fill="#ffffff" d="${d}"/>`).join('\n')}
</svg>
`;
const work = mkdtempSync(join(tmpdir(), 'vectra-icon-'));
writeFileSync(join(work, 'AppIcon.svg'), icon);
execFileSync('qlmanage', ['-t', '-s', '1024', '-o', work, join(work, 'AppIcon.svg')], { stdio: 'ignore' });
// iOS rejects app icons with an alpha channel; sips re-encodes the thumbnail as opaque JPEG-backed PNG.
execFileSync('sips', ['-s', 'format', 'jpeg', join(work, 'AppIcon.svg.png'), '--out', join(work, 'AppIcon.jpg')], { stdio: 'ignore' });
execFileSync('sips', ['-s', 'format', 'png', join(work, 'AppIcon.jpg'), '--out', join(work, 'AppIcon.png')], { stdio: 'ignore' });
renameSync(join(work, 'AppIcon.png'), join(here, '..', 'iosApp', 'iosApp', 'Assets.xcassets', 'AppIcon.appiconset', 'AppIcon.png'));
rmSync(work, { recursive: true, force: true });
console.log(`ios icon: ${paths.length} paths`);
