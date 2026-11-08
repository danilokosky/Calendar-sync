import React, { useState, useEffect } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  PrimaryContextMode,
  WEATHER_TRIGGER_CATALOG,
  DEFAULT_TASKS_LIST,
  formatCompactWeatherLocation,
  filterActiveTasksForWatch,
  isSameCalendarDay,
  formatDayRelative,
  formatCountdownText,
} from '../utils/calendarSyncEngine';
import {
  Bluetooth,
  Calendar,
  Settings,
  Home,
  Info,
  Check,
  X,
  Plus,
  Trash2,
  AlertTriangle,
  CloudRain,
  CheckSquare,
  RefreshCw,
  Maximize2,
  Minimize2,
  GripVertical,
  ShieldAlert,
  ShieldCheck,
  Moon,
} from 'lucide-react';

interface SmartphoneCompanionSimulatorProps {
  calendars: CalendarSource[];
  events: CalendarEventItem[];
  config: SyncConfiguration;
  referenceTimeMs: number;
  lastSyncTimeMs: number;
  syncedEventsCount: number;
  isBluetoothConnected: boolean;
  onToggleCalendar: (id: string) => void;
  onUpdateConfig: (partial: Partial<SyncConfiguration>) => void;
  onAddEvent: (ev: Omit<CalendarEventItem, 'id'>) => void;
  onDeleteEvent: (id: string) => void;
  onTriggerManualSync: () => void;
}

type BottomTab = 'home' | 'settings' | 'about';

interface ModernSwitchProps {
  checked: boolean;
  onChange: () => void;
  ariaLabel: string;
}

const ModernOneUiSwitch: React.FC<ModernSwitchProps> = ({
  checked,
  onChange,
  ariaLabel,
}) => (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={ariaLabel}
    onClick={(e) => {
      e.stopPropagation();
      onChange();
    }}
    className={`w-[52px] h-[28px] rounded-full p-[3px] transition-all duration-150 active:scale-95 flex items-center cursor-pointer shrink-0 border ${
      checked
        ? 'bg-[#F5C438] border-[#FDE047]'
        : 'bg-[#2A3441] border-[#64748B]'
    }`}
  >
    <span
      className={`w-[20px] h-[20px] rounded-full transition-transform duration-150 shadow-xs ${
        checked
          ? 'translate-x-[24px] bg-[#050507]'
          : 'translate-x-0 bg-[#CBD5E1]'
      }`}
    />
  </button>
);

const DEFAULT_WIDGET_ORDER: PrimaryContextMode[] = [
  'EVENTS',
  'TASKS',
  'WEATHER',
  'SUN',
  'SLEEP',
  'STEPS',
];

export const SmartphoneCompanionSimulator: React.FC<
  SmartphoneCompanionSimulatorProps
