import { useState } from 'react';
import { RiShareBoxLine, RiAddBoxLine, RiDownloadLine, RiCheckLine, RiMoreLine, RiMore2Line, RiArrowDownSLine } from '@remixicon/react';
import { usePwaInstall } from '@/hooks/use-pwa-install';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { BackgroundGlow } from '@/components/ui/background-glow';

function detectIos(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    (/iPhone|iPad|iPod/i.test(navigator.userAgent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))
  );
}

export function PwaMobileGate() {
  const { isInstallable, promptInstall } = usePwaInstall();
  const [isIos] = useState(detectIos);

  return (
    <div
      className="fixed inset-0 z-[200] min-h-[100dvh] w-full flex flex-col items-center justify-between px-5 sm:px-8 overflow-y-auto bg-surface-primary text-primary select-none transition-colors"
      style={{
        paddingTop: 'calc(max(env(safe-area-inset-top, 0px), var(--safe-area-top, 0px), 16px) + 8px)',
        paddingBottom: 'calc(max(env(safe-area-inset-bottom, 0px), 16px) + 8px)',
      }}
    >
      <BackgroundGlow />

      {/* Center Hero Instruction Card */}
      <div className="relative z-10 w-full max-w-md md:max-w-lg my-auto py-4 sm:py-6 space-y-6 text-center">
        {/* Real App Icon - Themed Rounded Squircle */}
        <div className="mx-auto w-24 h-24 sm:w-28 sm:h-28 rounded-[24px] sm:rounded-[28px] bg-surface-secondary border border-subtle p-4 flex items-center justify-center shadow-2xl shadow-blue-500/15">
          <img
            src="/favicon.svg"
            alt="Vectra"
            className="w-16 h-16 sm:w-20 sm:h-20 object-contain drop-shadow-md"
          />
        </div>

        <div className="space-y-1.5">
          <h1 className="text-2xl sm:text-3xl font-black tracking-tight text-primary">
            Install Vectra on Home Screen
          </h1>
          <p className="text-xs sm:text-sm text-secondary">
            Add to your home screen for the full app experience
          </p>
        </div>

        {/* Step-by-Step Card */}
        <Card className="p-5 sm:p-7 bg-surface-secondary/95 border-subtle text-left space-y-4 sm:space-y-5 shadow-xl w-full">
          {isIos ? (
            <>
              {/* Step 1 */}
              <div className="flex items-center gap-3.5">
                <div className="w-8 h-8 rounded-full bg-surface-tertiary border border-subtle text-primary font-bold text-xs flex items-center justify-center shrink-0">
                  1
                </div>
                <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                  <span>Tap</span>
                  <span className="h-7 px-2 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center justify-center text-[11px] sm:text-xs">
                    <RiMoreLine className="w-4 h-4 text-secondary" />
                  </span>
                  <span>then</span>
                  <span className="h-7 px-2.5 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center gap-1.5 text-[11px] sm:text-xs">
                    <RiShareBoxLine className="w-3.5 h-3.5 text-secondary" /> Share
                  </span>
                </div>
              </div>

              {/* Step 2 */}
              <div className="flex items-center gap-3.5">
                <div className="w-8 h-8 rounded-full bg-surface-tertiary border border-subtle text-primary font-bold text-xs flex items-center justify-center shrink-0">
                  2
                </div>
                <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                  <span>Tap</span>
                  <span className="h-7 px-2.5 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center gap-1 text-[11px] sm:text-xs">
                    <RiArrowDownSLine className="w-3.5 h-3.5 text-secondary" /> View More
                  </span>
                </div>
              </div>

              {/* Step 3 */}
              <div className="flex items-center gap-3.5">
                <div className="w-8 h-8 rounded-full bg-surface-tertiary border border-subtle text-primary font-bold text-xs flex items-center justify-center shrink-0">
                  3
                </div>
                <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                  <span>Tap</span>
                  <span className="h-7 px-2.5 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center gap-1.5 text-[11px] sm:text-xs">
                    <RiAddBoxLine className="w-3.5 h-3.5 text-secondary" /> Add to Home Screen
                  </span>
                </div>
              </div>

              {/* Step 4: Finished */}
              <div className="flex items-center gap-3.5">
                <div className="w-8 h-8 rounded-full bg-emerald-500/15 border border-emerald-500/30 text-emerald-600 dark:text-emerald-400 font-bold text-xs flex items-center justify-center shrink-0">
                  <RiCheckLine className="w-4 h-4" />
                </div>
                <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                  <span>Open</span>
                  <strong className="text-primary font-bold">Vectra</strong>
                  <span>from your home screen.</span>
                </div>
              </div>
            </>
          ) : (
            <>
              {isInstallable ? (
                <div className="space-y-3 pt-1">
                  <Button
                    variant="blue"
                    size="lg"
                    onClick={promptInstall}
                    className="w-full h-12 rounded-full font-bold text-sm sm:text-base text-white shadow-lg shadow-blue-500/25 flex items-center justify-center gap-2"
                  >
                    <RiDownloadLine className="w-4 h-4" />
                    <span>Install Vectra App</span>
                  </Button>
                  <p className="text-[11px] sm:text-xs text-center text-muted">
                    Tap to trigger instant home screen installation.
                  </p>
                </div>
              ) : (
                <>
                  {/* Step 1 */}
                  <div className="flex items-center gap-3.5">
                    <div className="w-8 h-8 rounded-full bg-surface-tertiary border border-subtle text-primary font-bold text-xs flex items-center justify-center shrink-0">
                      1
                    </div>
                    <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                      <span>Tap the</span>
                      <span className="h-7 px-2.5 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center gap-1.5 text-[11px] sm:text-xs">
                        <RiMore2Line className="w-3.5 h-3.5 text-secondary" /> (⋮)
                      </span>
                      <span>menu in Chrome.</span>
                    </div>
                  </div>

                  {/* Step 2 */}
                  <div className="flex items-center gap-3.5">
                    <div className="w-8 h-8 rounded-full bg-surface-tertiary border border-subtle text-primary font-bold text-xs flex items-center justify-center shrink-0">
                      2
                    </div>
                    <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                      <span>Select</span>
                      <span className="h-7 px-2.5 rounded-lg bg-surface-tertiary border border-subtle text-primary font-semibold inline-flex items-center gap-1.5 text-[11px] sm:text-xs">
                        <RiDownloadLine className="w-3.5 h-3.5 text-secondary" /> Install App
                      </span>
                      <span>or</span>
                      <strong className="text-primary font-semibold">Add to Home screen</strong>.
                    </div>
                  </div>

                  {/* Step 3 */}
                  <div className="flex items-center gap-3.5">
                    <div className="w-8 h-8 rounded-full bg-emerald-500/15 border border-emerald-500/30 text-emerald-600 dark:text-emerald-400 font-bold text-xs flex items-center justify-center shrink-0">
                      <RiCheckLine className="w-4 h-4" />
                    </div>
                    <div className="flex items-center flex-wrap gap-1.5 text-xs sm:text-sm text-secondary min-h-[32px]">
                      <span>Open</span>
                      <strong className="text-primary font-bold">Vectra</strong>
                      <span>from your home screen.</span>
                    </div>
                  </div>
                </>
              )}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}
