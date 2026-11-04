import { RiCheckLine, RiCloseLine, RiErrorWarningLine, RiCalendarCloseLine, type RemixiconComponentType } from '@remixicon/react';
import type { StatusTone } from '@/components/ui/status-label';
import type { AttendanceStatus } from './tracker-types';

export interface StatusButtonSpec {
  status: AttendanceStatus;
  label: string;
  icon: RemixiconComponentType;
  tone: StatusTone;
}

/**
 * The four states a session can be marked with, in display order. Tones sit
 * next to the status they describe so a tone change happens in one place.
 */
export const STATUS_BUTTONS: StatusButtonSpec[] = [
  {
    status: 'present',
    label: 'Present',
    icon: RiCheckLine,
    tone: 'emerald',
  },
  {
    status: 'absent',
    label: 'Absent',
    icon: RiCloseLine,
    tone: 'red',
  },
  {
    status: 'excused',
    label: 'Excused',
    icon: RiErrorWarningLine,
    tone: 'amber',
  },
  {
    status: 'cancelled',
    label: 'No class',
    icon: RiCalendarCloseLine,
    tone: 'neutral',
  },
];

export const STATUS_LABELS: Record<AttendanceStatus, string> = {
  present: 'Present',
  absent: 'Absent',
  excused: 'Excused',
  cancelled: 'No class',
  unmarked: 'Not marked',
};
