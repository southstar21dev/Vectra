import { useState, useEffect, useCallback } from 'react';
import { RiRefreshLine, RiCheckLine, RiDeleteBinLine, RiLink as LinkIcon, RiTimeLine, RiErrorWarningLine, RiCheckboxCircleLine } from '@remixicon/react';

import { SETTINGS_BLOCK_CLASS, SettingsRow, SettingsSection } from '@/features/settings/settings-section';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { useClearAllScheduleEvents } from './use-schedule';
import { useNeptunSync, checkSyncRateLimit, MAX_SYNCS_PER_HOUR, clearNeptunEvents } from './use-neptun-sync';
import { toast } from 'sonner';

import { useAuth } from '@/features/auth/use-auth';
import { encryptNeptunUrl, decryptNeptunUrl } from '@/lib/encryption';

function getSyncCounterText(lastSyncStr: string | null): string {
  if (!lastSyncStr) return 'Never synced';
  const ts = Number(lastSyncStr);
  if (isNaN(ts) || ts <= 0) return 'Never synced';
  const diffMs = Date.now() - ts;
  const diffMins = Math.floor(diffMs / 60000);
  if (diffMins < 1) return 'Synced just now';
  if (diffMins < 60) return `Synced ${diffMins} minute${diffMins === 1 ? '' : 's'} ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `Synced ${diffHours} hour${diffHours === 1 ? '' : 's'} ago`;
  const diffDays = Math.floor(diffHours / 24);
  return `Synced ${diffDays} day${diffDays === 1 ? '' : 's'} ago`;
}

export function NeptunSettings() {
  const { profile, updateProfile } = useAuth();
  const { syncStatus, syncError, runSync } = useNeptunSync();
  const [urlInput, setUrlInput] = useState('');
  const [savedUrl, setSavedUrl] = useState('');
  const clearAll = useClearAllScheduleEvents();
  const [confirmClear, setConfirmClear] = useState(false);

  // Profile is the Single Source of Truth — last sync lives in neptun_last_sync.
  const lastSyncMs = profile?.neptun_last_sync ? new Date(profile.neptun_last_sync).getTime() : null;

  // Re-render every 30s so the "Synced X ago" counter ticks.
  const [, setNowTick] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNowTick(Date.now()), 30000);
    return () => clearInterval(id);
  }, []);
  const counterText = getSyncCounterText(lastSyncMs ? String(lastSyncMs) : null);

  // Load saved state on mount & profile sync
  useEffect(() => {
    let cancelled = false;

    const applyProfile = async () => {
      if (profile !== undefined) {
        const dbUrl = profile?.neptun_ics_url ? await decryptNeptunUrl(profile.neptun_ics_url.trim()) : '';
        if (cancelled) return;

        if (dbUrl) {
          setSavedUrl(dbUrl);
        } else {
          setSavedUrl('');
        }
        setUrlInput(''); // Clear the input field for security
      }
    };

    applyProfile();
    return () => {
      cancelled = true;
    };
  }, [profile]);

  const handleClearUrl = async () => {
    setSavedUrl('');
    setUrlInput('');
    toast.success('Neptun link removed');
    try {
      // Remove the synced calendar from the DB and clear the profile link.
      await clearNeptunEvents();
      await updateProfile({ neptun_ics_url: null, neptun_last_sync: null });
    } catch (e) {
      console.warn('Failed to clear Neptun data from profile:', e);
    }
  };

  /** Deletes every class (manual and Neptun) and the saved link, so all
   *  devices see the schedule emptied. */
  const handleClearAll = async () => {
    try {
      await clearAll.mutateAsync();
      setSavedUrl('');
      setUrlInput('');
      await updateProfile({ neptun_ics_url: null, neptun_last_sync: null });
      toast.success('All classes cleared');
    } catch (e) {
      toast.error('Could not clear your classes', { description: e instanceof Error ? e.message : undefined });
    }
  };

  const handleSaveUrl = async () => {
    const trimmed = urlInput.trim();
    if (!trimmed) {
      await handleClearUrl();
      return;
    }
    try {
      const encryptedUrl = await encryptNeptunUrl(trimmed);
      await updateProfile({ neptun_ics_url: encryptedUrl });
      setSavedUrl(trimmed);
      setUrlInput(''); // Clear the input field after pasting and saving
      toast.success('Neptun URL saved successfully');
    } catch (e) {
      console.warn('Failed to save Neptun URL to profile:', e);
      toast.error('Failed to save Neptun URL', {
        description: 'The encryption service is unavailable. Please try again.',
      });
    }
  };

  const [rateLimited, setRateLimited] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);

  const handleManualSync = useCallback(async () => {
    if (!savedUrl) return;

    const rateCheck = checkSyncRateLimit();
    if (!rateCheck.allowed) {
      setRateLimited(true);
      toast.error(`Rate limit reached`, {
        description: `You can manually sync at most ${MAX_SYNCS_PER_HOUR} times per hour to prevent Neptun servers from blocking your requests. Please wait ${rateCheck.remainingMinutes} minute(s) before syncing again.`,
      });
      setTimeout(() => setRateLimited(false), 1500);
      return;
    }

    setIsSyncing(true);
    try {
      await runSync(savedUrl, false);
    } finally {
      setIsSyncing(false);
    }
  }, [savedUrl, runSync]);

  return (
    <SettingsSection
      title="Neptun"
      tilt="left"
    >
      <SettingsRow
        icon={RiTimeLine}
        iconClass="bg-tint-blue text-tint-blue"
        title="Last sync"
        subtitle={counterText}
        control={
          savedUrl && lastSyncMs ? (
            <Button
              onClick={handleManualSync}
              disabled={isSyncing || syncStatus === 'syncing'}
              variant="outline"
              size="sm"
              className={`w-[6.5rem] shrink-0 justify-center gap-1.5 px-2 text-xs font-bold rounded-full transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-300 ${
                rateLimited
                  ? 'border-red-500/50 text-tint-red bg-red-500/10 shadow-[0_0_12px_rgba(239,68,68,0.25)] animate-[shake_0.3s_ease-in-out] motion-reduce:animate-none'
                  : syncStatus === 'success'
                  ? 'border-emerald-500/50 text-tint-emerald bg-emerald-500/10 shadow-[0_0_12px_rgba(16,185,129,0.25)]'
                  : ''
              }`}
            >
              {rateLimited ? (
                <RiErrorWarningLine className="w-3.5 h-3.5" />
              ) : isSyncing || syncStatus === 'syncing' ? (
                <RiRefreshLine className="w-3.5 h-3.5 animate-spin" />
              ) : syncStatus === 'success' ? (
                <RiCheckboxCircleLine className="w-3.5 h-3.5 text-emerald-400" />
              ) : (
                <RiRefreshLine className="w-3.5 h-3.5" />
              )}
              <span>
                {rateLimited
                  ? 'Rate limited'
                  : syncStatus === 'success'
                  ? 'Synced'
                  : isSyncing || syncStatus === 'syncing'
                  ? 'Syncing…'
                  : 'Sync now'}
              </span>
            </Button>
          ) : undefined
        }
      />

      {savedUrl ? (
        <SettingsRow
          icon={LinkIcon}
          iconClass="bg-tint-emerald text-tint-emerald"
          title="Calendar link"
          subtitle="Connected · stored encrypted"
          control={
            <Button
              onClick={handleClearUrl}
              variant="outline"
              size="sm"
              className="shrink-0 gap-1.5 text-xs font-bold rounded-full border-tint-red text-tint-red hover:bg-tint-red cursor-pointer"
            >
              <RiDeleteBinLine className="w-3.5 h-3.5" />
              <span>Remove</span>
            </Button>
          }
        />
      ) : (
        <div className={cn(SETTINGS_BLOCK_CLASS, 'space-y-3')}>
          <div>
            <p className="text-sm font-semibold text-primary leading-tight">Calendar link (.ics)</p>
            <p className="text-xs text-secondary mt-0.5">
              Paste your personal Neptun calendar link to sync your classes every 12 hours.
            </p>
          </div>
          <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2.5">
            <Input
              placeholder="https://neptun3r.web.uni-corvinus.hu/Hallgatoi/api/Calendar/..."
              value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)}
              className="font-mono text-xs flex-1 rounded-xl bg-surface-overlay"
            />
            <Button
              onClick={handleSaveUrl}
              variant="default"
              size="sm"
              className="h-9 px-4 text-xs font-bold shrink-0 rounded-xl"
            >
              <RiCheckLine className="w-3.5 h-3.5" />
              <span>Connect</span>
            </Button>
          </div>
        </div>
      )}

      <SettingsRow
        icon={RiDeleteBinLine}
        iconClass="bg-tint-red text-tint-red"
        title="Clear all classes"
        subtitle="Synced and manual classes"
        danger
        control={
          <Button
            onClick={() => setConfirmClear(true)}
            disabled={clearAll.isPending}
            variant="outline"
            size="sm"
            className="shrink-0 gap-1.5 text-xs font-bold rounded-full border-tint-red text-tint-red hover:bg-tint-red cursor-pointer"
          >
            <span>Clear</span>
          </Button>
        }
      />

      {syncStatus === 'error' && syncError && (
        <div className={SETTINGS_BLOCK_CLASS}>
          <Alert variant="destructive" className="bg-red-500/10 border-tint-red text-tint-red">
            <RiErrorWarningLine className="h-5 w-5 !text-tint-red" />
            <AlertTitle className="font-bold text-tint-red tracking-wide text-sm">Sync failed</AlertTitle>
            <AlertDescription className="space-y-3 mt-2">
              <div className="leading-relaxed opacity-90 text-xs">{syncError}</div>
              <div className="pt-3 border-t border-red-500/20 text-xs text-tint-red/80 leading-snug">
                The automatic background sync failed. Please check your network connection, or if your URL has expired, generate a new one from Neptun.
              </div>
            </AlertDescription>
          </Alert>
        </div>
      )}

      <ConfirmDialog
        open={confirmClear}
        onClose={() => setConfirmClear(false)}
        onConfirm={handleClearAll}
        title="Clear all classes?"
        description="This deletes every class in your schedule, including the ones synced from Neptun, and disconnects the Neptun link. Work shifts and tasks stay. This can't be undone."
        confirmText="Clear all"
        cancelText="Cancel"
        isLoading={clearAll.isPending}
      />
    </SettingsSection>
  );
}
