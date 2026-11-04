import { useAppearance } from '@/hooks/use-appearance';

/** App-wide side-effect only: keeps the DOM attributes in sync with prefs. */
export function AppearanceManager() {
  useAppearance();
  return null;
}
