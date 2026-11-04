import { describe, expect, it } from 'vitest';
import { RiCloudyLine, RiThunderstormsLine, RiSunLine } from '@remixicon/react';
import {
  weatherCodeMeta,
  hourlyRainProbability,
  currentHourAtOffset,
  weatherResponseSchema,
  type WeatherData,
} from './weather-api';
import { weatherAlertItems } from './weather-alerts';

describe('weatherCodeMeta', () => {
  it('maps WMO codes to label + icon', () => {
    expect(weatherCodeMeta(0).label).toBe('Clear');
    expect(weatherCodeMeta(0).icon).toBe(RiSunLine);
    expect(weatherCodeMeta(3).label).toBe('Overcast');
    expect(weatherCodeMeta(95).label).toBe('Thunderstorm');
    expect(weatherCodeMeta(95).icon).toBe(RiThunderstormsLine);
  });

  it('falls back to a generic label + Cloud icon for unmapped codes', () => {
    const fallback = weatherCodeMeta(999);
    expect(fallback.label).toBe('Weather');
    expect(fallback.icon).toBe(RiCloudyLine);
  });
});

describe('weatherResponseSchema', () => {
  it('accepts a valid Open-Meteo response and keeps the fields we use', () => {
    const parsed = weatherResponseSchema.parse({
      current: { temperature_2m: 21.3, apparent_temperature: 19.8, wind_speed_10m: 14.2, weather_code: 2 },
      hourly: { time: ['2026-08-09T00:00'], precipitation_probability: [10, 20, 30] },
      daily: { time: ['2026-08-09'], temperature_2m_max: [24.1], temperature_2m_min: [13.7] },
      utc_offset_seconds: 7200,
    });
    expect(parsed.current.temperature_2m).toBe(21.3);
    expect(parsed.hourly.precipitation_probability).toHaveLength(3);
    expect(parsed.daily.temperature_2m_max[0]).toBe(24.1);
  });

  it('rejects a malformed response (string where a number belongs)', () => {
    expect(() =>
      weatherResponseSchema.parse({
        current: { temperature_2m: 'warm' },
      })
    ).toThrow();
  });
});

describe('hourlyRainProbability', () => {
  it('picks the probability for the current hour at the given UTC offset', () => {
    // 10:30 UTC with UTC+2 (Budapest summer) = 12:30 local → index 12.
    const now = new Date('2026-08-09T10:30:00Z');
    const probs: Array<number | null> = Array.from({ length: 24 }, (_, i) => (i === 12 ? 70 : 0));
    expect(hourlyRainProbability({ precipitation_probability: probs }, 7200, now)).toBe(70);
  });

  it('returns 0 for null or missing probability', () => {
    const now = new Date('2026-08-09T10:30:00Z');
    const allNull: Array<number | null> = Array.from({ length: 24 }, () => null);
    expect(hourlyRainProbability({ precipitation_probability: allNull }, 7200, now)).toBe(0);
    expect(hourlyRainProbability({ precipitation_probability: [] }, 7200, now)).toBe(0);
  });
});

describe('currentHourAtOffset', () => {
  it('computes the local hour for positive and negative offsets', () => {
    const now = new Date('2026-08-09T10:30:00Z'); // 37800s into the UTC day
    expect(currentHourAtOffset(7200, now)).toBe(12); // UTC+2 → 12:30
    expect(currentHourAtOffset(3600, now)).toBe(11); // UTC+1 → 11:30
    expect(currentHourAtOffset(0, now)).toBe(10); // UTC → 10:30
    expect(currentHourAtOffset(-18000, now)).toBe(5); // UTC-5 → 05:30
  });

  it('wraps across midnight', () => {
    const now = new Date('2026-08-09T23:30:00Z');
    expect(currentHourAtOffset(3600, now)).toBe(0); // 00:30 next day
    expect(currentHourAtOffset(-3600, now)).toBe(22); // 22:30 previous day
  });
});

function weather(overrides: Partial<WeatherData> = {}): WeatherData {
  return {
    temperature: 20,
    feelsLike: 20,
    windSpeed: 10,
    weatherCode: 2,
    rainProbability: 0,
    high: 24,
    low: 13,
    ...overrides,
  };
}

describe('weatherAlertItems', () => {
  it('returns no alerts for benign conditions', () => {
    expect(weatherAlertItems(weather())).toEqual([]);
  });

  it('alerts at and above the 25 km/h wind threshold', () => {
    expect(weatherAlertItems(weather({ windSpeed: 24.9 }))).toEqual([]);
    expect(weatherAlertItems(weather({ windSpeed: 25 }))).toEqual([{ key: 'highWind', label: 'Strong wind' }]);
    expect(weatherAlertItems(weather({ windSpeed: 41.2 }))).toEqual([{ key: 'highWind', label: 'Strong wind' }]);
  });

  it('alerts at and above 30°C heat', () => {
    expect(weatherAlertItems(weather({ temperature: 29.9 }))).toEqual([]);
    expect(weatherAlertItems(weather({ temperature: 30 }))).toEqual([{ key: 'heat', label: 'High temperature' }]);
    expect(weatherAlertItems(weather({ temperature: 31.2 }))).toEqual([{ key: 'heat', label: 'High temperature' }]);
  });

  it('alerts at and below 5°C cold', () => {
    expect(weatherAlertItems(weather({ temperature: 5.1 }))).toEqual([]);
    expect(weatherAlertItems(weather({ temperature: 5 }))).toEqual([{ key: 'cold', label: 'Low temperature' }]);
    expect(weatherAlertItems(weather({ temperature: 4.4 }))).toEqual([{ key: 'cold', label: 'Low temperature' }]);
  });

  it('alerts at and above 50% rain probability', () => {
    expect(weatherAlertItems(weather({ rainProbability: 49 }))).toEqual([]);
    expect(weatherAlertItems(weather({ rainProbability: 50 }))).toEqual([{ key: 'rain', label: 'High rain chance' }]);
    expect(weatherAlertItems(weather({ rainProbability: 88 }))).toEqual([{ key: 'rain', label: 'High rain chance' }]);
  });

  it('reports multiple simultaneous alerts in fixed order', () => {
    const items = weatherAlertItems(weather({ temperature: 31.2, windSpeed: 33.7, rainProbability: 88 }));
    expect(items.map((a) => a.key)).toEqual(['highWind', 'heat', 'rain']);
  });
});
