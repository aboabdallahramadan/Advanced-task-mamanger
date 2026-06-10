import { describe, it, expect, beforeEach, vi } from 'vitest';
import { loadLocalPrefs, saveLocalPref, LOCAL_PREF_KEYS, type LocalPrefs } from './localPrefs';

function memoryStorage(): Storage {
  let m: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(m).length;
    },
    clear: () => {
      m = {};
    },
    getItem: (k: string) => (k in m ? m[k] : null),
    key: (i: number) => Object.keys(m)[i] ?? null,
    removeItem: (k: string) => {
      delete m[k];
    },
    setItem: (k: string, v: string) => {
      m[k] = v;
    },
  };
}

describe('localPrefs', () => {
  let store: Storage;
  beforeEach(() => {
    store = memoryStorage();
  });

  it('returns defaults (all false) when nothing is stored', () => {
    expect(loadLocalPrefs(store)).toEqual({
      sidebarCollapsed: false,
      notesCollapsed: false,
      projectsCollapsed: false,
    });
  });

  it('round-trips a single pref via saveLocalPref', () => {
    saveLocalPref('sidebarCollapsed', true, store);
    expect(loadLocalPrefs(store)).toEqual({
      sidebarCollapsed: true,
      notesCollapsed: false,
      projectsCollapsed: false,
    });
  });

  it('ignores malformed JSON and falls back to default for that key', () => {
    store.setItem('tmap.pref.notesCollapsed', '{not json');
    const prefs = loadLocalPrefs(store);
    expect(prefs.notesCollapsed).toBe(false);
  });

  it('exposes exactly the three local-only keys', () => {
    expect([...LOCAL_PREF_KEYS].sort()).toEqual(
      ['notesCollapsed', 'projectsCollapsed', 'sidebarCollapsed'].sort(),
    );
  });

  it('is a no-op (no throw) when storage access throws', () => {
    const throwing: Storage = {
      ...memoryStorage(),
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(() => loadLocalPrefs(throwing)).not.toThrow();
    expect(() => saveLocalPref('sidebarCollapsed', true, throwing)).not.toThrow();
    const _vi = vi; // keep import used
  });
});
