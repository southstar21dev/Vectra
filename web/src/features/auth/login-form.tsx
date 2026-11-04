import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { EASE_OUT } from '@/components/ui/motion-tokens';
import { useAuth } from './use-auth';
import { usePasskeys } from './use-passkeys';
import { passkeyErrorMessage } from './passkeys';
import { RiEyeLine, RiEyeOffLine, RiLoader4Line, RiMailLine, RiLockLine, RiUserLine, RiErrorWarningLine, RiFingerprintLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Toggle } from '@/components/ui/toggle';
import { toast } from 'sonner';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  FULL_NAME_MAX_LENGTH,
  MIN_PASSWORD_LENGTH,
  loginErrorMessage,
  pocketbaseErrorField,
  pocketbaseFieldErrorMessage,
} from '@/features/settings/account-settings-logic';

interface AuthCardProps {
  mode: 'login' | 'signup';
  onSwitchMode: (mode: 'login' | 'signup') => void;
}

export function AuthCard({ mode, onSwitchMode }: AuthCardProps) {
  // The page wraps this card in `Reveal`, so its entrance lives there: a CSS
  // scale-in here too would play twice, and behind the boot splash on a cold
  // start (which is every signed-out launch).
  return (
    <div className="w-full max-w-md mx-auto">
      {/* Brand logo */}
      <div className="flex justify-center mb-0">
        <img
          src="/favicon.svg"
          alt="Vectra"
          className="w-32 h-32 sm:w-36 sm:h-36 object-contain drop-shadow-[0_0_36px_rgba(168,85,247,0.45)]"
        />
      </div>

      {/* Container Panel */}
      <motion.div
        layout
        transition={{ duration: 0.28, ease: EASE_OUT }}
        className="card-candy wash-purple bg-surface-secondary rounded-3xl px-6 pt-3 pb-6 sm:px-8 sm:pt-4 sm:pb-8 relative overflow-hidden"
      >
        {/* Standard shadcn Tabs */}
        <Tabs value={mode} onValueChange={(val) => onSwitchMode(val as 'login' | 'signup')} className="mb-6">
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="login">Sign in</TabsTrigger>
            <TabsTrigger value="signup">Create account</TabsTrigger>
          </TabsList>
        </Tabs>

        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={mode}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            {mode === 'login' ? (
              <LoginForm />
            ) : (
              <SignupForm />
            )}
          </motion.div>
        </AnimatePresence>
      </motion.div>
    </div>
  );
}

export function LoginForm() {
  const { login, loginError, loginPending } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  // One-shot hint when the session watchdog bounced us here after a dead
  // refresh token — explains why the user is on the login page again.
  const [sessionExpired] = useState(() => {
    try {
      const v = sessionStorage.getItem('vectra_session_expired');
      sessionStorage.removeItem('vectra_session_expired');
      return v === '1';
    } catch {
      return false;
    }
  });

  const { isSupported, signIn, signInPending, signInError } = usePasskeys();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await login({ email, password });
      window.location.href = '/';
    } catch {
      // Error handled by loginError
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-5 animate-fade-in">
      {sessionExpired && (
        <div className="p-3.5 rounded-xl bg-amber-500/10 border border-tint-amber text-tint-amber text-xs flex items-center gap-2.5">
          <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-amber" />
          <span>Your session expired — sign in again to continue.</span>
        </div>
      )}

      {loginError && (
        <div id="login-error" role="alert" className="p-3.5 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5">
          <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" aria-hidden="true" />
          <span>{loginErrorMessage(loginError)}</span>
        </div>
      )}

      {isSupported && (
        <div className="space-y-2">
          <Button
            type="button"
            onClick={async () => {
              try {
                await signIn();
                window.location.href = '/';
              } catch {
                // handled via signInError
              }
            }}
            disabled={signInPending}
            variant="outline"
            className="w-full py-3 font-bold flex items-center justify-center gap-2"
          >
            {signInPending ? (
              <RiLoader4Line className="w-4 h-4 animate-spin" />
            ) : (
              <>
                <RiFingerprintLine className="w-4 h-4" />
                Continue with biometrics
              </>
            )}
          </Button>
          {signInError && !signInError.cancelled && (
            <div role="alert" className="p-3 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5">
              <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" />
              <span>{passkeyErrorMessage(signInError.code)}</span>
            </div>
          )}
        </div>
      )}

      {/* Email Input */}
      <div className="space-y-1.5">
        <label htmlFor="login-email" className="block text-xs font-bold text-secondary">
          Email address
        </label>
        <Input
          id="login-email"
          type="email"
          autoComplete="username"
          icon={RiMailLine}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@example.com"
          required
          aria-invalid={loginError ? true : undefined}
          aria-describedby={loginError ? 'login-error' : undefined}
        />
      </div>

      {/* Password Input */}
      <div className="space-y-1.5">
        <label htmlFor="login-password" className="block text-xs font-bold text-secondary">
          Password
        </label>
        <Input
          id="login-password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          icon={RiLockLine}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="••••••••"
          required
          aria-invalid={loginError ? true : undefined}
          aria-describedby={loginError ? 'login-error' : undefined}
          rightElement={
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="p-1 text-secondary hover:text-primary transition-colors cursor-pointer"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
            >
              {showPassword ? <RiEyeOffLine className="w-4 h-4" /> : <RiEyeLine className="w-4 h-4" />}
            </button>
          }
        />
      </div>

      {/* Submit Button */}
      <Button
        type="submit"
        disabled={loginPending}
        variant="primary"
        className="w-full py-3 bg-gradient-to-r from-accent-primary to-accent-primary/80 hover:from-accent-primary/90 hover:to-accent-primary/70 text-white font-bold shadow-lg shadow-accent-primary/25 flex items-center justify-center gap-2 active:scale-[0.99] transition-[color,background-color,border-color,box-shadow,transform,opacity] cursor-pointer"
      >
        {loginPending ? <RiLoader4Line className="w-4 h-4 animate-spin" /> : 'Sign in'}
      </Button>
    </form>
  );
}

