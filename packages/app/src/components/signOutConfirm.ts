// packages/app/src/components/signOutConfirm.ts
/** C10: an explicit sign-out must confirm only when unsynced work would be lost. */
export function shouldConfirmSignOut(pendingOps: number): boolean {
  return pendingOps > 0;
}

/** Warning copy for the confirm dialog when pendingOps > 0. */
export function signOutWarning(pendingOps: number): string {
  const noun = pendingOps === 1 ? 'unsynced change' : 'unsynced changes';
  return `You have ${pendingOps} ${noun} that haven’t reached the server yet. Signing out will erase your local data and discard them. Continue?`;
}

/**
 * Side-effecting steps an explicit sign-out drives, injected so the orchestration
 * order can be unit-tested in the node env (no React/Dexie needed).
 */
export interface SignOutSteps {
  /** Clear the global `tmap:lastUserId` pointer (explicit-logout-only, C8.3). */
  clearPointer: () => void;
  /**
   * Drive the auth store logout. This performs the best-effort network revoke and,
   * critically, runs the logout teardown (AppRoot.onLoggedOut → engine.stop() +
   * store.close()) so the SyncEngine's periodic timer + connectivity subscription
   * are torn down and the Dexie connection is closed.
   */
  logout: () => Promise<void>;
  /** Delete the per-user Dexie DB. Best-effort; sign-out proceeds regardless. */
  wipe: () => Promise<void>;
}

/**
 * C10/§7.2 sign-out order. The engine MUST be stopped and the store closed BEFORE
 * the local DB is wiped, otherwise a periodic tick or an `online` event between the
 * wipe and `engine.stop()` would call `syncNow()`, re-open the just-deleted Dexie DB,
 * and re-apply pulled server rows — resurrecting the data the user chose to erase
 * (Dexie.delete also blocks on open connections). So: (1) clear the pointer,
 * (2) `await logout()` (which tears down the engine + closes the store via
 * onLoggedOut), THEN (3) wipe the DB. The wipe is best-effort and never throws.
 */
export async function performSignOut(steps: SignOutSteps): Promise<void> {
  steps.clearPointer();
  await steps.logout();
  try {
    await steps.wipe();
  } catch {
    /* best-effort — sign-out has already completed (engine stopped, store closed) */
  }
}
