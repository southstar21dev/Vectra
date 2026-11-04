import { useState } from 'react';
import { RiAlertLine, RiErrorWarningLine, RiLoader4Line } from '@remixicon/react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  /** May return a promise: the dialog then stays open (buttons disabled)
   *  until it settles, closes on success, and shows the error on failure. */
  onConfirm: () => void | Promise<void>;
  title: string;
  description: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'destructive' | 'default';
  isLoading?: boolean;
  hideCancel?: boolean;
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmText = 'CONFIRM',
  cancelText = 'CANCEL',
  variant = 'destructive',
  isLoading = false,
  hideCancel = false,
}: ConfirmDialogProps) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const busy = isLoading || pending;

  const handleClose = () => {
    if (busy) return;
    setError(null);
    onClose();
  };

  const handleConfirm = async () => {
    setError(null);
    const result = onConfirm();
    if (!result || typeof (result as Promise<void>).then !== 'function') {
      onClose();
      return;
    }
    setPending(true);
    try {
      await result;
      setPending(false);
      onClose();
    } catch (err) {
      setPending(false);
      setError(err instanceof Error && err.message ? err.message : 'Something went wrong. Try again.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && handleClose()}>
      <DialogContent className="max-w-md" presentation="dialog">
        <DialogHeader className="pt-2">
          <div className="flex items-center gap-2.5">
            {variant === 'destructive' && (
              <div className="w-8 h-8 rounded-xl bg-tint-red border border-tint-red flex items-center justify-center text-tint-red shrink-0">
                <RiAlertLine className="w-4 h-4" />
              </div>
            )}
            <DialogTitle>{title}</DialogTitle>
          </div>
          <DialogDescription className="text-secondary mt-2">
            {description}
          </DialogDescription>
        </DialogHeader>

        {error && (
          <div
            role="alert"
            className="mt-3 p-3 rounded-xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2.5"
          >
            <RiErrorWarningLine className="w-4 h-4 shrink-0 text-tint-red" aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}

        <DialogFooter className="mt-4">
          {!hideCancel && (
            <Button variant="ghost" size="sm" onClick={handleClose} disabled={busy}>
              {cancelText}
            </Button>
          )}
          <Button
            variant={variant}
            size="sm"
            onClick={() => void handleConfirm()}
            disabled={busy}
            aria-busy={busy || undefined}
          >
            {pending ? <RiLoader4Line className="w-4 h-4 animate-spin" aria-hidden="true" /> : null}
            {confirmText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
