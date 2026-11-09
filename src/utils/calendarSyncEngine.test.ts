import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildSyncPayload,
  evaluateWatchAutonomousState,
  formatCountdownText,
  detectHourlyWeatherShift,
  computeSolarCurvePoint,
  resolveEffectiveWatchContextMode,
  formatCompactWeatherLocation,
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

  // 4) Condition shift (e.g. Céu Limpo -> Chuva) triggers shift even if temperature is stable
  const conditionShift = detectHourlyWeatherShift(
    [25, 25, 25, 24, 24],
    20,
    25,
    true
  );
  assert.equal(conditionShift.hasShift, true);
});

test('evaluateWatchAutonomousState supports SUN (Sunrise/Sunset) and SLEEP context modes', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  const sunCache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 60,
    isBluetoothConnected: true,
    contextWindowMode: 'SUN',
  };

  const state = evaluateWatchAutonomousState(sunCache, baseTime);
  assert.equal(state.effectiveContextMode, 'SUN');

  const sleepCache: WatchCacheState = {
    ...sunCache,
    contextWindowMode: 'SLEEP',
  };
  const sleepState = evaluateWatchAutonomousState(sleepCache, baseTime);
  assert.equal(sleepState.effectiveContextMode, 'SLEEP');
});

test('computeSolarCurvePoint places sun on horizon at sunrise/sunset and below horizon at night (pre-dawn and evening)', () => {
  const sunriseMins = 5 * 60 + 27; // 05:27 (327m)
  const sunsetMins = 17 * 60 + 40; // 17:40 (1060m)

  // At exact sunrise (05:27), sun crosses horizon line
  const atSunrise = computeSolarCurvePoint(sunriseMins, sunriseMins, sunsetMins);
  assert.equal(atSunrise.sunX, atSunrise.sunriseX);
  assert.equal(atSunrise.sunY, atSunrise.horizonY);

  // At exact sunset (17:40), sun crosses horizon line
  const atSunset = computeSolarCurvePoint(sunsetMins, sunriseMins, sunsetMins);
  assert.equal(atSunset.sunX, atSunset.sunsetX);
  assert.equal(atSunset.sunY, atSunset.horizonY);

  // At 22:42 (1362m -> night after sunset), sun is BELOW the horizon line (sunY > horizonY)
  const atNightEvening = computeSolarCurvePoint(22 * 60 + 42, sunriseMins, sunsetMins);
  assert.equal(atNightEvening.isBelowHorizon, true);
  assert.ok(atNightEvening.sunY > atNightEvening.horizonY);

  // At 03:30 (210m -> pre-dawn before sunrise), sun is BELOW the horizon line climbing toward sunrise
  const atPreDawn = computeSolarCurvePoint(3 * 60 + 30, sunriseMins, sunsetMins);
  assert.equal(atPreDawn.isBelowHorizon, true);
  assert.ok(atPreDawn.sunY > atPreDawn.horizonY);
});

test('progress bar stays at 0% outside configured window and grows linearly from 0% to 100% inside window (30m, 1h, 2h, 3h)', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);
  // ev-1 starts at baseTime + 45m. If user configured a 30m window, at baseTime (45m before event) progress MUST be 0%!
  const cache30m: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 30,
    isBluetoothConnected: true,
  };

  const beforeWindow = evaluateWatchAutonomousState(cache30m, baseTime);
  assert.equal(beforeWindow.isWithinProgressWindow, false);
  assert.equal(beforeWindow.progressRatio, 0);

  // At 15m before event (halfway through 30m window), progress MUST be 50% (0.5)
  const halfway30m = evaluateWatchAutonomousState(cache30m, baseTime + 30 * 60 * 1000);
  assert.equal(halfway30m.isWithinProgressWindow, true);
  assert.equal(halfway30m.progressRatio, 0.5);
});

