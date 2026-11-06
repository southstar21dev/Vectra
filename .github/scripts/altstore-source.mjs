#!/usr/bin/env node
// Writes the AltStore/SideStore sources into the directory given as the first argument, built from
// the repo's GitHub Releases (every release with an .ipa asset):
//   altstore.json       releases from master only
//   altstore-beta.json  also the prereleases from staging (-rc.N) and dev (-dev.N)
// Rebuilding from the releases each time keeps the sources in step with what's actually published,
// so nothing has to be committed back to the repo. Needs GITHUB_REPOSITORY and GITHUB_TOKEN.
//
//   node .github/scripts/altstore-source.mjs site
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const outDir = process.argv[2] ?? 'site';
const repo = process.env.GITHUB_REPOSITORY ?? 'southstar21dev/Vectra';
const token = process.env.GITHUB_TOKEN;

const KEEP = 20;
const iconURL = `https://raw.githubusercontent.com/${repo}/master/native/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png`;
const tintColor = '8B5CF6';
const minOSVersion = '18.5'; // native/iosApp/project.yml deploymentTarget

async function releases() {
  const all = [];
  for (let page = 1; ; page++) {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=100&page=${page}`, {
      headers: {
        Accept: 'application/vnd.github+json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    if (!response.ok) throw new Error(`GitHub API: ${response.status} ${await response.text()}`);
    const batch = await response.json();
    all.push(...batch);
    if (batch.length < 100) return all;
  }
}

// Same rule as native/androidApp/build.gradle.kts and iosApp/project.yml: dev.N is 0-49, rc.N is
// 50-98 and the release is 99, so every published build installs as an update over the one before.
// CFBundleShortVersionString is the plain x.y.z.
function bundleVersions(tag) {
  const m = /^v?(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(dev|rc)\.(\d+))?$/.exec(tag);
  if (!m) return null;
  const [maj, min, patch, kind, n] = [+m[1], +m[2], +m[3], m[4], +(m[5] ?? 0)];
  const build = kind === 'dev' ? Math.min(n, 49) : kind === 'rc' ? 50 + Math.min(n, 48) : 99;
  return {
    version: `${maj}.${min}.${patch}`,
    buildVersion: String((maj * 10_000 + min * 100 + patch) * 100 + build),
  };
}

function toVersion(release) {
  const ipa = release.assets.find((asset) => asset.name.endsWith('.ipa'));
  const versions = bundleVersions(release.tag_name);
  if (!ipa || !versions || release.draft) return null;
  const name = release.tag_name.replace(/^v/, '');
  return {
    ...versions,
    // No milliseconds: AltStore/SideStore parse dates with ISO8601DateFormatter, which rejects them.
    date: release.published_at.replace(/\.\d{3}Z$/, 'Z'),
    // The full prerelease name goes in the notes; iOS only shows x.y.z.
    localizedDescription: `${name}\n\n${release.body ?? ''}`.trim(),
    downloadURL: ipa.browser_download_url,
    size: ipa.size,
    minOSVersion,
    prerelease: release.prerelease,
  };
}

function source({ beta, versions }) {
  return {
    name: beta ? 'Vectra (beta)' : 'Vectra',
    identifier: beta ? 'dev.southstar21.vectra.source.beta' : 'dev.southstar21.vectra.source',
    subtitle: beta ? 'Releases and prereleases from dev and staging.' : 'Stable releases of Vectra.',
    description: beta
      ? 'Vectra releases and prereleases, straight from GitHub.'
      : 'Vectra releases, straight from GitHub.',
    iconURL,
    website: `https://github.com/${repo}`,
    tintColor,
    apps: [
      {
        name: 'Vectra',
        bundleIdentifier: 'dev.southstar21.vectra',
        developerName: 'southstar21',
        subtitle: 'Your timetable and Budapest transit in one place.',
        localizedDescription:
          'Your Neptun timetable, live Budapest public transport with nearby departures and route maps, and the weather, in one app.',
        iconURL,
        tintColor,
        category: 'utilities',
        screenshots: [],
        // AltStore treats the first version as the latest, so order by build number.
        versions: versions.slice(0, KEEP).map(({ prerelease, ...version }) => version),
        appPermissions: {
          entitlements: [],
          privacy: {
            NSLocationWhenInUseUsageDescription:
              'Vectra uses your location to find nearby stops and departures and to show local weather.',
          },
        },
      },
    ],
    news: [],
  };
}

const versions = (await releases())
  .map(toVersion)
  .filter(Boolean)
  .sort((a, b) => Number(b.buildVersion) - Number(a.buildVersion));

await mkdir(outDir, { recursive: true });
for (const beta of [false, true]) {
  const included = beta ? versions : versions.filter((version) => !version.prerelease);
  const file = join(outDir, beta ? 'altstore-beta.json' : 'altstore.json');
  await writeFile(file, `${JSON.stringify(source({ beta, versions: included }), null, 2)}\n`);
  console.log(`${file}: ${Math.min(included.length, KEEP)} version(s)`);
}
