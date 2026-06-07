import { describe, it, expect } from 'vitest';
import { workMinutes, sumDurationMinutes, capacityStatus } from './capacity';

describe('capacity', () => {
  it('workMinutes spans the work day', () => {
    expect(workMinutes(8, 20)).toBe(720);
  });
  it('sumDurationMinutes tolerates missing durations', () => {
    expect(sumDurationMinutes([{ durationMinutes: 30 }, {}, { durationMinutes: 15 }])).toBe(45);
  });
  it('capacityStatus reports remaining and over-capacity', () => {
    const under = capacityStatus([{ durationMinutes: 60 }], 8, 10); // cap 120
    expect(under).toEqual({ planned: 60, capacity: 120, remaining: 60, over: false });
    const over = capacityStatus([{ durationMinutes: 200 }], 8, 10);
    expect(over).toEqual({ planned: 200, capacity: 120, remaining: -80, over: true });
  });
});
