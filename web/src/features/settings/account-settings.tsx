import { useId, useState } from 'react';
import { RiUserLine, RiMailLine, RiLockLine, RiLoader4Line, RiErrorWarningLine, RiDeleteBinLine } from '@remixicon/react';
import { useAuth } from '@/features/auth/use-auth';
import { SETTINGS_BLOCK_CLASS, SettingsRow } from './settings-section';
import { cn } from '@/lib/utils';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog';
import { toast } from 'sonner';
import {
  isValidEmail,
  validatePassword,
  validateConfirmPassword,
  DELETE_ACCOUNT_CONFIRM_TEXT,
  deleteConfirmationMatches,
  functionsErrorMessage,
  FULL_NAME_MAX_LENGTH,
  MIN_PASSWORD_LENGTH,
  pocketbaseFieldErrorMessage,
} from './account-settings-logic';

const EDIT_BUTTON_CLASS = 'shrink-0 text-xs font-bold rounded-full cursor-pointer hover:bg-surface-secondary';

/** Small destructive alert block used under the forms. */
function FieldError({ id, message }: { id?: string; message: string | null }) {
  if (!message) return null;
  return (
    <div id={id} role="alert" className="p-3 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5">
      <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" aria-hidden="true" />
      <span>{message}</span>
    </div>
  );
}

/** aria-invalid / aria-describedby for an input tied to a FieldError. */
function invalidProps(invalid: boolean, errorId: string) {
  return invalid ? { 'aria-invalid': true, 'aria-describedby': errorId } : {};
}

