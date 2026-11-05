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

export interface SyncConfiguration {
  horizonDays: SyncHorizonDays;
  progressWindowMinutes: ProgressWindowMinutes;
  syncIntervalMinutes: SyncIntervalMinutes;
  autoSyncOnCalendarChange: boolean;
}

export interface WatchCacheState {
  cachedEvents: CalendarEventItem[];
  lastSyncEpochMs: number;
  syncedHorizonDays: SyncHorizonDays;
  progressWindowMinutes: ProgressWindowMinutes;
  isBluetoothConnected: boolean;
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
  isOngoing: boolean;
  autonomousCacheValidUntilMs: number;
  isOperatingAutonomously: boolean;
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
      isOngoing: false,
      autonomousCacheValidUntilMs,
      isOperatingAutonomously: !cache.isBluetoothConnected,
    };
  }

  const remainingMs = Math.max(0, nextEvent.startEpochMs - currentWatchTimeMs);
  const windowMs = cache.progressWindowMinutes * 60 * 1000;

  // Progress bar calculation:
  // If remainingMs > windowMs, progress is 0 (or minimal lead-in) and isWithinProgressWindow = false.
  // Once remainingMs <= windowMs, progress ramps linearly from 0.0 (at windowMs before start) to 1.0 (at event start).
  let progressRatio = 0;
  let isWithinProgressWindow = false;

  if (remainingMs <= windowMs) {
    isWithinProgressWindow = true;
    progressRatio = Math.min(1, Math.max(0, 1 - remainingMs / windowMs));
  }

  return {
    hasUpcomingEvent: true,
    nextEvent,
    upcomingThree,
    remainingMs,
    formattedCountdown: formatCountdownText(remainingMs),
    formattedStartTime: formatTimeHHMM(nextEvent.startEpochMs),
    progressRatio: Number(progressRatio.toFixed(4)),
    isWithinProgressWindow,
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
