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