/** Editable full name — saves via the existing profile upsert. */
function NameRow() {
  const { profile, updateProfile } = useAuth();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(profile?.full_name ?? '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const ids = useId();

  const startEdit = () => {
    setValue(profile?.full_name ?? '');
    setError(null);
    setEditing(true);
  };

  const save = async () => {
    const name = value.trim();
    if (!name) {
      setError('Name cannot be empty');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await updateProfile({ full_name: name });
      setEditing(false);
      toast('Name updated');
    } catch (err) {
      setError(pocketbaseFieldErrorMessage(err) ?? ((err as Error).message || 'Could not update name'));
    } finally {
      setSaving(false);
    }
  };

  return editing ? (
    <div className={cn(SETTINGS_BLOCK_CLASS, 'space-y-3')}>
      <label htmlFor={`${ids}-name`} className="block text-xs font-bold text-secondary">Full name</label>
      <Input
        id={`${ids}-name`}
        type="text"
        autoComplete="name"
        maxLength={FULL_NAME_MAX_LENGTH}
        {...invalidProps(!!error, `${ids}-error`)}
        icon={RiUserLine}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="Your name"
        autoFocus
      />
      <FieldError id={`${ids}-error`} message={error} />
      <div className="flex items-center justify-end gap-2">
        <Button variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={saving}>
          Cancel
        </Button>
        <Button
          variant="primary"
          size="sm"
          onClick={save}
          disabled={saving}
          className="flex items-center gap-1.5"
        >
          {saving ? <RiLoader4Line className="w-3.5 h-3.5 animate-spin" /> : 'Save'}
        </Button>
      </div>
    </div>
  ) : (
    <SettingsRow
      icon={RiUserLine}
      iconClass="bg-tint-blue border-tint-blue text-tint-blue"
      title={profile?.full_name || 'Not set'}
      subtitle="Full name"
      control={
        <Button variant="outline" size="sm" onClick={startEdit} className={EDIT_BUTTON_CLASS}>
          Edit
        </Button>
      }
    />
  );
}

/** Email change — verifies the current password, then switches instantly
 *  (or shows a pending-confirmation note when the server defers it). */
function EmailRow() {
  const { user, updateEmail } = useAuth();
  const [editing, setEditing] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const ids = useId();

  const startEdit = () => {
    setCurrentPassword('');
    setNewEmail(user?.email ?? '');
    setError(null);
    setEditing(true);
  };

  const save = async () => {
    const email = newEmail.trim();
    if (!currentPassword) {
      setError('Enter your current password');
      return;
    }
    if (!isValidEmail(email)) {
      setError('Enter a valid email address');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      const { pendingConfirmation } = await updateEmail({ currentPassword, newEmail: email });
      if (pendingConfirmation) {
        toast('Confirmation link sent to your new email');
      } else {
        toast('Email address updated');
      }
      setEditing(false);
      setCurrentPassword('');
      setNewEmail('');
    } catch (err) {
      setError(pocketbaseFieldErrorMessage(err) ?? ((err as Error).message || 'Could not update email'));
    } finally {
      setSaving(false);
    }
  };

  return editing ? (
    <div className={cn(SETTINGS_BLOCK_CLASS, 'space-y-3')}>
      <label htmlFor={`${ids}-email`} className="block text-xs font-bold text-secondary">New email address</label>
      <Input
        id={`${ids}-email`}
        type="email"
        autoComplete="email"
        {...invalidProps(!!error && error !== 'Enter your current password', `${ids}-error`)}
        icon={RiMailLine}
        value={newEmail}
        onChange={(e) => setNewEmail(e.target.value)}
        placeholder="name@example.com"
        autoFocus
      />
      <label htmlFor={`${ids}-current`} className="block text-xs font-bold text-secondary">Current password</label>
      <Input
        id={`${ids}-current`}
        type="password"
        autoComplete="current-password"
        {...invalidProps(error === 'Enter your current password', `${ids}-error`)}
        icon={RiLockLine}
        value={currentPassword}
        onChange={(e) => setCurrentPassword(e.target.value)}
        placeholder="Your current password"
      />
      <FieldError id={`${ids}-error`} message={error} />
      <div className="flex items-center justify-end gap-2">
        <Button variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={saving}>
          Cancel
        </Button>
        <Button
          variant="primary"
          size="sm"
          onClick={save}
          disabled={saving}
          className="flex items-center gap-1.5"
        >
          {saving ? <RiLoader4Line className="w-3.5 h-3.5 animate-spin" /> : 'Save'}
        </Button>
      </div>
    </div>
  ) : (
    <SettingsRow
      icon={RiMailLine}
      iconClass="bg-tint-purple border-tint-purple text-tint-purple"
      title={user?.email || 'N/A'}
      subtitle="Email address"
      control={
        <Button variant="outline" size="sm" onClick={startEdit} className={EDIT_BUTTON_CLASS}>
          Change
        </Button>
      }
    />
  );
}

/** Password change — verifies the current password, then updates. */
function PasswordRow() {
  const { updatePassword } = useAuth();
  const [editing, setEditing] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [errorField, setErrorField] = useState<'current' | 'new' | 'confirm' | null>(null);
  const ids = useId();

  const startEdit = () => {
    setErrorField(null);
    setCurrentPassword('');
    setNewPassword('');
    setConfirmPassword('');
    setError(null);
    setEditing(true);
  };

  const save = async () => {
    if (!currentPassword) {
      setError('Enter your current password');
      setErrorField('current');
      return;
    }
    const pwError = validatePassword(newPassword);
    if (pwError) {
      setError(pwError);
      setErrorField('new');
      return;
    }
    const cfError = validateConfirmPassword(newPassword, confirmPassword);
    if (cfError) {
      setError(cfError);
      setErrorField('confirm');
      return;
    }
    setError(null);
    setErrorField(null);
    setSaving(true);
    try {
      await updatePassword({ currentPassword, newPassword });
      toast('Password updated');
      setEditing(false);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      const message = pocketbaseFieldErrorMessage(err) ?? ((err as Error).message || 'Could not update password');
      setError(message);
      setErrorField(message === 'Current password is incorrect' ? 'current' : null);
    } finally {
      setSaving(false);
    }
  };

  return editing ? (
    <div className={cn(SETTINGS_BLOCK_CLASS, 'space-y-3')}>
      <label htmlFor={`${ids}-current`} className="block text-xs font-bold text-secondary">Current password</label>
      <Input
        id={`${ids}-current`}
        type="password"
        autoComplete="current-password"
        {...invalidProps(errorField === 'current', `${ids}-error`)}
        icon={RiLockLine}
        value={currentPassword}
        onChange={(e) => setCurrentPassword(e.target.value)}
        placeholder="Your current password"
        autoFocus
      />
      <label htmlFor={`${ids}-new`} className="block text-xs font-bold text-secondary">New password</label>
      <Input
        id={`${ids}-new`}
        type="password"
        autoComplete="new-password"
        {...invalidProps(errorField === 'new', `${ids}-error`)}
        icon={RiLockLine}
        value={newPassword}
        onChange={(e) => setNewPassword(e.target.value)}
        placeholder={`At least ${MIN_PASSWORD_LENGTH} characters`}
      />
      <label htmlFor={`${ids}-confirm`} className="block text-xs font-bold text-secondary">Confirm new password</label>
      <Input
        id={`${ids}-confirm`}
        type="password"
        autoComplete="new-password"
        {...invalidProps(errorField === 'confirm', `${ids}-error`)}
        icon={RiLockLine}
        value={confirmPassword}
        onChange={(e) => setConfirmPassword(e.target.value)}
        placeholder="Repeat the new password"
      />
      <FieldError id={`${ids}-error`} message={error} />
      <div className="flex items-center justify-end gap-2">
        <Button variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={saving}>
          Cancel
        </Button>
        <Button
          variant="primary"
          size="sm"
          onClick={save}
          disabled={saving}
          className="flex items-center gap-1.5"
        >
          {saving ? <RiLoader4Line className="w-3.5 h-3.5 animate-spin" /> : 'Save'}
        </Button>
      </div>
    </div>
  ) : (
    <SettingsRow
      icon={RiLockLine}
      iconClass="bg-tint-sky border-tint-sky text-tint-sky"
      title="Password"
      subtitle="Change your password"
      control={
        <Button variant="outline" size="sm" onClick={startEdit} className={EDIT_BUTTON_CLASS}>
          Change
        </Button>
      }
    />
  );
}

/** The editable profile rows (name, email, password) shown in User Profile. */
export function AccountSettings() {
  return (
    <>
      <NameRow />
      <EmailRow />
      <PasswordRow />
    </>
  );
}

/** Destructive delete-account control: opens a type-to-confirm dialog that
 *  permanently deletes the account via the `POST /api/vectra/account/delete`
 *  route. */
export function DeleteAccountButton() {
  const { deleteAccount } = useAuth();
  const [open, setOpen] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const close = () => {
    if (deleting) return;
    setOpen(false);
    setConfirmText('');
    setError(null);
  };

  const handleDelete = async () => {
    setDeleting(true);
    setError(null);
    try {
      await deleteAccount();
      toast('Account deleted');
      window.location.href = '/login';
    } catch (err) {
      setError((await functionsErrorMessage(err)) ?? 'Could not delete account');
      setDeleting(false);
    }
  };

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setConfirmText('');
          setError(null);
          setOpen(true);
        }}
        className="shrink-0 text-xs font-bold rounded-full flex items-center gap-1.5 border-tint-red text-tint-red hover:bg-tint-red"
      >
        <RiDeleteBinLine className="w-3.5 h-3.5" />
        Delete
      </Button>

      <Dialog open={open} onOpenChange={(o) => (o ? undefined : close())}>
        <DialogContent className="max-w-md" presentation="dialog">
          <DialogHeader className="pt-2">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-xl bg-tint-red border border-tint-red flex items-center justify-center text-tint-red shrink-0">
                <RiDeleteBinLine className="w-4 h-4" />
              </div>
              <DialogTitle>Delete account?</DialogTitle>
            </div>
            <DialogDescription className="text-secondary mt-2">
              This permanently deletes your account and all of your data — profile,
              schedule, transit and more. This cannot
              be undone.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <label className="block text-xs font-bold text-secondary">
              Type <span className="text-tint-red">{DELETE_ACCOUNT_CONFIRM_TEXT}</span> to confirm
            </label>
            <Input
              type="text"
              value={confirmText}
              onChange={(e) => setConfirmText(e.target.value)}
              placeholder={DELETE_ACCOUNT_CONFIRM_TEXT}
              disabled={deleting}
            />
            <FieldError message={error} />
          </div>

          <DialogFooter className="mt-4">
            <Button variant="ghost" size="sm" onClick={close} disabled={deleting}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              size="sm"
              onClick={handleDelete}
              disabled={deleting || !deleteConfirmationMatches(confirmText)}
              className="flex items-center gap-1.5"
            >
              {deleting ? <RiLoader4Line className="w-3.5 h-3.5 animate-spin" /> : <RiDeleteBinLine className="w-3.5 h-3.5" />}
              Delete my account
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}