test('resolveEffectiveWatchContextMode switches to EVENTS specifically when <= 15 minutes remain before commitment and respects Sun/Sleep exposure', () => {
  // When primary context is STEPS and event is 25m away (> 15m), Canvas stays on STEPS
  const modeAt25m = resolveEffectiveWatchContextMode(
    'DYNAMIC',
    true,
    25 * 60 * 1000,
    false,
    {
      autoPrimaryContext: 'STEPS',
      unifiedCanvasHoldMinutes: 30,
      currentMinutesOfDay: 14 * 60,
    }
  );
  assert.equal(modeAt25m, 'STEPS');

  // When event is 15m or less away (e.g. 14m), Canvas switches to EVENTS with top priority
  const modeAt14m = resolveEffectiveWatchContextMode(
    'DYNAMIC',
    true,
    14 * 60 * 1000,
    false,
    {
      autoPrimaryContext: 'STEPS',
      unifiedCanvasHoldMinutes: 30,
      currentMinutesOfDay: 14 * 60,
    }
  );
  assert.equal(modeAt14m, 'EVENTS');

  // When event is 50m away, near sunset (17:35 vs 17:40) exposes SUN
  const modeNearSunset = resolveEffectiveWatchContextMode(
    'DYNAMIC',
    true,
    50 * 60 * 1000,
    false,
    {
      autoPrimaryContext: 'STEPS',
      autoSunExposureMinutes: 30,
      currentMinutesOfDay: 17 * 60 + 35,
      sunriseMinutesOfDay: 327,
      sunsetMinutesOfDay: 1060,
    }
  );
  assert.equal(modeNearSunset, 'SUN');

  // Upon waking up at 07:10 (within 30m of 07:00 wake time), exposes SLEEP
  const modeMorningWake = resolveEffectiveWatchContextMode(
    'DYNAMIC',
    true,
    120 * 60 * 1000,
    false,
    {
      autoPrimaryContext: 'EVENTS',
      unifiedCanvasHoldMinutes: 30,
      sleepSyncEnabled: true,
      wakeMinutesOfDay: 420,
      currentMinutesOfDay: 430,
    }
  );
  assert.equal(modeMorningWake, 'SLEEP');
});

test('formatCompactWeatherLocation converts full state names to official IBGE 2-letter UF siglas and truncates long city names', () => {
  // Official IBGE UF sigla even when Geocoder returns "State of ...", "Estado do ...", etc.
  assert.equal(formatCompactWeatherLocation('São Luís, Maranhão'), 'São Luís, MA');
  assert.equal(formatCompactWeatherLocation('Curitiba, Paraná'), 'Curitiba, PR');
  assert.equal(formatCompactWeatherLocation('Curitiba, Estado do Paraná'), 'Curitiba, PR');
  assert.equal(formatCompactWeatherLocation('Pinheiros, São Paulo'), 'Pinheiros, SP');
  assert.equal(formatCompactWeatherLocation('São Paulo, State of São Paulo'), 'São Paulo, SP');
  assert.equal(formatCompactWeatherLocation('Belém, Pará'), 'Belém, PA');
  assert.equal(formatCompactWeatherLocation('Campo Grande, Mato Grosso do Sul'), 'Campo Grande, MS');
  assert.equal(formatCompactWeatherLocation('Cuiabá, Mato Grosso'), 'Cuiabá, MT');

  // Long multi-word city (> 12 chars): keeps first word whole + half of second word + "..."
  assert.equal(
    formatCompactWeatherLocation('São Bernardo do Campo, São Paulo'),
    'São Bern..., SP'
  );
  assert.equal(
    formatCompactWeatherLocation('Governador Valadares, Minas Gerais'),
    'Governador Valad..., MG'
  );

  // Single very long word (> 12 chars): truncated with ellipsis + UF sigla
  assert.equal(
    formatCompactWeatherLocation('Pindamonhangaba, São Paulo'),
    'Pindamonh..., SP'
  );
});

