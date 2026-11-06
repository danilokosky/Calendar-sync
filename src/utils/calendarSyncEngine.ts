export interface CalendarSource {
  id: string;
  name: string;
  account: string;
  color: string;
  enabled: boolean;
}

export interface CalendarEventItem {
  id: string;
  calendarId: string;
  title: string;
  location: string;
  startEpochMs: number;
  endEpochMs: number;
  isTask?: boolean;
}

export type SyncHorizonDays = 2 | 3 | 5 | 7;
export type ProgressWindowMinutes = 30 | 60 | 120 | 180;
export type SyncIntervalMinutes = 15 | 30 | 60;
export type PhoneWeatherCheckIntervalMinutes = 30 | 60 | 120 | 180;
export type ContextWindowMode = 'DYNAMIC' | 'EVENTS' | 'WEATHER' | 'STEPS';

export interface WeatherTriggerAppItem {
  packageName: string;
  displayName: string;
  categorySubtitle: string;
}

export const WEATHER_TRIGGER_CATALOG: WeatherTriggerAppItem[] = [
  {
    packageName: 'com.google.android.googlequicksearchbox',
    displayName: 'App Google',
    categorySubtitle: 'Alertas de Clima e Previsão do Google',
  },
  {
    packageName: 'com.google.android.apps.weather',
    displayName: 'Google Weather',
    categorySubtitle: 'App Clima oficial do Google / Pixel / Wear',
  },
  {
    packageName: 'com.sec.android.daemonapp',
    displayName: 'App Clima (Samsung)',
    categorySubtitle: 'Clima nativo One UI / Galaxy Watch',
  },
  {
    packageName: 'br.com.climatempo.meteorologia',
    displayName: 'Climatempo',
    categorySubtitle: 'Previsão do Tempo e Alertas de Chuva',
  },
  {
    packageName: 'com.accuweather.android',
    displayName: 'AccuWeather',
    categorySubtitle: 'Alertas Meteorológicos e Radar',
  },
  {
    packageName: 'com.weather.Weather',
    displayName: 'The Weather Channel',
    categorySubtitle: 'Radar e Mudanças Bruscas de Clima',
  },
  {
    packageName: 'com.windyty.android',
    displayName: 'Windy',
    categorySubtitle: 'Mapas de Vento, Frente Fria e Tempestade',
  },
];

export interface SyncConfiguration {
  horizonDays: SyncHorizonDays;
  progressWindowMinutes: ProgressWindowMinutes;
  syncIntervalMinutes: SyncIntervalMinutes;
  autoSyncOnCalendarChange: boolean;
  contextWindowMode: ContextWindowMode;
  highFreq15MinRule: boolean;
  updateOnScreenWake: boolean;
  phoneWeatherAutoCheckEnabled?: boolean;
  phoneWeatherCheckIntervalMinutes?: PhoneWeatherCheckIntervalMinutes;
  selectedWeatherTriggerApps?: string[];
  watchStandaloneWeatherEnabled?: boolean;
}

export interface WatchCacheState {
  cachedEvents: CalendarEventItem[];
  lastSyncEpochMs: number;
  syncedHorizonDays: SyncHorizonDays;
  progressWindowMinutes: ProgressWindowMinutes;
  isBluetoothConnected: boolean;
  contextWindowMode?: ContextWindowMode;
  highFreq15MinRule?: boolean;
  updateOnScreenWake?: boolean;
  hasActiveWatchWeatherAlert?: boolean;
  watchStandaloneWeatherEnabled?: boolean;
}

export interface ComplicationProgressState {
  hasUpcomingEvent: boolean;
  nextEvent: CalendarEventItem | null;
  upcomingThree: CalendarEventItem[];
  remainingMs: number;
  formattedCountdown: string;
  formattedStartTime: string;
  progressRatio: number; // 0.0 to 1.0
  isWithinProgressWindow: boolean;
  isWithinTurbo15MinWindow: boolean;
  effectiveContextMode: 'EVENTS' | 'WEATHER' | 'STEPS';
  isOngoing: boolean;
  autonomousCacheValidUntilMs: number;
  isOperatingAutonomously: boolean;
}

