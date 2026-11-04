// Adds a release to an AltStore source file (stable.json or beta.json on the `altstore` branch).
//
//   CHANNEL=stable|beta VERSION=1.4.0-rc.2 IPA_URL=… IPA_SIZE=… NOTES=… \
//     node .github/scripts/altstore-source.mjs path/to/stable.json
//
// Creates the source on first use. AltStore treats the first version as the latest, so versions
// are ordered by build number: a dev build never shows as an update over a higher rc.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const [file] = process.argv.slice(2);
const { CHANNEL, VERSION, IPA_URL, IPA_SIZE, NOTES = '', GITHUB_REPOSITORY } = process.env;
if (!file || !CHANNEL || !VERSION || !IPA_URL || !IPA_SIZE) {
  console.error('usage: CHANNEL VERSION IPA_URL IPA_SIZE [NOTES] env, then the source file path');
  process.exit(1);
}

const KEEP = 10;
const beta = CHANNEL !== 'stable';
const repo = `https://github.com/${GITHUB_REPOSITORY}`;
const iconURL = `https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/master/native/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png`;
const tintColor = '8B5CF6';

// Same rule as native/androidApp/build.gradle.kts and iosApp/project.yml.
function buildNumber(version) {
  const m = /^(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(dev|rc)\.(\d+))?$/.exec(version);
  if (!m) throw new Error(`Unexpected version ${version}`);
  const [maj, min, patch, kind, n] = [+m[1], +m[2], +m[3], m[4], +(m[5] ?? 0)];
  const build = kind === 'dev' ? Math.min(n, 49) : kind === 'rc' ? 50 + Math.min(n, 48) : 99;
  return (maj * 10_000 + min * 100 + patch) * 100 + build;
}

const source = existsSync(file)
  ? JSON.parse(readFileSync(file, 'utf8'))
  : {
      name: beta ? 'Vectra Beta' : 'Vectra',
      identifier: beta ? 'dev.southstar21.vectra.source.beta' : 'dev.southstar21.vectra.source',
      subtitle: beta ? 'Prereleases from the dev and staging branches.' : 'Stable releases of Vectra.',
      website: repo,
      iconURL,
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
          appPermissions: {
            entitlements: [],
            privacy: {
              NSLocationWhenInUseUsageDescription:
                'Vectra uses your location to find nearby stops and departures and to show local weather.',
            },
          },
          versions: [],
        },
      ],
      news: [],
    };

const app = source.apps[0];
const build = String(buildNumber(VERSION));
const entry = {
  // iOS wants x.y.z; the full prerelease name goes in the notes, and the build number orders them.
  version: VERSION.split('-')[0],
  buildVersion: build,
  // No milliseconds: AltStore/SideStore parse dates with ISO8601DateFormatter, which rejects them.
  date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
  localizedDescription: `${VERSION}\n\n${NOTES}`.trim(),
  downloadURL: IPA_URL,
  size: Number(IPA_SIZE),
  minOSVersion: '18.5',
};
app.versions = [entry, ...app.versions.filter((v) => v.buildVersion !== build)]
  .sort((a, b) => Number(b.buildVersion) - Number(a.buildVersion))
  .slice(0, KEEP)
  .map((v) => ({ ...v, date: v.date.replace(/\.\d{3}Z$/, 'Z') }));

writeFileSync(file, `${JSON.stringify(source, null, 2)}\n`);
console.log(`${file}: Vectra ${VERSION} (build ${build}), ${app.versions.length} versions listed`);