> = ({
  calendars,
  events,
  config,
  referenceTimeMs,
  syncedEventsCount,
  isBluetoothConnected,
  onToggleCalendar,
  onUpdateConfig,
  onAddEvent,
  onDeleteEvent,
  onTriggerManualSync,
}) => {
  const [activeTab, setActiveTab] = useState<BottomTab>('home');
  const [isWearAppInstalledOnWatch, setIsWearAppInstalledOnWatch] = useState(true);

  // Estado de Pull-to-Refresh e Verificação Automática de Foco/Aba
  const [isPullRefreshing, setIsPullRefreshing] = useState(false);
  const [pullDragY, setPullDragY] = useState(0);
  const [touchStartY, setTouchStartY] = useState<number | null>(null);
  const [lastStatusCheckHHMMSS, setLastStatusCheckHHMMSS] = useState('09:00:00');

  // Permissões do Smartphone (exibidas no quadro informativo abaixo da representação do relógio)
  const [calendarPermGranted, setCalendarPermGranted] = useState(true);
  const [locationPermGranted, setLocationPermGranted] = useState(true);
  const [notifPermGranted, setNotifPermGranted] = useState(true);
  const [batteryOptIgnored, setBatteryOptIgnored] = useState(true);

  // Estados das chaves de Settings
  const [bgServiceEnabled, setBgServiceEnabled] = useState(true);
  const [notificationsSyncEnabled, setNotificationsSyncEnabled] = useState(true);
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(true);

  // Representação Virtual do Relógio no Topo e Widgets 2x2
  const [virtualWatchPreviewOverride, setVirtualWatchPreviewOverride] =
    useState<PrimaryContextMode | null>(null);
  const [draggedWidgetId, setDraggedWidgetId] =
    useState<PrimaryContextMode | null>(null);

  // Modais
  const [isSyncedCalendarsDialogOpen, setIsSyncedCalendarsDialogOpen] =
    useState(false);
  const [isWeatherAppsDialogOpen, setIsWeatherAppsDialogOpen] = useState(false);
  const [isManageTasksModalOpen, setIsManageTasksModalOpen] = useState(false);
  const [newTaskTitle, setNewTaskTitle] = useState('');
  const [newTaskDueHHMM, setNewTaskDueHHMM] = useState('Hoje');
  const [newTitle, setNewTitle] = useState('');
  const [newMinutesFromNow, setNewMinutesFromNow] = useState<number>(45);

  const widgetOrder: PrimaryContextMode[] =
    config.homeWidgetsOrder ?? DEFAULT_WIDGET_ORDER;
  const expandedWidgets: PrimaryContextMode[] = config.expandedWidgets ?? [];

  const formatHHMM = (epochMs: number) => {
    const d = new Date(epochMs);
    return d.toLocaleTimeString('pt-BR', {
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const refreshConnectionAndStatus = (triggerSync = false) => {
    const now = new Date();
    const hhmmss = now.toLocaleTimeString('pt-BR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    setLastStatusCheckHHMMSS(hhmmss);
    if (triggerSync) {
      onTriggerManualSync();
    }
  };

  // Atualiza automaticamente o status de conexão sempre que a aba muda ou o app volta ao foco
  useEffect(() => {
    refreshConnectionAndStatus(false);
  }, [activeTab, isBluetoothConnected, isWearAppInstalledOnWatch]);

  useEffect(() => {
    const onWindowFocus = () => {
      refreshConnectionAndStatus(false);
    };
    window.addEventListener('focus', onWindowFocus);
    return () => window.removeEventListener('focus', onWindowFocus);
  }, []);

  const handleTriggerPullRefresh = () => {
    if (isPullRefreshing) return;
    setIsPullRefreshing(true);
    setTimeout(() => {
      refreshConnectionAndStatus(true);
      setIsPullRefreshing(false);
      setPullDragY(0);
    }, 320);
  };

  const selectedCalendarsCount = calendars.filter((c) => c.enabled).length;
  const selectedWeatherApps = config.selectedWeatherTriggerApps ?? [
    'com.google.android.googlequicksearchbox',
    'com.google.android.apps.weather',
    'com.sec.android.daemonapp',
    'br.com.climatempo.meteorologia',
  ];

  const handleToggleWeatherTriggerApp = (pkg: string) => {
    const exists = selectedWeatherApps.includes(pkg);
    const next = exists
      ? selectedWeatherApps.filter((p) => p !== pkg)
      : [...selectedWeatherApps, pkg];
    onUpdateConfig({ selectedWeatherTriggerApps: next });
  };

  // Separação estrita dos compromissos de Hoje vs Amanhã (idêntico ao relógio)
  const futureEvents = events
    .filter((e) => e.startEpochMs > referenceTimeMs)
    .sort((a, b) => a.startEpochMs - b.startEpochMs);

  const todayEvents = futureEvents.filter((ev) =>
    isSameCalendarDay(referenceTimeMs, ev.startEpochMs)
  );

  const resolveStrictAgendaDayAndEvents = (): {
    dayHeader: string;
    dayEvents: CalendarEventItem[];
  } => {
    if (todayEvents.length > 0) {
      return { dayHeader: 'Hoje', dayEvents: todayEvents.slice(0, 3) };
    }
    if (futureEvents.length > 0) {
      const firstNext = futureEvents[0];
      const rel = formatDayRelative(firstNext.startEpochMs, referenceTimeMs);
      const header = rel === 'Amanhã' ? 'Amanhã' : rel;
      const sameDayList = futureEvents
        .filter((ev) => isSameCalendarDay(firstNext.startEpochMs, ev.startEpochMs))
        .slice(0, 3);
      return { dayHeader: header, dayEvents: sameDayList };
    }
    return { dayHeader: 'Hoje', dayEvents: [] };
  };

  const { dayHeader: strictAgendaHeader, dayEvents: strictAgendaEvents } =
    resolveStrictAgendaDayAndEvents();
  const primaryAgendaEvent = strictAgendaEvents[0] || null;

  const allTasks = config.tasks ?? DEFAULT_TASKS_LIST;
  const activeTodayOrTimelessTasks = filterActiveTasksForWatch(allTasks);

  const handleCompleteTaskInWidget = (taskId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const updated = allTasks.map((t) =>
      t.id === taskId ? { ...t, completed: true } : t
    );
    onUpdateConfig({ tasks: updated });
  };

  const handleAddNewTask = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTaskTitle.trim()) return;
    const created = {
      id: `t_${Date.now()}`,
      title: newTaskTitle.trim(),
      dueHHMM: newTaskDueHHMM.trim() || 'Hoje',
      sourceLabel: 'Tasks' as const,
      completed: false,
      isTodayOrTimeless: true,
    };
    onUpdateConfig({
      tasksSyncEnabled: true,
      tasks: [created, ...allTasks],
    });
    setNewTaskTitle('');
    setNewTaskDueHHMM('Hoje');
  };

  // Resolve qual contexto a Representação Virtual do Relógio no topo deve desenhar
  const resolveVirtualWatchActiveMode = (): PrimaryContextMode => {
    if (virtualWatchPreviewOverride) return virtualWatchPreviewOverride;
    if (config.contextWindowMode !== 'DYNAMIC') {
      return config.contextWindowMode;
    }
    const primary = config.autoPrimaryContext ?? 'EVENTS';
    if (
      primary === 'EVENTS' &&
      todayEvents.length === 0 &&
      (config.autoFallbackAgendaToTasks ?? true) &&
      (config.tasksSyncEnabled ?? true) &&
      activeTodayOrTimelessTasks.length > 0
    ) {
      return 'TASKS';
    }
    return primary;
  };

  const virtualWatchMode = resolveVirtualWatchActiveMode();

  const handleCycleVirtualWatchPreview = () => {
    const cycle: PrimaryContextMode[] = [
      'EVENTS',
      'TASKS',
      'WEATHER',
      'SUN',
      'SLEEP',
      'STEPS',
    ];
    const idx = cycle.indexOf(virtualWatchMode);
    setVirtualWatchPreviewOverride(cycle[(idx + 1) % cycle.length]);
  };

  // Expansão e Reordenação (Drag & Drop + Setas) dos Blocos de Widgets 2x2
  const handleToggleExpandWidget = (
    widgetId: PrimaryContextMode,
    e: React.MouseEvent
  ) => {
    e.stopPropagation();
    const isExp = expandedWidgets.includes(widgetId);
    const nextExp = isExp
      ? expandedWidgets.filter((id) => id !== widgetId)
      : [...expandedWidgets, widgetId];
    onUpdateConfig({ expandedWidgets: nextExp });
  };

  const handleMoveWidgetOrder = (
    widgetId: PrimaryContextMode,
    delta: number,
    e: React.MouseEvent
  ) => {
    e.stopPropagation();
    const list = [...widgetOrder];
    const idx = list.indexOf(widgetId);
    if (idx < 0) return;
    const targetIdx = Math.max(0, Math.min(list.length - 1, idx + delta));
    if (targetIdx === idx) return;
    list.splice(idx, 1);
    list.splice(targetIdx, 0, widgetId);
    onUpdateConfig({ homeWidgetsOrder: list });
  };

  const handleSwapWidgets = (
    sourceId: PrimaryContextMode,
    targetId: PrimaryContextMode
  ) => {
    if (sourceId === targetId) return;
    const list = [...widgetOrder];
    const fromIdx = list.indexOf(sourceId);
    const toIdx = list.indexOf(targetId);
    if (fromIdx < 0 || toIdx < 0) return;
    list.splice(fromIdx, 1);
    list.splice(toIdx, 0, sourceId);
    onUpdateConfig({ homeWidgetsOrder: list });
  };

  const handleCreateQuickEvent = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    const startMs = referenceTimeMs + newMinutesFromNow * 60 * 1000;
    onAddEvent({
      calendarId: calendars.find((c) => c.enabled)?.id || calendars[0].id,
      title: newTitle.trim(),
      location: 'Agenda Local',
      startEpochMs: startMs,
      endEpochMs: startMs + 45 * 60 * 1000,
    });
    setNewTitle('');
  };

  // Lista de Permissões Pendentes para o Quadro Informativo abaixo da Representação do Relógio
  const missingPermissions = [
    !calendarPermGranted && {
      id: 'cal',
      title: 'Acesso ao Calendário (READ_CALENDAR)',
      desc: 'Necessário para sincronizar os compromissos de Hoje e Amanhã.',
      onGrant: () => setCalendarPermGranted(true),
    },
    !locationPermGranted && {
      id: 'loc',
      title: 'Localização Unificada (Clima & Pôr do Sol)',
      desc: 'Necessário para obter a cidade/UF oficial e calcular o pôr do sol.',
      onGrant: () => setLocationPermGranted(true),
    },
    !notifPermGranted && {
      id: 'notif',
      title: 'Notificações e Leitor de Gatilhos',
      desc: 'Necessário para alertas de mudança climática e lembretes.',
      onGrant: () => setNotifPermGranted(true),
    },
    !batteryOptIgnored && {
      id: 'bat',
      title: 'Sem Restrição de Bateria em 2º Plano',
      desc: 'Garante sincronização contínua de agenda, tarefas e sono.',
      onGrant: () => setBatteryOptIgnored(true),
    },
  ].filter(Boolean) as Array<{
    id: string;
    title: string;
    desc: string;
    onGrant: () => void;
  }>;

  const weatherCategories = Array.from(
    new Set(WEATHER_TRIGGER_CATALOG.map((app) => app.categoryName))
  );

  const activeTabIndex =
    activeTab === 'home' ? 0 : activeTab === 'settings' ? 1 : 2;

  const contextModeOptions = [
    { id: 'DYNAMIC', label: 'Auto' },
    { id: 'EVENTS', label: 'Agenda' },
    { id: 'TASKS', label: 'Tarefas' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'STEPS', label: 'Passos' },
    { id: 'SUN', label: 'Sol' },
    { id: 'SLEEP', label: 'Sono' },
  ] as const;

  const autoPrimaryOptions = [
    { id: 'EVENTS', label: 'Agenda' },
    { id: 'TASKS', label: 'Tarefas' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'SUN', label: 'Sol' },
    { id: 'STEPS', label: 'Passos' },
    { id: 'SLEEP', label: 'Sono' },
  ] as const;

  const exposureMinuteOptions = [5, 15, 30, 45, 60] as const;

  // Renderiza o conteúdo gráfico interno de cada contexto (usado tanto na Representação Virtual do Relógio quanto nos Blocos de Widgets)
  const renderContextVisualDrawing = (
    mode: PrimaryContextMode,
    isInsideVirtualWatch: boolean
  ) => {
    const winMs = config.progressWindowMinutes * 60 * 1000;
    const remMs = primaryAgendaEvent
      ? Math.max(0, primaryAgendaEvent.startEpochMs - referenceTimeMs)
      : 0;
    const progPct = primaryAgendaEvent
      ? remMs >= winMs
        ? 12
        : Math.round(Math.min(100, Math.max(12, (1 - remMs / winMs) * 100)))
      : 0;

    switch (mode) {
      case 'EVENTS': {
        const ev0 = strictAgendaEvents[0] || null;
        const ev1 = strictAgendaEvents[1] || null;
        const ev2 = strictAgendaEvents[2] || null;
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between gap-1">
              <span className="text-[11px] font-bold text-[#F5C438] leading-none">
                {strictAgendaHeader}
              </span>
              <span className="text-[10px] font-mono font-bold text-[#67E8F9] leading-none">
                {ev0 ? formatCountdownText(remMs) : 'Livre'}
              </span>
            </div>

            <div className="space-y-0.5">
              <div className="flex items-center gap-1 text-[10.5px] leading-tight">
                <span className="font-mono font-bold text-white shrink-0">
                  {ev0 ? formatHHMM(ev0.startEpochMs) : '--:--'}
                </span>
                <span className="font-bold text-[#F5C438] truncate">
                  {ev0 ? ev0.title : 'Não há compromissos'}
                </span>
              </div>
              <div className="w-full h-[4px] bg-[#1E293B] rounded-full overflow-hidden">
                <div
                  className="h-full bg-[#F5C438] rounded-full transition-all duration-200"
                  style={{ width: `${progPct}%` }}
                />
              </div>
            </div>

            <div className="flex items-center gap-1 text-[9.5px] leading-tight">
              <span className="font-mono text-slate-300 shrink-0">
                {ev1 ? formatHHMM(ev1.startEpochMs) : '--:--'}
              </span>
              <span className="font-semibold text-amber-200/90 truncate">
                {ev1 ? ev1.title : 'Livre'}
              </span>
            </div>

            <div className="flex items-center gap-1 text-[9.5px] leading-tight">
              <span className="font-mono text-slate-400 shrink-0">
                {ev2 ? formatHHMM(ev2.startEpochMs) : '--:--'}
              </span>
              <span className="font-semibold text-amber-200/75 truncate">
                {ev2 ? ev2.title : 'Livre'}
              </span>
            </div>
          </div>
        );
      }

      case 'TASKS': {
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between">
              <span className="text-[10.5px] font-bold text-[#38BDF8] leading-none">
                Hoje / Sem horário
              </span>
              <span className="text-[9.5px] font-mono text-emerald-300 leading-none">
                {activeTodayOrTimelessTasks.length} ativas
              </span>
            </div>

            {activeTodayOrTimelessTasks.length === 0 ? (
              <div className="my-auto text-[10px] text-slate-400">
                Não há tarefas ativas para hoje
              </div>
            ) : (
              <div className="space-y-1 my-auto">
                {activeTodayOrTimelessTasks.slice(0, 3).map((t, i) => (
                  <div
                    key={t.id}
                    onClick={
                      !isInsideVirtualWatch
                        ? (e) => handleCompleteTaskInWidget(t.id, e)
                        : undefined
                    }
                    title="Toque para concluir e remover do Canvas"
                    className="flex items-center justify-between gap-1 text-[10px] leading-tight hover:bg-white/5 rounded px-0.5 cursor-pointer"
                  >
                    <div className="flex items-center gap-1 min-w-0">
                      <span className="w-3 h-3 rounded-[3px] border border-[#38BDF8] flex items-center justify-center text-[8px] text-transparent hover:text-emerald-400 shrink-0">
                        ✓
                      </span>
                      <span
                        className={`truncate font-semibold ${
                          i === 0 ? 'text-[#F5C438]' : 'text-slate-100'
                        }`}
                      >
                        {t.title}
                      </span>
                    </div>
                    <span className="text-[8.5px] font-mono text-sky-300 shrink-0">
                      {t.dueHHMM || 'Hoje'}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      }

      case 'WEATHER': {
        const loc = formatCompactWeatherLocation(
          config.weatherLocationName ?? 'São Paulo, SP'
        );
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between">
              <span className="text-[10.5px] font-bold text-[#F5C438] leading-none">
                29°C • Parc. nublado
              </span>
              <span className="text-[9.5px] font-mono text-sky-300 leading-none">
                💧25%
              </span>
            </div>
            <div className="text-[9.5px] font-semibold text-slate-300 truncate leading-none">
              📍 {loc}
            </div>
            <svg viewBox="0 0 180 36" className="w-full h-[32px] overflow-visible">
              <path
                d="M 10 12 C 35 12, 45 16, 65 16 C 85 16, 95 26, 115 26 C 135 26, 150 20, 170 20"
                fill="none"
                stroke="#F5C438"
                strokeWidth="2.2"
                strokeLinecap="round"
              />
              {[
                { x: 10, y: 12, t: '29°' },
                { x: 65, y: 16, t: '27°' },
                { x: 115, y: 26, t: '24°' },
                { x: 170, y: 20, t: '25°' },
              ].map((p, idx) => (
                <g key={idx}>
                  <circle cx={p.x} cy={p.y} r="2.5" fill="#67E8F9" />
                  <text
                    x={p.x}
                    y={p.y - 4}
                    textAnchor="middle"
                    fill="#FFFFFF"
                    fontSize="8.5"
                    fontWeight="bold"
                    fontFamily="monospace"
                  >
                    {p.t}
                  </text>
                </g>
              ))}
            </svg>
          </div>
        );
      }

      case 'SUN': {
        const loc = formatCompactWeatherLocation(
          config.weatherLocationName ?? 'São Paulo, SP'
        );
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between text-[10px] font-mono font-bold text-white leading-none">
              <span className="text-amber-300">
                ↑ {config.solarSunriseHHMM ?? '05:27'}
              </span>
              <span className="text-sky-300">
                ↓ {config.solarSunsetHHMM ?? '17:40'}
              </span>
            </div>
            <div className="text-[9px] text-slate-400 truncate leading-none">
              📍 {loc} (Unificado)
            </div>
            <svg viewBox="0 0 180 38" className="w-full h-[34px] overflow-visible">
              <line
                x1="4"
                y1="26"
                x2="176"
                y2="26"
                stroke="#60A5FA"
                strokeWidth="1.5"
              />
              <path
                d="M 4 34 Q 45 26, 52 26 Q 90 4, 128 26 Q 135 26, 176 34"
                fill="none"
                stroke="#F5C438"
                strokeWidth="2.2"
              />
              <circle cx="90" cy="14" r="4" fill="#FDE047" />
            </svg>
          </div>
        );
      }

      case 'SLEEP': {
        const hasSleepToday = config.sleepHasDataToday ?? true;
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between">
              <span className="text-[10.5px] font-bold text-purple-300 leading-none">
                Sono de Hoje
              </span>
              <span className="text-[9.5px] font-mono text-sky-300 leading-none">
                {hasSleepToday ? `7h 42m • ${config.sleepScore ?? 86}p` : 'Vazio'}
              </span>
            </div>

            {hasSleepToday ? (
              <>
                <div className="text-[9.5px] text-slate-300 leading-none">
                  {config.sleepBedtimeHHMM ?? '23:18'} →{' '}
                  {config.sleepWakeHHMM ?? '07:00'}
                </div>
                <div className="w-full h-[14px] rounded overflow-hidden flex gap-[2px] bg-slate-900 p-[2px]">
                  <div className="h-full w-[8%] bg-rose-400 rounded-xs" />
                  <div className="h-full w-[44%] bg-blue-400 rounded-xs" />
                  <div className="h-full w-[26%] bg-indigo-500 rounded-xs" />
                  <div className="h-full w-[22%] bg-sky-400 rounded-xs" />
                </div>
                <div className="text-[8.5px] font-mono text-slate-400 leading-none">
                  Acord • Leve • Prof • REM
                </div>
              </>
            ) : (
              <div className="my-auto space-y-0.5">
                <div className="text-[10px] font-bold text-slate-200 leading-tight">
                  Não há dados de sono para hoje
                </div>
                <div className="text-[8.5px] text-slate-400 leading-tight">
                  Verificado pelo sincronizador
                </div>
              </div>
            )}
          </div>
        );
      }

      case 'STEPS': {
        const bars = [58, 82, 74, 94, 88, 62, 54];
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between">
              <span className="text-[10.5px] font-bold text-emerald-400 leading-none">
                8.435 passos
              </span>
              <span className="text-[9.5px] font-mono text-slate-300 leading-none">
                84% meta
              </span>
            </div>
            <div className="flex items-end justify-between gap-1 h-[36px] pt-1">
              {bars.map((pct, i) => (
                <div
                  key={i}
                  className="flex-1 h-full bg-slate-800/80 rounded-xs flex items-end overflow-hidden"
                >
                  <div
                    className={`w-full rounded-xs ${
                      i === 4 ? 'bg-[#F5C438]' : 'bg-emerald-500'
                    }`}
                    style={{ height: `${pct}%` }}
                  />
                </div>
              ))}
            </div>
            <div className="flex justify-between text-[8px] font-mono text-slate-400 leading-none">
              <span>S</span>
              <span>T</span>
              <span>Q</span>
              <span>Q</span>
              <span className="text-[#F5C438] font-bold">S</span>
              <span>S</span>
              <span>D</span>
            </div>
          </div>
        );
      }
    }
  };

  return (
    <div
      data-testid="smartphone-companion-root"
      onTouchStart={(e) => {
        setTouchStartY(e.touches[0].clientY);
      }}
      onTouchMove={(e) => {
        if (touchStartY !== null) {
          const dy = e.touches[0].clientY - touchStartY;
          if (dy > 0) setPullDragY(Math.min(72, dy * 0.5));
        }
      }}
      onTouchEnd={() => {
        if (pullDragY >= 42) {
          handleTriggerPullRefresh();
        } else {
          setPullDragY(0);
        }
        setTouchStartY(null);
      }}
      className="relative rounded-[36px] border border-[#2A332C] text-slate-100 shadow-2xl overflow-hidden flex flex-col justify-between min-h-[620px]"
      style={{
        background:
          'linear-gradient(180deg, #1E241E 0%, #101713 28%, #090C0A 65%, #070908 100%)',
      }}
    >
      <div className="px-5 pt-4 pb-24 flex-1 flex flex-col">
        {/* BARRA PUXAR PARA ATUALIZAR (PULL-TO-REFRESH) */}
        <button
          type="button"
          data-testid="phone-pull-to-refresh-bar"
          onClick={handleTriggerPullRefresh}
          style={{
            transform: pullDragY > 0 ? `translateY(${pullDragY * 0.25}px)` : undefined,
          }}
          className="w-full mb-2.5 py-1.5 px-3 rounded-xl bg-[#0D1310] border border-[#26352C] hover:border-emerald-500/40 flex items-center justify-center gap-2 text-[11px] font-medium text-sky-300 transition-all cursor-pointer"
        >
          <RefreshCw
            className={`w-3.5 h-3.5 text-[#F5C438] ${
              isPullRefreshing ? 'animate-spin' : ''
            }`}
          />
          <span>
            {isPullRefreshing
              ? 'Atualizando Bluetooth, App do Relógio e Dados...'
              : `↓ Puxe para atualizar ou toque aqui (Verificado às ${lastStatusCheckHHMMSS})`}
          </span>
        </button>

        {/* Cabeçalho Essencial do App Smartphone */}
        <div className="flex items-center justify-between gap-2 pb-2.5 mb-3 border-b border-white/10">
          <div className="flex items-center gap-2">
            <span className="text-base font-bold tracking-tight text-white">
              ChronoWear Companion
            </span>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-300 border border-amber-500/30">
              v1.2.1
            </span>
          </div>

          <button
            type="button"
            onClick={() => setIsWearAppInstalledOnWatch((prev) => !prev)}
            className={`px-2.5 py-1 rounded-full text-[10.5px] font-semibold transition-all duration-150 active:scale-95 cursor-pointer ${
              isWearAppInstalledOnWatch
                ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-400/35'
                : 'bg-amber-500/20 text-amber-200 border border-amber-400/35'
            }`}
          >
            {isWearAppInstalledOnWatch
              ? 'App Wear: Instalado'
              : 'App Wear: Ausente'}
          </button>
        </div>

        {/* =============================================================== */}
        {/* ABA 1: INÍCIO (REPRESENTAÇÃO VIRTUAL DO RELÓGIO + PERMISSÕES +  */}
        {/* WIDGETS 2X2 EXPANSÍVEIS E REORDENÁVEIS)                         */}
        {/* =============================================================== */}
        {activeTab === 'home' && (
          <div
            key="tab-home"
            className="space-y-3 flex-1 flex flex-col transition-all duration-200"
          >
            {/* 1. REPRESENTAÇÃO VIRTUAL CIRCULAR GERADA PELO SMARTPHONE */}
            <div className="rounded-[24px] bg-[#0E1411] border border-[#24332B] p-3.5 shadow-lg flex flex-col items-center">
              <div className="w-full flex items-center justify-between text-[10.5px] mb-2">
                <span className="font-mono font-bold text-[#F5C438] uppercase tracking-wider">
                  Representação Virtual •{' '}
                  {config.contextWindowMode === 'DYNAMIC'
                    ? `Auto (${virtualWatchMode})`
                    : `Fixo (${virtualWatchMode})`}
                </span>
                <span className="text-slate-400 text-[10px]">
                  Toque no relógio p/ alternar
                </span>
              </div>

              {/* Relógio Virtual Desenhado Diretamente pelo App Smartphone */}
              <div
                data-testid="phone-virtual-watch-preview"
                onClick={handleCycleVirtualWatchPreview}
                title="Representação virtual gerada diretamente pelo app de smartphone refletindo a configuração atual"
                className="relative w-[212px] h-[212px] rounded-full bg-[#05070B] border-[5px] border-[#222D3A] shadow-2xl flex flex-col items-center justify-between py-2.5 px-4 cursor-pointer select-none hover:border-[#F5C438]/60 transition-colors"
              >
                {/* Arcos laterais desenhados em SVG pelo Smartphone */}
                <svg
                  viewBox="0 0 220 220"
                  className="absolute inset-0 w-full h-full pointer-events-none"
                >
                  <path
                    d="M 16 76 A 96 96 0 0 0 16 144"
                    fill="none"
                    stroke="#F5C438"
                    strokeWidth="5"
                    strokeLinecap="round"
                  />
                  <path
                    d="M 204 76 A 96 96 0 0 1 204 144"
                    fill="none"
                    stroke="#38BDF8"
                    strokeWidth="5"
                    strokeLinecap="round"
                  />
                </svg>

                {/* Hora e Data no topo da Representação Virtual */}
                <div className="text-center z-10">
                  <div className="text-[8.5px] font-bold text-[#F5C438] tracking-wider leading-none">
                    SEX. 06 • CONFIG ATUAL
                  </div>
                  <div className="text-[21px] font-mono font-bold text-white leading-tight">
                    {formatHHMM(referenceTimeMs)}
                    <span className="text-[11px] text-[#F5C438] ml-0.5">:06</span>
                  </div>
                </div>

                {/* Quadro Central Virtual Desenhado pelo Smartphone */}
                <div className="w-[158px] h-[94px] rounded-xl bg-[#080B11] border border-[#1E293B] p-2 z-10 overflow-hidden">
                  {renderContextVisualDrawing(virtualWatchMode, true)}
                </div>

                {/* Pílula Inferior de Bateria & Local */}
                <div className="px-2.5 py-0.5 rounded-full bg-[#111827] border border-[#334155] text-[8.5px] font-mono font-bold text-slate-200 z-10">
                  ⚡ 86% •{' '}
                  {formatCompactWeatherLocation(
                    config.weatherLocationName ?? 'São Paulo, SP'
                  )}
                </div>
              </div>

              {/* Status Preciso de Conexão ( Sensível a Bluetooth + App do Relógio ) */}
              <div
                data-testid="phone-accurate-connection-banner"
                className="w-full mt-3 pt-2.5 border-t border-white/10 flex flex-col gap-1.5"
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <span
                      className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                        !isBluetoothConnected
                          ? 'bg-rose-500'
                          : !isWearAppInstalledOnWatch
                          ? 'bg-amber-400'
                          : 'bg-emerald-400'
                      }`}
                    />
                    <span className="text-xs font-bold text-white truncate">
                      {!isBluetoothConnected
                        ? 'Desconectado • Bluetooth Desligado'
                        : !isWearAppInstalledOnWatch
                        ? 'Relógio Pareado • App Wear Não Instalado'
                        : 'Conectado • Galaxy Watch Ultra (Wear v1.2.1)'}
                    </span>
                  </div>
                  <span className="text-[10px] font-mono text-emerald-300 shrink-0">
                    {syncedEventsCount} eventos
                  </span>
                </div>

                <div className="grid grid-cols-2 gap-1.5 text-[10.5px]">
                  <div
                    className={`rounded-lg px-2.5 py-1 border flex items-center justify-between ${
                      isBluetoothConnected
                        ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
                        : 'bg-rose-500/10 border-rose-500/30 text-rose-300'
                    }`}
                  >
                    <span className="flex items-center gap-1">
                      <Bluetooth className="w-3 h-3" />
                      Bluetooth:
                    </span>
                    <span className="font-bold">
                      {isBluetoothConnected ? 'Ativo' : 'Offline'}
                    </span>
                  </div>
                  <div
                    className={`rounded-lg px-2.5 py-1 border flex items-center justify-between ${
                      isBluetoothConnected && isWearAppInstalledOnWatch
                        ? 'bg-sky-500/10 border-sky-500/30 text-sky-300'
                        : 'bg-amber-500/10 border-amber-500/30 text-amber-300'
                    }`}
                  >
                    <span>App Relógio:</span>
                    <span className="font-bold">
                      {!isBluetoothConnected
                        ? 'Inacessível'
                        : isWearAppInstalledOnWatch
                        ? 'Ativo'
                        : 'Ausente'}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* 2. QUADRO INFORMATIVO DE PERMISSÕES PENDENTES (ABAIXO DA REPRESENTAÇÃO DO RELÓGIO) */}
            <div
              data-testid="phone-permissions-status-card"
              className={`rounded-[20px] border p-3 transition-colors ${
                missingPermissions.length > 0
                  ? 'bg-amber-500/10 border-amber-500/40'
                  : 'bg-emerald-500/10 border-emerald-500/30'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-1.5">
                  {missingPermissions.length > 0 ? (
                    <ShieldAlert className="w-4 h-4 text-amber-400 shrink-0" />
                  ) : (
                    <ShieldCheck className="w-4 h-4 text-emerald-400 shrink-0" />
                  )}
                  <span className="text-xs font-bold text-white">
                    {missingPermissions.length > 0
                      ? `Permissões Pendentes (${missingPermissions.length})`
                      : 'Todas as Permissões Necessárias Concedidas'}
                  </span>
                </div>

                <button
                  type="button"
                  onClick={() => {
                    if (missingPermissions.length === 0) {
                      setCalendarPermGranted(false);
                      setLocationPermGranted(false);
                    } else {
                      setCalendarPermGranted(true);
                      setLocationPermGranted(true);
                      setNotifPermGranted(true);
                      setBatteryOptIgnored(true);
                    }
                  }}
                  className="text-[10px] font-mono underline text-slate-300 hover:text-white cursor-pointer"
                >
                  {missingPermissions.length === 0
                    ? 'Simular Pendente'
                    : 'Conceder Todas'}
                </button>
              </div>

              {missingPermissions.length > 0 && (
                <div className="mt-2 space-y-1.5">
                  {missingPermissions.map((perm) => (
                    <div
                      key={perm.id}
                      className="flex items-center justify-between gap-2 rounded-xl bg-black/40 border border-amber-500/30 px-2.5 py-1.5"
                    >
                      <div className="min-w-0">
                        <div className="text-[11px] font-bold text-amber-200 truncate">
                          {perm.title}
                        </div>
                        <div className="text-[10px] text-slate-300 truncate">
                          {perm.desc}
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={perm.onGrant}
                        className="px-2.5 py-1 rounded-full bg-[#F5C438] text-slate-950 font-bold text-[10px] shrink-0 cursor-pointer"
                      >
                        Conceder
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 3. QUADROS DOS STATUS EM BLOCOS 2X2 (EXPANSÍVEIS E REORDENÁVEIS COMO WIDGETS) */}
            <div className="space-y-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-[11px] font-bold uppercase tracking-wider text-slate-200">
                  Quadros de Status (Widgets 2×2)
                </span>
                <span className="text-[10px] text-slate-400">
                  Arraste p/ reordenar • ⤢ expande linha
                </span>
              </div>

              <div
                data-testid="phone-widgets-grid"
                className="grid grid-cols-2 gap-2.5"
              >
                {widgetOrder.map((widgetId) => {
                  const isExpanded = expandedWidgets.includes(widgetId);
                  const isSelectedOnWatch = virtualWatchMode === widgetId;
                  const widgetTitleMap: Record<PrimaryContextMode, string> = {
                    EVENTS: '📅 Agenda',
                    TASKS: '✓ Tarefas',
                    WEATHER: '⛅ Clima',
                    SUN: '☀ Sol',
                    SLEEP: '🌙 Sono',
                    STEPS: '👟 Passos',
                  };

                  return (
                    <div
                      key={widgetId}
                      data-testid={`phone-widget-block-${widgetId}`}
                      draggable
                      onDragStart={() => setDraggedWidgetId(widgetId)}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={() => {
                        if (draggedWidgetId) {
                          handleSwapWidgets(draggedWidgetId, widgetId);
                          setDraggedWidgetId(null);
                        }
                      }}
                      onClick={() => setVirtualWatchPreviewOverride(widgetId)}
                      className={`${
                        isExpanded ? 'col-span-2' : 'col-span-1'
                      } h-[142px] rounded-[20px] bg-[#0E1411] border p-2.5 flex flex-col justify-between transition-all cursor-pointer select-none ${
                        isSelectedOnWatch
                          ? 'border-[#F5C438] shadow-[0_0_0_1px_rgba(245,196,56,0.35)]'
                          : 'border-[#233129] hover:border-slate-600'
                      }`}
                    >
                      {/* Barra Superior do Bloco Widget */}
                      <div className="flex items-center justify-between gap-1 pb-1 border-b border-white/10">
                        <div className="flex items-center gap-1 min-w-0">
                          <GripVertical className="w-3 h-3 text-slate-500 shrink-0 cursor-grab" />
                          <span className="text-[11px] font-bold text-white truncate">
                            {widgetTitleMap[widgetId]}
                          </span>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          {widgetId === 'TASKS' && (
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setIsManageTasksModalOpen(true);
                              }}
                              title="Adicionar ou gerenciar tarefas"
                              className="px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-300 text-[9.5px] font-bold hover:bg-emerald-500/30 cursor-pointer"
                            >
                              +
                            </button>
                          )}
                          {widgetId === 'SLEEP' && (
                            <button
                              type="button"
                              data-testid="widget-toggle-sleep-today-data"
                              onClick={(e) => {
                                e.stopPropagation();
                                onUpdateConfig({
                                  sleepHasDataToday: !(
                                    config.sleepHasDataToday ?? true
                                  ),
                                });
                              }}
                              title="Alternar entre Com Dados de Sono Hoje vs Sem Dados de Sono para Hoje"
                              className="px-1.5 py-0.5 rounded bg-purple-500/20 text-purple-300 text-[9.5px] font-bold hover:bg-purple-500/30 cursor-pointer"
                            >
                              {(config.sleepHasDataToday ?? true) ? 'Hoje' : 'Vazio'}
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={(e) =>
                              handleMoveWidgetOrder(widgetId, -1, e)
                            }
                            title="Mover bloco para trás"
                            className="px-1 py-0.5 rounded bg-slate-800 text-slate-300 text-[9px] hover:bg-slate-700 cursor-pointer"
                          >
                            ◀
                          </button>
                          <button
                            type="button"
                            onClick={(e) =>
                              handleMoveWidgetOrder(widgetId, +1, e)
                            }
                            title="Mover bloco para frente"
                            className="px-1 py-0.5 rounded bg-slate-800 text-slate-300 text-[9px] hover:bg-slate-700 cursor-pointer"
                          >
                            ▶
                          </button>
                          <button
                            type="button"
                            data-testid={`widget-expand-${widgetId}`}
                            onClick={(e) =>
                              handleToggleExpandWidget(widgetId, e)
                            }
                            title={
                              isExpanded
                                ? 'Reduzir para meio bloco (1 coluna)'
                                : 'Expandir para linha inteira (2 colunas, mesma altura)'
                            }
                            className="p-1 rounded bg-slate-800 text-amber-300 hover:bg-slate-700 cursor-pointer"
                          >
                            {isExpanded ? (
                              <Minimize2 className="w-2.5 h-2.5" />
                            ) : (
                              <Maximize2 className="w-2.5 h-2.5" />
                            )}
                          </button>
                        </div>
                      </div>

                      {/* Desenho Gráfico do Bloco */}
                      <div className="flex-1 pt-1.5 overflow-hidden">
                        {renderContextVisualDrawing(widgetId, false)}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 2: CONFIGURAÇÕES (SWITCHES RESPONSIVOS E INFORMAÇÕES        */}
        {/* ESSENCIAIS)                                                     */}
        {/* =============================================================== */}
        {activeTab === 'settings' && (
          <div
            key="tab-settings"
            className="space-y-3 flex-1 max-h-[490px] overflow-y-auto pr-1 transition-all duration-200"
          >
            {/* SEÇÃO 1: MODO DO CANVAS & FOCO PRINCIPAL */}
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-3">
              <div className="text-[11px] font-bold tracking-wider uppercase text-[#F5C438]">
                1. Modo do Canvas e Foco Principal
              </div>

              <div className="grid grid-cols-4 gap-1.5">
                {contextModeOptions.map((m) => (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => onUpdateConfig({ contextWindowMode: m.id })}
                    className={`py-1.5 px-2 rounded-xl text-[11px] font-semibold transition-all cursor-pointer ${
                      config.contextWindowMode === m.id
                        ? 'bg-[#F5C438] text-[#050507] font-bold'
                        : 'bg-[#0C100E] text-slate-200 border border-[#26332B]'
                    }`}
                  >
                    {m.label}
                  </button>
                ))}
              </div>

              <div className="space-y-1.5 pt-2 border-t border-white/10">
                <span className="text-xs font-bold text-slate-200 block">
                  Foco Principal no Modo Automático:
                </span>
                <div className="grid grid-cols-3 gap-1.5">
                  {autoPrimaryOptions.map((opt) => (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ autoPrimaryContext: opt.id })
                      }
                      className={`py-1.5 px-2 rounded-xl text-[11px] font-semibold transition-all cursor-pointer ${
                        (config.autoPrimaryContext ?? 'EVENTS') === opt.id
                          ? 'bg-[#F5C438] text-[#050507] font-bold'
                          : 'bg-[#0C100E] text-slate-200 border border-[#26332B]'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Linha clicável inteira para nunca falhar o toque no Switch */}
              <div
                onClick={() =>
                  onUpdateConfig({
                    autoFallbackAgendaToTasks: !(
                      config.autoFallbackAgendaToTasks ?? true
                    ),
                  })
                }
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Priorizar Tarefas se Não Houver Agenda Hoje
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Alterna para Tarefas ativas do dia quando Hoje estiver sem eventos
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.autoFallbackAgendaToTasks ?? true}
                  ariaLabel="Exibir Tarefas se Não Houver Agenda Hoje"
                  onChange={() =>
                    onUpdateConfig({
                      autoFallbackAgendaToTasks: !(
                        config.autoFallbackAgendaToTasks ?? true
                      ),
                    })
                  }
                />
              </div>

              <div className="space-y-1.5 pt-2 border-t border-white/10">
                <span className="text-xs font-bold text-slate-200 block">
                  Retenção de Canvas Temporário:
                </span>
                <div className="grid grid-cols-5 gap-1.5">
                  {exposureMinuteOptions.map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ unifiedCanvasHoldMinutes: mins })
                      }
                      className={`py-1.5 rounded-xl text-[11px] font-semibold transition-all cursor-pointer ${
                        (config.unifiedCanvasHoldMinutes ?? 30) === mins
                          ? 'bg-[#F5C438] text-[#050507] font-bold'
                          : 'bg-[#0C100E] text-slate-200 border border-[#26332B]'
                      }`}
                    >
                      {mins === 60 ? '1h' : `${mins}m`}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* SEÇÃO 2: NOTIFICAÇÕES NO RELÓGIO */}
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-3">
              <div className="text-[11px] font-bold tracking-wider uppercase text-[#F5C438]">
                2. Notificações do Relógio e Alerta 15m
              </div>

              <div
                onClick={() =>
                  onUpdateConfig({
                    notifyWatchOnCanvasChange: !(
                      config.notifyWatchOnCanvasChange ?? true
                    ),
                  })
                }
                className="flex items-center justify-between gap-3 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Notificar no Relógio ao Atualizar o Canvas
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Gera alerta no relógio quando o Canvas mudar de contexto
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.notifyWatchOnCanvasChange ?? true}
                  ariaLabel="Notificar no Relógio ao Atualizar o Canvas"
                  onChange={() =>
                    onUpdateConfig({
                      notifyWatchOnCanvasChange: !(
                        config.notifyWatchOnCanvasChange ?? true
                      ),
                    })
                  }
                />
              </div>

              <div
                onClick={() =>
                  onUpdateConfig({
                    notifyAgenda15MinEnabled: !(
                      config.notifyAgenda15MinEnabled ?? true
                    ),
                  })
                }
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Notificar Compromisso Faltando 15 Minutos
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Troca para a Agenda faltando 15 min e avisa no pulso
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.notifyAgenda15MinEnabled ?? true}
                  ariaLabel="Notificar Compromisso Faltando 15 Minutos"
                  onChange={() =>
                    onUpdateConfig({
                      notifyAgenda15MinEnabled: !(
                        config.notifyAgenda15MinEnabled ?? true
                      ),
                    })
                  }
                />
              </div>

              <div
                onClick={() =>
                  onUpdateConfig({
                    highFreq15MinRule: !config.highFreq15MinRule,
                  })
                }
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Regra Turbo 15 Minutos (1 em 1 min)
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Atualiza o contador minuto a minuto nos 15 min finais
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.highFreq15MinRule}
                  ariaLabel="Regra Turbo 15 Minutos"
                  onChange={() =>
                    onUpdateConfig({
                      highFreq15MinRule: !config.highFreq15MinRule,
                    })
                  }
                />
              </div>
            </div>

            {/* SEÇÃO 3: AGENDA & TAREFAS (SINCRONIZAÇÃO CONJUNTA) */}
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-3">
              <div className="text-[11px] font-bold tracking-wider uppercase text-[#F5C438]">
                3. Agenda &amp; Tarefas (Hoje / Sem Horário)
              </div>

              <div
                onClick={() => setCalendarSyncEnabled((v) => !v)}
                className="flex items-center justify-between gap-3 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Compromissos da Agenda
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Progresso autônomo no relógio até o fim dos eventos do dia
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={calendarSyncEnabled}
                  ariaLabel="Sincronizar Calendários do Telefone"
                  onChange={() => setCalendarSyncEnabled((v) => !v)}
                />
              </div>

              <button
                type="button"
                onClick={() => setIsSyncedCalendarsDialogOpen(true)}
                className="w-full py-2 px-3 rounded-xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-white flex items-center justify-center gap-2 cursor-pointer"
              >
                <Calendar className="w-3.5 h-3.5 text-emerald-400" />
                <span>Calendários Sincronizados ({selectedCalendarsCount})</span>
              </button>

              <div
                onClick={() =>
                  onUpdateConfig({
                    tasksSyncEnabled: !(config.tasksSyncEnabled ?? true),
                  })
                }
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Tarefas Automaticamente com a Agenda
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Envia apenas tarefas não concluídas de Hoje ou Sem horário
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.tasksSyncEnabled ?? true}
                  ariaLabel="Sincronizar Tarefas"
                  onChange={() =>
                    onUpdateConfig({
                      tasksSyncEnabled: !(config.tasksSyncEnabled ?? true),
                    })
                  }
                />
              </div>

              <button
                type="button"
                data-testid="phone-sync-tasks-now"
                onClick={() => setIsManageTasksModalOpen(true)}
                className="w-full py-2 px-3 rounded-xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-emerald-300 flex items-center justify-center gap-2 cursor-pointer"
              >
                <CheckSquare className="w-3.5 h-3.5 text-emerald-400" />
                <span>
                  Gerenciar Tarefas Ativas ({activeTodayOrTimelessTasks.length})
                </span>
              </button>
            </div>

            {/* SEÇÃO 4: CLIMA, SOL UNIFICADO E SONO DE HOJE */}
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-3">
              <div className="text-[11px] font-bold tracking-wider uppercase text-[#F5C438]">
                4. Clima &amp; Sol Unificados + Sono do Dia
              </div>

              <div
                onClick={() => {
                  const next = !(config.phoneWeatherAutoCheckEnabled ?? true);
                  onUpdateConfig({
                    phoneWeatherAutoCheckEnabled: next,
                    phoneSolarAutoSyncEnabled: next,
                  });
                }}
                className="flex items-center justify-between gap-3 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Localização Fixa Unificada (Clima + Pôr do Sol)
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    📍{' '}
                    {formatCompactWeatherLocation(
                      config.weatherLocationName ?? 'São Paulo, SP'
                    )}{' '}
                    • Sol {config.solarSunriseHHMM ?? '05:27'} /{' '}
                    {config.solarSunsetHHMM ?? '17:40'}
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.phoneWeatherAutoCheckEnabled ?? true}
                  ariaLabel="Busca Automática de Clima e Sol pela Localização"
                  onChange={() => {
                    const next = !(config.phoneWeatherAutoCheckEnabled ?? true);
                    onUpdateConfig({
                      phoneWeatherAutoCheckEnabled: next,
                      phoneSolarAutoSyncEnabled: next,
                    });
                  }}
                />
              </div>

              <button
                type="button"
                onClick={() => setIsWeatherAppsDialogOpen(true)}
                className="w-full py-2 px-3 rounded-xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-sky-300 flex items-center justify-center gap-2 cursor-pointer"
              >
                <CloudRain className="w-3.5 h-3.5 text-sky-400" />
                <span>
                  Apps Gatilho de Clima ({selectedWeatherApps.length} selecionados)
                </span>
              </button>

              <div
                onClick={() =>
                  onUpdateConfig({
                    sleepSyncEnabled: !(config.sleepSyncEnabled ?? true),
                  })
                }
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Sono do Dia Automaticamente
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Verifica se há dados hoje ou informa que não há dados de sono
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.sleepSyncEnabled ?? true}
                  ariaLabel="Sincronizar Dados de Sono"
                  onChange={() =>
                    onUpdateConfig({
                      sleepSyncEnabled: !(config.sleepSyncEnabled ?? true),
                    })
                  }
                />
              </div>

              <button
                type="button"
                onClick={() =>
                  onUpdateConfig({
                    sleepHasDataToday: !(config.sleepHasDataToday ?? true),
                  })
                }
                className="w-full py-2 px-3 rounded-xl border border-purple-500/40 bg-purple-500/10 hover:bg-purple-500/20 text-xs font-semibold text-purple-200 flex items-center justify-center gap-2 cursor-pointer"
              >
                <Moon className="w-3.5 h-3.5 text-purple-300" />
                <span>
                  Estado do Sono Hoje:{' '}
                  {(config.sleepHasDataToday ?? true)
                    ? 'Com Dados Registrados (7h 42m)'
                    : 'Sem Dados de Sono para Hoje'}
                </span>
              </button>

              <div
                onClick={() => setBgServiceEnabled((v) => !v)}
                className="flex items-center justify-between gap-3 pt-2.5 border-t border-white/10 cursor-pointer"
              >
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Serviço Contínuo em Segundo Plano
                  </div>
                  <div className="text-[10.5px] text-slate-400">
                    Mantém sincronização ativa de agenda, tarefas, clima e sono
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={bgServiceEnabled}
                  ariaLabel="Acesso a Notificações Ativo"
                  onChange={() => setBgServiceEnabled((v) => !v)}
                />
              </div>
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 3: SOBRE (MODELO REPRESENTATIVO & ARQUITETURA + TESTES)      */}
        {/* =============================================================== */}
        {activeTab === 'about' && (
          <div
            key="tab-about"
            className="space-y-3.5 flex-1 max-h-[490px] overflow-y-auto pr-1 transition-all duration-200"
          >
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-2.5 text-xs text-slate-300 leading-relaxed">
              <div className="text-sm font-bold text-[#F5C438]">
                Modelo Representativo &amp; Arquitetura (v1.2.1)
              </div>
              <p>
                • <strong>Janela de Contexto em Canvas Bitmap:</strong> O mostrador utiliza um único slot central 302×136dp (6 modos: Auto, Agenda, Tarefas, Clima, Sol e Sono) desenhado em tempo real.
              </p>
              <p>
                • <strong>Progressão Autônoma da Agenda:</strong> O relógio armazena o horizonte de compromissos em cache SQLite local e atualiza o cronômetro e a lista minuto a minuto sem depender do telefone até acabarem os eventos do dia.
              </p>
              <p>
                • <strong>Localização Persistente Clima + Sol:</strong> As coordenadas válidas são salvas para evitar regressão para localizações genéricas em segundo plano, exibindo sempre a UF oficial.
              </p>
            </div>

            {/* Simulador de Alteração na Agenda */}
            <div className="rounded-[22px] bg-[#141A16] border border-[#253029] p-4 space-y-3">
              <div className="text-xs font-bold text-white">
                Adicionar Compromisso de Teste na Agenda
              </div>
              <form onSubmit={handleCreateQuickEvent} className="flex gap-2">
                <input
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Novo evento na agenda..."
                  className="flex-1 bg-[#0E110F] border border-[#2B332E] rounded-xl px-3 py-1.5 text-xs text-white focus:outline-none focus:border-emerald-400"
                />
                <select
                  value={newMinutesFromNow}
                  onChange={(e) => setNewMinutesFromNow(Number(e.target.value))}
                  aria-label="Minutos até o evento"
                  className="bg-[#0E110F] border border-[#2B332E] rounded-xl px-2 py-1.5 text-xs text-slate-200"
                >
                  <option value={20}>+20m</option>
                  <option value={45}>+45m</option>
                  <option value={90}>+1h30</option>
                </select>
                <button
                  type="submit"
                  className="px-3 py-1.5 rounded-xl bg-[#F5C438] text-[#050507] font-bold text-xs flex items-center gap-1 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                  <span>Criar</span>
                </button>
              </form>

              <div className="space-y-1.5 max-h-28 overflow-y-auto pr-1">
                {futureEvents.slice(0, 3).map((ev) => (
                  <div
                    key={ev.id}
                    className="flex items-center justify-between bg-[#1B201D] border border-[#29312C] rounded-xl px-3 py-1.5 text-xs"
                  >
                    <span className="truncate text-slate-200">
                      <strong className="text-amber-300 font-mono mr-1.5">
                        {formatHHMM(ev.startEpochMs)}
                      </strong>
                      {ev.title}
                    </span>
                    <button
                      type="button"
                      onClick={() => onDeleteEvent(ev.id)}
                      className="text-slate-400 hover:text-rose-400 p-1 cursor-pointer"
                      title="Apagar compromisso"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* RODAPÉ DE NAVEGAÇÃO */}
      <div className="absolute bottom-4 left-0 right-0 px-5 pointer-events-none z-10">
        <nav
          aria-label="Navegação principal do app"
          className="relative pointer-events-auto mx-auto rounded-[36px] bg-[#1E2520]/95 backdrop-blur-md border border-[#344038] p-1.5 shadow-2xl grid grid-cols-3"
        >
          <div
            className="absolute top-1.5 bottom-1.5 left-1.5 w-[calc((100%-12px)/3)] rounded-[28px] bg-[#36423A] transition-transform duration-200"
            style={{
              transform: `translateX(${activeTabIndex * 100}%)`,
            }}
          />

          {(
            [
              { id: 'home', label: 'Início', Icon: Home },
              { id: 'settings', label: 'Configurações', Icon: Settings },
              { id: 'about', label: 'Sobre', Icon: Info },
            ] as const
          ).map((item) => {
            const isActive = activeTab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className="relative z-10 flex flex-col items-center justify-center py-2 px-3 rounded-[28px] cursor-pointer select-none"
              >
                <item.Icon
                  className={`w-4 h-4 ${
                    isActive ? 'text-[#F5C438]' : 'text-[#A0ABA3]'
                  }`}
                />
                <span
                  className={`text-[11px] mt-0.5 ${
                    isActive ? 'font-bold text-white' : 'font-medium text-[#A0ABA3]'
                  }`}
                >
                  {item.label}
                </span>
              </button>
            );
          })}
        </nav>
      </div>

      {/* Modal Gerenciar Tarefas de Hoje / Sem Horário */}
      {isManageTasksModalOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20">
          <div className="w-full max-w-sm rounded-[26px] bg-[#161C18] border border-[#344038] p-5 space-y-3 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-bold text-white">
                Tarefas Ativas (Hoje / Sem Horário)
              </h3>
              <button
                type="button"
                onClick={() => setIsManageTasksModalOpen(false)}
                className="text-slate-400 hover:text-white p-1 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleAddNewTask} className="flex gap-1.5">
              <input
                type="text"
                value={newTaskTitle}
                onChange={(e) => setNewTaskTitle(e.target.value)}
                placeholder="Nova tarefa..."
                className="flex-1 bg-black/50 border border-slate-700 rounded-xl px-2.5 py-1.5 text-xs text-white"
              />
              <input
                type="text"
                value={newTaskDueHHMM}
                onChange={(e) => setNewTaskDueHHMM(e.target.value)}
                placeholder="Hoje ou 16:00"
                className="w-20 bg-black/50 border border-slate-700 rounded-xl px-2 py-1.5 text-xs text-sky-300 font-mono"
              />
              <button
                type="submit"
                className="px-3 py-1.5 rounded-xl bg-[#F5C438] text-slate-950 font-bold text-xs cursor-pointer"
              >
                +
              </button>
            </form>

            <div className="space-y-1.5 max-h-48 overflow-y-auto">
              {activeTodayOrTimelessTasks.length === 0 ? (
                <div className="text-xs text-slate-400 py-3 text-center">
                  Nenhuma tarefa pendente para hoje.
                </div>
              ) : (
                activeTodayOrTimelessTasks.map((t) => (
                  <div
                    key={t.id}
                    onClick={(e) => handleCompleteTaskInWidget(t.id, e)}
                    className="flex items-center justify-between gap-2 p-2 rounded-xl bg-black/40 border border-slate-800 hover:border-emerald-500/40 cursor-pointer"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="w-4 h-4 rounded border border-sky-400 flex items-center justify-center text-[10px] text-emerald-400">
                        ✓
                      </span>
                      <span className="text-xs font-semibold text-white truncate">
                        {t.title}
                      </span>
                    </div>
                    <span className="text-[10px] font-mono text-sky-300 shrink-0">
                      {t.dueHHMM || 'Hoje'}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* Modal Calendários Sincronizados */}
      {isSyncedCalendarsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20">
          <div className="w-full max-w-sm rounded-[28px] bg-[#1A201C] border border-[#344038] p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white">
                Calendários Sincronizados
              </h3>
              <button
                type="button"
                onClick={() => setIsSyncedCalendarsDialogOpen(false)}
                className="text-slate-400 hover:text-white p-1 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-2">
              {calendars.map((cal) => (
                <button
                  key={cal.id}
                  type="button"
                  onClick={() => onToggleCalendar(cal.id)}
                  className={`w-full flex items-center justify-between gap-3 p-3 rounded-2xl border text-left cursor-pointer ${
                    cal.enabled
                      ? 'bg-[#26322B] border-[#4A6353]'
                      : 'bg-[#141815] border-[#242B26] opacity-70'
                  }`}
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <span
                      className="w-3 h-3 rounded-full shrink-0"
                      style={{ backgroundColor: cal.color }}
                    />
                    <div className="min-w-0">
                      <div className="text-xs font-bold text-white truncate">
                        {cal.name}
                      </div>
                      <div className="text-[10px] text-slate-400 truncate">
                        {cal.account}
                      </div>
                    </div>
                  </div>
                  <div
                    className={`w-5 h-5 rounded-md flex items-center justify-center shrink-0 ${
                      cal.enabled
                        ? 'bg-[#22C55E] text-[#052E16]'
                        : 'border border-slate-500'
                    }`}
                  >
                    {cal.enabled && <Check className="w-3.5 h-3.5 stroke-[3]" />}
                  </div>
                </button>
              ))}
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => {
                  setIsSyncedCalendarsDialogOpen(false);
                  onTriggerManualSync();
                }}
                className="px-5 py-2 rounded-full bg-[#22C55E] text-[#052E16] font-bold text-xs cursor-pointer"
              >
                Salvar
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal Apps de Clima */}
      {isWeatherAppsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20">
          <div className="w-full max-w-sm rounded-[28px] bg-[#1A201C] border border-[#344038] p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white">
                Apps de Clima por Categoria
              </h3>
              <button
                type="button"
                onClick={() => setIsWeatherAppsDialogOpen(false)}
                className="text-slate-400 hover:text-white p-1 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3 max-h-64 overflow-y-auto pr-1">
              {weatherCategories.map((category) => {
                const appsInCat = WEATHER_TRIGGER_CATALOG.filter(
                  (a) => a.categoryName === category
                );
                return (
                  <div key={category} className="space-y-1.5">
                    <div className="text-[10px] font-bold uppercase tracking-wider text-emerald-400 px-1">
                      {category}
                    </div>
                    {appsInCat.map((appItem) => {
                      const isChecked = selectedWeatherApps.includes(
                        appItem.packageName
                      );
                      return (
                        <button
                          key={appItem.packageName}
                          type="button"
                          onClick={() =>
                            handleToggleWeatherTriggerApp(appItem.packageName)
                          }
                          className={`w-full flex items-center justify-between gap-3 p-2.5 rounded-2xl border text-left cursor-pointer ${
                            isChecked
                              ? 'bg-[#26322B] border-[#4A6353]'
                              : 'bg-[#141815] border-[#242B26] opacity-75'
                          }`}
                        >
                          <div className="min-w-0">
                            <div className="text-xs font-bold text-white truncate">
                              {appItem.displayName}
                            </div>
                            <div className="text-[10px] font-mono text-slate-400 truncate">
                              {appItem.packageName}
                            </div>
                          </div>
                          <div
                            className={`w-5 h-5 rounded-md flex items-center justify-center shrink-0 ${
                              isChecked
                                ? 'bg-[#22C55E] text-[#052E16]'
                                : 'border border-slate-500'
                            }`}
                          >
                            {isChecked && (
                              <Check className="w-3.5 h-3.5 stroke-[3]" />
                            )}
                          </div>
                        </button>
                      );
                    })}
                  </div>
                );
              })}
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => {
                  setIsWeatherAppsDialogOpen(false);
                  onTriggerManualSync();
                }}
                className="px-5 py-2 rounded-full bg-[#22C55E] text-[#052E16] font-bold text-xs cursor-pointer"
              >
                Salvar Triggers
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
