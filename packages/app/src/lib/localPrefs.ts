/** Local-only UI prefs (not synced to the server). Spec §6. */
export interface LocalPrefs {
  sidebarCollapsed: boolean;
  notesCollapsed: boolean;
  projectsCollapsed: boolean;
}

export const LOCAL_PREF_KEYS = [
  'sidebarCollapsed',
  'notesCollapsed',
  'projectsCollapsed',
] as const;

export type LocalPrefKey = (typeof LOCAL_PREF_KEYS)[number];

const PREFIX = 'tmap.pref.';

const DEFAULTS: LocalPrefs = {
  sidebarCollapsed: false,
  notesCollapsed: false,
  projectsCollapsed: false,
};

function resolveStorage(explicit?: Storage): Storage | null {
  if (explicit) return explicit;
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** Read all local-only prefs; any missing/malformed key falls back to its default. */
export function loadLocalPrefs(storage?: Storage): LocalPrefs {
  const s = resolveStorage(storage);
  if (!s) return { ...DEFAULTS };
  const out: LocalPrefs = { ...DEFAULTS };
  for (const key of LOCAL_PREF_KEYS) {
    try {
      const raw = s.getItem(PREFIX + key);
      if (raw == null) continue;
      const parsed = JSON.parse(raw);
      if (typeof parsed === 'boolean') out[key] = parsed;
    } catch {
      // malformed or storage error → keep default
    }
  }
  return out;
}

/** Persist one local-only pref. Never throws (storage may be unavailable). */
export function saveLocalPref(key: LocalPrefKey, value: boolean, storage?: Storage): void {
  const s = resolveStorage(storage);
  if (!s) return;
  try {
    s.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // storage full / blocked → ignore
  }
}
