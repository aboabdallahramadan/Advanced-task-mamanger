// packages/app/src/components/__tests__/signOutConfirm.test.ts
import { describe, it, expect } from 'vitest';
import { shouldConfirmSignOut, signOutWarning } from '../signOutConfirm';

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
