import React, { useState, useEffect } from 'react';
import {
  ComplicationProgressState,
  WatchCacheState,
  CalendarSource,
  SyncConfiguration,
  ContextWindowMode,
  DEFAULT_TASKS_LIST,
  TaskItem,
  formatTimeHHMM,
  formatDayRelative,
  computeSolarCurvePoint,
  formatCompactWeatherLocation,
  resolveTasksCanvasState,
} from '../utils/calendarSyncEngine';
import {
  Bluetooth,
  BluetoothOff,
  FastForward,
  RotateCcw,
  Watch,
  Layers,
  Database,
  Calendar,
  Footprints,
  Sun,
  CloudRain,
  Sparkles,
  BellRing,
  Zap,
  Moon,
  CheckSquare,
} from 'lucide-react';

interface WearOsSmartwatchSimulatorProps {
  watchState: ComplicationProgressState;
  watchCache: WatchCacheState;
  calendars: CalendarSource[];
  currentWatchTimeMs: number;
  config: SyncConfiguration;
  hasActiveWatchWeatherAlert: boolean;
  onToggleWatchWeatherAlert: () => void;
  onUpdateConfig: (partial: Partial<SyncConfiguration>) => void;
  onToggleBluetooth: () => void;
  onAdvanceTimeMinutes: (minutes: number) => void;
  onResetTime: () => void;
}

function parseMinutesHHMM(hhmm: string | undefined, fallbackMins: number): number {
  if (!hhmm || !hhmm.includes(':')) return fallbackMins;
  const [h, m] = hhmm.split(':').map((v) => parseInt(v, 10));
  if (Number.isNaN(h) || Number.isNaN(m)) return fallbackMins;
  return h * 60 + m;
}

