import { describe, expect, it } from 'vitest';
import { formatDateTime, formatQty, humanize, quantityError } from './format';

describe('formatQty', () => {
  it('formats decimals strictly to scale without silent rounding', () => {
    expect(formatQty(12.5, 2)).toBe('12.50');
    expect(formatQty(12.5, 0)).toBe('13');
    expect(formatQty(0, 3)).toBe('0.000');
  });

  it('prefixes positive deltas when signed=true', () => {
    expect(formatQty(5, 0, true)).toBe('+5');
    expect(formatQty(-5, 0, true)).toBe('-5');
    expect(formatQty(0, 0, true)).toBe('0');
  });

  it('handles null/undefined gracefully', () => {
    expect(formatQty(null, 2)).toBe('');
    expect(formatQty(undefined, 2)).toBe('');
  });
});

describe('quantityError', () => {
  it('rejects empty input', () => {
    expect(quantityError('', 2)).toBe('Quantity is required');
    expect(quantityError(null, 2)).toBe('Quantity is required');
  });

  it('rejects non-numeric characters', () => {
    expect(quantityError('12a', 2)).toBe('Enter a number without signs or separators');
    expect(quantityError('-5', 2)).toBe('Enter a number without signs or separators');
  });

  it('enforces UOM scale', () => {
    expect(quantityError('1.5', 0)).toBe('This UOM allows whole numbers only');
    expect(quantityError('1.123', 2)).toBe('Maximum 2 decimal places for this UOM');
    expect(quantityError('1.12', 2)).toBeNull();
    expect(quantityError('1', 0)).toBeNull();
  });

  it('enforces positive unless allowZero is true', () => {
    expect(quantityError('0', 2)).toBe('Quantity must be greater than 0');
    expect(quantityError('0', 2, true)).toBeNull();
  });
});

describe('humanize', () => {
  it('converts SNAKE_CASE to Title case', () => {
    expect(humanize('GOODS_RECEIPT')).toBe('Goods receipt');
    expect(humanize('PARTIALLY_RECEIVED')).toBe('Partially received');
  });
});
