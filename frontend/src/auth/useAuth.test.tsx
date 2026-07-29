import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { useAuth } from './useAuth';

function Probe() {
  useAuth();
  return null;
}

describe('useAuth', () => {
  it('throws when used outside <AuthProvider>', () => {
    const previous = console.error;
    console.error = () => {};
    try {
      expect(() => render(<Probe />)).toThrow(/AuthProvider/);
    } finally {
      console.error = previous;
    }
  });
});
