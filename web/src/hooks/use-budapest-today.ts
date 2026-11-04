import { nowInBudapest } from '@/lib/date-utils';
import { useNow } from './use-now';

/**
 * Today's Budapest date (YYYY-MM-DD), re-evaluated every minute so screens
 * left open past midnight roll over to the new day. The string only changes
 * once a day, so memos keyed on it stay stable in between.
 */
export function useBudapestToday(): string {
  const now = useNow(60_000);
  return nowInBudapest(new Date(now)).ymd;
}
