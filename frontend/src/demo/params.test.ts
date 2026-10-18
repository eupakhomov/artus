import { describe, expect, it } from 'vitest';
import { parseDemoParams } from './params';

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
