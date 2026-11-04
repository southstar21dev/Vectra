/** A route as reported by the Futár API (references.routes). */
export interface FutarRoute {
  id: string;
  shortName: string;
  description: string;
  /** Some API forks return the human name under longName instead of description. */
  longName?: string;
  type: string; // SUBWAY | TRAM | BUS | TROLLEYBUS | RAIL | FERRY | ...
  color: string;
  textColor: string;
}

/** A physical stop as reported by the Futár API. */
export interface FutarStop {
  id: string;
  lat: number;
  lon: number;
  name: string;
  code?: string;
  /** Station-level id (BKK_CS…) when this stop is a platform. */
  parentStationId?: string;
}

/** Localized text block — the API returns hu/en translations plus a default. */
export interface FutarTranslations {
  translations?: Record<string, string>;
  someTranslation?: string;
}

/** A disruption/delay alert (OTP `references.alerts` entry, this fork's shape). */
export interface FutarAlert {
  id: string;
  /** Validity window, epoch SECONDS (open-ended when `end` is absent). */
  start?: number;
  end?: number;
  header?: FutarTranslations;
  description?: FutarTranslations;
  url?: FutarTranslations;
  effectType?: string;
  priority?: string | number;
  routeIds?: string[];
  stopIds?: string[];
  vehicleRouteTypes?: string[];
  /** Rich per-route attachment list this fork returns (routeId + per-route effectType). */
  routes?: Array<{ routeId?: string; effectType?: string }>;
}

/** A normalized alert after parsing (translated text, active-window filtered). */
export interface TransitAlert {
  id: string;
  header: string;
  description: string;
  url?: string;
  effectType?: string;
  /** Which routes/stops this alert is attached to (empty = agency-wide). */
  routeIds?: string[];
  stopIds?: string[];
}

/** One stopTime entry (a single vehicle visit). */
export interface FutarStopTime {
  stopId: string;
  stopHeadsign?: string;
  arrivalTime?: number; // epoch seconds, scheduled
  departureTime?: number;
  predictedArrivalTime?: number; // epoch seconds, live
  predictedDepartureTime?: number;
  stopSequence?: number;
  tripId: string;
  canceled?: boolean;
}

/** Raw shape of arrivals-and-departures-for-stop.json. */
export interface FutarArrivalsResponse {
  currentTime: number; // epoch ms
  status?: string;
  data?: {
    entry?: {
      stopId: string;
      stopTimes: FutarStopTime[];
      /** Alert ids that affect this stop's arrivals (keys into references.alerts). */
      alertIds?: string[];
    };
    references?: {
      routes?: Record<string, FutarRoute>;
      trips?: Record<string, { routeId?: string; tripHeadsign?: string }>;
      alerts?: Record<string, FutarAlert>;
      stops?: Record<string, FutarStop>;
    };
  };
}

/** A live vehicle on a route (vehicles-for-route.json `data.list` entry). */
export interface FutarVehicle {
  vehicleId: string;
  tripId?: string;
  routeId: string;
  bearing?: number;
  location: { lat: number; lon: number };
  lastUpdateTime?: number;
  status?: string;
}

/** Raw shape of vehicles-for-route.json. */
export interface FutarVehiclesForRouteResponse {
  currentTime?: number;
  status?: string;
  data?: {
    list?: FutarVehicle[];
    references?: {
      trips?: Record<string, { tripHeadsign?: string; directionId?: string; routeId?: string }>;
    };
  };
}

/** One scheduled stop on a trip (trip-details.json `entry.stopTimes`). */
export interface FutarTripStopTime {
  stopId: string;
  stopHeadsign?: string;
  stopSequence?: number;
  /** Scheduled times, epoch seconds. A terminus carries only `arrivalTime`. */
  arrivalTime?: number;
  departureTime?: number;
  /** Live (includeStatus) estimates, epoch seconds — absent when not tracked. */
  predictedArrivalTime?: number;
  predictedDepartureTime?: number;
  shapeDistTraveled?: number;
}

/** Raw shape of trip-details.json (subset we use). */
export interface FutarTripDetailsResponse {
  currentTime?: number;
  status?: string;
  data?: {
    entry?: {
      tripId?: string;
      vehicle?: FutarVehicle;
      polyline?: { points?: string; length?: number };
      stopTimes?: FutarTripStopTime[];
    };
    references?: {
      stops?: Record<string, FutarStop>;
    };
  };
}

/** One direction variant of a route (route-details.json `entry.variants`). */
export interface FutarRouteVariant {
  /** Source member route id when variants from combined lines (4/6, 28/28A,
   *  37/37A) are merged into one response. */
  routeId?: string;
  name?: string;
  headsign?: string;
  /** Ordered stop ids along this direction. */
  stopIds?: string[];
  polyline?: { points?: string; length?: number };
}

/** Raw shape of route-details.json (subset we use). */
export interface FutarRouteDetailsResponse {
  currentTime?: number;
  status?: string;
  data?: {
    entry?: {
      id?: string;
      variants?: FutarRouteVariant[];
    };
    references?: {
      stops?: Record<string, FutarStop>;
      routes?: Record<string, FutarRoute>;
    };
  };
}

/** A normalized, route-filtered arrival. */
export interface Arrival {
  arrivalMs: number; // epoch ms
  headSign: string;
  tripHeadsign?: string;
  isPredicted: boolean;
  tripId: string; // the trip whose vehicle is arriving — links to vehicles-for-route
  routeId?: string;
  routeShortName?: string;
  /** Physical stop (platform) this departure leaves from, resolved from
   *  `references.stops[stopTimes[].stopId]`. A station can have several
   *  same-named physical stops (e.g. Blaha Lujza tér 4/6 vs 28A/37A/62). */
  platformStopId?: string;
  platformLat?: number;
  platformLon?: number;
}

/** One stop configured within a multi-stop route tracker. */
export interface TrackerStop {
  stop_id: string;
  stop_name: string;
  /** Direction/terminus filter for this specific stop ('' or undefined = all directions). */
  headsign?: string;
}

export interface NewTrackerInput {
  route_id: string;
  route_short_name: string;
  route_long_name: string;
  route_type: string;
  route_color: string;
  route_text_color: string;
  stops?: TrackerStop[];
  /** Legacy optional single-stop fields for compatibility */
  stop_id?: string;
  stop_name?: string;
  headsign?: string;
}

export interface UpdateTrackerStopsInput {
  id: string;
  stops: TrackerStop[];
}

