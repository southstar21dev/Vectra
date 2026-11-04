import { lazy, Suspense, useEffect, useState } from 'react';
import {
  RiCloudyFill,
  RiDropLine,
  RiFoggyFill,
  RiMapPin2Fill,
  RiRadarLine,
  RiRainyFill,
  RiRainyLine,
  RiSnowflakeLine,
  RiSnowyFill,
  RiSunCloudyFill,
  RiSunFill,
  RiTempHotLine,
  RiThunderstormsFill,
  RiWindyLine,
  type RemixiconComponentType,
} from '@remixicon/react';
import { useWeather } from './use-weather';
import { useWeatherLocation } from './weather-location';
import { BUDAPEST_COORDS, weatherCodeMeta, type WeatherSky } from './weather-api';
import { weatherAlertItems, type WeatherAlertKey } from './weather-alerts';
import { Card } from '@/components/ui/card';
import { StatusLabel } from '@/components/ui/status-label';
import { useLazyOpen } from '@/hooks/use-lazy-open';
import { cn } from '@/lib/utils';

// The radar dialog statically pulls maplibre, so it stays out of the initial
// dashboard bundle. `loadWeatherRadar` is shared by the lazy component and a
// warm-up effect below so the chunk (and the map engine) is ready before the tap.
const loadWeatherRadar = () => import('./weather-radar-dialog');
const WeatherRadarDialog = lazy(() => loadWeatherRadar().then((m) => ({ default: m.WeatherRadarDialog })));

const SKY_ART: Record<WeatherSky, RemixiconComponentType> = {
  clear: RiSunFill,
  partly: RiSunCloudyFill,
  cloudy: RiCloudyFill,
  fog: RiFoggyFill,
  rain: RiRainyFill,
  snow: RiSnowyFill,
  storm: RiThunderstormsFill,
};

const SKY_TONE: Record<WeatherSky, string> = {
  clear: 'text-amber-400',
  partly: 'text-amber-400',
  cloudy: 'text-muted opacity-60',
  fog: 'text-muted opacity-60',
  rain: 'text-tint-sky',
  snow: 'text-tint-sky opacity-80',
  storm: 'text-tint-violet',
};

const ALERT_ICONS: Record<WeatherAlertKey, RemixiconComponentType> = {
  highWind: RiWindyLine,
  heat: RiTempHotLine,
  cold: RiSnowflakeLine,
  rain: RiRainyLine,
};

