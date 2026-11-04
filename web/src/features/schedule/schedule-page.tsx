import { useState, useEffect, useCallback, useMemo } from 'react';
import { RiAddLine, RiUploadLine, RiBriefcaseLine, RiTaskLine } from '@remixicon/react';
import { useSetNavbarActions, type NavbarActionConfig } from '@/features/navbar/navbar-action-context';
import { ScheduleView } from '@/features/schedule/schedule-view';
import { ScheduleEditor } from '@/features/schedule/schedule-editor';
import { CSVImportWizard } from '@/features/schedule/csv-import-wizard';
import { useSchedule, type ScheduleFormData, type ScheduleEvent } from '@/features/schedule/use-schedule';
import { useScheduleItems } from '@/features/schedule/use-schedule-items';
import { ScheduleItemEditor } from '@/features/schedule/schedule-item-editor';
import { type ScheduleItem } from '@/features/schedule/schedule-conflicts';
import type { StoredScheduleItem } from '@/features/schedule/schedule-items-store';
import { encryptNeptunUrl, decryptNeptunUrl } from '@/lib/encryption';
import { clearNeptunEvents } from '@/features/schedule/use-neptun-sync';
import { isExamEvent } from '@/features/schedule/exam-detection';
import { useDialogPresence } from '@/hooks/use-dialog-presence';

import { useAuth } from '@/features/auth/use-auth';
import { Button } from '@/components/ui/button';
import { Reveal } from '@/components/ui/reveal';
import { OfflineBanner } from '@/components/ui/offline-banner';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { CourseTrackerModal } from '@/features/course-tracker/components/course-tracker-modal';
import { toast } from 'sonner';
import { motion } from 'framer-motion';

