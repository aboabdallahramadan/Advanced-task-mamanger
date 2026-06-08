/** Round an elapsed interval (epoch ms) to whole minutes, never negative. */
export function sessionMinutes(startMs: number, endMs: number): number {
  return Math.max(0, Math.round((endMs - startMs) / 60000));
}
