import { useState } from 'react';
import { RiLogoutBoxRLine, RiLoader4Line, RiMapPinLine, RiInformationLine, RiDeleteBinLine } from '@remixicon/react';
import { useAppVersion } from '@/lib/app-version';
import { NotificationSettings } from '@/features/notifications/notification-settings';
import { NeptunSettings } from '@/features/schedule/neptun-settings';
import { useAuth } from '@/features/auth/use-auth';
import { PasskeySettings } from '@/features/auth/passkey-settings';
import { AppearanceSettings } from './appearance-settings';
import { AccountSettings, DeleteAccountButton } from './account-settings';
import { SettingsSection, SettingsRow } from './settings-section';
import { Reveal } from '@/components/ui/reveal';
import { Toggle } from '@/components/ui/toggle';
import { Button } from '@/components/ui/button';
import {
  useWeatherLocation,
  type WeatherLocationStatus,
} from '@/features/weather/weather-location';

/** Status line under the location toggle. */
const LOCATION_STATUS_COPY: Record<WeatherLocationStatus, string> = {
  idle: 'Checking location…',
  locating: 'Reading your location…',
  located: 'Using your location',
  budapest: 'Off, using Budapest',
  fallback: 'Not granted, using Budapest',
  denied: 'Denied. Allow location for Vectra in system settings',
  unsupported: 'Location is not supported on this device',
};

export function SettingsPage() {
  const { logout } = useAuth();
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const { state: weatherState, setMode: setWeatherMode } = useWeatherLocation();
  const versionInfo = useAppVersion();

  const handleLogout = async () => {
    setIsLoggingOut(true);
    // `logout` always clears the session (its push cleanup is bounded), but
    // leave for /login even if it throws so the button can never stay stuck.
    try {
      await logout();
    } finally {
      window.location.href = '/login';
    }
  };

  const locationSubtitle =
    weatherState.status === 'located' && weatherState.city
      ? `${LOCATION_STATUS_COPY.located} — ${weatherState.city}`
      : LOCATION_STATUS_COPY[weatherState.status];

  return (
    <div className="mx-auto grid w-full max-w-md grid-cols-1 gap-y-7">
      {/* Every card enters in reading order, the same fade-and-rise the
          dashboard uses — held until the boot splash clears. */}
      {/* Appearance first: the most-used controls. */}
      <Reveal index={0}>
        <AppearanceSettings />
      </Reveal>

      <Reveal index={1}>
        <SettingsSection title="Account" tilt="right">
          <AccountSettings />
          <PasskeySettings />
        </SettingsSection>
      </Reveal>

      <Reveal index={2}>
        <NeptunSettings />
      </Reveal>

      <Reveal index={3}>
        <SettingsSection title="Permissions" tilt="right">
          <SettingsRow
            icon={RiMapPinLine}
            iconClass="bg-tint-amber text-tint-amber"
            title="Location"
            subtitle={locationSubtitle}
            control={
              <Toggle
                checked={weatherState.mode === 'auto'}
                onChange={(checked) => setWeatherMode(checked ? 'auto' : 'budapest')}
                aria-label="Allow location access"
              />
            }
          />
          <NotificationSettings />
        </SettingsSection>
      </Reveal>

      <Reveal index={4}>
        <SettingsSection title="About" tilt="left">
          <SettingsRow
            icon={RiInformationLine}
            iconClass="bg-tint-sky text-tint-sky"
            title="Version"
            control={<span className="text-sm text-secondary tabular-nums">{versionInfo.appVersion}-web</span>}
          />
          <SettingsRow
            icon={RiLogoutBoxRLine}
            iconClass="bg-surface-overlay text-secondary"
            title="Sign out"
            subtitle="End your session on this device"
            control={
              <Button
                variant="outline"
                size="sm"
                onClick={handleLogout}
                disabled={isLoggingOut}
                className="shrink-0 rounded-full text-xs font-bold"
              >
                {isLoggingOut ? <RiLoader4Line className="h-3.5 w-3.5 animate-spin" /> : 'Sign out'}
              </Button>
            }
          />
          <SettingsRow
            icon={RiDeleteBinLine}
            iconClass="bg-tint-red text-tint-red"
            title="Delete account"
            subtitle="Permanently delete your account and data"
            danger
            control={<DeleteAccountButton />}
          />
        </SettingsSection>
      </Reveal>
    </div>
  );
}
