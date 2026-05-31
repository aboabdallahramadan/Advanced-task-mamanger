export function workMinutes(workStartHour: number, workEndHour: number): number {
  return (workEndHour - workStartHour) * 60;
}

export function sumDurationMinutes(tasks: { durationMinutes?: number }[]): number {
  return tasks.reduce((sum, t) => sum + (t.durationMinutes || 0), 0);
}

export interface CapacityStatus {
  planned: number;
  capacity: number;
  remaining: number;
  over: boolean;
}

export function capacityStatus(
  tasks: { durationMinutes?: number }[],
  workStartHour: number,
  workEndHour: number,
): CapacityStatus {
  const planned = sumDurationMinutes(tasks);
  const capacity = workMinutes(workStartHour, workEndHour);
  return { planned, capacity, remaining: capacity - planned, over: planned > capacity };
}