/**
 * Avalia se a previsão horária buscada periodicamente pelo nosso App (no telefone ou no serviço
 * Stand-Alone do relógio) apresenta mudança relevante de temperatura ou chuva nas próximas horas.
 */
export function detectHourlyWeatherShift(
  temps: number[],
  rainProbPct: number,
  previousFirstTemp?: number | null
): { hasShift: boolean; summary: string } {
  if (temps.length < 2) {
    return { hasShift: false, summary: 'Estável' };
  }
  const firstT = temps[0];
  const lastT = temps[temps.length - 1];
  const minT = Math.min(...temps);
  const maxT = Math.max(...temps);
  const swingInUpcomingHours = maxT - minT >= 4;
  const deltaFromPreviousCheck =
    previousFirstTemp != null && Math.abs(firstT - previousFirstTemp) >= 3;
  const highRain = rainProbPct >= 55;

  const hasShift = swingInUpcomingHours || deltaFromPreviousCheck || highRain;
  const summary =
    lastT < firstT
      ? `Queda ${firstT}°→${minT}°C (${rainProbPct}% chuva)`
      : lastT > firstT
      ? `Subida ${firstT}°→${maxT}°C (${rainProbPct}% chuva)`
      : `Variação ${minT}°..${maxT}°C (${rainProbPct}% chuva)`;

  return { hasShift, summary };
}

export function resolveEffectiveWatchContextMode(
  configuredMode: ContextWindowMode,
  hasUpcomingEvent: boolean,
  remainingMs: number,
  hasActiveWatchWeatherAlert: boolean
): 'EVENTS' | 'WEATHER' | 'STEPS' {
  if (configuredMode !== 'DYNAMIC') {
    return configuredMode;
  }
  const fifteenMinMs = 15 * 60 * 1000;
  if (hasUpcomingEvent && remainingMs > 0 && remainingMs <= fifteenMinMs) {
    return 'EVENTS';
  }
  if (hasActiveWatchWeatherAlert) {
    return 'WEATHER';
  }
  return hasUpcomingEvent ? 'EVENTS' : 'WEATHER';
}

/**
 * Filters phone events according to enabled calendars and the configured horizon window
 * starting from `syncTimeMs` up to `syncTimeMs + horizonDays * 86400_000`.
 */
export function buildSyncPayload(
  allPhoneEvents: CalendarEventItem[],
  calendars: CalendarSource[],
  syncTimeMs: number,
  horizonDays: SyncHorizonDays
): CalendarEventItem[] {
  const enabledCalIds = new Set(
    calendars.filter((c) => c.enabled).map((c) => c.id)
  );
  const maxTimeMs = syncTimeMs + horizonDays * 24 * 60 * 60 * 1000;

  return allPhoneEvents
    .filter((ev) => {
      if (!enabledCalIds.has(ev.calendarId)) return false;
      // Include events that haven't ended yet and start within the horizon window
      return ev.endEpochMs > syncTimeMs && ev.startEpochMs <= maxTimeMs;
    })
    .sort((a, b) => a.startEpochMs - b.startEpochMs);
}

/**
 * Evaluates the watch's local Room cache autonomously at any `currentWatchTimeMs`
 * (even when Bluetooth / Data Layer is disconnected for days).
 * Always returns the next 3 upcoming events, the countdown timer to the primary event,
 * and the RangedValue progress bar calculation based on `progressWindowMinutes` (e.g., 1h or 2h before).
 */