test('evaluateWatchAutonomousState supports TASKS context mode and prioritizes TASKS on Samsung Reminder / Google Tasks alert', () => {
  const syncedEvents = buildSyncPayload(mockEvents, mockCalendars, baseTime, 7);

  // Explicit TASKS mode
  const fixedTasksCache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 60,
    isBluetoothConnected: true,
    contextWindowMode: 'TASKS',
  };
  const fixedState = evaluateWatchAutonomousState(fixedTasksCache, baseTime);
  assert.equal(fixedState.effectiveContextMode, 'TASKS');

  // Dynamic mode with active Samsung Reminder / Google Tasks alert overrides primary context to TASKS
  const dynamicTaskAlertCache: WatchCacheState = {
    cachedEvents: syncedEvents,
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 7,
    progressWindowMinutes: 60,
    isBluetoothConnected: true,
    contextWindowMode: 'DYNAMIC',
    autoPrimaryContext: 'WEATHER',
    tasksSyncEnabled: true,
    hasActiveWatchTaskAlert: true,
  };
  const alertState = evaluateWatchAutonomousState(dynamicTaskAlertCache, baseTime);
  assert.equal(alertState.effectiveContextMode, 'TASKS');
});

test('evaluateWatchAutonomousState shows Amanhã header when next events are tomorrow and auto-switches from empty Today Agenda to active Tasks', () => {
  const tomorrowEvent: CalendarEventItem = {
    id: 'ev-tomorrow-1',
    calendarId: 'cal-work',
    title: 'Reunião de Alinhamento Amanhã',
    location: 'Sala 01',
    startEpochMs: baseTime + 24 * 60 * 60 * 1000, // +1 day (Tomorrow)
    endEpochMs: baseTime + 25 * 60 * 60 * 1000,
  };

  // 1) No events today, only tomorrow, AND active tasks exist ->
  //    agendaDayHeader is 'Amanhã', dayEvents has tomorrow's event, and Auto mode switches to 'TASKS'
  const cacheTomorrowWithTasks: WatchCacheState = {
    cachedEvents: [tomorrowEvent],
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 5,
    progressWindowMinutes: 60,
    isBluetoothConnected: true,
    contextWindowMode: 'DYNAMIC',
    autoPrimaryContext: 'EVENTS',
    autoFallbackAgendaToTasks: true,
    tasksSyncEnabled: true,
    tasks: [
      {
        id: 't1',
        title: 'Enviar relatório trimestral',
        dueHHMM: '14:00',
        sourceLabel: 'Reminder',
        completed: false,
      },
    ],
  };

  const stateTomorrowWithTasks = evaluateWatchAutonomousState(cacheTomorrowWithTasks, baseTime);
  assert.equal(stateTomorrowWithTasks.hasEventsToday, false);
  assert.equal(stateTomorrowWithTasks.agendaDayHeader, 'Amanhã');
  assert.equal(stateTomorrowWithTasks.dayEvents.length, 1);
  assert.equal(stateTomorrowWithTasks.dayEvents[0].id, 'ev-tomorrow-1');
  assert.equal(stateTomorrowWithTasks.effectiveContextMode, 'TASKS');

  // 2) If all tasks are completed (no active tasks), Auto mode stays on 'EVENTS' (showing Amanhã or Não há eventos)
  const cacheTomorrowNoActiveTasks: WatchCacheState = {
    ...cacheTomorrowWithTasks,
    tasks: [
      {
        id: 't1',
        title: 'Enviar relatório trimestral',
        dueHHMM: '14:00',
        sourceLabel: 'Reminder',
        completed: true,
      },
    ],
  };
  const stateNoActiveTasks = evaluateWatchAutonomousState(cacheTomorrowNoActiveTasks, baseTime);
  assert.equal(stateNoActiveTasks.effectiveContextMode, 'EVENTS');
  assert.equal(stateNoActiveTasks.agendaDayHeader, 'Amanhã');
});