export const WearOsSmartwatchSimulator: React.FC<WearOsSmartwatchSimulatorProps> = ({
  watchState,
  watchCache,
  currentWatchTimeMs,
  config,
  hasActiveWatchWeatherAlert,
  onToggleWatchWeatherAlert,
  onUpdateConfig,
  onToggleBluetooth,
  onAdvanceTimeMinutes,
  onResetTime,
}) => {
  const [watchScreenMode, setWatchScreenMode] = useState<'wff' | 'tile' | 'cache'>('wff');
  const [tapOverrideState, setTapOverrideState] = useState<{
    mode: 'EVENTS' | 'TASKS' | 'WEATHER' | 'SUN' | 'STEPS' | 'SLEEP' | 'BATTERY';
    startedAtWatchMs: number;
  } | null>(null);
  const [dismissedNotifKey, setDismissedNotifKey] = useState<string | null>(null);
  const [weatherNotifSource, setWeatherNotifSource] = useState<
    'ChronoWear Auto (1h)' | 'App Clima (Escolhido)' | 'Relógio Stand-Alone (1h)'
  >('ChronoWear Auto (1h)');

  // Sempre que o foco ou modo for customizado no smartphone, limpa imediatamente qualquer override manual de toque no relógio
  useEffect(() => {
    setTapOverrideState(null);
  }, [config.contextWindowMode, config.autoPrimaryContext]);

  const tasksList: TaskItem[] = config.tasks ?? DEFAULT_TASKS_LIST;
  const resolvedTasksState = resolveTasksCanvasState(tasksList, {
    googleTasksSyncEnabled:
      (watchCache.tasksSyncEnabled ?? config.tasksSyncEnabled ?? true) &&
      (watchCache.googleTasksSyncEnabled ??
        config.googleTasksSyncEnabled ??
        true),
  });
  const activeTasksForWatch = resolvedTasksState.displayTasks;

  const handleToggleTaskItem = (taskId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const updated = tasksList.map((t) =>
      t.id === taskId ? { ...t, completed: !t.completed } : t
    );
    onUpdateConfig({ tasks: updated });
  };

  const handleSimulateTaskReminderAlert = () => {
    const nextAlert = !(config.hasActiveWatchTaskAlert ?? false);
    onUpdateConfig({
      tasksSyncEnabled: true,
      hasActiveWatchTaskAlert: nextAlert,
    });
  };

  const ev1 = watchState.dayEvents[0] || null;
  const ev2 = watchState.dayEvents[1] || null;
  const ev3 = watchState.dayEvents[2] || null;

  const watchDate = new Date(currentWatchTimeMs);
  const weekdayMap = ['DOM.', 'SEG.', 'TER.', 'QUA.', 'QUI.', 'SEX.', 'SÁB.'];
  const weekdayUpper = weekdayMap[watchDate.getDay()] || 'SEX.';
  const dayOfMonth = String(watchDate.getDate()).padStart(2, '0');
  const timeHHMMSS = `${formatTimeHHMM(currentWatchTimeMs)}:06`;

  const unifiedHoldMins = config.unifiedCanvasHoldMinutes ?? 30;
  const unifiedHoldMs = unifiedHoldMins * 60_000;
  const primaryCtx = config.autoPrimaryContext ?? 'EVENTS';

  // Verifica se falta 15 minutos ou menos (<= 15m) para o próximo compromisso da Agenda
  const isEventWithin15Min =
    watchState.hasUpcomingEvent &&
    watchState.remainingMs > 0 &&
    watchState.remainingMs <= 15 * 60 * 1000;

  // Prioridade máxima: qualquer gatilho real de contexto (Compromisso em <= 15m, Lembrete de Tarefa, Clima, Nascer/Pôr do Sol, Sono/Acordar)
  // tem prioridade máxima sobre o toque manual e assume o Canvas imediatamente.
  const isPriorityContextActive =
    config.contextWindowMode === 'DYNAMIC' &&
    (watchState.effectiveContextMode !== primaryCtx || isEventWithin15Min);

  const isTapOverrideValid =
    !isPriorityContextActive &&
    tapOverrideState !== null &&
    tapOverrideState.mode !== primaryCtx &&
    currentWatchTimeMs >= tapOverrideState.startedAtWatchMs &&
    currentWatchTimeMs - tapOverrideState.startedAtWatchMs <= unifiedHoldMs;

  const tapRemainingMins =
    isTapOverrideValid && tapOverrideState
      ? Math.max(
          1,
          Math.ceil(
            (tapOverrideState.startedAtWatchMs + unifiedHoldMs - currentWatchTimeMs) /
              60_000
          )
        )
      : 0;

  const effectiveMode = isPriorityContextActive
    ? watchState.effectiveContextMode
    : isTapOverrideValid && tapOverrideState
    ? tapOverrideState.mode
    : watchState.effectiveContextMode;

  const contextLabels: Record<
    'EVENTS' | 'TASKS' | 'WEATHER' | 'SUN' | 'STEPS' | 'SLEEP' | 'BATTERY',
    string
  > = {
    EVENTS: 'Agenda',
    TASKS: 'Tarefas',
    WEATHER: 'Clima',
    SUN: 'Nascer/Pôr do Sol',
    STEPS: 'Passos',
    SLEEP: 'Sono',
    BATTERY: 'Bateria',
  };

  // Notificação no relógio quando uma mudança real de contexto força a troca do Canvas:
  // - Respeita o switch geral do app do relógio (notifyWatchOnCanvasChange)
  // - Para compromissos da Agenda faltando 15 minutos, respeita também o switch opcional dedicado (notifyAgenda15MinEnabled)
  const forcedNotifKey = isPriorityContextActive
    ? `${watchState.effectiveContextMode}-${hasActiveWatchWeatherAlert ? 'alert' : config.hasActiveWatchTaskAlert ? 'task-alert' : isEventWithin15Min ? `15m-${ev1?.id ?? ''}` : 'auto'}`
    : null;
  const isNotificationAllowedForMode =
    (config.notifyWatchOnCanvasChange ?? true) &&
    (watchState.effectiveContextMode !== 'EVENTS' ||
      (config.notifyAgenda15MinEnabled ?? true));

  const showWatchForcedNotification =
    isNotificationAllowedForMode &&
    isPriorityContextActive &&
    forcedNotifKey !== null &&
    dismissedNotifKey !== forcedNotifKey;

  const forcedNotificationDetail = (() => {
    if (!isPriorityContextActive) return '';
    switch (watchState.effectiveContextMode) {
      case 'TASKS': {
        const nextPending = tasksList.find((t) => !t.completed) ?? tasksList[0];
        return nextPending
          ? `${nextPending.sourceLabel}: ${nextPending.title} (${nextPending.dueHHMM})`
          : 'Sincronização Google Tasks API';
      }
      case 'WEATHER':
        return `${formatCompactWeatherLocation(config.weatherLocationName ?? 'São Paulo, SP')} • ${
          hasActiveWatchWeatherAlert
            ? `${weatherNotifSource}: Chuva e queda 29°→23°C`
            : 'Previsão atualizada'
        }`;
      case 'EVENTS':
        return ev1
          ? `Faltam ≤15m (${watchState.formattedCountdown}) para ${ev1.title} às ${formatTimeHHMM(ev1.startEpochMs)}`
          : 'Compromisso em 15 minutos';
      case 'SUN':
        return `Janela solar (${config.solarSunriseHHMM ?? '05:27'} / ${config.solarSunsetHHMM ?? '17:40'})`;
      case 'SLEEP':
        return `Resumo de sono (${config.sleepBedtimeHHMM ?? '23:18'} → ${config.sleepWakeHHMM ?? '07:00'})`;
      default:
        return 'Mudança prioritária de contexto';
    }
  })();

  const handleForceRefreshWatchWeatherLocation = () => {
    const locationPresets = [
      { loc: 'São Paulo, State of São Paulo', rise: '05:27', set: '17:40' },
      { loc: 'São Luís, Maranhão', rise: '05:51', set: '17:58' },
      { loc: 'Curitiba, Estado do Paraná', rise: '05:38', set: '18:52' },
      { loc: 'São Bernardo do Campo, São Paulo', rise: '05:26', set: '17:41' },
      { loc: 'Governador Valadares, Minas Gerais', rise: '05:14', set: '17:32' },
    ];
    const curLoc = config.weatherLocationName ?? 'São Paulo, SP';
    const curIdx = locationPresets.findIndex(
      (p) =>
        formatCompactWeatherLocation(p.loc) ===
        formatCompactWeatherLocation(curLoc)
    );
    const nextPreset =
      locationPresets[(curIdx + 1) % locationPresets.length];
    onUpdateConfig({
      weatherLocationName: formatCompactWeatherLocation(nextPreset.loc),
      weatherUpdatedHHMM: formatTimeHHMM(currentWatchTimeMs),
      solarSunriseHHMM: nextPreset.rise,
      solarSunsetHHMM: nextPreset.set,
    });
  };

  const handleTapCanvasToCycle = () => {
    const order: (
      | 'EVENTS'
      | 'TASKS'
      | 'WEATHER'
      | 'SUN'
      | 'STEPS'
      | 'SLEEP'
      | 'BATTERY'
    )[] = [
      'EVENTS',
      'TASKS',
      'WEATHER',
      'SUN',
      'STEPS',
      'SLEEP',
      'BATTERY',
    ];
    const currentIdx = order.indexOf(effectiveMode);
    const nextMode = order[(currentIdx + 1) % order.length];
    if (config.contextWindowMode === 'DYNAMIC') {
      if (nextMode === primaryCtx) {
        setTapOverrideState(null);
      } else {
        setTapOverrideState({
          mode: nextMode,
          startedAtWatchMs: currentWatchTimeMs,
        });
      }
    } else {
      setTapOverrideState(null);
      onUpdateConfig({ contextWindowMode: nextMode });
    }
  };

  const contextModes: { id: ContextWindowMode; label: string }[] = [
    { id: 'DYNAMIC', label: 'Auto' },
    { id: 'EVENTS', label: 'Agenda' },
    { id: 'TASKS', label: 'Tarefas' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'STEPS', label: 'Passos' },
    { id: 'SUN', label: 'Sol' },
    { id: 'SLEEP', label: 'Sono' },
    { id: 'BATTERY', label: 'Bateria' },
  ];

  const weeklyStepsData = [
    { day: 'Seg', steps: 6420, isToday: false },
    { day: 'Ter', steps: 8190, isToday: false },
    { day: 'Qua', steps: 7310, isToday: false },
    { day: 'Qui', steps: 9120, isToday: false },
    { day: 'Sex', steps: 8435, isToday: true },
    { day: 'Sáb', steps: 5840, isToday: false },
    { day: 'Dom', steps: 5140, isToday: false },
  ];

  const handleCycleWeatherNotif = () => {
    if (!hasActiveWatchWeatherAlert) {
      setWeatherNotifSource('ChronoWear Auto (1h)');
      onToggleWatchWeatherAlert();
    } else if (weatherNotifSource === 'ChronoWear Auto (1h)') {
      setWeatherNotifSource('App Clima (Escolhido)');
    } else if (weatherNotifSource === 'App Clima (Escolhido)') {
      setWeatherNotifSource('Relógio Stand-Alone (1h)');
      onUpdateConfig({ watchStandaloneWeatherEnabled: true });
    } else {
      onToggleWatchWeatherAlert();
    }
  };

  // Cálculo da posição atual do sol na curva cosseno completa (dia acima do horizonte + noite abaixo do horizonte)
  const currentMinutesOfDay =
    watchDate.getHours() * 60 + watchDate.getMinutes();
  const sunriseMins = parseMinutesHHMM(config.solarSunriseHHMM, 5 * 60 + 27);
  const sunsetMins = parseMinutesHHMM(config.solarSunsetHHMM, 17 * 60 + 40);
  const solarPoint = computeSolarCurvePoint(
    currentMinutesOfDay,
    sunriseMins,
    sunsetMins
  );

  // Gera os pontos exatos da curva cosseno (x=2..258) cruzando y=44 em x=66 e x=194 e descendo até y=66 à noite
  const solarCurvePathD = (() => {
    const pts: string[] = [];
    const steps = 64;
    for (let i = 0; i <= steps; i++) {
      const x = 2 + (i / steps) * 256;
      const cosVal = Math.cos((Math.PI / 2) * ((x - 130) / 64));
      const y =
        cosVal >= 0 ? 44 - cosVal * 32 : 44 - cosVal * 22;
      pts.push(`${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`);
    }
    return pts.join(' ');
  })();

  const progressPercent = Math.round(watchState.progressRatio * 100);

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · JANELA DE CONTEXTO CANVAS (v1.2.1)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Localização no Clima · Notificação do Canvas (Ativar/Desativar) · Troca Agenda em ≤15m
          </p>
        </div>

        {/* Toggle Bluetooth / Offline Autonomous Test */}
        <button
          type="button"
          onClick={onToggleBluetooth}
          className={`px-3.5 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 border transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
            watchCache.isBluetoothConnected
              ? 'bg-emerald-500/15 border-emerald-500/50 text-emerald-300 hover:bg-emerald-500/25'
              : 'bg-amber-500/20 border-amber-500 text-amber-300 hover:bg-amber-500/30'
          }`}
        >
          {watchCache.isBluetoothConnected ? (
            <>
              <Bluetooth className="w-3.5 h-3.5" />
              <span>Conectado ao Smartphone</span>
            </>
          ) : (
            <>
              <BluetoothOff className="w-3.5 h-3.5" />
              <span>Modo Autônomo Offline Ativo</span>
            </>
          )}
        </button>
      </div>

      {/* Watch Mode Switcher & Quick Context Bar */}
      <div className="space-y-2 my-3">
        <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-xl border border-slate-800/80">
          <button
            type="button"
            onClick={() => setWatchScreenMode('wff')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              watchScreenMode === 'wff'
                ? 'bg-slate-800 text-amber-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Watch className="w-3.5 h-3.5" />
            <span>Watch Face v1.2.1</span>
          </button>
          <button
            type="button"
            onClick={() => setWatchScreenMode('tile')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              watchScreenMode === 'tile'
                ? 'bg-slate-800 text-amber-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span>Tile Top 3</span>
          </button>
          <button
            type="button"
            onClick={() => setWatchScreenMode('cache')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              watchScreenMode === 'cache'
                ? 'bg-slate-800 text-amber-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Database className="w-3.5 h-3.5" />
            <span>Cache Room ({watchCache.cachedEvents.length})</span>
          </button>
        </div>

        {/* Seletor Rápido da Janela de Contexto + Simulador dos 3 Gatilhos de Mudança de Clima */}
        <div className="flex flex-wrap items-center justify-between gap-2 bg-slate-950/90 border border-slate-800/80 rounded-xl px-3 py-2">
          <div className="flex flex-wrap items-center gap-1">
            <span className="text-[11px] font-mono text-slate-400 mr-1">Janela:</span>
            {contextModes.map((m) => {
              const active = config.contextWindowMode === m.id;
              return (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => {
                    setTapOverrideState(null);
                    onUpdateConfig({ contextWindowMode: m.id });
                  }}
                  className={`px-2 py-1 rounded-lg text-[11px] font-semibold transition-colors cursor-pointer ${
                    active
                      ? 'bg-amber-400 text-slate-950'
                      : 'bg-slate-900 text-slate-300 hover:bg-slate-800'
                  }`}
                >
                  {m.label}
                </button>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              data-testid="watch-simulate-task-alert"
              onClick={handleSimulateTaskReminderAlert}
              title="Simula foco prioritário de tarefa sincronizada via Google Tasks API"
              className={`px-2 py-1 rounded-lg text-[11px] font-semibold flex items-center gap-1 border transition-colors cursor-pointer ${
                config.hasActiveWatchTaskAlert
                  ? 'bg-emerald-500/20 border-emerald-400 text-emerald-300'
                  : 'bg-slate-900 border-slate-700 text-slate-300 hover:border-slate-600'
              }`}
            >
              <CheckSquare className="w-3 h-3 text-emerald-400" />
              <span>
                {config.hasActiveWatchTaskAlert
                  ? 'Foco Google Tasks: ATIVO'
                  : 'Simular Foco Tarefa'}
              </span>
            </button>

            <button
              type="button"
              onClick={handleCycleWeatherNotif}
              title="Testa os gatilhos de mudança de temperatura e condição climática (chuva/tempestade)"
              className={`px-2.5 py-1 rounded-lg text-[11px] font-semibold flex items-center gap-1.5 border transition-colors cursor-pointer ${
                hasActiveWatchWeatherAlert
                  ? 'bg-sky-500/20 border-sky-400 text-sky-300'
                  : 'bg-slate-900 border-slate-700 text-slate-300 hover:border-slate-600'
              }`}
            >
              <BellRing className="w-3 h-3 text-sky-400" />
              <span>
                {hasActiveWatchWeatherAlert
                  ? `${weatherNotifSource}: ATIVO`
                  : 'Simular Gatilho de Clima'}
              </span>
            </button>
          </div>
        </div>

        {/* Opções Diretas do App do Relógio (:wear): Ativar/Desativar Notificação do Canvas, Notificação Agenda 15m e Atualizar Localização */}
        <div className="flex flex-wrap items-center justify-between gap-1.5 bg-slate-950/75 border border-slate-800/80 rounded-xl px-3 py-1.5 text-[11px]">
          <div className="flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              data-testid="watch-toggle-canvas-notif"
              onClick={() =>
                onUpdateConfig({
                  notifyWatchOnCanvasChange: !(
                    config.notifyWatchOnCanvasChange ?? true
                  ),
                })
              }
              className={`px-2 py-1 rounded-lg font-semibold border transition-colors cursor-pointer ${
                config.notifyWatchOnCanvasChange ?? true
                  ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-300'
                  : 'bg-slate-900 border-slate-700 text-slate-400'
              }`}
            >
              Notif. Canvas (Relógio):{' '}
              {(config.notifyWatchOnCanvasChange ?? true) ? 'ATIVADA' : 'DESATIVADA'}
            </button>

            <button
              type="button"
              data-testid="watch-toggle-agenda-15m-notif"
              onClick={() =>
                onUpdateConfig({
                  notifyAgenda15MinEnabled: !(
                    config.notifyAgenda15MinEnabled ?? true
                  ),
                })
              }
              className={`px-2 py-1 rounded-lg font-semibold border transition-colors cursor-pointer ${
                config.notifyAgenda15MinEnabled ?? true
                  ? 'bg-amber-500/15 border-amber-500/40 text-amber-300'
                  : 'bg-slate-900 border-slate-700 text-slate-400'
              }`}
            >
              Notif. Agenda (15m):{' '}
              {(config.notifyAgenda15MinEnabled ?? true) ? 'OPCIONAL ON' : 'OPCIONAL OFF'}
            </button>
          </div>

          <button
            type="button"
            data-testid="watch-force-refresh-weather-location"
            onClick={handleForceRefreshWatchWeatherLocation}
            className="px-2 py-1 rounded-lg font-mono text-[10.5px] bg-sky-500/15 border border-sky-500/40 text-sky-300 hover:bg-sky-500/25 transition-colors cursor-pointer"
          >
            📍 Forçar Atualiz. Local ({config.weatherLocationName ?? 'São Paulo, SP'})
          </button>
        </div>

        {/* Status do Tempo Fixo Unificado (Toque no Canvas vs Prioridade Máxima com Notificação no Relógio) */}
        {isTapOverrideValid && tapOverrideState && (
          <div
            data-testid="watch-tap-hold-banner"
            className="flex items-center justify-between gap-2 bg-amber-500/10 border border-amber-500/35 rounded-xl px-3 py-1.5 text-[11px]"
          >
            <span className="text-amber-200 font-medium">
              Toque no Canvas: exibindo <strong>{contextLabels[tapOverrideState.mode]}</strong> temporariamente (retorna ao padrão <strong>{contextLabels[primaryCtx]}</strong> em <strong>{tapRemainingMins} min</strong> de {unifiedHoldMins}m)
            </span>
            <button
              type="button"
              onClick={() => setTapOverrideState(null)}
              className="text-amber-300 hover:text-amber-100 font-mono underline cursor-pointer shrink-0"
            >
              Voltar agora
            </button>
          </div>
        )}

        {showWatchForcedNotification && (
          <div
            data-testid="watch-forced-canvas-notification"
            className="flex items-center justify-between gap-2 bg-emerald-500/15 border border-emerald-500/40 rounded-xl px-3 py-1.5 text-[11px]"
          >
            <div className="flex items-center gap-2 min-w-0">
              <BellRing className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
              <span className="text-emerald-200 truncate">
                <strong>Notificação no Relógio:</strong> Canvas alterado para{' '}
                <strong>{contextLabels[watchState.effectiveContextMode]}</strong> ({forcedNotificationDetail}) • Fixo por {watchState.effectiveContextMode === 'EVENTS' ? 15 : unifiedHoldMins}m
              </span>
            </div>
            <button
              type="button"
              onClick={() => setDismissedNotifKey(forcedNotifKey)}
              className="text-emerald-300 hover:text-emerald-100 font-mono underline cursor-pointer shrink-0"
            >
              OK
            </button>
          </div>
        )}
      </div>

      {/* Center Circular Watch Bezel (450x450 logical representation) */}
      <div className="flex flex-col items-center justify-center my-1">
        <div
          data-testid="wear-watch-face"
          className="relative w-[376px] h-[376px] sm:w-[396px] sm:h-[396px] rounded-full bg-black border-[8px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-2.5 px-7 select-none overflow-hidden"
        >
          {/* SVG Overlay Renderizando os Arcos Laterais, Ícones Inclinados e a Barra Inferior Estilo Tubo de Ensaio */}
          {watchScreenMode === 'wff' && (
            <svg
              viewBox="0 0 450 450"
              className="absolute inset-0 w-full h-full pointer-events-none z-0"
            >
              {/* SLOT 7: Arco Lateral Esquerdo Bicolor (250°..290°) + Única Bolinha Laranja */}
              <path
                d="M 18.0 225.0 A 207 207 0 0 0 30.5 295.8"
                fill="none"
                stroke="#2E3B38"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <path
                d="M 30.5 154.2 A 207 207 0 0 0 18.0 225.0"
                fill="none"
                stroke="#453D20"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <circle cx="18.0" cy="225.0" r="8" fill="#F97316" />

              {/* SLOT 8: Arco Lateral Direito (70°..110°) */}
              <path
                d="M 419.5 154.2 A 207 207 0 0 1 419.5 295.8"
                fill="none"
                stroke="#25302B"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <path
                d="M 419.5 154.2 A 207 207 0 0 1 430.5 245.0"
                fill="none"
                stroke="#86EFAC"
                strokeWidth="18"
                strokeLinecap="round"
              />

              {/* BARRA INFERIOR DE BATERIA ESTILO TUBO DE ENSAIO (164°..196°) */}
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 282.0 424.0"
                fill="none"
                stroke="#22261A"
                strokeWidth="22"
                strokeLinecap="round"
              />
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 282.0 424.0"
                fill="none"
                stroke="#3F4430"
                strokeWidth="17"
                strokeLinecap="round"
              />
              <line x1="182" y1="414" x2="177" y2="439" stroke="#14170F" strokeWidth="3.2" />
              <line x1="192" y1="416" x2="189" y2="441" stroke="#14170F" strokeWidth="3.2" />
              <line x1="203" y1="418" x2="201" y2="443" stroke="#14170F" strokeWidth="3.2" />
              <line x1="214" y1="419" x2="213" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="225" y1="419" x2="225" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="236" y1="419" x2="237" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="247" y1="418" x2="249" y2="443" stroke="#14170F" strokeWidth="3.2" />
              <line x1="258" y1="416" x2="261" y2="441" stroke="#14170F" strokeWidth="3.2" />
              <line x1="268" y1="414" x2="273" y2="439" stroke="#14170F" strokeWidth="3.2" />
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 246.0 431.0"
                fill="none"
                stroke="#88D4DC"
                strokeOpacity="0.8"
                strokeWidth="11.5"
                strokeLinecap="round"
              />

              {/* Ícone de Bateria Dourado Inclinado a +26° na curva esquerda inferior */}
              <g transform="translate(130, 408) rotate(26) translate(-14, -16)">
                <rect x="10" y="2" width="8" height="4" rx="1.5" fill="#F5C438" />
                <rect x="6" y="6" width="16" height="22" rx="3.5" fill="#F5C438" />
              </g>

              {/* Percentual 62% Inclinado a -26° na curva direita inferior */}
              <g transform="translate(324, 406) rotate(-26)">
                <text
                  x="0"
                  y="6"
                  textAnchor="middle"
                  fill="#88D4DC"
                  fontSize="21"
                  fontWeight="bold"
                  fontFamily="monospace"
                >
                  62%
                </text>
              </g>
            </svg>
          )}

          {/* TOPO NO DPI SAMSUNG: SEX. 06 (25sp BOLD) + Relógio (70sp NORMAL) */}
          <div className="text-center w-full z-10 pt-0.5">
            <div className="text-[18px] font-bold tracking-wide flex items-center justify-center gap-2 leading-none">
              <span className="text-[#F5C438]">{weekdayUpper}</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[50px] font-normal font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — Janela de Contexto Dinâmica (Slot 1 SMALL_IMAGE) */}
          {watchScreenMode === 'wff' && (
            <>
              {/* Ícones e textos dos arcos laterais INCLINADOS seguindo a curvatura da tela */}
              <span className="absolute left-6 top-[102px] rotate-[28deg] text-[14px] font-bold text-[#F5C438] z-10">
                ⏳
              </span>
              <span className="absolute left-5 top-[278px] -rotate-[28deg] text-[13px] font-bold font-mono text-[#88D4DC] z-10">
                0
              </span>
              <span className="absolute right-6 top-[102px] -rotate-[28deg] w-5 h-5 rounded-full bg-[#F5C438] text-black text-[11px] font-bold flex items-center justify-center z-10">
                ✓
              </span>
              <span className="absolute right-5 top-[278px] rotate-[28deg] text-[13px] font-bold font-mono text-slate-200 z-10">
                0
              </span>

              {/* ÚNICO QUADRO CENTRAL (x=74, y=110, w=302, h=136) — Toque no Canvas alterna para o próximo contexto (PendingIntent.getBroadcast) */}
              <div
                data-testid="watch-context-canvas"
                onClick={handleTapCanvasToCycle}
                title="Toque no Canvas para alternar para o próximo contexto (Agenda → Clima → Sol → Passos → Sono)"
                className="w-[82%] h-[116px] rounded-[16px] bg-[#080A0E] border border-[#1B2430] hover:border-amber-400/50 active:scale-[0.99] transition-all cursor-pointer select-none px-3 py-1.5 flex flex-col justify-between z-10 -mt-1.5 overflow-hidden"
              >
                {/* MODO 1: PRÓXIMOS EVENTOS (EVENTS — Título dinâmico "Hoje" ou "Amanhã" + Barra de Progresso 0% → 100%) */}
                {effectiveMode === 'EVENTS' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <Calendar className="w-4 h-4 text-[#F5C438] shrink-0" />
                        <span
                          data-testid="agenda-canvas-day-header"
                          className="text-[14.5px] font-bold text-[#F5C438] leading-none"
                        >
                          {watchState.agendaDayHeader}
                        </span>
                      </div>
                      {watchState.isWithinTurbo15MinWindow ? (
                        <span className="text-[10px] font-mono font-bold px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 flex items-center gap-0.5">
                          <Zap className="w-2.5 h-2.5" />
                          1m Turbo
                        </span>
                      ) : (
                        <span className="text-[10px] font-mono text-slate-400">
                          Janela {config.progressWindowMinutes === 30 ? '30m' : `${config.progressWindowMinutes / 60}h`} ({progressPercent}%)
                        </span>
                      )}
                    </div>

                    {/* LINHA 1: Hora Branca + Nome Dourado + Timer Ciano + Barra 5px (0% a 100%) */}
                    <div className="w-full space-y-1">
                      <div className="flex items-center justify-between gap-1 text-[14.5px] leading-tight">
                        <div className="flex items-center min-w-0">
                          {ev1 ? (
                            <>
                              <span className="w-[46px] font-mono font-bold text-white shrink-0">
                                {formatTimeHHMM(ev1.startEpochMs)}
                              </span>
                              <span className="font-bold text-[#F5C438] truncate">
                                {ev1.title}
                              </span>
                            </>
                          ) : (
                            <span className="text-[#64748B] truncate">
                              Sem compromissos
                            </span>
                          )}
                        </div>
                        {ev1 && (
                          <span className="font-mono font-bold text-[12.5px] text-[#67E8F9] shrink-0 ml-1">
                            {watchState.formattedCountdown}
                          </span>
                        )}
                      </div>
                      <div className="w-full h-[5px] bg-[#252E3A] rounded-full overflow-hidden">
                        <div
                          data-testid="event-progress-fill"
                          className="h-full bg-[#F5C438] rounded-full transition-all duration-200"
                          style={{
                            width: `${progressPercent}%`,
                          }}
                        />
                      </div>
                    </div>

                    {/* LINHA 2: Hora Branca + Título Dourado ou Ponto Guia Ultra-Sutil */}
                    <div className="w-full flex items-center text-[14.5px] leading-tight min-h-[18px]">
                      {ev2 ? (
                        <>
                          <span className="w-[46px] font-mono font-bold text-white shrink-0">
                            {formatTimeHHMM(ev2.startEpochMs)}
                          </span>
                          <span className="font-bold text-[#F5C438] truncate">
                            {ev2.title}
                          </span>
                        </>
                      ) : (
                        <span className="font-mono font-bold text-[#263344] select-none">•</span>
                      )}
                    </div>

                    {/* LINHA 3: Hora Branca + Título Dourado ou Ponto Guia Ultra-Sutil */}
                    <div className="w-full flex items-center text-[14.5px] leading-tight min-h-[18px]">
                      {ev3 ? (
                        <>
                          <span className="w-[46px] font-mono font-bold text-white shrink-0">
                            {formatTimeHHMM(ev3.startEpochMs)}
                          </span>
                          <span className="font-bold text-[#F5C438] truncate">
                            {ev3.title}
                          </span>
                        </>
                      ) : (
                        <span className="font-mono font-bold text-[#263344] select-none">•</span>
                      )}
                    </div>
                  </>
                )}

                {/* MODO 2: CLIMA (WEATHER — Título apenas "Clima", condição climática à direita e subtítulo com Localização + Hora Atualizada) */}
                {effectiveMode === 'WEATHER' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <CloudRain className="w-4 h-4 text-[#67E8F9] shrink-0" />
                        <span
                          data-testid="weather-canvas-title"
                          className="text-[15px] font-bold text-[#F5C438] leading-none"
                        >
                          Clima
                        </span>
                      </div>
                      <span className="text-[12px] font-mono font-bold text-[#67E8F9] shrink-0">
                        {hasActiveWatchWeatherAlert
                          ? 'Chuva • 75%'
                          : 'Parcial nublado • 25%'}
                      </span>
                    </div>

                    <div
                      data-testid="weather-canvas-location-subtitle"
                      className="text-[11px] font-semibold text-slate-200 truncate leading-none"
                    >
                      📍{' '}
                      {formatCompactWeatherLocation(
                        config.weatherLocationName ?? 'São Paulo, SP'
                      )}{' '}
                      • Atualizado{' '}
                      {config.weatherUpdatedHHMM ??
                        formatTimeHHMM(watchCache.lastSyncEpochMs)}
                    </div>

                    {/* Curva Bezier de Temperatura no Canvas 604x272 com números maiores */}
                    <div className="relative w-full h-[46px] flex items-center">
                      <svg viewBox="0 0 240 54" className="w-full h-full overflow-visible">
                        <defs>
                          <linearGradient id="tempFill" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#67E8F9" stopOpacity="0.34" />
                            <stop offset="100%" stopColor="#67E8F9" stopOpacity="0.0" />
                          </linearGradient>
                        </defs>
                        <path
                          d="M 18 18 C 42 18, 50 23, 74 23 C 98 23, 106 37, 130 37 C 154 37, 162 43, 186 43 C 202 43, 212 32, 224 32 L 224 51 L 18 51 Z"
                          fill="url(#tempFill)"
                        />
                        <path
                          d="M 18 18 C 42 18, 50 23, 74 23 C 98 23, 106 37, 130 37 C 154 37, 162 43, 186 43 C 202 43, 212 32, 224 32"
                          fill="none"
                          stroke="#67E8F9"
                          strokeWidth="3"
                          strokeLinecap="round"
                        />
                        {[
                          { x: 18, y: 18, t: '29°', h: 'Agora', first: true },
                          { x: 74, y: 23, t: '27°', h: '16h', first: false },
                          { x: 130, y: 37, t: '24°', h: '18h', first: false },
                          { x: 186, y: 43, t: '23°', h: '20h', first: false },
                          { x: 224, y: 32, t: '25°', h: '22h', first: false },
                        ].map((pt) => (
                          <g key={pt.h}>
                            <circle
                              cx={pt.x}
                              cy={pt.y}
                              r={pt.first ? '3.8' : '3.2'}
                              fill={pt.first ? '#F5C438' : '#67E8F9'}
                            />
                            <text
                              x={pt.x}
                              y={pt.y - 5.5}
                              textAnchor="middle"
                              fill="#FFFFFF"
                              fontSize="11.5"
                              fontWeight="bold"
                              fontFamily="monospace"
                            >
                              {pt.t}
                            </text>
                          </g>
                        ))}
                      </svg>
                    </div>

                    <div className="flex items-center justify-between text-[11px] font-bold font-mono text-slate-300 px-1 leading-none">
                      <span>Agora</span>
                      <span>16h</span>
                      <span className="text-sky-300">18h</span>
                      <span>20h</span>
                      <span>22h</span>
                    </div>
                  </>
                )}

                {/* MODO 3: PASSOS SEMANAIS (STEPS — DPI Ampliado) */}
                {effectiveMode === 'STEPS' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <Footprints className="w-4 h-4 text-[#22C55E] shrink-0" />
                        <span className="text-[14.5px] font-bold text-[#F5C438] leading-none">
                          Passos
                        </span>
                      </div>
                      <span className="text-[12px] font-mono font-bold text-[#67E8F9]">
                        50.455 sem.
                      </span>
                    </div>

                    <div className="flex items-end justify-between gap-1.5 h-[58px] pt-2 px-0.5">
                      {weeklyStepsData.map((d) => {
                        const heightPct = Math.round((d.steps / 9500) * 100);
                        return (
                          <div
                            key={d.day}
                            className="flex-1 flex flex-col items-center justify-end h-full"
                          >
                            <span
                              className={`text-[9.5px] font-mono font-bold mb-0.5 leading-none ${
                                d.isToday ? 'text-white' : 'text-slate-300'
                              }`}
                            >
                              {(d.steps / 1000).toFixed(1)}k
                            </span>
                            <div className="w-full h-[34px] bg-[#1C2632] rounded-sm flex items-end overflow-hidden">
                              <div
                                className={`w-full rounded-sm ${
                                  d.isToday ? 'bg-[#F5C438]' : 'bg-[#22C55E]'
                                }`}
                                style={{ height: `${heightPct}%` }}
                              />
                            </div>
                          </div>
                        );
                      })}
                    </div>

                    <div className="flex items-center justify-between text-[10.5px] font-mono font-bold px-0.5 leading-none">
                      {weeklyStepsData.map((d) => (
                        <span
                          key={d.day}
                          className={
                            d.isToday ? 'text-[#F5C438]' : 'text-slate-300'
                          }
                        >
                          {d.day}
                        </span>
                      ))}
                    </div>
                  </>
                )}

                {/* MODO 4: NASCER E PÔR DO SOL (SUN — Com curva noturna abaixo do horizonte fiel à imagem One UI Watch) */}
                {effectiveMode === 'SUN' && (
                  <div
                    data-testid="sun-context-canvas"
                    className="flex flex-col justify-between h-full py-0.5"
                  >
                    {/* Linha Superior: Ícone Verde Nascer + 05:27 à esquerda | 17:40 + Ícone Azul Pôr do Sol à direita */}
                    <div className="flex items-center justify-between px-0.5">
                      <div className="flex items-center gap-1.5">
                        <svg viewBox="0 0 28 24" className="w-6 h-5 shrink-0">
                          <circle cx="14" cy="11" r="8" fill="#5D7B1E" opacity="0.55" />
                          <circle cx="14" cy="11" r="5.5" fill="#C9F31D" />
                          <path
                            d="M 2 19 L 9 19 L 14 14 L 19 19 L 26 19"
                            fill="none"
                            stroke="#84B126"
                            strokeWidth="2.4"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                        <span className="text-[16px] font-bold font-mono text-white leading-none">
                          {config.solarSunriseHHMM ?? '05:27'}
                        </span>
                      </div>

                      <div className="flex items-center gap-1.5">
                        <span className="text-[16px] font-bold font-mono text-white leading-none">
                          {config.solarSunsetHHMM ?? '17:40'}
                        </span>
                        <svg viewBox="0 0 28 24" className="w-6 h-5 shrink-0">
                          <circle cx="14" cy="11" r="8" fill="#46608C" opacity="0.6" />
                          <circle cx="14" cy="11" r="5.5" fill="#9AC1FF" />
                          <path
                            d="M 2 17 L 9 17 L 14 22 L 19 17 L 26 17"
                            fill="none"
                            stroke="#82A3D8"
                            strokeWidth="2.4"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                      </div>
                    </div>

                    {/* Gráfico Solar Astronômico com Gradiente Crepuscular, Horizonte Azul, Curva Dia+Noite e Símbolos ↑ ☼ ↓ */}
                    <div className="relative w-full flex-1 mt-0.5">
                      <svg viewBox="0 0 260 72" className="w-full h-full overflow-visible">
                        <defs>
                          <linearGradient id="twilightGrad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#1B2436" stopOpacity="0.06" />
                            <stop offset="55%" stopColor="#2E3E5C" stopOpacity="0.45" />
                            <stop offset="100%" stopColor="#4F6896" stopOpacity="0.72" />
                          </linearGradient>
                        </defs>

                        {/* Faixa de gradiente crepuscular acima da linha do horizonte */}
                        <rect
                          x="2"
                          y="10"
                          width="256"
                          height="34"
                          fill="url(#twilightGrad)"
                        />

                        {/* 3 linhas verticais pontilhadas (Nascer x=66, Meio-dia x=130, Pôr do Sol x=194) */}
                        {[66, 130, 194].map((xPos) => (
                          <line
                            key={xPos}
                            x1={xPos}
                            y1="6"
                            x2={xPos}
                            y2="54"
                            stroke="#788296"
                            strokeWidth="2"
                            strokeDasharray="1 5"
                            strokeLinecap="round"
                          />
                        ))}

                        {/* Linha horizontal azul-clara do horizonte (y=44) */}
                        <line
                          x1="2"
                          y1="44"
                          x2="258"
                          y2="44"
                          stroke="#8EB2F2"
                          strokeWidth="3.2"
                          strokeLinecap="round"
                        />

                        {/* Curva Cosseno Branca da Trajetória Solar (Cruza y=44 em x=66 e x=194 e desce abaixo do horizonte à noite até y=66) */}
                        <path
                          d={solarCurvePathD}
                          fill="none"
                          stroke="#FFFFFF"
                          strokeWidth="3.2"
                          strokeLinecap="round"
                        />

                        {/* Símbolo ↑ (Nascer) */}
                        <g stroke="#96A0B0" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none">
                          <line x1="66" y1="68" x2="66" y2="57" />
                          <polyline points="61,62 66,57 71,62" />
                        </g>

                        {/* Símbolo ☼ (Meio-dia Solar) */}
                        <g stroke="#96A0B0" strokeWidth="2" strokeLinecap="round" fill="none">
                          <circle cx="130" cy="62" r="3.2" />
                          <line x1="130" y1="55.5" x2="130" y2="57" />
                          <line x1="130" y1="67" x2="130" y2="68.5" />
                          <line x1="123.5" y1="62" x2="125" y2="62" />
                          <line x1="135" y1="62" x2="136.5" y2="62" />
                        </g>

                        {/* Símbolo ↓ (Pôr do Sol) */}
                        <g stroke="#96A0B0" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none">
                          <line x1="194" y1="57" x2="194" y2="68" />
                          <polyline points="189,63 194,68 199,63" />
                        </g>

                        {/* Ponto luminoso do Sol na trajetória atual (acima do horizonte de dia, abaixo do horizonte à noite) */}
                        <g data-testid="sun-position-dot" data-below-horizon={solarPoint.isBelowHorizon}>
                          <circle
                            cx={solarPoint.sunX}
                            cy={solarPoint.sunY}
                            r="8"
                            fill="#FFE699"
                            opacity="0.32"
                          />
                          <circle
                            cx={solarPoint.sunX}
                            cy={solarPoint.sunY}
                            r="5.5"
                            fill="#FFF2CC"
                            opacity="0.65"
                          />
                          <circle cx={solarPoint.sunX} cy={solarPoint.sunY} r="3.8" fill="#FFFDF5" />
                        </g>
                      </svg>
                    </div>
                  </div>
                )}

                {/* MODO 5: RESUMO DE SONO (SLEEP — Hipnograma Health Connect / Samsung Health ou Aviso Verificado de Sem Dados para Hoje) */}
                {effectiveMode === 'SLEEP' && (
                  <div
                    data-testid="sleep-context-canvas"
                    className="flex flex-col justify-between h-full py-0.5"
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <Moon className="w-4 h-4 text-[#A78BFA] shrink-0" />
                        <span className="text-[14.5px] font-bold text-[#F5C438] leading-none">
                          Sono • Hoje
                        </span>
                      </div>
                      {(config.sleepHasDataToday ?? true) ? (
                        <span className="text-[12.5px] font-mono font-bold text-[#67E8F9]">
                          {(config.sleepScoreVisible ?? true)
                            ? `7h 42m • ${config.sleepScore ?? 86} pts`
                            : '7h 42m'}
                        </span>
                      ) : (
                        <span className="text-[10.5px] font-mono font-bold text-slate-400">
                          Sem registro
                        </span>
                      )}
                    </div>

                    {(config.sleepHasDataToday ?? true) ? (
                      <>
                        <div className="text-[11px] font-semibold text-slate-300 leading-none">
                          {config.sleepBedtimeHHMM ?? '23:18'} → {config.sleepWakeHHMM ?? '07:00'}
                          {(config.sleepScoreVisible ?? true) ? ' • Excelente' : ''}
                        </div>

                        {/* Barra de Estágios do Sono (Hipnograma: Acordado, REM, Leve, Profundo) */}
                        <div className="w-full h-[20px] rounded-md overflow-hidden flex gap-[2px] bg-[#131B26] p-[2px]">
                          <div className="h-full w-[5%] bg-[#FB7185] rounded-xs" title="Acordado" />
                          <div className="h-full w-[22%] bg-[#60A5FA] rounded-xs" title="Sono Leve" />
                          <div className="h-full w-[19%] bg-[#6366F1] rounded-xs" title="Sono Profundo" />
                          <div className="h-full w-[18%] bg-[#38BDF8] rounded-xs" title="REM" />
                          <div className="h-full w-[21%] bg-[#60A5FA] rounded-xs" title="Sono Leve" />
                          <div className="h-full w-[15%] bg-[#6366F1] rounded-xs" title="Sono Profundo" />
                        </div>

                        <div className="flex items-center justify-between text-[10px] font-mono font-bold px-0.5 leading-none">
                          <span className="text-[#FB7185]">● Acord.</span>
                          <span className="text-[#38BDF8]">● REM</span>
                          <span className="text-[#60A5FA]">● Leve</span>
                          <span className="text-[#818CF8]">● Prof.</span>
                        </div>
                      </>
                    ) : (
                      <div className="flex flex-col justify-center flex-1 space-y-1">
                        <div
                          data-testid="sleep-empty-today-message"
                          className="text-[13px] font-bold text-slate-200 leading-tight"
                        >
                          Não há dados de sono para hoje
                        </div>
                        <div className="text-[10.5px] font-medium text-slate-400 leading-tight">
                          Sincronizado e verificado pelo app (Health Connect / Samsung Health)
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* MODO 6: QUADRO DE TAREFAS (TASKS — Hierarquia Estrita de 4 níveis: Hoje c/ hora -> Amanhã c/ hora -> Próximas c/ data -> Sem data e Atrasadas <=30d em vermelho) */}
                {effectiveMode === 'TASKS' && (
                  <div
                    data-testid="tasks-context-canvas"
                    data-tasks-bucket={resolvedTasksState.bucket}
                    className="flex flex-col justify-between h-full py-0.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <CheckSquare className="w-4 h-4 text-[#22C55E] shrink-0" />
                        <span
                          data-testid="watch-tasks-header-title"
                          className="text-[14px] font-bold text-[#F5C438] leading-none truncate"
                        >
                          {resolvedTasksState.headerTitle}
                        </span>
                      </div>
                      <span className="text-[10.5px] font-mono font-bold text-[#67E8F9] shrink-0">
                        {resolvedTasksState.rightBadgeText}
                      </span>
                    </div>

                    {activeTasksForWatch.length === 0 ? (
                      <div className="flex flex-col justify-center flex-1 space-y-1">
                        <div className="text-[13px] font-bold text-slate-200">
                          Não há tarefas pendentes
                        </div>
                        <div className="text-[10.5px] text-slate-400">
                          Todas as tarefas foram concluídas
                        </div>
                      </div>
                    ) : (
                      <div className="flex flex-col justify-start gap-2.5 flex-1 pt-1.5">
                        {activeTasksForWatch.slice(0, 3).map((task, idx) => (
                          <div
                            key={task.id}
                            data-testid={`watch-task-row-${task.id}`}
                            data-overdue={task.isRedOverdue ? 'true' : 'false'}
                            onClick={(e) => handleToggleTaskItem(task.id, e)}
                            title="Clique no check de não feito para concluir e avançar a fila de tarefas"
                            className="flex items-center justify-between gap-1.5 text-[12px] leading-tight hover:bg-white/5 rounded px-0.5 cursor-pointer"
                          >
                            <div className="flex items-center gap-1.5 min-w-0">
                              <span
                                className={`w-3.5 h-3.5 rounded-[4px] border flex items-center justify-center text-[9px] font-bold shrink-0 text-transparent hover:text-[#22C55E] ${
                                  task.isRedOverdue
                                    ? 'border-[#EF4444]'
                                    : 'border-[#67E8F9]'
                                }`}
                              >
                                ✓
                              </span>
                              {resolvedTasksState.showTime &&
                                task.displayTimeHHMM && (
                                  <span
                                    data-testid={`watch-task-time-${task.id}`}
                                    className="text-[11px] font-mono font-bold text-white shrink-0"
                                  >
                                    {task.displayTimeHHMM}
                                  </span>
                                )}
                              <span
                                data-testid={`watch-task-title-${task.id}`}
                                className={`truncate font-bold ${
                                  task.isRedOverdue
                                    ? 'text-[#F87171]'
                                    : idx === 0
                                    ? 'text-[#F5C438]'
                                    : 'text-slate-100'
                                }`}
                              >
                                {task.title}
                              </span>
                            </div>
                            <span
                              className={`text-[9px] font-mono font-bold px-1 py-0.2 rounded shrink-0 ${
                                task.isRedOverdue
                                  ? 'bg-red-500/25 text-red-300'
                                  : 'bg-sky-500/20 text-sky-300'
                              }`}
                            >
                              {task.sourceLabel}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {/* MODO 7: CONSUMO DE BATERIA DO RELÓGIO (BATTERY — Apenas bateria do relógio no topo + gráfico progressivo da esquerda para a direita sem polling extra) */}
                {effectiveMode === 'BATTERY' &&
                  (() => {
                    const winHours = config.batteryGraphWindowHours ?? 12;
                    const stepMs = (winHours * 3600_000) / 4;
                    const timeLabels = [0, 1, 2, 3, 4].map((idx) => {
                      if (idx === 4) return 'Agora';
                      const ptDate = new Date(
                        currentWatchTimeMs - (4 - idx) * stepMs
                      );
                      return `${String(ptDate.getHours()).padStart(2, '0')}h`;
                    });
                    const points = [
                      {
                        x: 18,
                        y: 16,
                        pct: '96%',
                        h: timeLabels[0],
                        isNow: false,
                      },
                      {
                        x: 74,
                        y: 22,
                        pct: '92%',
                        h: timeLabels[1],
                        isNow: false,
                      },
                      {
                        x: 130,
                        y: 28,
                        pct: '88%',
                        h: timeLabels[2],
                        isNow: false,
                      },
                      {
                        x: 186,
                        y: 34,
                        pct: '85%',
                        h: timeLabels[3],
                        isNow: false,
                      },
                      {
                        x: 224,
                        y: 39,
                        pct: '82%',
                        h: timeLabels[4],
                        isNow: true,
                      },
                    ];
                    return (
                      <div
                        data-testid="battery-context-canvas"
                        className="flex flex-col justify-between h-full py-0.5"
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-1.5">
                            <span className="inline-block w-3.5 h-2.5 rounded-[2px] border border-[#22C55E] bg-[#22C55E]/30 shrink-0" />
                            <span className="text-[14.5px] font-bold text-[#F5C438] leading-none">
                              Bateria • 82%
                            </span>
                          </div>
                          <span className="text-[11.5px] font-mono font-bold text-[#67E8F9]">
                            ~24h • -2,1%/h
                          </span>
                        </div>

                        <div className="relative w-full h-[52px] flex items-center">
                          <svg
                            viewBox="0 0 240 54"
                            className="w-full h-full overflow-visible"
                          >
                            <defs>
                              <linearGradient
                                id="watchBatteryFill"
                                x1="0"
                                y1="0"
                                x2="0"
                                y2="1"
                              >
                                <stop
                                  offset="0%"
                                  stopColor="#22C55E"
                                  stopOpacity="0.34"
                                />
                                <stop
                                  offset="100%"
                                  stopColor="#22C55E"
                                  stopOpacity="0.0"
                                />
                              </linearGradient>
                            </defs>
                            <path
                              d="M 18 16 C 46 16, 46 22, 74 22 C 102 22, 102 28, 130 28 C 158 28, 158 34, 186 34 C 205 34, 205 39, 224 39 L 224 51 L 18 51 Z"
                              fill="url(#watchBatteryFill)"
                            />
                            <path
                              d="M 18 16 C 46 16, 46 22, 74 22 C 102 22, 102 28, 130 28 C 158 28, 158 34, 186 34 C 205 34, 205 39, 224 39"
                              fill="none"
                              stroke="#22C55E"
                              strokeWidth="3"
                              strokeLinecap="round"
                            />
                            {points.map((pt, idx) => (
                              <g key={idx}>
                                <circle
                                  cx={pt.x}
                                  cy={pt.y}
                                  r={pt.isNow ? '3.8' : '3.2'}
                                  fill={pt.isNow ? '#F5C438' : '#22C55E'}
                                />
                                <text
                                  x={pt.x}
                                  y={pt.y - 5.5}
                                  textAnchor="middle"
                                  fill="#FFFFFF"
                                  fontSize="11"
                                  fontWeight="bold"
                                  fontFamily="monospace"
                                >
                                  {pt.pct}
                                </text>
                              </g>
                            ))}
                          </svg>
                        </div>

                        <div className="flex items-center justify-between text-[10.5px] font-bold font-mono text-slate-300 px-1 leading-none">
                          {points.map((pt, idx) => (
                            <span
                              key={idx}
                              className={pt.isNow ? 'text-[#F5C438]' : ''}
                            >
                              {pt.h}
                            </span>
                          ))}
                        </div>
                      </div>
                    );
                  })()}
              </div>

              {/* DOCK EM CARTUCHO ENVOLVENTE (x=84, y=268, w=282, h=92) COM APENAS 2 COMPLICATIONS MAIORES E CONTORNO FORTE */}
              <div
                title="Cartucho Externo Envolvente (x=84, y=268, w=282, h=92, r=46)"
                className="rounded-full bg-[#161D1C] border-[1.5px] border-[#283533] px-2 py-1.5 flex items-center justify-center gap-2.5 z-10 mt-0.5 mb-10 shadow-inner"
              >
                {/* Slot #4: Cartucho Interno Esquerdo */}
                <div
                  title="Slot #4: Complication Cartucho Esquerdo"
                  className="w-[104px] h-[56px] rounded-full bg-[#263230] border-[1.8px] border-[#455955] flex flex-col items-center justify-center px-2"
                >
                  <Footprints className="w-4 h-4 text-[#22C55E]" />
                  <span className="text-[15px] font-bold text-white leading-tight mt-0.5 tracking-tight">
                    2.435
                  </span>
                </div>

                {/* Slot #5: Cartucho Interno Direito */}
                <div
                  title="Slot #5: Complication Cartucho Direito"
                  className="w-[104px] h-[56px] rounded-full bg-[#263230] border-[1.8px] border-[#455955] flex flex-col items-center justify-center px-2"
                >
                  <Sun className="w-4 h-4 text-[#F97316]" />
                  <span className="text-[15px] font-bold text-white leading-tight mt-0.5 tracking-tight">
                    {config.solarSunsetHHMM ?? '17:40'}
                  </span>
                </div>
              </div>
            </>
          )}

          {/* VIEW 2: Wear OS Tile (UpNextThreeEventsTileService) */}
          {watchScreenMode === 'tile' && (
            <div className="w-[84%] flex-1 my-2 flex flex-col justify-between bg-slate-900/60 border border-slate-800 rounded-2xl p-3">
              <div className="text-center">
                <span className="text-[10px] font-mono text-amber-400 font-semibold">
                  TILE NATIVO · PRÓXIMO EM {watchState.formattedCountdown}
                </span>
              </div>
              <div className="space-y-1.5 my-auto">
                {watchState.upcomingThree.map((ev) => (
                  <div
                    key={ev.id}
                    className="bg-slate-950/90 border border-slate-800 rounded-lg px-2.5 py-1.5"
                  >
                    <div className="flex items-center justify-between text-[10px] font-mono text-sky-400">
                      <span>
                        {formatTimeHHMM(ev.startEpochMs)} – {formatTimeHHMM(ev.endEpochMs)}
                      </span>
                      <span>
                        {formatDayRelative(ev.startEpochMs, currentWatchTimeMs)}
                      </span>
                    </div>
                    <div className="text-xs font-semibold text-slate-100 truncate">
                      {ev.title}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* VIEW 3: Watch Local Room Database Inspector */}
          {watchScreenMode === 'cache' && (
            <div className="w-[84%] flex-1 my-2 flex flex-col justify-between bg-slate-950/90 border border-slate-800 rounded-2xl p-3 text-left overflow-y-auto">
              <div className="text-[10px] font-mono text-sky-400 border-b border-slate-800 pb-1">
                ROOM DB (AUTÔNOMO ATÉ +{watchCache.syncedHorizonDays} DIAS)
              </div>
              <div className="space-y-1 my-1 text-[11px] font-mono">
                <div>
                  Status BT: {watchCache.isBluetoothConnected ? 'ONLINE' : 'OFFLINE (CACHE)'}
                </div>
                <div>Contexto Ativo: {effectiveMode}</div>
                <div>
                  Progresso ({config.progressWindowMinutes}m): {progressPercent}%
                </div>
                <div className="text-amber-300 truncate">
                  1º: {watchState.nextEvent?.title || 'Nenhum'}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Time-Travel & Autonomous Offline Simulation Bar */}
      <div className="pt-3 border-t border-slate-800">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
            <Sparkles className="w-3.5 h-3.5 text-amber-400" />
            <span>Simulador de Passagem de Tempo (Progresso 0%→100% e Sol Noturno)</span>
          </span>
          <button
            type="button"
            onClick={onResetTime}
            className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 cursor-pointer"
          >
            <RotateCcw className="w-3 h-3" />
            <span>Resetar (09:00)</span>
          </button>
        </div>

        <div className="grid grid-cols-5 gap-1.5">
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(15)}
            className="py-2 px-2 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-amber-400" />
            <span>+15 min</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(25)}
            title="Avança para dentro da janela antes do próximo evento"
            className="py-2 px-2 bg-amber-500/15 hover:bg-amber-500/25 border border-amber-500/40 rounded-lg text-xs font-mono text-amber-300 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <Zap className="w-3 h-3 text-amber-400" />
            <span>Ir p/ ≤15m</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(1)}
            className="py-2 px-2 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-sky-400" />
            <span>+1 min</span>
          </button>
          <button
            type="button"
            data-testid="watch-advance-no-events-today"
            onClick={() => onAdvanceTimeMinutes(600)}
            title="Avança +10h (após o último evento de hoje às 18:00): sem agenda hoje, o modo Auto exibe Tarefas ativas e a Agenda mostra Amanhã"
            className="py-2 px-2 bg-emerald-500/15 hover:bg-emerald-500/25 border border-emerald-500/40 rounded-lg text-xs font-mono text-emerald-300 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-emerald-400" />
            <span>Sem Hoje (+10h)</span>
          </button>
          <button
            type="button"
            onClick={() => {
              onUpdateConfig({ contextWindowMode: 'SUN' });
              const currentMins = watchDate.getHours() * 60 + watchDate.getMinutes();
              const targetMins = 22 * 60 + 42; // 22:42 (Noite)
              const delta =
                targetMins >= currentMins
                  ? targetMins - currentMins
                  : 24 * 60 - currentMins + targetMins;
              onAdvanceTimeMinutes(delta);
            }}
            title="Avança o relógio para 22:42 e abre o contexto Sol para ver o sol abaixo da linha do horizonte"
            className="py-2 px-2 bg-indigo-500/15 hover:bg-indigo-500/25 border border-indigo-500/40 rounded-lg text-xs font-mono text-indigo-300 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <Moon className="w-3 h-3 text-indigo-400" />
            <span>Noite 22:42</span>
          </button>
        </div>
      </div>
    </div>
  );
};
