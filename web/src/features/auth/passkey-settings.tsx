import { useId, useState } from 'react';
import { RiFingerprintLine, RiLoader4Line, RiAddLine, RiDeleteBinLine, RiLockLine, RiErrorWarningLine } from '@remixicon/react';
import { usePasskeys } from './use-passkeys';
import { PasskeyError, passkeyErrorMessage } from './passkeys';
import { SettingsRow } from '@/features/settings/settings-section';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * Asks for the current password before enrolling a passkey: the server
 * re-checks it, so a stolen session token alone cannot add one.
 */
function AddPasskeyDialog({
  open,
  onClose,
  register,
}: {
  open: boolean;
  onClose: () => void;
  register: (currentPassword: string) => Promise<unknown>;
}) {
  const ids = useId();
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const close = () => {
    if (busy) return;
    setPassword('');
    setError(null);
    onClose();
  };

  const submit = async () => {
    if (busy || !password) return;
    setBusy(true);
    setError(null);
    try {
      await register(password);
      setBusy(false);
      setPassword('');
      onClose();
    } catch (err) {
      setBusy(false);
      // A cancelled system prompt is not an error worth showing.
      if (err instanceof PasskeyError && err.cancelled) {
        close();
        return;
      }
      setError(err instanceof PasskeyError ? passkeyErrorMessage(err.code) : 'Biometric sign-in failed — please try again.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && close()}>
      <DialogContent className="max-w-md" presentation="dialog">
        <DialogHeader className="pt-2">
          <DialogTitle>Add a passkey</DialogTitle>
          <DialogDescription className="text-secondary mt-2">
            Confirm it's you with your current password, then follow your device's prompt.
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <label htmlFor={`${ids}-current`} className="block text-xs font-bold text-secondary">Current password</label>
          <Input
            id={`${ids}-current`}
            type="password"
            autoComplete="current-password"
            icon={RiLockLine}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="Your current password"
            disabled={busy}
            autoFocus
          />
          {error && (
            <div role="alert" className="p-3 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5">
              <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" aria-hidden="true" />
              <span>{error}</span>
            </div>
          )}
          <DialogFooter className="mt-4">
            <Button type="button" variant="ghost" size="sm" onClick={close} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" variant="default" size="sm" disabled={busy || !password} aria-busy={busy || undefined}>
              {busy ? <RiLoader4Line className="w-4 h-4 animate-spin" aria-hidden="true" /> : null}
              Continue
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

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
  const [adding, setAdding] = useState(false);

  return (
    <>
      <AddPasskeyDialog open={adding} onClose={() => setAdding(false)} register={register} />
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
              <Button variant="default" size="sm" onClick={() => setAdding(true)} disabled={registerPending} className="h-9 px-4 rounded-xl font-bold">
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
            <Button variant="default" size="sm" onClick={() => setAdding(true)} disabled={registerPending} className="h-9 px-4 rounded-xl font-bold">
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
