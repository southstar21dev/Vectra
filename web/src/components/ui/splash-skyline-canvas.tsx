import { useEffect, useRef } from 'react';
import { STILL_FRAME_ELAPSED, createSplashScene, sceneTransform } from './splash-scene';

/** "r,g,b" of a CSS colour, resolved through the element's computed style. */
function resolveRgb(el: HTMLElement, cssColor: string): string {
  const probe = document.createElement('span');
  probe.style.color = cssColor;
  el.appendChild(probe);
  const rgb = getComputedStyle(probe).color.match(/[\d.]+/g)?.slice(0, 3).join(',') ?? '0,0,0';
  probe.remove();
  return rgb;
}

/**
 * Full-screen canvas for the boot splash: the dotted orb over the Budapest
 * skyline (see splash-scene.ts). Plays the sunrise exit once `exiting` turns
 * true. With `still`, draws a single lit frame and never animates.
 */
export function SplashSkylineCanvas({ exiting, still }: { exiting: boolean; still: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const exitAtRef = useRef<number | null>(null);

  useEffect(() => {
    if (exiting && exitAtRef.current == null) exitAtRef.current = performance.now();
  }, [exiting]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx || typeof OffscreenCanvas === 'undefined') return;

    const draw = createSplashScene();
    // Theme colours, re-read whenever <html>'s theme changes: the signed-in
    // profile can switch light/dark while the splash is still up, and the
    // canvas must repaint in the backdrop's new colour, not the boot one.
    let fg = '0,0,0', bg = '0,0,0', gain = 1;
    const readColors = () => {
      fg = resolveRgb(canvas, 'var(--text-primary)');
      bg = resolveRgb(canvas, 'var(--surface-primary)');
      const [r, g, b] = bg.split(',').map(Number);
      gain = 0.299 * r + 0.587 * g + 0.114 * b > 140 ? 1.7 : 1; // light theme
    };
    readColors();
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    let mapping = sceneTransform(window.innerWidth, window.innerHeight);

    const resize = () => {
      const w = window.innerWidth, h = window.innerHeight;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      mapping = sceneTransform(w, h);
      const { scale, offsetX, offsetY } = mapping;
      ctx.setTransform(dpr * scale, 0, 0, dpr * scale, dpr * offsetX, dpr * offsetY);
    };

    const start = performance.now();
    const frame = (now: number) => {
      const exitAt = exitAtRef.current;
      draw(ctx, {
        elapsed: still ? STILL_FRAME_ELAPSED : now - start,
        exitElapsed: still || exitAt == null ? null : now - exitAt,
        time: still ? 0 : now,
        view: mapping.view,
        fg,
        bg,
        gain,
      });
    };

    resize();
    const themeObserver = new MutationObserver(() => {
      readColors();
      if (still) frame(performance.now());
    });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });

    if (still) {
      frame(start);
      const onResize = () => { resize(); frame(performance.now()); };
      window.addEventListener('resize', onResize);
      return () => {
        themeObserver.disconnect();
        window.removeEventListener('resize', onResize);
      };
    }

    let raf = requestAnimationFrame(function loop(now) {
      frame(now);
      raf = requestAnimationFrame(loop);
    });
    window.addEventListener('resize', resize);
    return () => {
      themeObserver.disconnect();
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
    };
  }, [still]);

  return <canvas ref={canvasRef} aria-hidden className="absolute inset-0 h-full w-full" />;
}
