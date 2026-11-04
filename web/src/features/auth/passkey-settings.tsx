import { RiFingerprintLine, RiLoader4Line, RiAddLine, RiDeleteBinLine } from '@remixicon/react';
import { usePasskeys } from './use-passkeys';
import { SettingsRow } from '@/features/settings/settings-section';
import { Button } from '@/components/ui/button';

/** Passkey rows for the Account group: one per registered passkey plus an add
 *  row, or a single set-up row. */
export function PasskeySettings() {
  const {
    isSupported,
    passkeys,
    passkeysLoading,
    passkeysError,
    refetchPasskeys,
    register,
    registerPending,
    deletePasskey,
    deletePending,
  } = usePasskeys();

  return (
    <>
      {isSupported === false ? (
        <SettingsRow
          icon={RiFingerprintLine}
          iconClass="bg-cyan-500/15 border-cyan-500/30 text-cyan-400"
          title="Passkey sign-in"
          subtitle="Not supported on this device (needs Android 9+ or a modern browser on HTTPS)"
        />
      ) : passkeysLoading ? (
        <SettingsRow
          icon={RiFingerprintLine}
          iconClass="bg-cyan-500/15 border-cyan-500/30 text-cyan-400"
          title="Passkey sign-in"
          subtitle="Checking your passkeys…"
        />
      ) : passkeysError ? (
        // A failed list must not read as "no passkeys": that would invite a
        // duplicate registration of a passkey the account already has.
        <SettingsRow
          icon={RiFingerprintLine}
          iconClass="bg-cyan-500/15 border-cyan-500/30 text-cyan-400"
          title="Passkey sign-in"
          subtitle="Couldn't load your passkeys"
          control={
            <Button variant="outline" size="sm" onClick={() => void refetchPasskeys()} className="h-9 px-4 rounded-full text-xs font-bold">
              Retry
            </Button>
          }
        />
      ) : passkeys && passkeys.length > 0 ? (
        <>
          {passkeys.map((p) => (
            <SettingsRow
              key={p.id}
              icon={RiFingerprintLine}
              iconClass="bg-cyan-500/15 border-cyan-500/30 text-cyan-400"
              title={p.friendlyName || 'Passkey'}
              subtitle={`Added ${new Date(p.createdAt).toLocaleDateString()}`}
              control={
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Remove ${p.friendlyName || 'passkey'}`}
                  onClick={() => void deletePasskey(p.id)}
                  disabled={deletePending}
                  className="rounded-xl text-muted hover:text-tint-red hover:bg-red-500/10"
                >
                  {deletePending ? <RiLoader4Line className="w-4 h-4 animate-spin" /> : <RiDeleteBinLine className="w-4 h-4" />}
                </Button>
              }
            />
          ))}
          <SettingsRow
            icon={RiAddLine}
            iconClass="bg-surface-overlay border-subtle text-secondary"
            title="Add another passkey"
            subtitle="Register this device too"
            control={
              <Button variant="default" size="sm" onClick={() => void register()} disabled={registerPending} className="h-9 px-4 rounded-xl font-bold">
                {registerPending ? <RiLoader4Line className="w-3.5 h-3.5 animate-spin" /> : <RiAddLine className="w-3.5 h-3.5" />}
                <span>Add</span>
              </Button>
            }
          />
        </>
      ) : (
        <SettingsRow
          icon={RiFingerprintLine}
          iconClass="bg-cyan-500/15 border-cyan-500/30 text-cyan-400"
          title="Passkey sign-in"
          subtitle="Use your fingerprint or face instead of a password"
          control={
            <Button variant="default" size="sm" onClick={() => void register()} disabled={registerPending} className="h-9 px-4 rounded-xl font-bold">
              {registerPending ? (
                <RiLoader4Line className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <RiFingerprintLine className="w-3.5 h-3.5" />
              )}
              <span>Set up</span>
            </Button>
          }
        />
      )}
    </>
  );
}
