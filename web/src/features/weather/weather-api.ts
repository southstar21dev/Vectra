import { RiCloudyLine, RiDrizzleLine, RiFoggyLine, RiThunderstormsLine, RiRainyLine, RiSnowyLine, RiSunCloudyLine, RiSunLine, type RemixiconComponentType } from '@remixicon/react';
import { z } from 'zod';
import { timeoutSignal } from '@/lib/timeout-signal';

/** Fixed Budapest location — fallback when geolocation is off or not granted. */
export const BUDAPEST_COORDS = { latitude: 47.4979, longitude: 19.0402 } as const;

export interface WeatherCoords {
  latitude: number;
  longitude: number;
}

export interface WeatherData {
  /** Current temperature, °C. */
  temperature: number;
  /** Feels-like temperature, °C. */
  feelsLike: number;
  /** Wind speed, km/h. */
  windSpeed: number;
  /** WMO weather code (0–99). */
  weatherCode: number;
  /** Chance of precipitation in the current hour, percent. */
  rainProbability: number;
  /** Today's max temperature, °C. */
  high: number;
  /** Today's min temperature, °C. */
  low: number;
}

/** Sky family of a condition — drives the weather card's tint and artwork. */
export type WeatherSky = 'clear' | 'partly' | 'cloudy' | 'fog' | 'rain' | 'snow' | 'storm';

export interface WeatherConditionMeta {
  label: string;
  icon: RemixiconComponentType;
  sky: WeatherSky;
}

/**
 * The subset of the Open-Meteo /v1/forecast response this feature uses.
 * Unknown response fields are stripped (z.object strips by default).
 */
export const weatherResponseSchema = z.object({
  current: z.object({
    temperature_2m: z.number(),
    apparent_temperature: z.number(),
    wind_speed_10m: z.number(),
    weather_code: z.number(),
  }),
  hourly: z.object({
    time: z.array(z.string()),
    precipitation_probability: z.array(z.number().nullable()),
  }),
  daily: z.object({
    time: z.array(z.string()),
    temperature_2m_max: z.array(z.number()),
    temperature_2m_min: z.array(z.number()),
  }),
  utc_offset_seconds: z.number(),
});

const WMO_META: Record<number, WeatherConditionMeta> = {
  0: { label: 'Clear', icon: RiSunLine, sky: 'clear' },
  1: { label: 'Mostly clear', icon: RiSunCloudyLine, sky: 'partly' },
  2: { label: 'Partly cloudy', icon: RiSunCloudyLine, sky: 'partly' },
  3: { label: 'Overcast', icon: RiCloudyLine, sky: 'cloudy' },
  45: { label: 'Fog', icon: RiFoggyLine, sky: 'fog' },
  48: { label: 'Rime fog', icon: RiFoggyLine, sky: 'fog' },
  51: { label: 'Light drizzle', icon: RiDrizzleLine, sky: 'rain' },
  53: { label: 'Drizzle', icon: RiDrizzleLine, sky: 'rain' },
  55: { label: 'Dense drizzle', icon: RiDrizzleLine, sky: 'rain' },
  56: { label: 'Freezing drizzle', icon: RiDrizzleLine, sky: 'rain' },
  57: { label: 'Freezing drizzle', icon: RiDrizzleLine, sky: 'rain' },
  61: { label: 'Light rain', icon: RiRainyLine, sky: 'rain' },
  63: { label: 'Rain', icon: RiRainyLine, sky: 'rain' },
  65: { label: 'Heavy rain', icon: RiRainyLine, sky: 'rain' },
  66: { label: 'Freezing rain', icon: RiRainyLine, sky: 'rain' },
  67: { label: 'Freezing rain', icon: RiRainyLine, sky: 'rain' },
  71: { label: 'Light snow', icon: RiSnowyLine, sky: 'snow' },
  73: { label: 'Snow', icon: RiSnowyLine, sky: 'snow' },
  75: { label: 'Heavy snow', icon: RiSnowyLine, sky: 'snow' },
  77: { label: 'Snow grains', icon: RiSnowyLine, sky: 'snow' },
  80: { label: 'Light showers', icon: RiRainyLine, sky: 'rain' },
  81: { label: 'Showers', icon: RiRainyLine, sky: 'rain' },
  82: { label: 'Heavy showers', icon: RiRainyLine, sky: 'rain' },
  85: { label: 'Snow showers', icon: RiSnowyLine, sky: 'snow' },
  86: { label: 'Snow showers', icon: RiSnowyLine, sky: 'snow' },
  95: { label: 'Thunderstorm', icon: RiThunderstormsLine, sky: 'storm' },
  96: { label: 'Thunderstorm', icon: RiThunderstormsLine, sky: 'storm' },
  99: { label: 'Severe thunderstorm', icon: RiThunderstormsLine, sky: 'storm' },
};

/** WMO weather code → condition label + icon. Unmapped codes get a generic fallback. */
export function weatherCodeMeta(code: number): WeatherConditionMeta {
  return WMO_META[code] ?? { label: 'Weather', icon: RiCloudyLine, sky: 'cloudy' };
}

/** Hour-of-day (0–23) at the location described by utc_offset_seconds (Open-Meteo
 *  returns the current offset in `timezone: 'auto'` responses). Used to index the
 *  hourly precipitation series — correct for any location, not just Budapest. */
export function currentHourAtOffset(utcOffsetSeconds: number, now: Date = new Date()): number {
  const localSeconds = Math.floor(now.getTime() / 1000) + utcOffsetSeconds;
  return Math.floor((((localSeconds % 86400) + 86400) % 86400) / 3600);
}

/**
 * Chance of precipitation in the current hour. Open-Meteo aligns the hourly
 * series to the start of the local day, so the current hour's index is the
 * local hour-of-day derived from utc_offset_seconds. Null / missing values
 * read as 0.
 */
export function hourlyRainProbability(
  hourly: { precipitation_probability: Array<number | null> },
  utcOffsetSeconds: number,
  now: Date = new Date()
): number {
  return hourly.precipitation_probability[currentHourAtOffset(utcOffsetSeconds, now)] ?? 0;
}

const OPEN_METEO_URL = 'https://api.open-meteo.com/v1/forecast';

/** Current conditions at the given coordinates straight from Open-Meteo (keyless, CORS-open). */
export async function fetchWeather(coords: WeatherCoords = BUDAPEST_COORDS): Promise<WeatherData> {
  const params = new URLSearchParams({
    latitude: String(coords.latitude),
    longitude: String(coords.longitude),
    current: 'temperature_2m,apparent_temperature,wind_speed_10m,weather_code',
    hourly: 'precipitation_probability',
    daily: 'temperature_2m_max,temperature_2m_min',
    timezone: 'auto',
    forecast_days: '1',
  });
  const res = await fetch(`${OPEN_METEO_URL}?${params.toString()}`, {
    signal: timeoutSignal(15_000),
  });
  if (!res.ok) throw new Error(`Open-Meteo returned HTTP ${res.status}`);
  // zod throws on any shape change — the query errors and the card hides.
  const json = weatherResponseSchema.parse(await res.json());
  return {
    temperature: json.current.temperature_2m,
    feelsLike: json.current.apparent_temperature,
    windSpeed: json.current.wind_speed_10m,
    weatherCode: json.current.weather_code,
    rainProbability: hourlyRainProbability(json.hourly, json.utc_offset_seconds),
    high: json.daily.temperature_2m_max[0] ?? json.current.temperature_2m,
    low: json.daily.temperature_2m_min[0] ?? json.current.temperature_2m,
  };
}
