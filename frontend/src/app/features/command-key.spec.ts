import { describe, expect, it } from 'vitest';
import { CommandKey } from './command-key';

describe('CommandKey', () => {
  it('reuses the key for an identical payload (double submit)', () => {
    const k = new CommandKey();
    expect(k.for({ a: 1 })).toBe(k.for({ a: 1 }));
  });
  it('issues a new key when the payload changes', () => {
    const k = new CommandKey();
    const first = k.for({ a: 1 });
    expect(k.for({ a: 2 })).not.toBe(first);
  });
  it('issues a new key after reset', () => {
    const k = new CommandKey();
    const first = k.for({ a: 1 });
    k.reset();
    expect(k.for({ a: 1 })).not.toBe(first);
  });
});