export function SignupForm() {
  const { signup, signupError, signupPending } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [enableBiometrics, setEnableBiometrics] = useState(false);
  const { isSupported, register } = usePasskeys();
  const signupErrorField = signupError ? pocketbaseErrorField(signupError) : null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await signup({ email, password, fullName });
      if (enableBiometrics && isSupported === true) {
        try {
          await register();
        } catch {
          toast('You can set this up later in Settings');
        }
      }
      window.location.href = '/';
    } catch {
      // Error handled by signupError
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-5 animate-fade-in">
      {signupError && (
        <div id="signup-error" role="alert" className="p-3.5 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5">
          <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" />
          {/* PocketBase's top-level message is a generic "Failed to create
              record."; the field error says what to fix. */}
          <span>{pocketbaseFieldErrorMessage(signupError) ?? signupError.message}</span>
        </div>
      )}

      {/* Full Name */}
      <div className="space-y-1.5">
        <label htmlFor="signup-name" className="block text-xs font-bold text-secondary">
          Nickname
        </label>
        <Input
          id="signup-name"
          type="text"
          autoComplete="nickname"
          maxLength={FULL_NAME_MAX_LENGTH}
          icon={RiUserLine}
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          placeholder="Your nickname"
          required
        />
      </div>

      {/* Email */}
      <div className="space-y-1.5">
        <label htmlFor="signup-email" className="block text-xs font-bold text-secondary">
          Email address
        </label>
        <Input
          id="signup-email"
          type="email"
          autoComplete="email"
          icon={RiMailLine}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@example.com"
          required
          aria-invalid={signupErrorField === 'email' ? true : undefined}
          aria-describedby={signupErrorField === 'email' ? 'signup-error' : undefined}
        />
      </div>

      {/* Password */}
      <div className="space-y-1.5">
        <label htmlFor="signup-password" className="block text-xs font-bold text-secondary">
          Password
        </label>
        <Input
          id="signup-password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          aria-invalid={signupErrorField === 'password' ? true : undefined}
          aria-describedby={signupErrorField === 'password' ? 'signup-error' : undefined}
          icon={RiLockLine}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder={`At least ${MIN_PASSWORD_LENGTH} characters`}
          required
          minLength={MIN_PASSWORD_LENGTH}
          rightElement={
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="p-1 text-secondary hover:text-primary transition-colors cursor-pointer"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
            >
              {showPassword ? <RiEyeOffLine className="w-4 h-4" /> : <RiEyeLine className="w-4 h-4" />}
            </button>
          }
        />
      </div>

      {isSupported && (
        <div className="flex items-center justify-between gap-4 bg-surface-overlay border border-subtle rounded-xl p-4">
          <div className="flex-1 min-w-0">
            <p className="text-xs font-bold text-secondary">
              Enable biometric sign-in
            </p>
            <p className="text-xs text-muted mt-1">
              Sign in with your fingerprint instead of a password
            </p>
          </div>
          <Toggle
            checked={enableBiometrics}
            onChange={setEnableBiometrics}
            aria-label="Enable biometric sign-in for faster logins"
          />
        </div>
      )}

      {/* Submit Button */}
      <Button
        type="submit"
        disabled={signupPending}
        variant="primary"
        className="w-full py-3 bg-gradient-to-r from-accent-primary to-accent-primary/80 hover:from-accent-primary/90 hover:to-accent-primary/70 text-white font-bold shadow-lg shadow-accent-primary/25 flex items-center justify-center gap-2 active:scale-[0.99] transition-[color,background-color,border-color,box-shadow,transform,opacity] cursor-pointer"
      >
        {signupPending ? <RiLoader4Line className="w-4 h-4 animate-spin" /> : 'Create account'}
      </Button>
    </form>
  );
}