function ScheduleSkeleton() {
  return (
    <div className="flex flex-col gap-6 animate-pulse w-full">
      {/* Header Tabs Skeleton */}
      <div className="flex flex-col sm:flex-row gap-4 justify-between items-start sm:items-center">
        <div className="flex gap-2">
          <div className="h-9 w-24 bg-surface-overlay rounded-full" />
          <div className="h-9 w-24 bg-surface-overlay rounded-full" />
        </div>
        <div className="h-9 w-32 bg-surface-overlay rounded-xl hidden sm:block" />
      </div>

      {/* Grid Skeleton */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4 w-full">
        {[...Array(5)].map((_, i) => (
          <div key={i} className="flex flex-col gap-3">
            <div className="h-8 w-full bg-surface-overlay rounded-xl" />
            <div className="h-32 w-full bg-surface-overlay rounded-2xl border border-subtle" />
            {i % 2 !== 0 && <div className="h-28 w-full bg-surface-overlay rounded-2xl border border-subtle" />}
          </div>
        ))}
      </div>
    </div>
  );
}

export function SchedulePage() {
  const { profile, updateProfile } = useAuth();
  const {
    events,
    eventsByDay,
    semesters,
    currentSemester,
    isLoading,
    isOffline,
    createEvent,
    updateEvent,
    deleteEvent,
    isCreating,
  } = useSchedule();
  const scheduleItemsState = useScheduleItems(events);
  const {
    items,
    conflictsByEventId,
    needsAcknowledgmentIds,
    syncConflictIds,
    resolveSyncConflict,
    createItem,
    updateItem,
    deleteItem,
    toggleTaskCompleted,
    isSaving: isSavingItem,
    syncStatus: itemSyncStatus,
    syncError: itemSyncError,
  } = scheduleItemsState;

  const [showEditor, setShowEditor] = useState(false);
  const [showItemEditor, setShowItemEditor] = useState(false);
  const [showAddMenu, setShowAddMenu] = useState(false);
  const [newItemType, setNewItemType] = useState<'shift' | 'task'>('shift');
  const [editingItem, setEditingItem] = useState<StoredScheduleItem | null>(null);
  const [showImport, setShowImport] = useState(false);
  const [showNeptunEditNotice, setShowNeptunEditNotice] = useState(false);
  const [editingEvent, setEditingEvent] = useState<ScheduleEvent | null>(null);
  const courseTracker = useDialogPresence<ScheduleEvent>();
  const openCourseTracker = courseTracker.openWith;
  const [savedNeptunUrl, setSavedNeptunUrl] = useState<string>('');

  const handleSaveNeptunUrl = async (url: string) => {
    const trimmed = url.trim();
    if (!trimmed) {
      setSavedNeptunUrl('');
      try {
        await clearNeptunEvents();
        await updateProfile({ neptun_ics_url: null, neptun_last_sync: null });
      } catch (err) {
        console.warn('Failed to clear Neptun URL from profile:', err);
      }
      return;
    }
    setSavedNeptunUrl(trimmed);
    try {
      const encryptedUrl = await encryptNeptunUrl(trimmed);
      await updateProfile({ neptun_ics_url: encryptedUrl });
    } catch (err) {
      console.warn('Failed to update Neptun URL in profile:', err);
      toast.error('Failed to save Neptun URL', {
        description: 'The encryption service is unavailable. Please try again.',
      });
    }
  };

  const handleCreateEvent = async (data: ScheduleFormData) => {
    await createEvent(data);
  };

  const handleEditEvent = async (data: ScheduleFormData) => {
    if (editingEvent) {
      await updateEvent({ id: editingEvent.id, ...data });
    }
  };

  const handleSaveItem = async (item: ScheduleItem) => {
    try {
      if (editingItem) await updateItem(item);
      else await createItem(item);
      toast.success(item.type === 'shift' ? 'Work shift saved' : 'Task saved');
      setShowItemEditor(false);
      setEditingItem(null);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not save this schedule item.');
      throw error;
    }
  };

  const handleDeleteItem = async (id: string) => {
    await deleteItem(id);
    toast.success('Schedule item deleted');
  };

  const handleToggleTask = async (item: ScheduleItem) => {
    try {
      await toggleTaskCompleted(item);
    } catch (error) {
      if (error instanceof Error && error.name === 'ScheduleItemConflictError') {
        const stored = items.find((entry) => entry.id === item.id);
        setEditingItem({ ...(stored || item as StoredScheduleItem), completed: false });
        setShowItemEditor(true);
      } else {
        toast.error(error instanceof Error ? error.message : 'Could not update task.');
      }
    }
  };

  const handlePersonalItemClick = useCallback((item: ScheduleItem) => {
    const stored = items.find((entry) => entry.id === item.id);
    if (!stored) return;
    setEditingItem(stored);
    setNewItemType(stored.type);
    setShowItemEditor(true);
  }, [items]);

  useEffect(() => {
    let cancelled = false;

    const applyProfile = async () => {
      if (profile !== undefined) {
        const dbUrl = profile?.neptun_ics_url ? await decryptNeptunUrl(profile.neptun_ics_url.trim()) : '';
        if (cancelled) return;

        // Profile is the Single Source of Truth — no local copies to purge.
        setSavedNeptunUrl(dbUrl);
      }
    };

    applyProfile();
    return () => {
      cancelled = true;
    };
  }, [profile]);

  const handleEventClick = useCallback((event: ScheduleEvent) => {
    if (event.source === 'neptun') {
      // Neptun-synced events are managed by calendar sync — opening them brings up their Course Hub!
      // Exams have no attendance to track, so they never get one. isExamEvent also
      // recognises exams imported before the title check existed (their rows still
      // say event_type 'class'). This sits inside the neptun branch on purpose: a
      // personal event the student titled "exam" still has to open in the editor.
      if (isExamEvent(event)) return;
      openCourseTracker(event);
      return;
    }
    setEditingEvent(event);
    setShowEditor(true);
  }, [openCourseTracker]);

  const openNewClass = useCallback(() => {
    setEditingEvent(null);
    setShowEditor(true);
  }, []);
  const openNewItem = useCallback((type: 'shift' | 'task') => {
    setEditingItem(null);
    setNewItemType(type);
    setShowItemEditor(true);
  }, []);

  // Sync and add live in the navbar's action bubble (the same split action the
  // transit page uses); wide screens without the bubble keep them in the toolbar.
  const navbarConfig = useMemo<NavbarActionConfig>(() => ({
    icon: RiAddLine,
    actions: [
      { id: 'sync', label: 'Sync Neptun', icon: RiUploadLine, variant: 'blue', onClick: () => setShowImport(true) },
      { id: 'add-task', label: 'Add task', icon: RiTaskLine, variant: 'gray', onClick: () => openNewItem('task') },
      { id: 'add-shift', label: 'Add work shift', icon: RiBriefcaseLine, variant: 'gray', onClick: () => openNewItem('shift') },
      { id: 'add-class', label: 'Add class', icon: RiAddLine, variant: 'primary', onClick: openNewClass },
    ],
  }), [openNewClass, openNewItem]);
  useSetNavbarActions(navbarConfig);

  const actionButtons = (
    <div className="flex shrink-0 items-center gap-1.5">
      <Button
        onClick={() => setShowImport(true)}
        title="Sync your Neptun calendar subscription link"
        variant="ghost"
        className="justify-center whitespace-nowrap h-9 px-3 text-xs font-bold rounded-full text-secondary hover:text-primary hover:bg-surface-overlay transition-[color,background-color,transform] active:scale-95"
      >
        <RiUploadLine className="w-3.5 h-3.5 shrink-0" />
        <span className="ml-1.5">Sync</span>
      </Button>

      <div className="relative">
        <Button
          onClick={() => setShowAddMenu((open) => !open)}
          aria-haspopup="menu"
          aria-expanded={showAddMenu}
          title="Add to schedule"
          className="justify-center whitespace-nowrap h-9 px-4 text-xs font-bold bg-accent-primary hover:bg-accent-primary/90 text-white rounded-full border-0 shadow-sm transition-[color,background-color,box-shadow,transform] active:scale-95"
        >
          <RiAddLine className="w-3.5 h-3.5 shrink-0" />
          <span className="ml-1.5">Add</span>
        </Button>
        {showAddMenu && (
          <div role="menu" aria-label="Add to schedule" className="absolute right-0 top-full z-50 mt-2 w-48 rounded-2xl border border-subtle bg-surface-secondary p-1.5 shadow-xl">
            <button type="button" role="menuitem" onClick={() => { openNewClass(); setShowAddMenu(false); }} className="w-full rounded-xl px-3 py-2 text-left text-xs font-semibold text-primary hover:bg-surface-overlay">Class</button>
            <button type="button" role="menuitem" onClick={() => { openNewItem('shift'); setShowAddMenu(false); }} className="w-full rounded-xl px-3 py-2 text-left text-xs font-semibold text-primary hover:bg-surface-overlay">Work shift</button>
            <button type="button" role="menuitem" onClick={() => { openNewItem('task'); setShowAddMenu(false); }} className="w-full rounded-xl px-3 py-2 text-left text-xs font-semibold text-primary hover:bg-surface-overlay">Task</button>
          </div>
        )}
      </div>

    </div>
  );

  return (
    <>
      {isOffline && (
        <OfflineBanner message="No internet connection — Viewing cached offline schedule" />
      )}
      {itemSyncStatus === 'pending' && !isOffline && (
        <OfflineBanner message={itemSyncError || 'Schedule item changes are waiting to sync.'} />
      )}

      {isLoading ? (
        <ScheduleSkeleton />
      ) : events.length === 0 && items.length === 0 ? (
        /* The card's entrance is the shared `Reveal`, so it gets the same
           fade-and-rise every page's cards have — and on a cold start it waits
           for the boot splash instead of playing behind it. */
        <Reveal
          index={0}
          className="relative mt-4 sm:mt-6 rounded-3xl card-glass text-center py-14 sm:py-20 px-6 flex flex-col items-center justify-center gap-6 shadow-xl overflow-hidden group"
        >
          {/* Subtle animated background glow */}
          <div className="absolute inset-0 rounded-full bg-[radial-gradient(closest-side,rgba(14,165,233,0.2),transparent_70%)] opacity-70 group-hover:opacity-100 transition-opacity duration-1000" style={{ pointerEvents: 'none' }} />
          
          {/* Calendar icon — only the emoji drifts; the card itself enters */}
          <div className="relative z-10">
            <div className="w-20 h-20 mx-auto rounded-3xl bg-gradient-to-br from-sky-500/20 to-indigo-500/15 border border-white/15 text-sky-400 flex items-center justify-center text-4xl shadow-[inset_0_1px_0_rgba(255,255,255,0.2)] shadow-[0_0_30px_rgba(56,189,248,0.25)] relative">
               <motion.div
                 animate={{ y: [0, -6, 0] }}
                 transition={{ repeat: Infinity, duration: 4, ease: "easeInOut" }}
               >
                 📅
               </motion.div>
            </div>
          </div>

          <div className="relative z-10 space-y-2.5 max-w-md">
            <h3 className="text-xl sm:text-2xl font-black bg-clip-text bg-gradient-to-br from-white via-white/90 to-sky-300 tracking-tight">
              No classes scheduled
            </h3>
            <p className="text-xs sm:text-sm text-secondary leading-relaxed font-medium">
              Import your Neptun calendar (.ics file or subscription URL) to automatically view and categorize your classes across all semesters.
            </p>
          </div>

          <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2 relative z-10 w-full sm:w-auto">
            {actionButtons}
          </div>

        </Reveal>
      ) : (
        <ScheduleView
          events={events}
          eventsByDay={eventsByDay}
          semesters={semesters}
          currentSemester={currentSemester}
          onEventClick={handleEventClick}
          actions={actionButtons}
          scheduleItems={items}
          conflictsByEventId={conflictsByEventId}
          needsAcknowledgmentIds={needsAcknowledgmentIds}
          syncConflictIds={syncConflictIds}
          onItemClick={handlePersonalItemClick}
          onToggleTaskCompleted={handleToggleTask}
          onResolveSyncConflict={resolveSyncConflict}
        />
      )}

      {/* Editor */}
      {/* Not keyed by the edited row: closing clears it, and a re-key would
          remount the dialog mid-close so focus could not return to the
          trigger. Both editors reset their form whenever they open. */}
      <ScheduleEditor
        event={editingEvent || undefined}
        open={showEditor}
        onClose={() => {
          setShowEditor(false);
          setEditingEvent(null);
        }}
        onSubmit={editingEvent ? handleEditEvent : handleCreateEvent}
        onDelete={deleteEvent}
        onOpenCourseHub={openCourseTracker}
        isSubmitting={isCreating}
        availableSemesters={semesters}
      />

      <ScheduleItemEditor
        item={editingItem || undefined}
        items={items}
        events={events}
        initialType={newItemType}
        open={showItemEditor}
        isSubmitting={isSavingItem}
        onClose={() => { setShowItemEditor(false); setEditingItem(null); }}
        onSubmit={handleSaveItem}
        onDelete={handleDeleteItem}
      />

      {/* Neptun Sync Wizard */}
      <CSVImportWizard
        open={showImport}
        onClose={() => setShowImport(false)}
        initialUrl={savedNeptunUrl}
        onSaveUrl={handleSaveNeptunUrl}
      />

      {/* Course Tracker Hub Modal */}
      {courseTracker.value && (
        <CourseTrackerModal
          open={courseTracker.open}
          onOpenChange={courseTracker.onOpenChange}
          onExited={courseTracker.onExited}
          courseName={courseTracker.value.course_name}
          courseCode={courseTracker.value.course_code}
          semester={courseTracker.value.semester}
          instructor={courseTracker.value.instructor}
          location={courseTracker.value.location}
          color={courseTracker.value.color}
          dayOfWeek={courseTracker.value.day_of_week}
          startTime={courseTracker.value.start_time}
          endTime={courseTracker.value.end_time}
          eventType={courseTracker.value.event_type}
          scheduleEvents={events}
        />
      )}

      {/* Clear All Confirmation Modal */}


      {/* Neptun Edit Notice Modal */}
      <ConfirmDialog
        open={showNeptunEditNotice}
        onClose={() => setShowNeptunEditNotice(false)}
        onConfirm={() => setShowNeptunEditNotice(false)}
        title="Uneditable Class"
        description="This class was automatically fetched from Neptun and cannot be manually edited or deleted. It will be updated automatically when you sync your calendar."
        confirmText="Okay"
        hideCancel={true}
        variant="default"
      />
    </>
  );
}
