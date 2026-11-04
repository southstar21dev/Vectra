export type VehicleVariant = 'primary' | 'secondary' | 'approximate';

/** Meaning colours: the tracked line, other lines on the map, estimated positions. */
const VARIANT_COLOR: Record<VehicleVariant, string> = {
  primary: '#10b981',
  secondary: '#8b5cf6',
  approximate: '#ec4899',
};

/** Teardrop in a 44×44 box: the round body is centred on (22,22), the vehicle's
 *  position and rotation pivot, and the tip points along the heading. */
const TEARDROP_PATH = 'M22 7.5C22 7.5 31.5 17 31.5 22A9.5 9.5 0 0 1 12.5 22C12.5 17 22 7.5 22 7.5Z';

/** Static by design: the marker gliding along the route is the only motion. */
function teardropHtml(color: string): string {
  return (
    '<div class="relative flex h-11 w-11 items-center justify-center pointer-events-none">' +
    '<div data-vehicle-rotor class="absolute inset-0 flex items-center justify-center transition-transform duration-300 ease-out" style="transform-origin: center;">' +
    '<svg width="44" height="44" viewBox="0 0 44 44" class="overflow-visible drop-shadow-[0_1px_3px_rgba(0,0,0,0.45)]" aria-hidden="true">' +
    `<path d="${TEARDROP_PATH}" fill="${color}" stroke="#ffffff" stroke-width="2" stroke-linejoin="round" />` +
    '<circle cx="22" cy="22" r="3" fill="#ffffff" fill-opacity="0.85" />' +
    '</svg>' +
    '</div>' +
    '</div>'
  );
}

export function vehicleMarkerHtml(variant: VehicleVariant): string {
  return teardropHtml(VARIANT_COLOR[variant]);
}

export function coloredVehicleHtml(color: string): string {
  return teardropHtml(color.startsWith('#') ? color : `#${color}`);
}

/** Live vehicle teardrop in its meaning colour, pointing along its heading. */
export function createVehicleMarkerElement(variant: VehicleVariant): HTMLElement {
  const el = document.createElement('div');
  el.className = 'route-vehicle-marker';
  el.innerHTML = vehicleMarkerHtml(variant);
  return el;
}

/** Live vehicle teardrop in the route colour — used by the trip/route preview map. */
export function createColoredVehicleElement(color: string): HTMLElement {
  const el = document.createElement('div');
  el.className = 'route-vehicle-marker';
  el.innerHTML = coloredVehicleHtml(color);
  return el;
}


