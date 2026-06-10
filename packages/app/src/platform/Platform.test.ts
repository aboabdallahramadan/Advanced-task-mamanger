import { describe, it, expect } from 'vitest';
import type {
  Platform,
  AppChannel,
  FocusWidgetState,
  PlatformCapabilities,
} from './Platform';
import type { AuthTokenResponse } from '../auth/types';

describe('Platform contract', () => {
  it('a minimal stub satisfies the Platform interface (web-shaped: no focusWidget/autoLaunch)', () => {
    const caps: PlatformCapabilities = {
      tray: false,
      focusWidgetWindow: false,
      autoLaunch: false,
      dataPort: false,
    };
    const stub: Platform = {
      capabilities: caps,
      auth: {
        refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => null,
        clear: async () => {},
      },
      notify: () => {},
      on: () => {},
      off: () => {},
    };
    expect(stub.capabilities.tray).toBe(false);
    expect(stub.focusWidget).toBeUndefined();
    expect(stub.autoLaunch).toBeUndefined();
  });

  it('the AppChannel union includes the five known channels', () => {
    const channels: AppChannel[] = [
      'navigate',
      'focus:togglePlayPause',
      'focus:stop',
      'focus:done',
      'focus:resyncWidget',
    ];
    expect(channels).toHaveLength(5);
  });

  it('FocusWidgetState carries the timer fields the widget needs', () => {
    const s: FocusWidgetState = {
      taskTitle: 'Write spec',
      isPlaying: true,
      sessionStartTime: Date.now(),
      accumulatedMinutes: 12,
      plannedMinutes: 30,
      canComplete: true,
    };
    expect(s.taskTitle).toBe('Write spec');
  });
});
