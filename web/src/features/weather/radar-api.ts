import { z } from 'zod';
import { timeoutSignal } from '@/lib/timeout-signal';

export const RAINVIEWER_API_URL = 'https://api.rainviewer.com/public/weather-maps.json';

const frameSchema = z
  .object({
    time: z.union([z.number(), z.string().transform((v) => Number(v) || 0)]),
    path: z.string(),
  })
  .passthrough();

export const rainViewerResponseSchema = z
  .object({
    version: z.union([z.string(), z.number()]).optional(),
    generated: z
      .union([z.number(), z.string().transform((v) => Number(v) || 0)])
      .default(() => Math.floor(Date.now() / 1000)),
    host: z.string().default('https://tilecache.rainviewer.com'),
    radar: z
      .object({
        past: z.array(frameSchema).default([]),
      })
      .optional()
      .default({ past: [] }),
  })
  .passthrough();

export type RainViewerResponse = z.infer<typeof rainViewerResponseSchema>;

export interface RadarFrame {
  time: number;
  path: string;
}

export interface NormalizedRadarData {
  generated: number;
  host: string;
  radarFrames: RadarFrame[];
  latestPastTime: number;
}

export function parseRainViewerResponse(json: unknown): RainViewerResponse {
  return rainViewerResponseSchema.parse(json);
}

export function normalizeRadarData(data: RainViewerResponse): NormalizedRadarData {
  // RainViewer's free public API no longer serves nowcast/future frames
  // (discontinued Jan 2026), so the timeline is past-only.
  const radarFrames: RadarFrame[] = (data.radar?.past ?? []).map((f) => ({
    time: f.time,
    path: f.path,
  }));

  const latestPastTime =
    radarFrames.length > 0 ? radarFrames[radarFrames.length - 1].time : data.generated;

  return {
    generated: data.generated,
    host: data.host,
    radarFrames,
    latestPastTime,
  };
}

/**
 * Returns Leaflet tile URL template for RainViewer radar frames.
 * Uses 512px HD tiles paired with Leaflet tileSize: 512, zoomOffset: -1.
 */
export function getRadarTileUrl(
  host: string,
  path: string,
  colorScheme = 1,
  options = '1_1',
  size: 256 | 512 = 256
): string {
  const cleanHost = host.replace(/\/+$/, '');
  const cleanPath = path.startsWith('/') ? path : `/${path}`;
  return `${cleanHost}${cleanPath}/${size}/{z}/{x}/{y}/${colorScheme}/${options}.png`;
}

export interface RadarFrameLabel {
  timeStr: string;
  relative: string;
}

/**
 * Formats a radar frame's time for timeline sliders and display badges.
 */
export function formatRadarFrameLabel(
  frame: RadarFrame,
  referenceTime: number = Math.floor(Date.now() / 1000),
  hour12: boolean = false
): RadarFrameLabel {
  const date = new Date(frame.time * 1000);
  const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12 });

  const diffSeconds = frame.time - referenceTime;
  const diffMinutes = Math.round(diffSeconds / 60);

  const relative = diffMinutes <= -5 ? `-${Math.abs(diffMinutes)}m` : 'Now';

  return { timeStr, relative };
}

/**
 * Fetches the latest radar metadata and active frame timestamps.
 * Tries direct API first, falls back to dev proxy if blocked.
 */
export async function fetchRainViewerData(): Promise<NormalizedRadarData> {
  let json: unknown;
  try {
    const res = await fetch(RAINVIEWER_API_URL, {
      signal: timeoutSignal(10_000),
    });
    if (!res.ok) throw new Error(`RainViewer API returned HTTP ${res.status}`);
    json = await res.json();
  } catch (directErr) {
    try {
      const proxyRes = await fetch('/api/proxy-radar', {
        signal: timeoutSignal(10_000),
      });
      if (!proxyRes.ok) throw directErr;
      json = await proxyRes.json();
    } catch {
      throw directErr;
    }
  }

  const parsed = parseRainViewerResponse(json);
  return normalizeRadarData(parsed);
}
