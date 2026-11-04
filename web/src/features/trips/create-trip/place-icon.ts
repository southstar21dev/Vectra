import { RiGraduationCapLine, RiHistoryLine, RiHome5Line, type RemixiconComponentType } from '@remixicon/react';

/** The icon for a remembered place: home and uni get their own, the rest a clock. */
export function placeIcon(tag?: string): RemixiconComponentType {
  if (tag === 'Home') return RiHome5Line;
  if (tag === 'Uni') return RiGraduationCapLine;
  return RiHistoryLine;
}
