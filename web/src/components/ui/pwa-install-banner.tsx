import { useState } from 'react';
import { usePwaInstall } from '@/hooks/use-pwa-install';
import { useAuth } from '@/features/auth/use-auth';
import { RiDownloadLine, RiSmartphoneLine, RiCloseLine } from '@remixicon/react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

export function PwaInstallBanner() {
  const { isInstallable, isInstalled, promptInstall } = usePwaInstall();
  const { isAuthenticated } = useAuth();
  const [dismissed, setDismissed] = useState(false);

  const isAuthPage = typeof window !== 'undefined' && 
    (window.location.pathname === '/login' || window.location.pathname === '/signup');

  // ONLY show after user is logged in, and NOT on auth pages
  if (!isAuthenticated || isAuthPage || !isInstallable || isInstalled || dismissed) {
    return null;
  }

  return (
    <div className="fixed bottom-24 sm:bottom-6 left-1/2 -translate-x-1/2 z-40 w-[calc(100%-2rem)] max-w-md xl:hidden">
      <Card className="p-4 relative overflow-hidden">
        <div className="relative flex items-center gap-3">
          <div className="flex-shrink-0 w-10 h-10 rounded-xl bg-tint-blue border border-tint-blue flex items-center justify-center text-tint-blue">
            <RiSmartphoneLine className="w-5 h-5 text-tint-blue" />
          </div>

          <div className="flex-1 min-w-0">
            <h4 className="text-sm font-bold text-primary">Install Vectra App</h4>
            <p className="text-[11px] text-secondary truncate">Add to home screen for offline access</p>
          </div>

          <div className="flex items-center gap-1.5">
            <Button
              onClick={promptInstall}
              variant="default"
              size="sm"
            >
              <RiDownloadLine className="w-3.5 h-3.5" />
              <span>Install</span>
            </Button>
            <Button
              onClick={() => setDismissed(true)}
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              aria-label="Dismiss banner"
            >
              <RiCloseLine className="w-4 h-4" />
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
