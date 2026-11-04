import { useId, useMemo, useState } from 'react';
import { RiAlertLine, RiLoader4Line } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Toggle } from '@/components/ui/toggle';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { DeleteRow, FormField, FormGroup, PillRadio, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { cn } from '@/lib/utils';
import { formatMinuteOfDay, nowInBudapest } from '@/lib/date-utils';
import { formatTime } from '@/lib/date-utils';
import type { ScheduleEvent } from './use-schedule';
import { getScheduleConflicts, type ScheduleItem } from './schedule-conflicts';
import type { StoredScheduleItem } from './schedule-items-store';
import type { TimeFormat } from '@/features/settings/appearance-prefs';

interface ScheduleItemEditorProps {
  item?: ScheduleItem;
  items: StoredScheduleItem[];
  events: ScheduleEvent[];
  open: boolean;
  initialType?: 'shift' | 'task';
  isSubmitting?: boolean;
  onClose: () => void;
  onSubmit: (item: ScheduleItem) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
  timeFormat?: TimeFormat;
}

const TYPE_OPTIONS = [
  { value: 'shift' as const, label: 'Work shift' },
  { value: 'task' as const, label: 'Task' },
];

function newId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = Math.random() * 16 | 0;
    return (char === 'x' ? random : (random & 0x3 | 0x8)).toString(16);
  });
}

function timeToMinutes(time: string): number | null {
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  return hour <= 23 && minute <= 59 ? hour * 60 + minute : null;
}

