import { describe, expect, it } from 'vitest';
import { disallowedFor, enabledFrom } from './toolsModel';

describe('toolsModel', () => {
  it('leaves disallowedTools out when no built-in is enabled', () => {
    expect(disallowedFor(new Set())).toBeUndefined();
  });

  it('keeps the other defaults blocked when one built-in is enabled', () => {
    expect(disallowedFor(new Set(['WebSearch']))).toEqual(['WebFetch', 'NotebookEdit']);
  });

  it('sends the full default list when forced with nothing ticked', () => {
    expect(disallowedFor(new Set(), true)).toEqual(['WebSearch', 'WebFetch', 'NotebookEdit']);
  });

  it('sends an empty list when every built-in is enabled', () => {
    expect(disallowedFor(new Set(['WebSearch', 'WebFetch', 'NotebookEdit']))).toEqual([]);
  });

  it('leaves a list with non-built-in names to the backend merge', () => {
    expect(enabledFrom(['Write', 'WebFetch']).size).toBe(0);
  });

  it('reads the enabled set back from a stored list, and treats no list as the default', () => {
    expect([...enabledFrom(['WebFetch', 'NotebookEdit'])]).toEqual(['WebSearch']);
    expect(enabledFrom(undefined).size).toBe(0);
    expect(enabledFrom([]).size).toBe(3);
  });
});
