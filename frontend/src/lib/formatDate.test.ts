import { describe, expect, it } from 'vitest';
import { formatAbsolute, formatRelative } from './formatDate';

describe('formatRelative', () => {
  const now = new Date('2026-07-02T12:00:00Z');

  it('rounds down to seconds when the delta is small', () => {
    const then = new Date('2026-07-02T11:59:55Z').toISOString();
    expect(formatRelative(then, now)).toMatch(/second/);
  });

  it('picks the largest fitting unit for older timestamps', () => {
    expect(formatRelative(new Date('2025-07-02T12:00:00Z').toISOString(), now)).toMatch(/year/);
    expect(formatRelative(new Date('2026-06-01T12:00:00Z').toISOString(), now)).toMatch(/month/);
    expect(formatRelative(new Date('2026-06-15T12:00:00Z').toISOString(), now)).toMatch(/week/);
    expect(formatRelative(new Date('2026-07-01T12:00:00Z').toISOString(), now)).toMatch(
      /day|yesterday/,
    );
    expect(formatRelative(new Date('2026-07-02T09:00:00Z').toISOString(), now)).toMatch(/hour/);
    expect(formatRelative(new Date('2026-07-02T11:50:00Z').toISOString(), now)).toMatch(/minute/);
  });
});

describe('formatAbsolute', () => {
  it('returns a non-empty formatted string', () => {
    const output = formatAbsolute('2026-07-02T12:00:00Z');
    expect(output.length).toBeGreaterThan(0);
  });
});
