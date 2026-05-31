import { describe, it, expect } from 'vitest';
import { sessionMinutes } from './focusSession';

describe('sessionMinutes', () => {
    it('rounds elapsed ms to whole minutes', () => {
        expect(sessionMinutes(0, 120000)).toBe(2); // 2 min
        expect(sessionMinutes(0, 90000)).toBe(2); // 1.5 -> 2
    });
    it('returns 0 for sub-30-second sessions', () => {
        expect(sessionMinutes(0, 20000)).toBe(0); // 0.33 -> 0
    });
    it('never returns negative for inverted timestamps', () => {
        expect(sessionMinutes(120000, 0)).toBe(0);
    });
});
