import type { WeatherData } from './weather-api';

export type WeatherAlertKey = 'highWind' | 'heat' | 'cold' | 'rain';

export interface WeatherAlertItem {
  key: WeatherAlertKey;
  /** Full chip text, e.g. "High temperature". */
  label: string;
}

/** Fixed v1 thresholds — baked in per the approved design (no settings UI). */
export const WEATHER_ALERT_THRESHOLDS = {
  windKmh: 25,
  heatC: 30,
  coldC: 5,
  rainPercent: 50,
} as const;

/** Alert chips for the given conditions; empty when nothing is crossed.
 *  Order is fixed: highWind → heat → cold → rain. */
export function weatherAlertItems(weather: WeatherData): WeatherAlertItem[] {
  const alerts: WeatherAlertItem[] = [];
  if (weather.windSpeed >= WEATHER_ALERT_THRESHOLDS.windKmh) {
    alerts.push({ key: 'highWind', label: 'Strong wind' });
  }
  if (weather.temperature >= WEATHER_ALERT_THRESHOLDS.heatC) {
    alerts.push({ key: 'heat', label: 'High temperature' });
  }
  if (weather.temperature <= WEATHER_ALERT_THRESHOLDS.coldC) {
    alerts.push({ key: 'cold', label: 'Low temperature' });
  }
  if (weather.rainProbability >= WEATHER_ALERT_THRESHOLDS.rainPercent) {
    alerts.push({ key: 'rain', label: 'High rain chance' });
  }
  return alerts;
}
