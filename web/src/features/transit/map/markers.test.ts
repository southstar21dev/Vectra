import { describe, expect, it } from 'vitest';
import {
  vehicleMarkerHtml,
  coloredVehicleHtml,
} from './markers';

describe('vehicleMarkerHtml', () => {
  it('creates a 44px container with rotor and a static teardrop SVG', () => {
    const html = vehicleMarkerHtml('primary');
    expect(html).toContain('h-11 w-11');
    expect(html).toContain('data-vehicle-rotor');
    expect(html).toContain('viewBox="0 0 44 44"');
    // Teardrop body centred on the 22,22 pivot, tip pointing up (heading 0°)
    expect(html).toContain('d="M22 7.5C22 7.5 31.5 17 31.5 22A9.5 9.5 0 0 1 12.5 22C12.5 17 22 7.5 22 7.5Z"');
    // Primary emerald fill
    expect(html).toContain('fill="#10b981"');
    // No pulsing halo
    expect(html).not.toContain('beacon');
    expect(html).not.toContain('animate');
  });

  it('renders secondary and approximate variants with correct colors', () => {
    const sec = vehicleMarkerHtml('secondary');
    expect(sec).toContain('fill="#8b5cf6"');

    const approx = vehicleMarkerHtml('approximate');
    expect(approx).toContain('fill="#ec4899"');
  });
});

describe('coloredVehicleHtml', () => {
  it('creates a 44px container using the provided route hex color', () => {
    const html = coloredVehicleHtml('009fe3');
    expect(html).toContain('h-11 w-11');
    expect(html).toContain('data-vehicle-rotor');
    expect(html).toContain('fill="#009fe3"');
  });

  it('handles hex colors that already include the leading #', () => {
    const html = coloredVehicleHtml('#f7c600');
    expect(html).toContain('fill="#f7c600"');
  });
});