export function ScheduleItemEditor({
  item,
  items,
  events,
  open,
  initialType = 'shift',
  isSubmitting = false,
  onClose,
  onSubmit,
  onDelete,
  timeFormat = '24h',
}: ScheduleItemEditorProps) {
  const [draftId, setDraftId] = useState(() => item?.id || newId());
  const [type, setType] = useState<'shift' | 'task'>(item?.type || initialType);
  const [title, setTitle] = useState(item?.title || '');
  const [date, setDate] = useState(item?.date || nowInBudapest().ymd);
  const [startTime, setStartTime] = useState(item?.startTime || '09:00');
  const [endTime, setEndTime] = useState(item?.endTime || '10:00');
  const [location, setLocation] = useState(item?.location || '');
  const [acknowledgedConflictSignature, setAcknowledgedConflictSignature] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Reset the form to the edited item (or a blank draft) whenever the editor
  // opens or retargets. Adjusted during render so the previous item's values
  // can never paint for a frame, and so a background sync refresh cannot wipe
  // whatever the user has already typed.
  const [prevSync, setPrevSync] = useState<{ open: boolean; item?: ScheduleItem }>({ open, item });
  if (open !== prevSync.open || item !== prevSync.item) {
    setPrevSync({ open, item });
    if (open) {
      setDraftId(item?.id || newId());
      setType(item?.type || initialType);
      setTitle(item?.title || '');
      setDate(item?.date || nowInBudapest().ymd);
      setStartTime(item?.startTime || '09:00');
      setEndTime(item?.endTime || '10:00');
      setLocation(item?.location || '');
      setAcknowledgedConflictSignature(null);
    }
  }

  const candidate = useMemo<ScheduleItem>(() => ({
    id: draftId,
    type,
    title: title.trim() || 'Untitled item',
    date,
    startTime,
    endTime,
    ...(type === 'shift' && location.trim() ? { location: location.trim() } : {}),
    ...(type === 'task' ? { completed: item?.completed || false } : {}),
    acknowledgedConflictKeys: [],
  }), [date, draftId, endTime, item, location, startTime, title, type]);

  const currentConflicts = useMemo(() => {
    const candidateWithUpdatedAcknowledgments = { ...candidate, acknowledgedConflictKeys: [] };
    const calculated = getScheduleConflicts(
      [candidateWithUpdatedAcknowledgments, ...items.filter((entry) => entry.id !== candidate.id)],
      events,
      nowInBudapest().ymd
    );
    return calculated
      .filter((conflict) => conflict.itemId === candidate.id || conflict.otherItemId === candidate.id)
      // Every edit requires a fresh explicit acknowledgment, even if this item
      // or the other side had acknowledged the same conflict in an earlier save.
      .map((conflict) => ({ ...conflict, needsAcknowledgment: true }));
  }, [candidate, events, items]);

  const currentConflictKeys = Array.from(new Set(currentConflicts.map((conflict) => conflict.key))).sort();
  const currentUnacknowledgedKeys = currentConflicts.filter((conflict) => conflict.needsAcknowledgment).map((conflict) => conflict.key).sort();
  // The approval signature includes the overlapping window, so nudging the item
  // onto a different part of the same class still requires a fresh check.
  const unacknowledgedConflictSignature = JSON.stringify(currentConflicts
    .filter((conflict) => conflict.needsAcknowledgment)
    .map((conflict) => [conflict.key, conflict.date, conflict.startMinute, conflict.endMinute])
    .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))));
  const hasExistingUnacknowledged = currentConflicts.length > 0;
  const hasUnacknowledged = hasExistingUnacknowledged && acknowledgedConflictSignature !== unacknowledgedConflictSignature;
  const validStart = timeToMinutes(startTime);
  const validEnd = timeToMinutes(endTime);
  const timeValid = validStart !== null && validEnd !== null && validStart !== validEnd && (type === 'shift' || validEnd > validStart);
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const parsedDate = dateMatch ? new Date(Date.UTC(Number(dateMatch[1]), Number(dateMatch[2]) - 1, Number(dateMatch[3]))) : null;
  const dateValid = !!dateMatch && parsedDate?.getUTCFullYear() === Number(dateMatch[1]) && parsedDate.getUTCMonth() === Number(dateMatch[2]) - 1 && parsedDate.getUTCDate() === Number(dateMatch[3]);
  const canSave = title.trim().length > 0 && dateValid && timeValid && !hasUnacknowledged && !isSubmitting && !isDeleting;

  // The acknowledgment checkbox stores the exact signature it approved, so any
  // later change to the clash set (including one caused by a background refresh)
  // invalidates it without needing a separate reset effect.
  const handleConflictAcknowledge = (checked: boolean) => {
    setAcknowledgedConflictSignature(checked ? unacknowledgedConflictSignature : null);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSave) return;
    const existingAcknowledgments = new Set(item?.acknowledgedConflictKeys || []);
    const acknowledgedConflictKeys = Array.from(new Set([
      ...Array.from(existingAcknowledgments).filter((key) => currentConflictKeys.includes(key)),
      ...currentUnacknowledgedKeys,
    ]));
    await onSubmit({
      ...candidate,
      id: candidate.id,
      acknowledgedConflictKeys,
    });
    onClose();
  };

  const handleDelete = async () => {
    if (!item || !onDelete) return;
    setIsDeleting(true);
    try {
      await onDelete(item.id);
      onClose();
    } finally {
      setIsDeleting(false);
    }
  };

  const titleId = useId();
  const ids = useId();
  const [scrolled, setScrolled] = useState(false);
  const noun = type === 'shift' ? 'work shift' : 'task';
  const crossesMidnight = type === 'shift' && timeValid && endTime < startTime;

  return (
    <Dialog open={open} onOpenChange={(value) => !value && onClose()}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <form id="schedule-item-form" onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
          <SheetBar
            leading="close"
            onLeading={onClose}
            title={item ? `Edit ${noun}` : `Add ${noun}`}
            titleId={titleId}
            subtitle={item?.title || undefined}
            scrolled={scrolled}
          />

          <SheetBody onScrolledChange={setScrolled} fadeBottom={false} className={cn('space-y-6 pb-6 pt-3', SHEET_GUTTER)}>
            <section aria-labelledby={`${ids}-what`} className="space-y-3">
              <SheetSectionTitle id={`${ids}-what`}>{type === 'shift' ? 'Shift' : 'Task'}</SheetSectionTitle>
              <FormGroup>
                {!item && (
                  <FormField label="Kind" labelId="schedule-item-type-label">
                    <PillRadio value={type} onChange={setType} options={TYPE_OPTIONS} labelledBy="schedule-item-type-label" />
                  </FormField>
                )}
                <FormField label="Title" htmlFor="schedule-item-title">
                  <Input
                    id="schedule-item-title"
                    value={title}
                    onChange={(event) => setTitle(event.target.value)}
                    maxLength={200}
                    required
                    placeholder={type === 'shift' ? 'Evening shift' : 'Study for accounting'}
                    className={SHEET_INPUT_CLASS}
                  />
                </FormField>
                {type === 'shift' && (
                  <FormField label="Workplace" htmlFor="schedule-item-location" hint="Optional.">
                    <Input
                      id="schedule-item-location"
                      value={location}
                      onChange={(event) => setLocation(event.target.value)}
                      maxLength={200}
                      placeholder="Café on Ráday utca"
                      className={SHEET_INPUT_CLASS}
                    />
                  </FormField>
                )}
              </FormGroup>
            </section>

            <section aria-labelledby={`${ids}-when`} className="space-y-3">
              <SheetSectionTitle id={`${ids}-when`}>When</SheetSectionTitle>
              <FormGroup>
                <FormField label="Date" htmlFor="schedule-item-date" error={dateValid ? null : 'Pick a real date.'}>
                  <Input
                    id="schedule-item-date"
                    type="date"
                    value={date}
                    onChange={(event) => setDate(event.target.value)}
                    required
                    className={SHEET_INPUT_CLASS}
                  />
                </FormField>
                <div className="grid grid-cols-2 divide-x divide-subtle">
                  <FormField label="Starts" htmlFor="schedule-item-start">
                    <Input
                      id="schedule-item-start"
                      type="time"
                      value={startTime}
                      onChange={(event) => setStartTime(event.target.value)}
                      required
                      className={SHEET_INPUT_CLASS}
                    />
                  </FormField>
                  <FormField label="Ends" htmlFor="schedule-item-end">
                    <Input
                      id="schedule-item-end"
                      type="time"
                      value={endTime}
                      onChange={(event) => setEndTime(event.target.value)}
                      required
                      className={SHEET_INPUT_CLASS}
                    />
                  </FormField>
                </div>
                {(crossesMidnight || !timeValid) && (
                  <p
                    role={timeValid ? undefined : 'alert'}
                    className={cn('px-4 py-3 text-xs', timeValid ? 'text-secondary' : 'font-semibold text-tint-red')}
                  >
                    {timeValid
                      ? `Ends the next day at ${formatTime(endTime, timeFormat)}.`
                      : type === 'task'
                        ? 'A task has to end later on the same day it starts.'
                        : 'Start and end need to be different, valid times.'}
                  </p>
                )}
              </FormGroup>
            </section>

            {currentConflicts.length > 0 && (
              <section aria-labelledby={`${ids}-clash`} className="space-y-3" role="alert">
                <SheetSectionTitle id={`${ids}-clash`}>
                  <span className="inline-flex items-center gap-2 text-tint-red">
                    <RiAlertLine className="h-4 w-4" aria-hidden="true" />
                    {currentConflicts.length === 1 ? 'Clashes with a class' : `Clashes with ${currentConflicts.length} classes`}
                  </span>
                </SheetSectionTitle>
                <FormGroup className="border border-rose-500/30">
                  <ul className="divide-y divide-subtle">
                    {currentConflicts.map((conflict) => (
                      <li key={conflict.key} className="flex items-baseline justify-between gap-3 px-4 py-3">
                        <span className="min-w-0 truncate text-sm font-semibold text-primary">{conflict.otherTitle}</span>
                        <span className="shrink-0 text-xs tabular-nums text-secondary">
                          {formatTime(formatMinuteOfDay(conflict.startMinute), timeFormat)}–{formatTime(formatMinuteOfDay(conflict.endMinute), timeFormat)}
                        </span>
                      </li>
                    ))}
                  </ul>
                  {hasExistingUnacknowledged && (
                    // SwitchRow's layout, but as a <label>: the switch is a
                    // <button> (a labelable element), so tapping the text
                    // toggles it too.
                    <label className="flex cursor-pointer items-center justify-between gap-4 px-4 py-3.5">
                      <span className="min-w-0">
                        <span id="schedule-item-ack-label" className="block text-sm font-semibold text-primary">
                          I know, save it anyway
                        </span>
                        <span className="mt-0.5 block text-xs text-muted">
                          Saving stays off until you acknowledge the clash.
                        </span>
                      </span>
                      <span className="shrink-0">
                        <Toggle
                          checked={!hasUnacknowledged}
                          onChange={handleConflictAcknowledge}
                          aria-label="I understand and acknowledge these schedule clashes"
                        />
                      </span>
                    </label>
                  )}
                </FormGroup>
              </section>
            )}

            {item && onDelete && (
              <DeleteRow
                label={`Delete ${noun}`}
                onConfirm={handleDelete}
                disabled={isSubmitting}
                busy={isDeleting}
              />
            )}
          </SheetBody>

          <SheetFooter>
            <Button type="submit" size="lg" className="h-12 w-full rounded-2xl" disabled={!canSave}>
              {isSubmitting && <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />}
              {item ? 'Save changes' : `Add ${noun}`}
            </Button>
          </SheetFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