export function evaluateWatchAutonomousState(
  cache: WatchCacheState,
  currentWatchTimeMs: number
): ComplicationProgressState {
  // Filter cached events that are still in the future (or currently ongoing but primarily upcoming)
  // Wait: if an event hasn't started yet (startEpochMs > currentWatchTimeMs), it's upcoming for countdown.
  // What if an event has already started or we want upcoming events?
  // The user states: "apresentar os próximos três eventos, sempre os próximos três eventos, em uma janela grande e geralmente, se possível em cima, o tempo restante que falta para que esse evento comece."
  // Let's filter events whose startEpochMs > currentWatchTimeMs so countdown is always towards the next event start,
  // or if none start in the future, check ongoing. Keeping startEpochMs > currentWatchTimeMs guarantees "próximo evento vai começar e a contagem regressiva do próximo evento".
  const futureEvents = cache.cachedEvents
    .filter((ev) => ev.startEpochMs > currentWatchTimeMs)
    .sort((a, b) => a.startEpochMs - b.startEpochMs);

  const upcomingThree = futureEvents.slice(0, 3);
  const nextEvent = upcomingThree[0] ?? null;

  const autonomousCacheValidUntilMs =
    cache.lastSyncEpochMs + cache.syncedHorizonDays * 24 * 60 * 60 * 1000;

  const configuredMode: ContextWindowMode = cache.contextWindowMode ?? 'DYNAMIC';
  const highFreq15MinRule = cache.highFreq15MinRule ?? true;
  const hasWeatherAlert = cache.hasActiveWatchWeatherAlert ?? false;

  if (!nextEvent) {
    return {
      hasUpcomingEvent: false,
      nextEvent: null,
      upcomingThree: [],
      remainingMs: 0,
      formattedCountdown: 'Sem eventos',
      formattedStartTime: '--:--',
      progressRatio: 0,
      isWithinProgressWindow: false,
      isWithinTurbo15MinWindow: false,
      effectiveContextMode: resolveEffectiveWatchContextMode(
        configuredMode,
        false,
        0,
        hasWeatherAlert
      ),
      isOngoing: false,
      autonomousCacheValidUntilMs,
      isOperatingAutonomously: !cache.isBluetoothConnected,
    };
  }

  const remainingMs = Math.max(0, nextEvent.startEpochMs - currentWatchTimeMs);
  const windowMs = cache.progressWindowMinutes * 60 * 1000;
  const fifteenMinMs = 15 * 60 * 1000;

  // Progress bar calculation:
  // If remainingMs > windowMs, progress is 0 (or minimal lead-in) and isWithinProgressWindow = false.
  // Once remainingMs <= windowMs, progress ramps linearly from 0.0 (at windowMs before start) to 1.0 (at event start).
  let progressRatio = 0;
  let isWithinProgressWindow = false;

  if (remainingMs <= windowMs) {
    isWithinProgressWindow = true;
    progressRatio = Math.min(1, Math.max(0, 1 - remainingMs / windowMs));
  }

  const isWithinTurbo15MinWindow =
    highFreq15MinRule && remainingMs > 0 && remainingMs <= fifteenMinMs;

  return {
    hasUpcomingEvent: true,
    nextEvent,
    upcomingThree,
    remainingMs,
    formattedCountdown: formatCountdownText(remainingMs),
    formattedStartTime: formatTimeHHMM(nextEvent.startEpochMs),
    progressRatio: Number(progressRatio.toFixed(4)),
    isWithinProgressWindow,
    isWithinTurbo15MinWindow,
    effectiveContextMode: resolveEffectiveWatchContextMode(
      configuredMode,
      true,
      remainingMs,
      hasWeatherAlert
    ),
    isOngoing: false,
    autonomousCacheValidUntilMs,
    isOperatingAutonomously: !cache.isBluetoothConnected,
  };
}

export function formatCountdownText(remainingMs: number): string {
  if (remainingMs <= 0) return 'Agora';
  const totalSeconds = Math.floor(remainingMs / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (days > 0) {
    return `${days}d ${hours}h ${minutes}m`;
  }
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  }
  return `${String(minutes).padStart(2, '0')}m ${String(seconds).padStart(2, '0')}s`;
}

export function formatTimeHHMM(epochMs: number): string {
  const d = new Date(epochMs);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

export function formatDayRelative(epochMs: number, referenceMs: number): string {
  const dEvent = new Date(epochMs);
  const dRef = new Date(referenceMs);

  const startOfEventDay = new Date(
    dEvent.getFullYear(),
    dEvent.getMonth(),
    dEvent.getDate()
  ).getTime();
  const startOfRefDay = new Date(
    dRef.getFullYear(),
    dRef.getMonth(),
    dRef.getDate()
  ).getTime();

  const diffDays = Math.round((startOfEventDay - startOfRefDay) / 86400000);
  if (diffDays === 0) return 'Hoje';
  if (diffDays === 1) return 'Amanhã';
  if (diffDays > 1) return `+${diffDays} dias`;
  return 'Passado';
}
