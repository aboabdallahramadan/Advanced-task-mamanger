import { describe, it, expect, vi } from 'vitest';
import { createConnectivity } from '../connectivity';

// A minimal fake of the two globals connectivity reads.
function fakeEnv(initialOnline: boolean) {
  const listeners: Record<string, Set<() => void>> = { online: new Set(), offline: new Set() };
  const nav = { onLine: initialOnline };
  const win = {
    addEventListener: (type: string, cb: () => void) => {
      (listeners[type] ??= new Set()).add(cb);
    },
    removeEventListener: (type: string, cb: () => void) => {
      listeners[type]?.delete(cb);
    },
  };
  function emit(type: 'online' | 'offline') {
    nav.onLine = type === 'online';
    for (const cb of listeners[type]) cb();
  }
  return { nav: nav as unknown as Navigator, win: win as unknown as Window, listeners, emit };
}

describe('createConnectivity', () => {
  it('reports navigator.onLine through online()', () => {
    const { nav, win } = fakeEnv(true);
    const c = createConnectivity({ nav, win });
    expect(c.online()).toBe(true);

    const { nav: nav2, win: win2 } = fakeEnv(false);
    const c2 = createConnectivity({ nav: nav2, win: win2 });
    expect(c2.online()).toBe(false);
  });

  it('fires the callback on online and offline events with the current state', () => {
    const env = fakeEnv(false);
    const c = createConnectivity({ nav: env.nav, win: env.win });
    const cb = vi.fn();
    c.subscribe(cb);

    env.emit('online');
    expect(cb).toHaveBeenLastCalledWith(true);
    env.emit('offline');
    expect(cb).toHaveBeenLastCalledWith(false);
    expect(cb).toHaveBeenCalledTimes(2);
  });

  it('unsubscribe removes both listeners (no further callbacks)', () => {
    const env = fakeEnv(true);
    const c = createConnectivity({ nav: env.nav, win: env.win });
    const cb = vi.fn();
    const unsub = c.subscribe(cb);
    unsub();
    env.emit('offline');
    env.emit('online');
    expect(cb).not.toHaveBeenCalled();
    expect(env.listeners.online.size).toBe(0);
    expect(env.listeners.offline.size).toBe(0);
  });

  it('is safe when navigator/window are absent (node): online() true, subscribe a no-op', () => {
    const c = createConnectivity({ nav: undefined, win: undefined });
    expect(c.online()).toBe(true); // assume online — only gates whether a cycle is attempted
    const cb = vi.fn();
    const unsub = c.subscribe(cb);
    expect(() => unsub()).not.toThrow();
    expect(cb).not.toHaveBeenCalled();
  });
});
