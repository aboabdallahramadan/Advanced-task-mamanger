// packages/app/src/components/__tests__/signOutConfirm.test.ts
import { describe, it, expect, vi } from 'vitest';
import { shouldConfirmSignOut, signOutWarning, performSignOut } from '../signOutConfirm';

describe('signOut confirm gating (C10)', () => {
  it('requires confirmation when there are pending ops', () => {
    expect(shouldConfirmSignOut(1)).toBe(true);
    expect(shouldConfirmSignOut(7)).toBe(true);
  });

  it('does not require confirmation when the queue is empty', () => {
    expect(shouldConfirmSignOut(0)).toBe(false);
  });

  it('warning copy names the count of unsynced changes that will be lost', () => {
    expect(signOutWarning(1)).toMatch(/1 unsynced change/);
    expect(signOutWarning(3)).toMatch(/3 unsynced changes/);
  });
});

describe('performSignOut order (R5-6 review — engine stop before wipe)', () => {
  it('wipes the local DB only AFTER logout teardown (engine.stop + store.close) completes', async () => {
    const calls: string[] = [];
    // logout teardown is async (it awaits a best-effort network revoke before
    // onLoggedOut runs engine.stop()); the wipe must not start until it resolves.
    let logoutSettled = false;
    await performSignOut({
      clearPointer: () => calls.push('clearPointer'),
      logout: async () => {
        calls.push('logout:start');
        await Promise.resolve();
        logoutSettled = true;
        calls.push('logout:done');
      },
      wipe: async () => {
        // If the engine were still running here the deleted DB could be re-opened.
        expect(logoutSettled).toBe(true);
        calls.push('wipe');
      },
    });
    expect(calls).toEqual(['clearPointer', 'logout:start', 'logout:done', 'wipe']);
  });

  it('clears the pointer before logout so the engine never re-pins the wiped user', async () => {
    const calls: string[] = [];
    await performSignOut({
      clearPointer: () => calls.push('clearPointer'),
      logout: async () => calls.push('logout'),
      wipe: async () => calls.push('wipe'),
    });
    expect(calls.indexOf('clearPointer')).toBeLessThan(calls.indexOf('logout'));
  });

  it('a failing wipe does not throw — sign-out has already torn down the engine', async () => {
    const logout = vi.fn(async () => {});
    await expect(
      performSignOut({
        clearPointer: () => {},
        logout,
        wipe: async () => {
          throw new Error('Dexie.delete blocked');
        },
      }),
    ).resolves.toBeUndefined();
    expect(logout).toHaveBeenCalledOnce();
  });
});
