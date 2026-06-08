/**
 * Client mirror of the server's lexicographic rank scheme
 * (backend Tmap.Api.Common.Ranking). Keys are plain lowercase ASCII strings
 * compared with the default string ordering. A move/insert produces a single
 * key that sorts strictly between its neighbors so reorder touches one row.
 *
 * Contract (must match the server byte-for-byte for interleaving to work):
 *  - empty container seed: "n"
 *  - rankAfter(prev): increment the last char, or append "n" when it is 'z'
 *  - rankBetween(prev, next): pad prev to next.length with 'a', walk from the
 *    right for the first index where next[i] - paddedPrev[i] > 1, return
 *    prefix + midChar; otherwise fall back to rankAfter(prev).
 */

const SEED = 'n'; // midpoint of 'a'..'z'

/** Returns a rank that sorts strictly after `prev` (seed when prev is empty). */
export function rankAfter(prev: string | null): string {
  if (!prev) {
    return SEED;
  }
  const last = prev.charCodeAt(prev.length - 1);
  if (last < 'z'.charCodeAt(0)) {
    return prev.slice(0, -1) + String.fromCharCode(last + 1);
  }
  return prev + SEED;
}

/** Returns a rank that sorts strictly between `prev` and `next`. */
export function rankBetween(prev: string | null, next: string | null): string {
  if (!next) {
    return rankAfter(prev);
  }

  const p = prev ?? '';
  // Pad prev to next's length with 'a' (lowest visible char).
  const padded = p.length >= next.length ? p : p + 'a'.repeat(next.length - p.length);

  for (let i = next.length - 1; i >= 0; i--) {
    const pChar = i < padded.length ? padded.charCodeAt(i) : 'a'.charCodeAt(0);
    const nChar = next.charCodeAt(i);
    if (nChar - pChar > 1) {
      const mid = Math.floor((pChar + nChar) / 2);
      return padded.slice(0, i) + String.fromCharCode(mid);
    }
  }

  // No gap found at any position — start a new char level after prev.
  // rankAfter(prev) can equal `next` for tight neighbors (e.g. prev='b',
  // next='c'), so prefer appending the seed to guarantee prev < key < next
  // ('b' < 'bn' < 'c'); only use rankAfter when it stays strictly below next.
  const after = rankAfter(prev);
  if (after < next) {
    return after;
  }
  return (prev ?? '') + SEED;
}
