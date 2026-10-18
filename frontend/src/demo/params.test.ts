import { describe, expect, it } from 'vitest';
import { parseDemoParams, parseThemeMessage } from './params';

describe('parseDemoParams', () => {
  it('defaults', () => {
    expect(parseDemoParams('')).toEqual({ theme: null, speed: 1, autoplay: true });
  });
  it('reads the embed contract', () => {
    expect(parseDemoParams('?theme=light&speed=4&autoplay=0')).toEqual({ theme: 'light', speed: 4, autoplay: false });
  });
  it('ignores unknown values', () => {
    expect(parseDemoParams('?theme=neon&speed=9&autoplay=yes')).toEqual({ theme: null, speed: 1, autoplay: true });
  });
});

describe('parseThemeMessage', () => {
  it('accepts the embed contract message', () => {
    expect(parseThemeMessage({ type: 'artus-demo:theme', theme: 'light' })).toBe('light');
    expect(parseThemeMessage({ type: 'artus-demo:theme', theme: 'dark' })).toBe('dark');
  });
  it('ignores everything else', () => {
    expect(parseThemeMessage({ type: 'artus-demo:theme', theme: 'neon' })).toBeNull();
    expect(parseThemeMessage({ type: 'other', theme: 'dark' })).toBeNull();
    expect(parseThemeMessage({ theme: 'dark' })).toBeNull();
    expect(parseThemeMessage('artus-demo:theme')).toBeNull();
    expect(parseThemeMessage(null)).toBeNull();
    expect(parseThemeMessage(undefined)).toBeNull();
  });
});
