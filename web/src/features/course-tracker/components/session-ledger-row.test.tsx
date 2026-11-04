import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SessionLedgerRow } from './session-ledger-row';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import type { AttendanceSession, AttendanceStatus } from '../tracker-types';

const SEMINAR_BADGE = { label: 'Seminar', tone: 'emerald' as const };
const TODAY = '2026-09-16';
const OTHER_DAY = '2026-10-02';

interface RowOptions {
  date?: string;
  startTime?: string;
  weekTitle?: string;
  status?: AttendanceStatus;
  isCustom?: boolean;
  notes?: string;
  timeFormat?: TimeFormat;
  flash?: boolean;
}

/**
 * The row is a pure function of its props, so static markup is enough to pin
 * its content and ordering without a DOM.
 */
function renderRow(options: RowOptions = {}) {
  const session: AttendanceSession = {
    id: 's1',
    date: options.date ?? OTHER_DAY,
    startTime: options.startTime ?? '13:40',
    title: 'Week 3',
    sessionType: 'seminar',
    status: options.status ?? 'unmarked',
    ...(options.isCustom ? { isCustom: true } : {}),
    ...(options.notes ? { notes: options.notes } : {}),
  };

  const onSetStatus = vi.fn();
  const onRequestDelete = vi.fn();
  const html = renderToStaticMarkup(
    <SessionLedgerRow
      session={session}
      title={options.weekTitle ?? 'Week 3'}
      badge={SEMINAR_BADGE}
      todayIso={TODAY}
      timeFormat={options.timeFormat ?? '24h'}
      flash={options.flash}
      onSetStatus={onSetStatus}
      onRequestDelete={onRequestDelete}
    />
  );

  return { html, session, onSetStatus, onRequestDelete };
}

describe('SessionLedgerRow', () => {
  it('names the week and session type', () => {
    const { html } = renderRow();
    expect(html).toContain('Week 3');
    expect(html).toContain('Seminar');
  });

  it('uses the academic-week title the caller resolved, not the raw session title', () => {
    const { html } = renderRow({ weekTitle: 'Intensive Week' });
    expect(html).toContain('>Intensive Week</span>');
    expect(html).not.toContain('>Week 3</span>');
  });

  it('draws the date as a tile and reads the date and time out for screen readers', () => {
    const { html } = renderRow();
    // Tile: month, day, weekday.
    expect(html).toContain('>OCT<');
    expect(html).toContain('>2<');
    expect(html).toContain('>Fri<');
    expect(html).toContain('<span class="sr-only">, Fri 2 Oct 13:40</span>');
  });

  it('frames today in a solid accent tile and marks the row as the current date', () => {
    const today = renderRow({ date: TODAY }).html;
    const other = renderRow({ date: OTHER_DAY }).html;

    expect(today).toContain('aria-current="date"');
    expect(today).toContain('border-solid border-accent-primary');
    expect(today).toContain('sr-only">, Today 13:40');
    expect(other).not.toContain('aria-current');
    expect(other).toContain('border-dashed');
  });

  it('honours the 12-hour preference', () => {
    const { html } = renderRow({ timeFormat: '12h' });
    expect(html).toContain('1:40 PM');
    expect(html).not.toContain('13:40');
  });

  it('leaves out the time for a session that has none', () => {
    const { html } = renderRow({ startTime: '' });
    expect(html).toContain('sr-only">, Fri 2 Oct</span>');
  });

  it('marks custom sessions as custom', () => {
    expect(renderRow({ isCustom: true }).html).toContain('Custom');
    expect(renderRow().html).not.toContain('Custom');
  });

  it('names the session in the delete control for screen readers', () => {
    expect(renderRow().html).toContain('aria-label="Delete Week 3 on Fri 2 Oct"');
    expect(renderRow({ date: TODAY }).html).toContain('aria-label="Delete Week 3 on Today"');
  });

  it('asks the caller to confirm the delete instead of deleting itself', () => {
    const { html, onRequestDelete, onSetStatus } = renderRow();
    // No side effects on render — the row only wires its buttons to callbacks.
    expect(onRequestDelete).not.toHaveBeenCalled();
    expect(onSetStatus).not.toHaveBeenCalled();
    expect(html).toContain('title="Delete session"');
  });

  it('offers all four marks, quiet until one is chosen', () => {
    const unmarked = renderRow().html;
    for (const label of ['Present', 'Absent', 'Excused', 'No class']) {
      expect(unmarked).toContain(`aria-label="${label}"`);
    }
    expect(unmarked).not.toContain('aria-pressed="true"');
    expect(unmarked).not.toContain('mark-depth');
  });

  it('lights only the chosen mark', () => {
    const { html } = renderRow({ status: 'absent' });
    expect(html.match(/aria-pressed="true"/g)).toHaveLength(1);
    expect(html).toContain('mark-depth mark-depth-absent');
    expect(html.match(/mark-glass/g)).toHaveLength(3);
  });

  it('shows the note only when the session has one', () => {
    expect(renderRow().html).not.toContain('Bring the lab sheet');
    expect(renderRow({ notes: 'Bring the lab sheet' }).html).toContain('Bring the lab sheet');
  });

  it('washes the row when a semester dot jumps to it', () => {
    expect(renderRow({ flash: true }).html).toContain('bg-accent-primary/10');
    expect(renderRow().html).not.toContain('bg-accent-primary/10');
  });
});
