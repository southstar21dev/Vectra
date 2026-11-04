import { useId, useState } from 'react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { FormField, FormGroup, PillRadio, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { STATUS_BUTTONS } from '../session-status';
import { STATUS_TONE_CLASS } from '@/components/ui/status-label';
import { type AttendanceSession, type SessionType, type AttendanceStatus } from '../tracker-types';
import { genId } from '@/lib/id';
import { formatYMD } from '@/lib/date-utils';
import { cn } from '@/lib/utils';

interface AddSessionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAddSession: (session: AttendanceSession) => void;
}

const SESSION_TYPE_OPTIONS: { value: SessionType; label: string }[] = [
  { value: 'lecture', label: 'Lecture' },
  { value: 'seminar', label: 'Seminar' },
  { value: 'lab', label: 'Lab' },
  { value: 'consultation', label: 'Consultation' },
  { value: 'exam', label: 'Exam' },
  { value: 'other', label: 'Other' },
];

const DEFAULT_TITLE = 'Extra consultation';
const DEFAULT_START = '10:00';
const DEFAULT_END = '11:30';

interface AddSessionFormProps {
  titleId: string;
  onSubmit: (session: AttendanceSession) => void;
  onCancel: () => void;
}

/**
 * The form lives inside DialogContent, which unmounts when the dialog closes —
 * so every opening starts from clean defaults instead of an abandoned draft.
 */
function AddSessionForm({ titleId, onSubmit, onCancel }: AddSessionFormProps) {
  const ids = useId();
  const [scrolled, setScrolled] = useState(false);
  const [title, setTitle] = useState(DEFAULT_TITLE);
  const [date, setDate] = useState(formatYMD());
  const [startTime, setStartTime] = useState(DEFAULT_START);
  const [endTime, setEndTime] = useState(DEFAULT_END);
  const [sessionType, setSessionType] = useState<SessionType>('consultation');
  const [status, setStatus] = useState<AttendanceStatus>('present');
  const [notes, setNotes] = useState('');

  const canSubmit = title.trim().length > 0 && date.length > 0;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;

    onSubmit({
      id: genId(),
      title: title.trim(),
      date,
      startTime: startTime || undefined,
      endTime: endTime || undefined,
      sessionType,
      status,
      notes: notes.trim() || undefined,
      isCustom: true,
    });
  };

  return (
    <form onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col">
      <SheetBar leading="close" onLeading={onCancel} title="Add session" titleId={titleId} scrolled={scrolled} />

      <SheetBody onScrolledChange={setScrolled} fadeBottom={false} className={cn('space-y-6 pb-6 pt-3', SHEET_GUTTER)}>
        <section aria-labelledby={`${ids}-what`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-what`}>Session</SheetSectionTitle>
          <FormGroup>
            <FormField label="Title" htmlFor="session-title">
              <Input
                id="session-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Make-up lab, midterm prep…"
                className={SHEET_INPUT_CLASS}
                required
              />
            </FormField>
            <FormField label="Type" labelId="session-type-label">
              <PillRadio
                value={sessionType}
                onChange={setSessionType}
                options={SESSION_TYPE_OPTIONS}
                labelledBy="session-type-label"
                columns={3}
              />
            </FormField>
          </FormGroup>
        </section>

        <section aria-labelledby={`${ids}-when`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-when`}>When</SheetSectionTitle>
          <FormGroup>
            <FormField label="Date" htmlFor="session-date">
              <Input
                id="session-date"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                className={SHEET_INPUT_CLASS}
                required
              />
            </FormField>
            <div className="grid grid-cols-2 divide-x divide-subtle">
              <FormField label="Starts" htmlFor="session-start">
                <Input
                  id="session-start"
                  type="time"
                  value={startTime}
                  onChange={(e) => setStartTime(e.target.value)}
                  className={SHEET_INPUT_CLASS}
                />
              </FormField>
              <FormField label="Ends" htmlFor="session-end">
                <Input
                  id="session-end"
                  type="time"
                  value={endTime}
                  onChange={(e) => setEndTime(e.target.value)}
                  className={SHEET_INPUT_CLASS}
                />
              </FormField>
            </div>
          </FormGroup>
        </section>

        <section aria-labelledby={`${ids}-status`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-status`}>Attendance</SheetSectionTitle>
          <FormGroup>
            <FormField
              label="Mark it as"
              labelId="session-status-label"
              hint="Leave it unmarked to decide after the session."
            >
              <div role="radiogroup" aria-labelledby="session-status-label" className="grid grid-cols-2 gap-2">
                {STATUS_BUTTONS.map((btn) => {
                  const Icon = btn.icon;
                  const active = status === btn.status;
                  return (
                    <button
                      key={btn.status}
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => setStatus(btn.status)}
                      className={cn(
                        'flex h-10 items-center justify-center gap-1.5 rounded-full border px-3 text-sm font-semibold transition-[color,background-color,border-color,box-shadow,transform,opacity] cursor-pointer select-none active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary',
                        active ? 'bg-surface-overlay border-primary text-primary' : 'bg-surface-secondary border-subtle text-secondary hover:text-primary'
                      )}
                    >
                      <Icon className={cn('h-3.5 w-3.5 shrink-0', STATUS_TONE_CLASS[btn.tone])} aria-hidden="true" />
                      <span>{btn.label}</span>
                    </button>
                  );
                })}
                <button
                  type="button"
                  role="radio"
                  aria-checked={status === 'unmarked'}
                  onClick={() => setStatus('unmarked')}
                  className={cn(
                    'col-span-2 flex h-10 items-center justify-center rounded-full border px-3 text-sm font-semibold transition-colors cursor-pointer select-none active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary',
                    status === 'unmarked'
                      ? 'border-accent-primary/50 bg-accent-primary/15 text-accent-primary'
                      : 'border-subtle bg-surface-secondary text-secondary hover:border-primary hover:text-primary'
                  )}
                >
                  Not marked yet
                </button>
              </div>
            </FormField>
          </FormGroup>
        </section>

        <FormGroup>
          <FormField label="Notes" htmlFor="session-notes">
            <Input
              id="session-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Chapter 4 review, room 204…"
              className={SHEET_INPUT_CLASS}
            />
          </FormField>
        </FormGroup>
      </SheetBody>

      <SheetFooter>
        <Button type="submit" size="lg" className="h-12 w-full rounded-2xl" disabled={!canSubmit}>
          Add session
        </Button>
      </SheetFooter>
    </form>
  );
}

export function AddSessionDialog({ open, onOpenChange, onAddSession }: AddSessionDialogProps) {
  const titleId = useId();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-md max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <AddSessionForm
          titleId={titleId}
          onCancel={() => onOpenChange(false)}
          onSubmit={(session) => {
            onAddSession(session);
            onOpenChange(false);
          }}
        />
      </DialogContent>
    </Dialog>
  );
}
