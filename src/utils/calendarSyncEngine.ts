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
export type ContextWindowMode = 'DYNAMIC' | 'EVENTS' | 'WEATHER' | 'STEPS' | 'SUN';

export interface WeatherTriggerAppItem {
  packageName: string;
  displayName: string;
  categoryName: string;
  categorySubtitle: string;
  isInstalledOnDevice: boolean;
}

export const WEATHER_TRIGGER_CATALOG: WeatherTriggerAppItem[] = [
  {
    packageName: 'com.google.android.googlequicksearchbox',
    displayName: 'App Google',
    categoryName: 'Provedores do Sistema',
    categorySubtitle: 'Alertas meteorológicos e de chuva do Google',
    isInstalledOnDevice: true,
  },
  {
    packageName: 'com.google.android.apps.weather',
    displayName: 'Google Weather',
    categoryName: 'Provedores do Sistema',
    categorySubtitle: 'App dedicado de Clima do Google / Wear OS',
    isInstalledOnDevice: true,
  },
  {
    packageName: 'com.sec.android.daemonapp',
    displayName: 'Samsung Clima (One UI Weather)',
    categoryName: 'Provedores do Sistema',
    categorySubtitle: 'Provedor oficial de Clima da Samsung',
    isInstalledOnDevice: true,
  },
  {
    packageName: 'br.com.climatempo.meteorologia',
    displayName: 'Climatempo',
    categoryName: 'Clima e Meteorologia',
    categorySubtitle: 'Previsão do tempo, chuva e frente fria',
    isInstalledOnDevice: true,
  },
  {
    packageName: 'com.accuweather.android',
    displayName: 'AccuWeather',
    categoryName: 'Clima e Meteorologia',
    categorySubtitle: 'Alertas de precipitação e variação térmica',
    isInstalledOnDevice: false,
  },
  {
    packageName: 'com.weather.Weather',
    displayName: 'The Weather Channel',
    categoryName: 'Clima e Meteorologia',
    categorySubtitle: 'Notificações de mudança brusca de clima',
    isInstalledOnDevice: false,
  },
  {
    packageName: 'com.windyty.android',
    displayName: 'Windy.com',
    categoryName: 'Clima e Radar',
    categorySubtitle: 'Mapas de vento, frente fria e tempestade',
    isInstalledOnDevice: true,
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
  phoneSolarAutoSyncEnabled?: boolean;
  watchStandaloneSolarEnabled?: boolean;
  solarSunriseHHMM?: string;
  solarSunsetHHMM?: string;
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
  watchStandaloneSolarEnabled?: boolean;
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
  effectiveContextMode: 'EVENTS' | 'WEATHER' | 'STEPS' | 'SUN';
  isOngoing: boolean;
  autonomousCacheValidUntilMs: number;
  isOperatingAutonomously: boolean;
}

/**
 * Avalia se a previsão horária buscada periodicamente pelo nosso App (no telefone ou no serviço
 * Stand-Alone do relógio) apresenta mudança relevante de temperatura, chuva ou condição climática.
 */
export function detectHourlyWeatherShift(
  temps: number[],
  rainProbPct: number,
  previousFirstTemp?: number | null,
  conditionChanged?: boolean
): { hasShift: boolean; summary: string } {
  if (temps.length < 2) {
    return { hasShift: false, summary: 'Estável' };
  }
  const firstT = temps[0];
  const lastT = temps[temps.length - 1];
  const minT = Math.min(...temps);
  const maxT = Math.max(...temps);
  const swingInUpcomingHours = maxT - minT >= 3;
  const deltaFromPreviousCheck =
    previousFirstTemp != null && Math.abs(firstT - previousFirstTemp) >= 2;
  const highRain = rainProbPct >= 45;

  const hasShift =
    swingInUpcomingHours ||
    deltaFromPreviousCheck ||
    highRain ||
    Boolean(conditionChanged);

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
): 'EVENTS' | 'WEATHER' | 'STEPS' | 'SUN' {
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
      return ev.endEpochMs > syncTimeMs && ev.startEpochMs <= maxTimeMs;
    })
    .sort((a, b) => a.startEpochMs - b.startEpochMs);
}

export function evaluateWatchAutonomousState(
  cache: WatchCacheState,
  currentWatchTimeMs: number
): ComplicationProgressState {
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
