import {
  RiDashboardLine,
  RiDashboardFill,
  RiCalendar2Line,
  RiCalendar2Fill,
  RiTrainLine,
  RiTrainFill,
  RiSettings3Line,
  RiSettings3Fill,
  type RemixiconComponentType } from '@remixicon/react';

export interface NavItem {
  href: string;
  label: string;
  icon: RemixiconComponentType;
  /** Filled variant shown while the item is the current route. */
  activeIcon: RemixiconComponentType;
}

export const desktopNavItems: NavItem[] = [
  { href: '/', label: 'Dashboard', icon: RiDashboardLine, activeIcon: RiDashboardFill },
  { href: '/schedule', label: 'Schedule', icon: RiCalendar2Line, activeIcon: RiCalendar2Fill },
  { href: '/transit', label: 'Transit', icon: RiTrainLine, activeIcon: RiTrainFill },
  { href: '/settings', label: 'Settings', icon: RiSettings3Line, activeIcon: RiSettings3Fill },
];

export const mobilePrimaryNavItems: NavItem[] = [
  { href: '/', label: 'Dashboard', icon: RiDashboardLine, activeIcon: RiDashboardFill },
  { href: '/schedule', label: 'Schedule', icon: RiCalendar2Line, activeIcon: RiCalendar2Fill },
  { href: '/transit', label: 'Transit', icon: RiTrainLine, activeIcon: RiTrainFill },
  { href: '/settings', label: 'Settings', icon: RiSettings3Line, activeIcon: RiSettings3Fill },
];