test('resolveTasksCanvasState enforces strict 3-tier hierarchy: Today (with time) -> Tomorrow (with time) -> Undated & Overdue in red (without date or time)', async () => {
  const { resolveTasksCanvasState } = await import('./calendarSyncEngine');

  const mixedTasks = [
    {
      id: 't-today-1',
      title: 'Revisar PR de hoje',
      dueHHMM: '15:00',
      sourceLabel: 'Tasks' as const,
      completed: false,
      scheduleCategory: 'TODAY' as const,
      isTodayOrTimeless: true,
    },
    {
      id: 't-tomorrow-1',
      title: 'Apresentação de amanhã',
      dueHHMM: '10:30',
      sourceLabel: 'Reminder' as const,
      completed: false,
      scheduleCategory: 'TOMORROW' as const,
      isTodayOrTimeless: true,
    },
    {
      id: 't-undated-1',
      title: 'Ligar para contabilidade',
      dueHHMM: '',
      sourceLabel: 'Reminder' as const,
      completed: false,
      scheduleCategory: 'UNDATED' as const,
      isTodayOrTimeless: true,
    },
    {
      id: 't-overdue-1',
      title: 'Pagar fatura atrasada',
      dueHHMM: 'Ontem 18:00',
      sourceLabel: 'Tasks' as const,
      completed: false,
      scheduleCategory: 'OVERDUE' as const,
      isOverdue: true,
      isTodayOrTimeless: true,
    },
    {
      id: 't-done',
      title: 'Tarefa já concluída',
      dueHHMM: '11:00',
      sourceLabel: 'Tasks' as const,
      completed: true,
      scheduleCategory: 'TODAY' as const,
      isTodayOrTimeless: true,
    },
  ];

  // TIER 1: When Today tasks exist -> ONLY Today tasks are shown, WITH time (showTime = true)
  const tier1 = resolveTasksCanvasState(mixedTasks);
  assert.equal(tier1.bucket, 'TODAY');
  assert.equal(tier1.headerTitle, 'Tarefas • Hoje');
  assert.equal(tier1.showTime, true);
  assert.deepEqual(
    tier1.displayTasks.map((t) => t.id),
    ['t-today-1']
  );
  assert.equal(tier1.displayTasks[0].displayTimeHHMM, '15:00');
  assert.equal(tier1.displayTasks[0].isRedOverdue, false);

  // TIER 2: When Today tasks are completed/absent -> ONLY Tomorrow tasks are shown, WITH time (showTime = true)
  const withoutToday = mixedTasks.map((t) =>
    t.id === 't-today-1' ? { ...t, completed: true } : t
  );
  const tier2 = resolveTasksCanvasState(withoutToday);
  assert.equal(tier2.bucket, 'TOMORROW');
  assert.equal(tier2.headerTitle, 'Tarefas • Amanhã');
  assert.equal(tier2.showTime, true);
  assert.deepEqual(
    tier2.displayTasks.map((t) => t.id),
    ['t-tomorrow-1']
  );
  assert.equal(tier2.displayTasks[0].displayTimeHHMM, '10:30');
  assert.equal(tier2.displayTasks[0].isRedOverdue, false);

  // TIER 3: When Today and Tomorrow tasks are absent -> Undated & Overdue tasks are shown WITHOUT date/time (showTime = false), and Overdue is flagged red
  const withoutTodayAndTomorrow = withoutToday.map((t) =>
    t.id === 't-tomorrow-1' ? { ...t, completed: true } : t
  );
  const tier3 = resolveTasksCanvasState(withoutTodayAndTomorrow);
  assert.equal(tier3.bucket, 'UNDATED_OVERDUE');
  assert.equal(tier3.headerTitle, 'Tarefas • Sem data');
  assert.equal(tier3.showTime, false);
  assert.deepEqual(
    tier3.displayTasks.map((t) => t.id),
    ['t-overdue-1', 't-undated-1']
  );
  // Overdue task has NO date/time displayed and is marked red
  assert.equal(tier3.displayTasks[0].displayTimeHHMM, '');
  assert.equal(tier3.displayTasks[0].isRedOverdue, true);
  // Undated task has NO date/time displayed and is not red
  assert.equal(tier3.displayTasks[1].displayTimeHHMM, '');
  assert.equal(tier3.displayTasks[1].isRedOverdue, false);

  // Also verify Agenda day separation (Today vs Tomorrow)
  const todayEv: CalendarEventItem = {
    id: 'ev-today-only',
    calendarId: 'cal-work',
    title: 'Compromisso de Hoje',
    location: 'Meet',
    startEpochMs: baseTime + 30 * 60 * 1000,
    endEpochMs: baseTime + 60 * 60 * 1000,
  };
  const tomorrowEv: CalendarEventItem = {
    id: 'ev-tomorrow-only',
    calendarId: 'cal-work',
    title: 'Compromisso de Amanhã',
    location: 'Meet',
    startEpochMs: baseTime + 26 * 60 * 60 * 1000,
    endEpochMs: baseTime + 27 * 60 * 60 * 1000,
  };
  const mixedCache: WatchCacheState = {
    cachedEvents: [todayEv, tomorrowEv],
    lastSyncEpochMs: baseTime,
    syncedHorizonDays: 5,
    progressWindowMinutes: 60,
    isBluetoothConnected: false,
  };
  const stateToday = evaluateWatchAutonomousState(mixedCache, baseTime);
  assert.equal(stateToday.agendaDayHeader, 'Hoje');
  assert.equal(stateToday.dayEvents.length, 1);
  assert.equal(stateToday.dayEvents[0].id, 'ev-today-only');

  const stateAfterTodayPassed = evaluateWatchAutonomousState(
    mixedCache,
    baseTime + 2 * 60 * 60 * 1000
  );
  assert.equal(stateAfterTodayPassed.agendaDayHeader, 'Amanhã');
  assert.equal(stateAfterTodayPassed.dayEvents.length, 1);
  assert.equal(stateAfterTodayPassed.dayEvents[0].id, 'ev-tomorrow-only');
});

