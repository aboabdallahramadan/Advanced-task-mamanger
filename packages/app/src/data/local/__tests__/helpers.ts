import 'fake-indexeddb/auto';
import { LocalStore } from '../LocalStore';
import type { SyncBridge } from '../LocalDataClient';
import type {
  TaskSyncRow,
  SubtaskSyncRow,
  ProjectSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  RecurrenceRuleSyncRow,
  FocusSessionSyncRow,
  DailyPlanSyncRow,
  SettingSyncRow,
} from '../rows';

let nextUser = 0;
export function freshUserId(): string {
  return `u${Date.now()}-${nextUser++}`;
}

const stores: LocalStore[] = [];
export function openStore(userId = freshUserId()): LocalStore {
  const s = LocalStore.open(userId);
  stores.push(s);
  return s;
}
export function closeAll(): void {
  for (const s of stores.splice(0)) s.close();
}

/** A fake SyncBridge that records nudges and lets tests script online/ensureInstances. */
export function fakeBridge(opts: {
  online?: boolean;
  ensureInstances?: (start: string, end: string) => Promise<TaskSyncRow[]>;
} = {}): SyncBridge & { nudges: number } {
  return {
    nudges: 0,
    nudge() {
      this.nudges++;
    },
    online() {
      return opts.online ?? false;
    },
    ensureInstances:
      opts.ensureInstances ?? (async () => []),
  };
}

export function taskRow(id: string, o: Partial<TaskSyncRow> = {}): TaskSyncRow {
  return {
    id,
    title: `Task ${id}`,
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Planned',
    plannedDate: '2026-06-11',
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'n',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function subtaskRow(id: string, taskId: string, o: Partial<SubtaskSyncRow> = {}): SubtaskSyncRow {
  return {
    id,
    taskId,
    title: `Sub ${id}`,
    completed: false,
    sortOrder: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function projectRow(id: string, o: Partial<ProjectSyncRow> = {}): ProjectSyncRow {
  return {
    id,
    name: `Project ${id}`,
    color: '#6366f1',
    emoji: '📁',
    rank: 'n',
    actualTimeMinutes: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function noteGroupRow(id: string, o: Partial<NoteGroupSyncRow> = {}): NoteGroupSyncRow {
  return {
    id,
    name: `Group ${id}`,
    emoji: '🗂️',
    projectId: null,
    rank: 'n',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function noteRow(id: string, o: Partial<NoteSyncRow> = {}): NoteSyncRow {
  return {
    id,
    groupId: null,
    projectId: null,
    title: `Note ${id}`,
    content: '',
    rank: 'n',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function ruleRow(id: string, o: Partial<RecurrenceRuleSyncRow> = {}): RecurrenceRuleSyncRow {
  return {
    id,
    frequency: 'Daily',
    interval: 1,
    daysOfWeek: [],
    endType: 'Never',
    endCount: null,
    endDate: null,
    generatedUntil: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function focusRow(id: string, o: Partial<FocusSessionSyncRow> = {}): FocusSessionSyncRow {
  return {
    id,
    taskId: null,
    project: '',
    startedAt: '2026-06-11T09:00:00Z',
    endedAt: '2026-06-11T09:25:00Z',
    minutes: 25,
    date: '2026-06-11',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function planRow(date: string, o: Partial<DailyPlanSyncRow> = {}): DailyPlanSyncRow {
  return {
    date,
    committedAt: '2026-06-11T08:00:00Z',
    plannedTaskIds: [],
    plannedMinutes: 0,
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function settingRow(key: string, value: string, o: Partial<SettingSyncRow> = {}): SettingSyncRow {
  return {
    key,
    value,
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}
