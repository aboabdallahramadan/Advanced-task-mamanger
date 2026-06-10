import { describe, it, expect } from 'vitest';
import { splitSyncedSettings, applyLoadedSettings, type SyncedSettings } from './store';

describe('settings split', () => {
  it('splitSyncedSettings stringifies the three numeric synced values and carries timeZoneId top-level', () => {
    const { settings, timeZoneId } = splitSyncedSettings({
      workStartHour: 8,
      workEndHour: 20,
      timeIncrement: 15,
      timeZoneId: 'Europe/Berlin',
    });
    expect(settings).toEqual({
      workStartHour: '8',
      workEndHour: '20',
      timeIncrement: '15',
    });
    expect(timeZoneId).toBe('Europe/Berlin');
  });

  it('applyLoadedSettings parses string→number for the three synced values', () => {
    const applied = applyLoadedSettings(
      { workStartHour: '9', workEndHour: '17', timeIncrement: '30' },
      'America/New_York',
    );
    expect(applied).toEqual({
      workStartHour: 9,
      workEndHour: 17,
      timeIncrement: 30,
      timeZoneId: 'America/New_York',
    });
  });

  it('applyLoadedSettings ignores non-numeric / missing values (keeps undefined for the caller to default)', () => {
    const applied = applyLoadedSettings({ workStartHour: 'oops' }, 'UTC');
    expect(applied.workStartHour).toBeUndefined();
    expect(applied.timeZoneId).toBe('UTC');
  });
});