export function WeatherCard() {
  const [isRadarOpen, setIsRadarOpen] = useState(false);
  const radarLoaded = useLazyOpen(isRadarOpen);
  const { state } = useWeatherLocation();
  const coords = state.coords ?? BUDAPEST_COORDS;
  const { data, isLoading, isError, isPlaceholderData } = useWeather(coords);

  // Budapest (mode off, or location not granted) keeps the classic header;
  // geolocated mode shows the reverse-geocoded city, "MY LOCATION" when the
  // name is unknown.
  const liveTitle = state.coords === null ? 'Budapest' : state.city ? state.city : 'My location';
  // While a location change is in flight the card keeps showing the previous
  // location's weather (placeholder data), so the header must keep naming the
  // place that data was fetched for until the new fetch lands.
  const [settledTitle, setSettledTitle] = useState(liveTitle);
  if (data && !isPlaceholderData && settledTitle !== liveTitle) setSettledTitle(liveTitle);
  const locationTitle = isPlaceholderData ? settledTitle : liveTitle;

  // Warm the radar chunk — and the maplibre engine it statically imports — as
  // soon as the card is on screen, so tapping it opens the dialog immediately
  // instead of waiting on the chunk parse. Importing the module only parses it;
  // no map (and no WebGL context) is created until the dialog actually mounts.
  useEffect(() => {
    void loadWeatherRadar();
  }, []);

  // Fetch failures (offline, API down, shape change) with nothing to show hide
  // the card entirely — the dashboard already shows a global offline banner; a
  // dead weather strip would just add noise. A failed background refresh keeps
  // the last good reading on screen.
  if (isError && !data) return null;
  if (isLoading || !data) return <WeatherCardSkeleton />;

  const meta = weatherCodeMeta(data.weatherCode);
  const alerts = weatherAlertItems(data);
  const SkyArt = SKY_ART[meta.sky];

  return (
    <>
      <section className="min-w-0 mb-6">
        {/* Only transform/shadow/border animate — `transition-all` would also
            tween layout properties (width, flex reflow at breakpoints), which
            makes the card twitch while the window is being resized. */}
        <Card
          role="button"
          tabIndex={0}
          onClick={() => setIsRadarOpen(true)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setIsRadarOpen(true);
            }
          }}
          className={cn(
            'relative w-full min-w-0 overflow-hidden rounded-3xl card-glass p-4 sm:p-5 cursor-pointer select-none group transition-[transform,box-shadow,border-color] hover:shadow-lg hover:scale-[1.005] active:scale-[0.995]',
            `weather-sky-${meta.sky}`
          )}
          aria-label={`Weather for ${locationTitle}: ${Math.round(data.temperature)} degrees, ${meta.label}. Tap to open live weather radar.`}
        >
          {/* Condition artwork spilling off the top-right corner. */}
          <SkyArt
            aria-hidden
            className={cn(
              'pointer-events-none absolute -right-5 -top-6 h-26 w-26 transition-transform duration-500 group-hover:rotate-6',
              SKY_TONE[meta.sky]
            )}
          />

          <div className="relative flex items-center gap-1.5 text-xs font-semibold text-secondary">
            <RiMapPin2Fill className="h-3.5 w-3.5 shrink-0" aria-hidden />
            <h2 className="truncate">{locationTitle}</h2>
            <span className="ml-1.5 inline-flex shrink-0 items-center gap-1 text-muted group-hover:text-secondary transition-colors">
              <RiRadarLine className="h-3 w-3" aria-hidden />
              Radar
            </span>
          </div>

          <p className="relative mt-2 text-[54px] font-bold leading-none tracking-tighter tabular-nums text-primary">
            {Math.round(data.temperature)}°
          </p>

          <div className="relative mt-1.5 flex items-center justify-between gap-3">
            <p className="min-w-0 truncate text-sm font-semibold text-primary">
              {meta.label}
              <span className="font-medium text-secondary">
                {' · '}H {Math.round(data.high)}° L {Math.round(data.low)}°
              </span>
            </p>
            <div className="flex shrink-0 items-center gap-3 text-[13px] font-bold tabular-nums text-primary">
              <span className="inline-flex items-center gap-1" title="Feels like">
                <RiTempHotLine className="h-3.5 w-3.5 text-muted" aria-hidden />
                <span className="sr-only">Feels like </span>{Math.round(data.feelsLike)}°
              </span>
              <span className="inline-flex items-center gap-1" title="Chance of rain">
                <RiDropLine className="h-3.5 w-3.5 text-muted" aria-hidden />
                <span className="sr-only">Rain </span>{Math.round(data.rainProbability)}%
              </span>
              <span className="inline-flex items-center gap-1" title="Wind">
                <RiWindyLine className="h-3.5 w-3.5 text-muted" aria-hidden />
                <span className="sr-only">Wind </span>{Math.round(data.windSpeed)} km/h
              </span>
            </div>
          </div>

          {alerts.length > 0 && (
            <div className="relative mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-subtle pt-3">
              {alerts.map((alert) => (
                <StatusLabel key={alert.key} tone="amber" icon={ALERT_ICONS[alert.key]}>
                  {alert.label}
                </StatusLabel>
              ))}
            </div>
          )}
        </Card>
      </section>

      {radarLoaded && (
        <Suspense fallback={null}>
          <WeatherRadarDialog
            open={isRadarOpen}
            onOpenChange={setIsRadarOpen}
            coords={coords}
            weatherData={data}
          />
        </Suspense>
      )}
    </>
  );
}

/** Slim pulse strip while the first fetch is in flight — no layout jump. */
function WeatherCardSkeleton() {
  return (
    <section className="min-w-0 mb-6">
      <Card className="p-6 w-full min-w-0 card-glass border-subtle rounded-3xl transition-[color,background-color,border-color,box-shadow,transform,opacity]">
        <div className="flex items-center gap-4 animate-pulse">
          <div className="w-10 h-10 rounded-xl bg-surface-overlay" />
          <div className="space-y-2">
            <div className="h-3 w-36 rounded bg-surface-overlay" />
            <div className="h-3 w-24 rounded bg-surface-overlay" />
          </div>
          <div className="h-9 w-16 rounded bg-surface-overlay ml-auto" />
        </div>
      </Card>
    </section>
  );
}
