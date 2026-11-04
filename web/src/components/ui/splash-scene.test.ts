import { describe, it, expect } from 'vitest';
import { SUNRISE_EXIT_MS, sceneTransform, sunriseExit } from './splash-scene';

describe('sunriseExit', () => {
  it('starts with the orb and the city in place and fully visible', () => {
    expect(sunriseExit(0)).toEqual({ orbLift: 0, orbFade: 1, citySink: 0, cityFade: 1 });
  });

  it('has the orb gone off the top and the city sunk and faded when the exit ends', () => {
    const end = sunriseExit(SUNRISE_EXIT_MS);
    expect(end.orbFade).toBe(0);
    expect(end.cityFade).toBe(0);
    expect(end.orbLift).toBeGreaterThan(400);
    expect(end.citySink).toBe(110);
  });
});

describe('sceneTransform', () => {
  it('spans a phone screen edge to edge with the waterline at 81.5% of the height', () => {
    const { scale, offsetX, offsetY } = sceneTransform(390, 844);
    expect(scale).toBeCloseTo(1.5);
    expect(offsetX).toBeCloseTo(0);
    expect(424 * scale + offsetY).toBeCloseTo(844 * 0.815);
  });

  it('caps the scale on wide screens and centres the skyline', () => {
    const { scale, offsetX, view } = sceneTransform(1920, 1080);
    expect(scale).toBe(2.4);
    expect(offsetX).toBeCloseTo((1920 - 260 * 2.4) / 2);
    expect(view.left).toBeLessThan(0);
  });
});
