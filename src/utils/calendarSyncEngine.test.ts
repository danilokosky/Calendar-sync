import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildSyncPayload,
  evaluateWatchAutonomousState,
  formatCountdownText,
  detectHourlyWeatherShift,
  CalendarSource,
  CalendarEventItem,
  WatchCacheState,
} from './calendarSyncEngine';

const mockCalendars: CalendarSource[] = [
  { id: 'cal-work', name: 'Trabalho / Sprint', account: 'dev@empresa.com', color: '#f59e0b', enabled: true },
  { id: 'cal-personal', name: 'Pessoal & Tarefas', account: 'danilo@gmail.com', color: '#38bdf8', enabled: false },
];

const baseTime = new Date('2026-11-05T09:00:00.000Z').getTime();

const mockEvents: CalendarEventItem[] = [
  {
    id: 'ev-1',
    calendarId: 'cal-work',
    title: 'Daily Standup & Arquitetura Wear OS',
    location: 'Google Meet',
    startEpochMs: baseTime + 45 * 60 * 1000, // +45 min
    endEpochMs: baseTime + 75 * 60 * 1000,
  },
  {
    id: 'ev-2',
    calendarId: 'cal-personal', // disabled calendar
    title: 'Treino Funcional',
    location: 'Academia',
    startEpochMs: baseTime + 60 * 60 * 1000,
    endEpochMs: baseTime + 120 * 60 * 1000,
  },
  {
    id: 'ev-3',
    calendarId: 'cal-work',
    title: 'Code Review Watch Face Format XML',
    location: 'Sala 04',
    startEpochMs: baseTime + 3 * 60 * 60 * 1000, // +3h
    endEpochMs: baseTime + 4 * 60 * 60 * 1000,
  },
  {
    id: 'ev-4',
    calendarId: 'cal-work',
    title: 'Planejamento Semanal (Daqui a 2 dias)',
    location: 'Remoto',
    startEpochMs: baseTime + 2 * 24 * 60 * 60 * 1000, // +2 days
    endEpochMs: baseTime + 2 * 24 * 60 * 60 * 1000 + 3600000,
  },
  {
    id: 'ev-5',
    calendarId: 'cal-work',
    title: 'Retrospectiva da Sprint (Daqui a 6 dias)',
    location: 'Auditório',
    startEpochMs: baseTime + 6 * 24 * 60 * 60 * 1000, // +6 days
    endEpochMs: baseTime + 6 * 24 * 60 * 60 * 1000 + 3600000,
  },
];

test('buildSyncPayload filters by enabled calendars and horizon days', () => {
  // With 3-day horizon, ev-5 (+6 days) and ev-2 (disabled calendar) must be excluded
  const payload3Days = buildSyncPayload(mockEvents, mockCalendars, baseTime, 3);
  assert.equal(payload3Days.length, 3);
  assert.deepEqual(
    payload3Days.map((e) => e.id),
    ['ev-1', 'ev-3', 'ev-4']
  );

  // With 7-day horizon, ev-5 (+6 days) is included
  const payload7Days = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  assert.equal(payload7Days.length, 4);
});

test('evaluateWatchAutonomousState calculates progress bar within 1h or 2h window', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  const cache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 60, // 1 hour window before event
    isBluetoothConnected: true,
  };

  // Next event is at +45 min. Since window is 60 min, 15 min of the window has elapsed -> 25% progress (0.25)
  const stateAtBase = evaluateWatchAutonomousState(cache, baseTime);
  assert.equal(stateAtBase.hasUpcomingEvent, true);
  assert.equal(stateAtBase.nextEvent?.id, 'ev-1');
  assert.equal(stateAtBase.upcomingThree.length, 3);
  assert.equal(stateAtBase.isWithinProgressWindow, true);
  assert.equal(stateAtBase.progressRatio, 0.25);
});

test('evaluateWatchAutonomousState works autonomously offline 2 days later using cached horizon', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  const offlineCache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 120, // 2 hours window
    isBluetoothConnected: false, // Disconnected from smartphone!
  };

  // Advance clock by 1 day and 23 hours (1 hour before ev-4 which is at +2 days)
  const futureWatchTime = baseTime + 2 * 24 * 60 * 60 * 1000 - 60 * 60 * 1000;
  const autonomousState = evaluateWatchAutonomousState(offlineCache, futureWatchTime);

  assert.equal(autonomousState.isOperatingAutonomously, true);
  assert.equal(autonomousState.nextEvent?.id, 'ev-4');
  assert.equal(autonomousState.upcomingThree.length, 2); // ev-4 and ev-5 remain
  assert.equal(autonomousState.isWithinProgressWindow, true);
  // 60 minutes remaining out of 120-minute window => 0.5 progress ratio
  assert.equal(autonomousState.progressRatio, 0.5);
  assert.equal(formatCountdownText(autonomousState.remainingMs), '1h 00m');
});

test('evaluateWatchAutonomousState enables 1-minute turbo rule at <= 15m and switches DYNAMIC context on watch weather notification', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  const dynamicWeatherCache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 60,
    isBluetoothConnected: true,
    contextWindowMode: 'DYNAMIC',
    highFreq15MinRule: true,
    hasActiveWatchWeatherAlert: true,
  };

  // At baseTime (+45m to event), weather alert overrides DYNAMIC to WEATHER
  const stateAt45m = evaluateWatchAutonomousState(dynamicWeatherCache, baseTime);
  assert.equal(stateAt45m.isWithinTurbo15MinWindow, false);
  assert.equal(stateAt45m.effectiveContextMode, 'WEATHER');

  // At baseTime + 35m (10m remaining before ev-1 -> inside <=15m window), DYNAMIC prioritizes EVENTS and enables 1-min turbo
  const stateAt10mRemaining = evaluateWatchAutonomousState(
    dynamicWeatherCache,
    baseTime + 35 * 60 * 1000
  );
  assert.equal(stateAt10mRemaining.isWithinTurbo15MinWindow, true);
  assert.equal(stateAt10mRemaining.effectiveContextMode, 'EVENTS');
});

test('detectHourlyWeatherShift detects temperature shifts and rain across periodic checks', () => {
  // 1) Temperature drop >= 4°C in upcoming hours triggers shift
  const shiftDrop = detectHourlyWeatherShift([29, 27, 24, 23, 25], 30, 29);
  assert.equal(shiftDrop.hasShift, true);
  assert.match(shiftDrop.summary, /Queda 29°→23°C/);

  // 2) Stable temperatures (< 4°C swing, low rain, small delta from previous check) does NOT trigger shift
  const stable = detectHourlyWeatherShift([26, 26, 25, 25, 26], 15, 25);
  assert.equal(stable.hasShift, false);

  // 3) Sudden delta >= 3°C compared to previous periodic check triggers shift
  const deltaShift = detectHourlyWeatherShift([22, 22, 21, 21, 22], 20, 26);
  assert.equal(deltaShift.hasShift, true);
});

