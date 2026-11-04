import { useEffect, useRef, useState, type ReactNode } from 'react';

interface DeferredMountProps {
  children: ReactNode;
  /** Rendered until the real content mounts. */
  placeholder?: ReactNode;
  /** Mount this far ahead of the viewport so loading finishes before it
   *  scrolls in. */
  rootMargin?: string;
}

/**
 * Mounts its children only once the wrapper scrolls near the viewport, keeping
 * heavy widgets (maps, charts) out of cold start when they sit below the fold.
 * Falls back to mounting immediately where IntersectionObserver is unavailable.
 */
export function DeferredMount({ children, placeholder = null, rootMargin = '400px' }: DeferredMountProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(false);

  useEffect(() => {
    if (active) return;
    const el = containerRef.current;
    if (!el || typeof IntersectionObserver === 'undefined') {
      setActive(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setActive(true);
          observer.disconnect();
        }
      },
      { rootMargin }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [active, rootMargin]);

  return <div ref={containerRef}>{active ? children : placeholder}</div>;
}
