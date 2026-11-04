import { useState } from 'react';
import { AuthCard } from '@/features/auth/login-form';
import { BackgroundGlow } from '@/components/ui/background-glow';
import { Reveal } from '@/components/ui/reveal';

interface LoginPageProps {
  initialMode?: 'login' | 'signup';
}

export function LoginPage({ initialMode = 'login' }: LoginPageProps) {
  const [mode, setMode] = useState<'login' | 'signup'>(initialMode);

  return (
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden">
      <BackgroundGlow />

      {/* The sign-in card enters like every other page's cards — held until
          the boot splash clears, so a cold start to /login still shows it. */}
      <Reveal index={0} className="relative z-10 w-full max-w-md my-auto">
        <AuthCard mode={mode} onSwitchMode={setMode} />
      </Reveal>
    </div>
  );
}
