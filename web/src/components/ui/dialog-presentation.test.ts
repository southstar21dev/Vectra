import { describe, it, expect } from 'vitest';
import { resolveDialogPresentation } from './dialog-presentation';

const base = {
  presentation: 'auto' as const,
  compact: false,
  edgeToEdge: false,
  reduceMotion: false,
};

describe('resolveDialogPresentation', () => {
  it('centers auto dialogs on desktop', () => {
    const result = resolveDialogPresentation(base);

    expect(result.isFullscreen).toBe(false);
    expect(result.panelStyle).toBeUndefined();
    expect(result.closeButtonStyle).toBeUndefined();
    expect(result.motion.initial).toEqual({ opacity: 0, scale: 0.9 });
  });

  it('keeps explicit dialog presentation centered even on compact viewports', () => {
    const result = resolveDialogPresentation({
      ...base,
      presentation: 'dialog',
      compact: true,
    });

    expect(result.isFullscreen).toBe(false);
    expect(result.panelStyle).toBeUndefined();
    expect(result.motion.initial).toEqual({ opacity: 0, scale: 0.9 });
  });

  it('makes auto dialogs fixed full-screen panels on compact viewports', () => {
    const result = resolveDialogPresentation({ ...base, compact: true });

    expect(result.isFullscreen).toBe(true);
    expect(result.panelStyle).toMatchObject({
      position: 'fixed',
      inset: 0,
      width: '100%',
      height: '100dvh',
      maxHeight: 'none',
      maxWidth: 'none',
      margin: 0,
      borderRadius: 'var(--screen-radius, 0px)',
      borderWidth: 0,
    });
    expect(result.panelStyle?.paddingTop).toBe(
      'max(1rem, var(--safe-area-top, env(safe-area-inset-top, 0px)))'
    );
    expect(result.panelStyle?.paddingBottom).toBe(
      'max(1rem, env(safe-area-inset-bottom, 0px))'
    );
    expect(result.closeButtonStyle?.top).toBe(
      'max(0.75rem, var(--safe-area-top, env(safe-area-inset-top, 0px)))'
    );
  });

  it('leaves edge-to-edge compact panels unpadded', () => {
    const result = resolveDialogPresentation({ ...base, compact: true, edgeToEdge: true });

    expect(result.isFullscreen).toBe(true);
    expect(result.panelStyle?.paddingTop).toBeUndefined();
    expect(result.panelStyle?.paddingBottom).toBeUndefined();
    expect(result.closeButtonStyle?.top).toBe(
      'max(0.75rem, var(--safe-area-top, env(safe-area-inset-top, 0px)))'
    );
  });

  it('expands compact dialogs from the tap origin', () => {
    const result = resolveDialogPresentation({ ...base, compact: true });

    expect(result.motion.initial).toEqual({ opacity: 0, scale: 0.96 });
    expect(result.motion.animate).toEqual({ opacity: 1, scale: 1 });
    expect(result.motion.exit).toEqual({ opacity: 0, scale: 0.98 });
    expect(result.motion.transition).toEqual({
      type: 'spring',
      stiffness: 420,
      damping: 38,
      mass: 0.9,
    });
  });

  it('uses a plain fade when reduced motion is requested in both modes', () => {
    const compact = resolveDialogPresentation({ ...base, compact: true, reduceMotion: true });
    const desktop = resolveDialogPresentation({ ...base, reduceMotion: true });

    for (const result of [compact, desktop]) {
      expect(result.motion.initial).toEqual({ opacity: 0 });
      expect(result.motion.animate).toEqual({ opacity: 1 });
      expect(result.motion.exit).toEqual({ opacity: 0 });
      expect(result.motion.transition).toEqual({ duration: 0.15 });
    }
  });
});