test('resolveTasksCanvasState respects independent Samsung Reminder and Google Tasks toggles', async () => {
  const { resolveTasksCanvasState } = await import('./calendarSyncEngine');

  const sourceTasks = [
    {
      id: 't-rem-today',
      title: 'Lembrete Samsung de Hoje',
      dueHHMM: '11:30',
      sourceLabel: 'Reminder' as const,
      completed: false,
      scheduleCategory: 'TODAY' as const,
      isTodayOrTimeless: true,
    },
    {
      id: 't-gtask-today',
      title: 'Tarefa Google de Hoje',
      dueHHMM: '14:00',
      sourceLabel: 'Tasks' as const,
      completed: false,
      scheduleCategory: 'TODAY' as const,
      isTodayOrTimeless: true,
    },
  ];

  // Both enabled -> returns both
  const bothOn = resolveTasksCanvasState(sourceTasks, {
    samsungReminderSyncEnabled: true,
    googleTasksSyncEnabled: true,
  });
  assert.deepEqual(
    bothOn.displayTasks.map((t) => t.id),
    ['t-rem-today', 't-gtask-today']
  );

  // Only Samsung Reminder enabled -> returns only Reminder task
  const onlyReminder = resolveTasksCanvasState(sourceTasks, {
    samsungReminderSyncEnabled: true,
    googleTasksSyncEnabled: false,
  });
  assert.deepEqual(
    onlyReminder.displayTasks.map((t) => t.id),
    ['t-rem-today']
  );

  // Only Google Tasks enabled -> returns only Google Tasks item
  const onlyGoogleTasks = resolveTasksCanvasState(sourceTasks, {
    samsungReminderSyncEnabled: false,
    googleTasksSyncEnabled: true,
  });
  assert.deepEqual(
    onlyGoogleTasks.displayTasks.map((t) => t.id),
    ['t-gtask-today']
  );

  // Both disabled -> empty
  const bothOff = resolveTasksCanvasState(sourceTasks, {
    samsungReminderSyncEnabled: false,
    googleTasksSyncEnabled: false,
  });
  assert.equal(bothOff.bucket, 'EMPTY');
  assert.equal(bothOff.displayTasks.length, 0);
});
