import { creativityLabel, energyLabel, estimateLabel } from './labels';

describe('GTD labels', () => {
  it('formats estimates in minutes, whole hours and hours with minutes', () => {
    expect(estimateLabel(5)).toBe('5 min');
    expect(estimateLabel(59)).toBe('59 min');
    expect(estimateLabel(60)).toBe('1 h');
    expect(estimateLabel(61)).toBe('1 h 1 min');
    expect(estimateLabel(120)).toBe('2 h');
    expect(estimateLabel(0)).toBe('0 min');
  });

  it('labels energy and creativity levels', () => {
    expect(energyLabel('LOW')).toBe('Low');
    expect(energyLabel('MEDIUM')).toBe('Medium');
    expect(creativityLabel('ROUTINE')).toBe('Routine');
    expect(creativityLabel('CREATIVE')).toBe('Creative');
  });
});
