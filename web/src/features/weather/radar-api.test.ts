import { describe, expect, it } from 'vitest';
import {
  formatRadarFrameLabel,
  getRadarTileUrl,
  normalizeRadarData,
  parseRainViewerResponse,
  type RainViewerResponse,
} from './radar-api';

describe('RainViewer Radar API & Utilities', () => {
  const sampleResponse: RainViewerResponse = {
    version: '2.0',
    generated: 1725576000,
    host: 'https://tilecache.rainviewer.com',
    radar: {
      past: [
        { time: 1725572400, path: '/v2/radar/1725572400' },
        { time: 1725574200, path: '/v2/radar/1725574200' },
        { time: 1725576000, path: '/v2/radar/1725576000' },
      ],
    },
  };

  it('parses valid RainViewer json response with zod schema', () => {
    const parsed = parseRainViewerResponse(sampleResponse);
    expect(parsed.radar.past).toHaveLength(3);
  });

  it('normalizes past radar data into ordered timeline frames', () => {
    const normalized = normalizeRadarData(sampleResponse);
    expect(normalized.radarFrames).toHaveLength(3);
    expect(normalized.radarFrames[0].time).toBe(1725572400);
    expect(normalized.latestPastTime).toBe(1725576000);
  });

  it('ignores nowcast frames (discontinued by RainViewer free tier)', () => {
    // The free public API stopped serving nowcast in Jan 2026, but guard the
    // timeline against future frames anyway if they ever reappear.
    const withNowcast = {
      ...sampleResponse,
      radar: {
        past: sampleResponse.radar.past,
        nowcast: [{ time: 1725576600, path: '/v2/radar/nowcast_1725576600' }],
      },
    } as unknown as RainViewerResponse;

    const normalized = normalizeRadarData(withNowcast);
    expect(normalized.radarFrames).toHaveLength(3);
    expect(normalized.latestPastTime).toBe(1725576000);
  });

  it('generates proper tile URLs for radar frames', () => {
    const radarUrl = getRadarTileUrl('https://tilecache.rainviewer.com', '/v2/radar/1725576000', 1, '1_1');
    expect(radarUrl).toBe('https://tilecache.rainviewer.com/v2/radar/1725576000/256/{z}/{x}/{y}/1/1_1.png');
  });

  it('formats frame labels with relative and clock time', () => {
    const latestPastTime = 1725576000;

    // Past 1 hour (3600s earlier)
    const pastFrame = { time: 1725572400, path: '/v2/radar/1725572400' };
    const pastLabel = formatRadarFrameLabel(pastFrame, latestPastTime);
    expect(pastLabel.relative).toBe('-60m');

    // Current / latest past
    const nowFrame = { time: 1725576000, path: '/v2/radar/1725576000' };
    const nowLabel = formatRadarFrameLabel(nowFrame, latestPastTime);
    expect(nowLabel.relative).toBe('Now');
  });
});
