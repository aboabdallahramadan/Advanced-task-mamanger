import { describe, it, expect } from 'vitest';
import { rankBetween } from './ranking';

// Mirrors backend Tmap.Api.Common.Ranking. Keys are plain lowercase strings
// compared with default JS string `<`. Seed is "n"; append increments last char.
describe('rankBetween', () => {
  it('returns the seed "n" for an empty container (no prev, no next)', () => {
    expect(rankBetween(null, null)).toBe('n');
  });

  it('appends after prev when next is null (increment last char)', () => {
    expect(rankBetween('n', null)).toBe('o');
    expect(rankBetween('a', null)).toBe('b');
  });

  it('appends a new level when prev ends in z and next is null', () => {
    expect(rankBetween('z', null)).toBe('zn');
    expect(rankBetween('az', null)).toBe('azn');
  });

  it('prepends before next when prev is null (midpoint with padded "a")', () => {
    // prev padded to "a", next "n": gap a..n > 1 -> mid of 'a'(97) and 'n'(110) = 103 'g'
    expect(rankBetween(null, 'n')).toBe('g');
  });

  it('returns a key strictly between two adjacent-but-gapped keys', () => {
    const mid = rankBetween('a', 'c'); // gap a..c > 1 -> 'b'
    expect(mid).toBe('b');
    expect('a' < mid && mid < 'c').toBe(true);
  });

  it('extends to a new char level when neighbors are tight (b,c -> "bn")', () => {
    const mid = rankBetween('b', 'c'); // no gap at last index -> RankAfter('b') = 'c'? no: fallback
    expect('b' < mid && mid < 'c').toBe(true);
  });

  it('finds a midpoint between multi-char ranks', () => {
    const mid = rankBetween('aa', 'ac'); // index1: a..c gap>1 -> 'ab'
    expect(mid).toBe('ab');
    expect('aa' < mid && mid < 'ac').toBe(true);
  });

  it('is always strictly increasing for a sequence of appends', () => {
    let prev: string | null = null;
    const keys: string[] = [];
    for (let i = 0; i < 50; i++) {
      const k = rankBetween(prev, null);
      keys.push(k);
      prev = k;
    }
    const sorted = [...keys].sort();
    expect(keys).toEqual(sorted);
  });

  it('produces strictly-between keys when repeatedly inserting in the same gap', () => {
    let lo = 'a';
    const hi = 'z';
    for (let i = 0; i < 20; i++) {
      const mid = rankBetween(lo, hi);
      expect(lo < mid && mid < hi).toBe(true);
      lo = mid;
    }
  });
});
