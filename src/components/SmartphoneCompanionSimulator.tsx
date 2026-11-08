import React, { useState, useEffect } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  PrimaryContextMode,
  TaskScheduleCategory,
  WEATHER_TRIGGER_CATALOG,
  DEFAULT_TASKS_LIST,
  formatCompactWeatherLocation,
  resolveTasksCanvasState,
  isSameCalendarDay,
  formatDayRelative,
  formatCountdownText,
  computeSolarCurvePoint,
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
  CloudRain,
  CheckSquare,
  Maximize2,
  Minimize2,
  GripVertical,
  ShieldAlert,
  ShieldCheck,
  Moon,
  Footprints,
  Sun,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Lock,
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
  disabled?: boolean;
}

const ModernOneUiSwitch: React.FC<ModernSwitchProps> = ({
  checked,
  onChange,
  ariaLabel,
  disabled = false,
}) => (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={ariaLabel}
    disabled={disabled}
    onClick={(e) => {
      e.stopPropagation();
      if (!disabled) onChange();
    }}
    className={`w-[52px] h-[28px] rounded-full p-[3px] transition-all duration-150 active:scale-95 flex items-center cursor-pointer shrink-0 border ${
      checked
        ? 'bg-[#F5C438] border-[#FDE047]'
        : 'bg-[#2A3441] border-[#64748B]'
    } ${disabled ? 'opacity-50 pointer-events-none' : ''}`}
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

function parseMinutesHHMM(hhmm: string | undefined, fallbackMins: number): number {
  if (!hhmm || !hhmm.includes(':')) return fallbackMins;
  const [h, m] = hhmm.split(':').map((v) => parseInt(v, 10));
  if (Number.isNaN(h) || Number.isNaN(m)) return fallbackMins;
  return h * 60 + m;
}

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

  // Estado de Pull-to-Refresh com Flecha Circular que Gira Conforme Puxa
  const [isPullRefreshing, setIsPullRefreshing] = useState(false);
  const [pullDragY, setPullDragY] = useState(0);
  const [touchStartY, setTouchStartY] = useState<number | null>(null);
  const [lastStatusCheckHHMMSS, setLastStatusCheckHHMMSS] = useState('09:00:00');
  const [hasVerifiedValidSync, setHasVerifiedValidSync] = useState(true);

  // Permissões do Smartphone (com Mini Tutorial e Proteção por Camada Fina Escura)
  const [calendarPermGranted, setCalendarPermGranted] = useState(true);
  const [locationPermGranted, setLocationPermGranted] = useState(true);
  const [notifPermGranted, setNotifPermGranted] = useState(true);
  const [batteryOptIgnored, setBatteryOptIgnored] = useState(true);

  // Estados das chaves de Settings
  const [bgServiceEnabled, setBgServiceEnabled] = useState(true);
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(true);

  // Representação Virtual 1:1 do Relógio no Topo e Widgets 2x2
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
  const [newTaskDueHHMM, setNewTaskDueHHMM] = useState('14:00');
  const [newTaskCategory, setNewTaskCategory] =
    useState<TaskScheduleCategory>('TODAY');
  const [newTitle, setNewTitle] = useState('');
  const [newMinutesFromNow, setNewMinutesFromNow] = useState<number>(45);

  // Sempre que o modo ou foco principal mudar nas configurações, limpa qualquer override local da prévia para refletir imediatamente
  useEffect(() => {
    setVirtualWatchPreviewOverride(null);
  }, [config.contextWindowMode, config.autoPrimaryContext]);

  const widgetOrder: PrimaryContextMode[] =
    config.homeWidgetsOrder ?? DEFAULT_WIDGET_ORDER;
  const expandedWidgets: PrimaryContextMode[] = config.expandedWidgets ?? [];

  // Lista de Permissões Pendentes
  const missingPermissions = [
    !calendarPermGranted && {
      id: 'cal',
      title: 'Acesso ao Calendário (Agenda)',
      desc: 'Necessário para ler os próximos compromissos de Hoje e Amanhã.',
      onGrant: () => setCalendarPermGranted(true),
    },
    !locationPermGranted && {
      id: 'loc',
      title: 'Localização Unificada (Clima e Pôr do Sol)',
      desc: 'Usada simultaneamente para obter a cidade/UF oficial e calcular o pôr do sol.',
      onGrant: () => setLocationPermGranted(true),
    },
    !notifPermGranted && {
      id: 'notif',
      title: 'Notificações e Leitor de Gatilhos',
      desc: 'Necessário para alertas de mudança climática e lembretes de tarefas.',
      onGrant: () => setNotifPermGranted(true),
    },
    !batteryOptIgnored && {
      id: 'bat',
      title: 'Execução em 2º Plano sem Restrição de Bateria',
      desc: 'Garante que a sincronização de agenda, tarefas e sono não trave.',
      onGrant: () => setBatteryOptIgnored(true),
    },
  ].filter(Boolean) as Array<{
    id: string;
    title: string;
    desc: string;
    onGrant: () => void;
  }>;

  // Bloqueio de Personalização: ativo sempre que faltar permissão OU o app não estiver instalado/conectado no relógio
  const isCustomizationLocked =
    missingPermissions.length > 0 ||
    !isBluetoothConnected ||
    !isWearAppInstalledOnWatch;

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

    // Somente dispara sincronização real se todas as permissões estiverem concedidas E o app do relógio estiver instalado/conectado
    const canActuallySync =
      missingPermissions.length === 0 &&
      isBluetoothConnected &&
      isWearAppInstalledOnWatch;

    setHasVerifiedValidSync(canActuallySync);
    if (triggerSync && canActuallySync) {
      onTriggerManualSync();
    }
  };

  useEffect(() => {
    refreshConnectionAndStatus(false);
  }, [
    activeTab,
    isBluetoothConnected,
    isWearAppInstalledOnWatch,
    calendarPermGranted,
    locationPermGranted,
    notifPermGranted,
    batteryOptIgnored,
  ]);

  useEffect(() => {
    const onWindowFocus = () => {
      refreshConnectionAndStatus(false);
    };
    window.addEventListener('focus', onWindowFocus);
    return () => window.removeEventListener('focus', onWindowFocus);
  }, []);

  const handleTriggerPullRefresh = () => {
    if (isPullRefreshing) return;
    setPullDragY(56);
    setIsPullRefreshing(true);
    setTimeout(() => {
      refreshConnectionAndStatus(true);
      setIsPullRefreshing(false);
      setPullDragY(0);
    }, 420);
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
  const resolvedTasksState = resolveTasksCanvasState(allTasks);
  const activeTodayOrTimelessTasks = resolvedTasksState.displayTasks;

  const handleCompleteTaskInWidget = (taskId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (isCustomizationLocked) return;
    const updated = allTasks.map((t) =>
      t.id === taskId ? { ...t, completed: !t.completed } : t
    );
    onUpdateConfig({ tasks: updated });
    refreshConnectionAndStatus(true);
  };

  const handleApplyTasksTierScenario = (
    tier: 'TODAY' | 'TOMORROW' | 'UNDATED_OVERDUE'
  ) => {
    if (isCustomizationLocked) return;
    const updated = DEFAULT_TASKS_LIST.map((t) => {
      if (t.id === 't7') return { ...t, completed: true };
      if (tier === 'TODAY') {
        return { ...t, completed: false };
      }
      if (tier === 'TOMORROW') {
        return {
          ...t,
          completed: t.scheduleCategory === 'TODAY',
        };
      }
      return {
        ...t,
        completed:
          t.scheduleCategory === 'TODAY' || t.scheduleCategory === 'TOMORROW',
      };
    });
    onUpdateConfig({
      tasksSyncEnabled: true,
      contextWindowMode: 'TASKS',
      autoPrimaryContext: 'TASKS',
      tasks: updated,
    });
    refreshConnectionAndStatus(true);
  };

  const handleAddNewTask = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTaskTitle.trim()) return;
    const isTimed =
      newTaskCategory === 'TODAY' || newTaskCategory === 'TOMORROW';
    const created = {
      id: `t_${Date.now()}`,
      title: newTaskTitle.trim(),
      dueHHMM: isTimed ? newTaskDueHHMM.trim() || '14:00' : '',
      sourceLabel: 'Tasks' as const,
      completed: false,
      scheduleCategory: newTaskCategory,
      isOverdue: newTaskCategory === 'OVERDUE',
      isTodayOrTimeless: true,
    };
    onUpdateConfig({
      tasksSyncEnabled: true,
      tasks: [created, ...allTasks],
    });
    refreshConnectionAndStatus(true);
    setNewTaskTitle('');
  };

  // Resolve qual contexto a Watchface 1:1 no topo deve desenhar (idêntico ao relógio)
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

  // Ao tocar na Watchface 1:1 ou escolher um contexto no smartphone, reflete IMEDIATAMENTE no relógio!
  const handleSelectAndPushFocusToWatch = (nextMode: PrimaryContextMode) => {
    if (isCustomizationLocked) return;
    setVirtualWatchPreviewOverride(null);
    if (config.contextWindowMode === 'DYNAMIC') {
      onUpdateConfig({ autoPrimaryContext: nextMode });
    } else {
      onUpdateConfig({
        contextWindowMode: nextMode,
        autoPrimaryContext: nextMode,
      });
    }
    refreshConnectionAndStatus(true);
  };

  const handleCycleVirtualWatchPreview = () => {
    if (isCustomizationLocked) return;
    const cycle: PrimaryContextMode[] = [
      'EVENTS',
      'TASKS',
      'WEATHER',
      'SUN',
      'SLEEP',
      'STEPS',
    ];
    const idx = cycle.indexOf(virtualWatchMode);
    const nextMode = cycle[(idx + 1) % cycle.length];
    handleSelectAndPushFocusToWatch(nextMode);
  };

  // Expansão e Redução determinística dos Blocos de Widgets (corrige bug de aumentar mas não diminuir)
  const handleToggleExpandWidget = (
    widgetId: PrimaryContextMode,
    e: React.MouseEvent
  ) => {
    e.stopPropagation();
    e.preventDefault();
    if (isCustomizationLocked) return;
    const currentSet = new Set(expandedWidgets);
    if (currentSet.has(widgetId)) {
      currentSet.delete(widgetId);
    } else {
      currentSet.add(widgetId);
    }
    onUpdateConfig({ expandedWidgets: Array.from(currentSet) });
  };

  const handleMoveWidgetOrder = (
    widgetId: PrimaryContextMode,
    delta: number,
    e: React.MouseEvent
  ) => {
    e.stopPropagation();
    e.preventDefault();
    if (isCustomizationLocked) return;
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
    if (isCustomizationLocked || sourceId === targetId) return;
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

  // Cálculo da posição atual do sol na curva cosseno completa (dia acima do horizonte + noite abaixo do horizonte)
  const watchDate = new Date(referenceTimeMs);
  const weekdayMap = ['DOM.', 'SEG.', 'TER.', 'QUA.', 'QUI.', 'SEX.', 'SÁB.'];
  const weekdayUpper = weekdayMap[watchDate.getDay()] || 'SEX.';
  const dayOfMonth = String(watchDate.getDate()).padStart(2, '0');
  const timeHHMMSS = `${formatHHMM(referenceTimeMs)}:06`;

  const currentMinutesOfDay =
    watchDate.getHours() * 60 + watchDate.getMinutes();
  const sunriseMins = parseMinutesHHMM(config.solarSunriseHHMM, 5 * 60 + 27);
  const sunsetMins = parseMinutesHHMM(config.solarSunsetHHMM, 17 * 60 + 40);
  const solarPoint = computeSolarCurvePoint(
    currentMinutesOfDay,
    sunriseMins,
    sunsetMins
  );

  // Curva cosseno completa (x=2..258) cruzando a linha do horizonte (y=44) em x=66 e x=194 e descendo abaixo da linha do horizonte (até y=66) à noite
  const solarCurvePathD = (() => {
    const pts: string[] = [];
    const steps = 64;
    for (let i = 0; i <= steps; i++) {
      const x = 2 + (i / steps) * 256;
      const cosVal = Math.cos((Math.PI / 2) * ((x - 130) / 64));
      const y = cosVal >= 0 ? 44 - cosVal * 32 : 44 - cosVal * 22;
      pts.push(`${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`);
    }
    return pts.join(' ');
  })();

  // Renderiza o Canvas Solar idêntico ao do relógio (com ícones Nascer/Pôr do Sol, gradiente crepuscular, horizonte azul e curva que desce abaixo do horizonte no momento sem sol)
  const renderIdenticalSunCanvas = (svgIdPrefix: string) => (
    <div
      data-testid={`${svgIdPrefix}-sun-canvas`}
      className="flex flex-col justify-between h-full py-0.5 select-none"
    >
      {/* Linha Superior: Ícone Verde Nascer + 05:27 à esquerda | 17:40 + Ícone Azul Pôr do Sol à direita */}
      <div className="flex items-center justify-between px-0.5">
        <div className="flex items-center gap-1">
          <svg viewBox="0 0 28 24" className="w-4 h-3.5 shrink-0">
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
          <span className="text-[11px] font-bold font-mono text-white leading-none">
            {config.solarSunriseHHMM ?? '05:27'}
          </span>
        </div>

        <div className="flex items-center gap-1">
          <span className="text-[11px] font-bold font-mono text-white leading-none">
            {config.solarSunsetHHMM ?? '17:40'}
          </span>
          <svg viewBox="0 0 28 24" className="w-4 h-3.5 shrink-0">
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

      {/* Gráfico Solar Astronômico com Gradiente Crepuscular, Horizonte Azul, Curva Dia+Noite (abaixo do horizonte) e Símbolos ↑ ☼ ↓ */}
      <div className="relative w-full flex-1 mt-0.5 min-h-[44px]">
        <svg
          viewBox="0 0 260 72"
          className="w-full h-full overflow-visible"
          preserveAspectRatio="none"
        >
          <defs>
            <linearGradient
              id={`${svgIdPrefix}-twilightGrad`}
              x1="0"
              y1="0"
              x2="0"
              y2="1"
            >
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
            fill={`url(#${svgIdPrefix}-twilightGrad)`}
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

          {/* Curva Cosseno Branca da Trajetória Solar (Cruza y=44 em x=66 e x=194 e desce ABAIXO da linha do horizonte até y=66 representando o momento sem sol) */}
          <path
            data-testid={`${svgIdPrefix}-solar-full-curve`}
            d={solarCurvePathD}
            fill="none"
            stroke="#FFFFFF"
            strokeWidth="3.2"
            strokeLinecap="round"
          />

          {/* Símbolo ↑ (Nascer) */}
          <g
            stroke="#96A0B0"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            fill="none"
          >
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
          <g
            stroke="#96A0B0"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            fill="none"
          >
            <line x1="194" y1="57" x2="194" y2="68" />
            <polyline points="189,63 194,68 199,63" />
          </g>

          {/* Ponto luminoso do Sol na trajetória atual (acima do horizonte de dia, abaixo do horizonte à noite) */}
          <g
            data-testid={`${svgIdPrefix}-sun-dot`}
            data-below-horizon={solarPoint.isBelowHorizon}
          >
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
            <circle
              cx={solarPoint.sunX}
              cy={solarPoint.sunY}
              r="3.8"
              fill="#FFFDF5"
            />
          </g>
        </svg>
      </div>
    </div>
  );

  // Renderiza o conteúdo gráfico interno de cada contexto (idêntico entre Watchface 1:1 e Tiles 2x2)
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
              <div className="flex items-center gap-1">
                <Calendar className="w-3 h-3 text-[#F5C438] shrink-0" />
                <span className="text-[11px] font-bold text-[#F5C438] leading-none">
                  {strictAgendaHeader}
                </span>
              </div>
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
          <div
            data-testid={
              isInsideVirtualWatch
                ? 'phone-watchface-tasks-canvas'
                : 'phone-tile-tasks-canvas'
            }
            data-tasks-bucket={resolvedTasksState.bucket}
            className="flex flex-col justify-between h-full text-left"
          >
            <div className="flex items-center justify-between">
              <span
                data-testid={
                  isInsideVirtualWatch
                    ? 'phone-watchface-tasks-header'
                    : 'phone-tile-tasks-header'
                }
                className="text-[10.5px] font-bold text-[#F5C438] leading-none"
              >
                {resolvedTasksState.headerTitle}
              </span>
              <span className="text-[9.5px] font-mono text-[#67E8F9] leading-none">
                {resolvedTasksState.rightBadgeText}
              </span>
            </div>

            {activeTodayOrTimelessTasks.length === 0 ? (
              <div className="my-auto text-[10px] text-slate-400">
                Não há tarefas pendentes
              </div>
            ) : (
              <div className="space-y-1 my-auto">
                {activeTodayOrTimelessTasks.slice(0, 3).map((t, i) => (
                  <div
                    key={t.id}
                    data-testid={
                      isInsideVirtualWatch
                        ? `phone-watchface-task-${t.id}`
                        : `phone-tile-task-${t.id}`
                    }
                    data-overdue={t.isRedOverdue ? 'true' : 'false'}
                    onClick={(e) => handleCompleteTaskInWidget(t.id, e)}
                    title="Toque no check para concluir e avançar para o próximo nível de tarefas"
                    className="flex items-center justify-between gap-1 text-[10px] leading-tight hover:bg-white/5 rounded px-0.5 cursor-pointer"
                  >
                    <div className="flex items-center gap-1 min-w-0">
                      <span
                        className={`w-3 h-3 rounded-[3px] border flex items-center justify-center text-[8px] text-transparent hover:text-emerald-400 shrink-0 ${
                          t.isRedOverdue
                            ? 'border-[#EF4444]'
                            : 'border-[#38BDF8]'
                        }`}
                      >
                        ✓
                      </span>
                      {resolvedTasksState.showTime && t.displayTimeHHMM && (
                        <span
                          data-testid={
                            isInsideVirtualWatch
                              ? `phone-watchface-task-time-${t.id}`
                              : `phone-tile-task-time-${t.id}`
                          }
                          className="text-[9px] font-mono font-bold text-white shrink-0"
                        >
                          {t.displayTimeHHMM}
                        </span>
                      )}
                      <span
                        className={`truncate font-semibold ${
                          t.isRedOverdue
                            ? 'text-[#F87171]'
                            : i === 0
                            ? 'text-[#F5C438]'
                            : 'text-slate-100'
                        }`}
                      >
                        {t.title}
                      </span>
                    </div>
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
                Clima • 29°C
              </span>
              <span className="text-[9.5px] font-mono text-[#67E8F9] leading-none">
                Parc. nublado • 25%
              </span>
            </div>
            <div className="text-[9.5px] font-semibold text-slate-300 truncate leading-none">
              📍 {loc}
            </div>
            <svg viewBox="0 0 180 36" className="w-full h-[32px] overflow-visible">
              <path
                d="M 10 12 C 35 12, 45 16, 65 16 C 85 16, 95 26, 115 26 C 135 26, 150 20, 170 20"
                fill="none"
                stroke="#67E8F9"
                strokeWidth="2.4"
                strokeLinecap="round"
              />
              {[
                { x: 10, y: 12, t: '29°', first: true },
                { x: 65, y: 16, t: '27°', first: false },
                { x: 115, y: 26, t: '24°', first: false },
                { x: 170, y: 20, t: '25°', first: false },
              ].map((p, idx) => (
                <g key={idx}>
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r="2.8"
                    fill={p.first ? '#F5C438' : '#67E8F9'}
                  />
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
        return renderIdenticalSunCanvas(
          isInsideVirtualWatch ? 'phone-watchface' : 'phone-tile'
        );
      }

      case 'SLEEP': {
        const hasSleepToday = config.sleepHasDataToday ?? true;
        return (
          <div className="flex flex-col justify-between h-full text-left">
            <div className="flex items-center justify-between">
              <span className="text-[10.5px] font-bold text-[#F5C438] leading-none">
                Sono • Hoje
              </span>
              <span className="text-[9.5px] font-mono text-[#67E8F9] leading-none">
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
              <span className="text-[10.5px] font-bold text-[#F5C438] leading-none">
                Passos • 8.435
              </span>
              <span className="text-[9.5px] font-mono text-[#67E8F9] leading-none">
                50.455 sem.
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

  // Status honesto de atualização ao puxar a página (nunca afirma que atualizou os dados se faltar permissão ou se o app do relógio não estiver instalado)
  const pullRefreshBannerStatus = (() => {
    if (isPullRefreshing) {
      return {
        tone: 'border-amber-400/50 bg-[#141C16] text-amber-300',
        text: 'Verificando permissões, Bluetooth e app ChronoWear no relógio...',
      };
    }
    if (missingPermissions.length > 0) {
      return {
        tone: 'border-amber-500/45 bg-amber-500/10 text-amber-300',
        text: `⚠ Dados não atualizados: libere as ${missingPermissions.length} permissão(ões) pendente(s) abaixo`,
      };
    }
    if (!isBluetoothConnected) {
      return {
        tone: 'border-rose-500/45 bg-rose-500/10 text-rose-300',
        text: '⚠ Não sincronizado: Bluetooth desligado ou relógio desconectado',
      };
    }
    if (!isWearAppInstalledOnWatch) {
      return {
        tone: 'border-amber-500/45 bg-amber-500/10 text-amber-300',
        text: '⚠ Não sincronizado: app ChronoWear não instalado no relógio',
      };
    }
    if (hasVerifiedValidSync) {
      return {
        tone: 'border-emerald-500/35 bg-[#0D1511] text-emerald-300',
        text: `✓ Sincronizado com o relógio às ${lastStatusCheckHHMMSS} • Puxe a página para atualizar`,
      };
    }
    return {
      tone: 'border-[#26352C] bg-[#0D1310] text-sky-300',
      text: '↓ Puxe a página para baixo para verificar e atualizar',
    };
  })();

  const pullArrowRotationDeg = Math.round(
    Math.min(1, pullDragY / 56) * 320
  );

  return (
    <div
      data-testid="smartphone-companion-root"
      onTouchStart={(e) => {
        setTouchStartY(e.touches[0].clientY);
      }}
      onTouchMove={(e) => {
        if (touchStartY !== null) {
          const dy = e.touches[0].clientY - touchStartY;
          if (dy > 0) setPullDragY(Math.min(72, dy * 0.55));
        }
      }}
      onTouchEnd={() => {
        if (pullDragY >= 38) {
          handleTriggerPullRefresh();
        } else {
          setPullDragY(0);
        }
        setTouchStartY(null);
      }}
      className="relative rounded-[36px] border border-[#2A332C] text-slate-100 shadow-2xl overflow-hidden flex flex-col justify-between min-h-[640px]"
      style={{
        background:
          'linear-gradient(180deg, #1E241E 0%, #101713 28%, #090C0A 65%, #070908 100%)',
      }}
    >
      <div className="px-5 pt-3.5 pb-24 flex-1 flex flex-col">
        {/* INDICADOR DE PUXAR A PÁGINA COM FLECHA CIRCULAR QUE GIRA QUANTO MAIS PUXA (SEM BOTÃO "ATUALIZAR" NO TOPO) */}
        <div
          data-testid="phone-pull-to-refresh-bar"
          onClick={handleTriggerPullRefresh}
          title="Puxe a página para baixo para girar a flecha circular e atualizar"
          style={{
            transform:
              pullDragY > 0 ? `translateY(${pullDragY * 0.22}px)` : undefined,
          }}
          className={`w-full mb-2.5 py-1.5 px-3 rounded-xl border flex items-center justify-center gap-2 text-[11px] font-semibold transition-all cursor-pointer select-none ${pullRefreshBannerStatus.tone}`}
        >
          {/* Flecha Circular Vetorial que Gira Proporcionalmente ao Puxar */}
          <svg
            data-testid="phone-pull-arrow-spinner"
            viewBox="0 0 24 24"
            style={{
              transform: !isPullRefreshing
                ? `rotate(${pullArrowRotationDeg}deg)`
                : undefined,
            }}
            className={`w-4 h-4 shrink-0 text-[#F5C438] transition-transform duration-75 ${
              isPullRefreshing ? 'animate-spin' : ''
            }`}
          >
            <path
              d="M 20 12 A 8 8 0 1 1 17.6 6.3"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.4"
              strokeLinecap="round"
            />
            <polyline
              points="14 6 18.2 6.2 18 2"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <span className="truncate">{pullRefreshBannerStatus.text}</span>
        </div>

        {/* Cabeçalho Essencial do App Smartphone (Sem botão redundante "Atualizar") */}
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
            data-testid="phone-toggle-wear-installed"
            onClick={() => setIsWearAppInstalledOnWatch((prev) => !prev)}
            title="Simular detecção do app ChronoWear instalado ou ausente no relógio"
            className={`px-2.5 py-1 rounded-full text-[10.5px] font-semibold transition-all duration-150 active:scale-95 cursor-pointer ${
              isWearAppInstalledOnWatch
                ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-400/35'
                : 'bg-amber-500/20 text-amber-200 border border-amber-400/35'
            }`}
          >
            {isWearAppInstalledOnWatch
              ? 'App Wear: Detectado'
              : 'App Wear: Não detectado'}
          </button>
        </div>

        {/* =============================================================== */}
        {/* ABA 1: INÍCIO (CÓPIA IDÊNTICA 1:1 DA WATCHFACE + MINI TUTORIAL  */}
        {/* DE PERMISSÕES / LINK LASKOSKY + TILES 2X2 ERGONÔMICOS)          */}
        {/* =============================================================== */}
        {activeTab === 'home' && (
          <div
            key="tab-home"
            className="space-y-3 flex-1 flex flex-col transition-all duration-200"
          >
            {/* 1. WATCHFACE IDÊNTICA 1:1 AO RELÓGIO + STATUS PRECISO DE CONEXÃO */}
            <div className="rounded-[24px] bg-[#0B100E] border border-[#24332B] p-3.5 shadow-lg flex flex-col items-center">
              {/* Contêiner da Watchface com Proteção de Camada Fina Escura quando bloqueado */}
              <div className="relative w-full flex flex-col items-center">
                <div className="w-full flex items-center justify-between text-[10.5px] mb-2">
                  <span className="font-mono font-bold text-[#F5C438] uppercase tracking-wider">
                    Watchface WFF 1:1 •{' '}
                    {config.contextWindowMode === 'DYNAMIC'
                      ? `Auto (${virtualWatchMode})`
                      : `Fixo (${virtualWatchMode})`}
                  </span>
                  <span className="text-slate-400 text-[10px]">
                    Toque no Canvas p/ alternar
                  </span>
                </div>

                {/* Cópia Idêntica 1:1 da Watchface do Relógio (450x450 logical geometry) */}
                <div
                  data-testid="phone-virtual-watch-preview"
                  onClick={handleCycleVirtualWatchPreview}
                  title="Cópia idêntica 1:1 da Watchface do relógio gerada pelo app de smartphone"
                  className="relative w-[292px] h-[292px] rounded-full bg-black border-[7px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-2 px-5 cursor-pointer select-none overflow-hidden"
                >
                  {/* SVG Overlay Idêntico ao do Relógio (Arcos Laterais Bicolores + Bolinha Laranja + Barra Tubo de Ensaio 62%) */}
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

                  {/* Ícones inclinados nos arcos laterais idênticos ao relógio */}
                  <span className="absolute left-4 top-[74px] rotate-[28deg] text-[11px] font-bold text-[#F5C438] z-10">
                    ⏳
                  </span>
                  <span className="absolute left-4 top-[202px] -rotate-[28deg] text-[10px] font-bold font-mono text-[#88D4DC] z-10">
                    0
                  </span>
                  <span className="absolute right-4 top-[74px] -rotate-[28deg] w-4 h-4 rounded-full bg-[#F5C438] text-black text-[9px] font-bold flex items-center justify-center z-10">
                    ✓
                  </span>
                  <span className="absolute right-4 top-[202px] rotate-[28deg] text-[10px] font-bold font-mono text-slate-200 z-10">
                    0
                  </span>

                  {/* TOPO DA WATCHFACE: DIA DA SEMANA DOURADO + DIA BRANCO + HORA:MIN:SEG */}
                  <div className="text-center w-full z-10 pt-0.5">
                    <div className="text-[13px] font-bold tracking-wide flex items-center justify-center gap-1.5 leading-none">
                      <span className="text-[#F5C438]">{weekdayUpper}</span>
                      <span className="text-white">{dayOfMonth}</span>
                    </div>
                    <div className="text-[35px] font-normal font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
                      {timeHHMMSS}
                    </div>
                  </div>

                  {/* SLOT CENTRAL 302x136 DA WATCHFACE */}
                  <div className="w-[84%] h-[92px] rounded-[14px] bg-[#080A0E] border border-[#1B2430] px-2.5 py-1.5 flex flex-col justify-between z-10 -mt-1 overflow-hidden">
                    {renderContextVisualDrawing(virtualWatchMode, true)}
                  </div>

                  {/* DOCK INFERIOR EM CARTUCHO ENVOLVENTE (Passos 2.435 + Pôr do Sol 17:40) IDÊNTICO AO RELÓGIO */}
                  <div
                    data-testid="phone-watchface-dual-dock"
                    className="rounded-full bg-[#161D1C] border-[1.5px] border-[#283533] px-2 py-1 flex items-center justify-center gap-2 z-10 mb-6 shadow-inner"
                  >
                    <div className="w-[76px] h-[38px] rounded-full bg-[#263230] border-[1.5px] border-[#455955] flex flex-col items-center justify-center px-1.5">
                      <Footprints className="w-3 h-3 text-[#22C55E]" />
                      <span className="text-[11px] font-bold text-white leading-tight tracking-tight">
                        2.435
                      </span>
                    </div>
                    <div className="w-[76px] h-[38px] rounded-full bg-[#263230] border-[1.5px] border-[#455955] flex flex-col items-center justify-center px-1.5">
                      <Sun className="w-3 h-3 text-[#F97316]" />
                      <span className="text-[11px] font-bold text-white leading-tight tracking-tight">
                        {config.solarSunsetHHMM ?? '17:40'}
                      </span>
                    </div>
                  </div>
                </div>

                {/* CAMADA FINA ESCURA DE PROTEÇÃO SOBRE A WATCHFACE QUANDO FALTA PERMISSÃO OU APP NO RELÓGIO */}
                {isCustomizationLocked && (
                  <div
                    data-testid="watch-preview-lock-overlay"
                    onClick={(e) => e.stopPropagation()}
                    className="absolute inset-0 rounded-[18px] bg-black/65 backdrop-blur-[1px] z-20 flex flex-col items-center justify-center p-4 text-center select-none"
                  >
                    <div className="px-3 py-1.5 rounded-full bg-[#111714]/95 border border-amber-400/50 flex items-center gap-1.5 text-[11px] font-bold text-amber-300 shadow-lg">
                      <Lock className="w-3.5 h-3.5 shrink-0" />
                      <span>
                        {missingPermissions.length > 0
                          ? 'Conclua o Passo 1 (Permissões) abaixo'
                          : 'Instale o App no Relógio para liberar'}
                      </span>
                    </div>
                  </div>
                )}
              </div>

              {/* Status de Sincronização e Conexão (Sempre visível e descoberto para informar o usuário) */}
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
                        ? 'Relógio Pareado • App Wear Não Detectado'
                        : 'Conectado • Galaxy Watch7 (Wear v1.2.1)'}
                    </span>
                  </div>
                  <span className="text-[10px] font-mono text-emerald-300 shrink-0">
                    {isCustomizationLocked ? 'Bloqueado' : `${syncedEventsCount} eventos`}
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
                    <span>App Wear:</span>
                    <span className="font-bold">
                      {!isBluetoothConnected
                        ? 'Inacessível'
                        : isWearAppInstalledOnWatch
                        ? 'Instalado'
                        : 'Não detectado'}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* 2. QUADRO DE PERMISSÕES PENDENTES + MINI TUTORIAL OBRIGATÓRIO + AVISO E LINK WWW.LASKOSKY.COM.BR */}
            <div
              data-testid="phone-permissions-status-card"
              className={`rounded-[20px] border p-3.5 transition-colors space-y-2.5 ${
                isCustomizationLocked
                  ? 'bg-amber-500/10 border-amber-500/45'
                  : 'bg-emerald-500/10 border-emerald-500/30'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-1.5">
                  {isCustomizationLocked ? (
                    <ShieldAlert className="w-4 h-4 text-amber-400 shrink-0" />
                  ) : (
                    <ShieldCheck className="w-4 h-4 text-emerald-400 shrink-0" />
                  )}
                  <span className="text-xs font-bold text-white">
                    {missingPermissions.length > 0
                      ? `PASSO 1: Permissões Pendentes (${missingPermissions.length})`
                      : !isWearAppInstalledOnWatch || !isBluetoothConnected
                      ? 'PASSO 2: Instalar / Conectar App no Relógio'
                      : 'Permissões e App do Relógio Verificados'}
                  </span>
                </div>

                <button
                  type="button"
                  data-testid="phone-simulate-permissions-toggle"
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
                    : 'Liberar Todas'}
                </button>
              </div>

              {/* MINI TUTORIAL PASSO A PASSO FORÇANDO A LIBERAÇÃO DAS PERMISSÕES E DETECÇÃO DO APP NO RELÓGIO */}
              {isCustomizationLocked && (
                <div
                  data-testid="phone-onboarding-mini-tutorial"
                  className="rounded-xl bg-[#0D1310] border border-amber-400/35 px-3 py-2 text-[11px] text-amber-100 leading-snug space-y-1"
                >
                  <div className="font-bold text-[#F5C438]">
                    {missingPermissions.length > 0
                      ? '📌 Mini Tutorial (Passo 1 de 2): Libere as permissões abaixo primeiro'
                      : '📌 Mini Tutorial (Passo 2 de 2): Instale o app ChronoWear no relógio'}
                  </div>
                  <p className="text-[10.5px] text-slate-300">
                    {missingPermissions.length > 0
                      ? 'Por segurança e precisão, as opções de personalização e os blocos permanecem protegidos por uma camada escura até você tocar em "Conceder" nas permissões abaixo.'
                      : 'Permissões concluídas! Porém o módulo no relógio não foi detectado. As opções de customização só são desbloqueadas após instalar o app no relógio.'}
                  </p>
                </div>
              )}

              {/* Lista de Permissões Pendentes para Clicar */}
              {missingPermissions.length > 0 && (
                <div className="space-y-1.5">
                  {missingPermissions.map((perm) => (
                    <div
                      key={perm.id}
                      className="flex items-center justify-between gap-2 rounded-xl bg-black/45 border border-amber-500/30 px-2.5 py-2"
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
                        className="px-3 py-1 rounded-full bg-[#F5C438] hover:bg-[#FDE047] text-slate-950 font-bold text-[10.5px] shrink-0 cursor-pointer"
                      >
                        Conceder
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {/* Bloco Informativo de Instalação do App no Relógio + Botão para www.laskosky.com.br */}
              <div
                data-testid="phone-watch-install-info-box"
                className={`rounded-xl border px-3 py-2 flex flex-col sm:flex-row sm:items-center justify-between gap-2 ${
                  !isWearAppInstalledOnWatch
                    ? 'bg-amber-500/15 border-amber-400/50'
                    : 'bg-black/35 border-white/10'
                }`}
              >
                <div className="min-w-0">
                  <div className="text-[11px] font-bold text-white">
                    {!isWearAppInstalledOnWatch
                      ? '⚠ App ChronoWear não detectado no relógio'
                      : '✓ App ChronoWear instalado no relógio'}
                  </div>
                  <div className="text-[10px] text-slate-300 leading-tight">
                    É necessário instalar o app no relógio Wear OS para sincronizar e liberar a customização.
                  </div>
                </div>
                <a
                  href="https://www.laskosky.com.br"
                  target="_blank"
                  rel="noopener noreferrer"
                  data-testid="phone-install-watch-app-link"
                  className="px-3 py-1.5 rounded-full bg-[#F5C438] hover:bg-[#FDE047] text-[#050507] font-bold text-[10.5px] flex items-center justify-center gap-1 shrink-0 cursor-pointer"
                >
                  <span>www.laskosky.com.br</span>
                  <ExternalLink className="w-3 h-3" />
                </a>
              </div>
            </div>

            {/* 3. QUADROS DOS STATUS EM BLOCOS 2X2 (COM BOTÕES ERGONÔMICOS MAIORES E PROTEÇÃO POR CAMADA ESCURA QUANDO BLOQUEADO) */}
            <div className="relative space-y-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-[11px] font-bold uppercase tracking-wider text-slate-200">
                  Quadros de Status (Widgets 2×2)
                </span>
                <span className="text-[10px] text-slate-400">
                  ◀ ▶ reordena • ⤢ / ⤡ expande e reduz
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
                      data-expanded={isExpanded ? 'true' : 'false'}
                      draggable={!isCustomizationLocked}
                      onDragStart={() => {
                        if (!isCustomizationLocked) setDraggedWidgetId(widgetId);
                      }}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={() => {
                        if (draggedWidgetId && !isCustomizationLocked) {
                          handleSwapWidgets(draggedWidgetId, widgetId);
                          setDraggedWidgetId(null);
                        }
                      }}
                      onClick={() => {
                        if (!isCustomizationLocked) {
                          handleSelectAndPushFocusToWatch(widgetId);
                        }
                      }}
                      className={`${
                        isExpanded ? 'col-span-2' : 'col-span-1'
                      } h-[156px] rounded-[20px] bg-[#0E1411] border p-2.5 flex flex-col justify-between transition-all cursor-pointer select-none ${
                        isSelectedOnWatch
                          ? 'border-[#F5C438] shadow-[0_0_0_1px_rgba(245,196,56,0.35)]'
                          : 'border-[#233129] hover:border-slate-600'
                      }`}
                    >
                      {/* Barra Superior do Bloco Widget com Botões Ergonômicos Maiores (w-8 h-7 e ícones w-4 h-4 bem visíveis) */}
                      <div
                        draggable={false}
                        onClick={(e) => e.stopPropagation()}
                        className="flex items-center justify-between gap-1 pb-1.5 border-b border-white/10"
                      >
                        <div className="flex items-center gap-1 min-w-0">
                          <GripVertical className="w-3.5 h-3.5 text-slate-400 shrink-0 cursor-grab" />
                          <span className="text-[11.5px] font-bold text-white truncate">
                            {widgetTitleMap[widgetId]}
                          </span>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          {widgetId === 'TASKS' && (
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                if (!isCustomizationLocked) {
                                  setIsManageTasksModalOpen(true);
                                }
                              }}
                              title="Adicionar ou gerenciar tarefas"
                              className="w-8 h-7 rounded-lg bg-emerald-500/20 border border-emerald-500/40 text-emerald-300 flex items-center justify-center hover:bg-emerald-500/30 active:scale-95 cursor-pointer"
                            >
                              <Plus className="w-4 h-4 stroke-[2.5]" />
                            </button>
                          )}
                          {widgetId === 'SLEEP' && (
                            <button
                              type="button"
                              data-testid="widget-toggle-sleep-today-data"
                              onClick={(e) => {
                                e.stopPropagation();
                                if (!isCustomizationLocked) {
                                  onUpdateConfig({
                                    sleepHasDataToday: !(
                                      config.sleepHasDataToday ?? true
                                    ),
                                  });
                                }
                              }}
                              title="Alternar entre Com Dados de Sono Hoje vs Sem Dados de Sono para Hoje"
                              className="h-7 px-2 rounded-lg bg-purple-500/20 border border-purple-500/40 text-purple-200 text-[10px] font-bold hover:bg-purple-500/30 active:scale-95 cursor-pointer"
                            >
                              {(config.sleepHasDataToday ?? true) ? 'Hoje' : 'Vazio'}
                            </button>
                          )}
                          <button
                            type="button"
                            data-testid={`widget-move-prev-${widgetId}`}
                            onClick={(e) =>
                              handleMoveWidgetOrder(widgetId, -1, e)
                            }
                            title="Mover bloco para trás"
                            className="w-8 h-7 rounded-lg bg-[#1A2420] border border-[#33463E] text-slate-100 flex items-center justify-center hover:bg-[#24322C] active:scale-95 cursor-pointer"
                          >
                            <ChevronLeft className="w-4 h-4 stroke-[2.5]" />
                          </button>
                          <button
                            type="button"
                            data-testid={`widget-move-next-${widgetId}`}
                            onClick={(e) =>
                              handleMoveWidgetOrder(widgetId, +1, e)
                            }
                            title="Mover bloco para frente"
                            className="w-8 h-7 rounded-lg bg-[#1A2420] border border-[#33463E] text-slate-100 flex items-center justify-center hover:bg-[#24322C] active:scale-95 cursor-pointer"
                          >
                            <ChevronRight className="w-4 h-4 stroke-[2.5]" />
                          </button>
                          <button
                            type="button"
                            data-testid={`widget-expand-${widgetId}`}
                            onClick={(e) =>
                              handleToggleExpandWidget(widgetId, e)
                            }
                            title={
                              isExpanded
                                ? 'Diminuir para meio bloco (1 coluna)'
                                : 'Expandir para linha inteira (2 colunas)'
                            }
                            className={`w-8 h-7 rounded-lg border flex items-center justify-center active:scale-95 cursor-pointer ${
                              isExpanded
                                ? 'bg-[#F5C438]/25 border-[#F5C438] text-[#F5C438]'
                                : 'bg-[#1A2420] border-[#33463E] text-[#F5C438] hover:bg-[#24322C]'
                            }`}
                          >
                            {isExpanded ? (
                              <Minimize2 className="w-4 h-4 stroke-[2.5]" />
                            ) : (
                              <Maximize2 className="w-4 h-4 stroke-[2.5]" />
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

              {/* CAMADA FINA ESCURA DE PROTEÇÃO SOBRE OS TILES QUANDO FALTA PERMISSÃO OU APP NO RELÓGIO */}
              {isCustomizationLocked && (
                <div
                  data-testid="widgets-lock-overlay"
                  onClick={(e) => e.stopPropagation()}
                  className="absolute inset-0 rounded-[20px] bg-black/65 backdrop-blur-[1px] z-20 flex flex-col items-center justify-center p-5 text-center select-none"
                >
                  <div className="max-w-xs rounded-2xl bg-[#101613]/95 border border-amber-400/50 px-4 py-3 space-y-1.5 shadow-2xl">
                    <div className="flex items-center justify-center gap-1.5 text-xs font-bold text-[#F5C438]">
                      <Lock className="w-4 h-4" />
                      <span>Customização de Blocos Bloqueada</span>
                    </div>
                    <p className="text-[11px] text-slate-200 leading-snug">
                      {missingPermissions.length > 0
                        ? 'Libere todas as permissões pendentes no quadro acima para interagir com os blocos.'
                        : 'O app ChronoWear não foi detectado no relógio. Instale-o pelo link www.laskosky.com.br no quadro acima para desbloquear.'}
                    </p>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 2: CONFIGURAÇÕES (PROTEGIDA PELA CAMADA FINA ESCURA SE      */}
        {/* FALTAR PERMISSÃO OU APP NO RELÓGIO)                             */}
        {/* =============================================================== */}
        {activeTab === 'settings' && (
          <div
            key="tab-settings"
            className="relative space-y-3 flex-1 max-h-[490px] overflow-y-auto pr-1 transition-all duration-200"
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
                    disabled={isCustomizationLocked}
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
                      disabled={isCustomizationLocked}
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

              <div
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      autoFallbackAgendaToTasks: !(
                        config.autoFallbackAgendaToTasks ?? true
                      ),
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
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
                      disabled={isCustomizationLocked}
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
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      notifyWatchOnCanvasChange: !(
                        config.notifyWatchOnCanvasChange ?? true
                      ),
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
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
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      notifyAgenda15MinEnabled: !(
                        config.notifyAgenda15MinEnabled ?? true
                      ),
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
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
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      highFreq15MinRule: !config.highFreq15MinRule,
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
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
                onClick={() => {
                  if (!isCustomizationLocked) setCalendarSyncEnabled((v) => !v);
                }}
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
                  disabled={isCustomizationLocked}
                  onChange={() => setCalendarSyncEnabled((v) => !v)}
                />
              </div>

              <button
                type="button"
                disabled={isCustomizationLocked}
                onClick={() => setIsSyncedCalendarsDialogOpen(true)}
                className="w-full py-2 px-3 rounded-xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-white flex items-center justify-center gap-2 cursor-pointer"
              >
                <Calendar className="w-3.5 h-3.5 text-emerald-400" />
                <span>Calendários Sincronizados ({selectedCalendarsCount})</span>
              </button>

              <div
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      tasksSyncEnabled: !(config.tasksSyncEnabled ?? true),
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
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
                disabled={isCustomizationLocked}
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
                  if (!isCustomizationLocked) {
                    const next = !(config.phoneWeatherAutoCheckEnabled ?? true);
                    onUpdateConfig({
                      phoneWeatherAutoCheckEnabled: next,
                      phoneSolarAutoSyncEnabled: next,
                    });
                  }
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
                  disabled={isCustomizationLocked}
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
                disabled={isCustomizationLocked}
                onClick={() => setIsWeatherAppsDialogOpen(true)}
                className="w-full py-2 px-3 rounded-xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-sky-300 flex items-center justify-center gap-2 cursor-pointer"
              >
                <CloudRain className="w-3.5 h-3.5 text-sky-400" />
                <span>
                  Apps Gatilho de Clima ({selectedWeatherApps.length} selecionados)
                </span>
              </button>

              <div
                onClick={() => {
                  if (!isCustomizationLocked) {
                    onUpdateConfig({
                      sleepSyncEnabled: !(config.sleepSyncEnabled ?? true),
                    });
                  }
                }}
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
                  disabled={isCustomizationLocked}
                  onChange={() =>
                    onUpdateConfig({
                      sleepSyncEnabled: !(config.sleepSyncEnabled ?? true),
                    })
                  }
                />
              </div>

              <div
                onClick={() => {
                  if (!isCustomizationLocked) setBgServiceEnabled((v) => !v);
                }}
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
                  disabled={isCustomizationLocked}
                  onChange={() => setBgServiceEnabled((v) => !v)}
                />
              </div>
            </div>

            {/* CAMADA FINA ESCURA SOBRE AS CONFIGURAÇÕES QUANDO FALTA PERMISSÃO OU APP NO RELÓGIO */}
            {isCustomizationLocked && (
              <div
                data-testid="settings-lock-overlay"
                className="absolute inset-0 rounded-[22px] bg-black/65 backdrop-blur-[1px] z-20 flex flex-col items-center justify-center p-5 text-center select-none"
              >
                <div className="max-w-xs rounded-2xl bg-[#101613]/95 border border-amber-400/50 p-4 space-y-2 shadow-2xl">
                  <div className="flex items-center justify-center gap-1.5 text-xs font-bold text-[#F5C438]">
                    <Lock className="w-4 h-4" />
                    <span>Opções de Personalização Bloqueadas</span>
                  </div>
                  <p className="text-[11px] text-slate-200 leading-snug">
                    {missingPermissions.length > 0
                      ? 'Volte à aba Início e libere todas as permissões pendentes primeiro.'
                      : 'O app ChronoWear não foi detectado no relógio. Instale-o em www.laskosky.com.br para liberar as configurações.'}
                  </p>
                  <button
                    type="button"
                    onClick={() => setActiveTab('home')}
                    className="px-3.5 py-1.5 rounded-full bg-[#F5C438] text-slate-950 font-bold text-[11px] cursor-pointer"
                  >
                    Ir para Permissões e Status
                  </button>
                </div>
              </div>
            )}
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
                • <strong>Watchface Idêntica 1:1 ao Relógio:</strong> O topo do app smartphone renderiza a cópia fiel da Watchface 450×450px (arcos laterais bicolores, dock duplo de cápsulas Passos + Sol, barra inferior de bateria em tubo de ensaio e janela central de contexto 302×136px).
              </p>
              <p>
                • <strong>Curva Solar Completa (Dia + Noite Abaixo do Horizonte):</strong> O quadro de Pôr do Sol exibe o gradiente crepuscular, a linha azul do horizonte e a curva cosseno completa de 24h que desce abaixo da linha do horizonte durante o período sem sol.
              </p>
              <p>
                • <strong>Download do Módulo do Relógio:</strong> Disponível em{' '}
                <a
                  href="https://www.laskosky.com.br"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-amber-300 underline font-mono"
                >
                  www.laskosky.com.br
                </a>
                .
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

      {/* BARRA DE NAVEGAÇÃO INFERIOR ESTILO SAMSUNG HEALTH (MAIS COMPACTA COM O CÍRCULO DENTRO) */}
      <div className="absolute bottom-3.5 left-0 right-0 px-4 pointer-events-none z-30 flex justify-center">
        <nav
          data-testid="samsung-health-bottom-nav"
          aria-label="Navegação principal do app"
          className="relative pointer-events-auto w-[288px] h-[54px] rounded-full bg-[#181F1B]/95 backdrop-blur-md border border-[#2F3D35] p-1 shadow-2xl grid grid-cols-3 items-center"
        >
          {/* Cápsula deslizante compacta com bordas circulares */}
          <div
            className="absolute top-1 bottom-1 left-1 w-[calc((100%-8px)/3)] rounded-full bg-[#2A362F] border border-[#3E5045] transition-transform duration-200"
            style={{
              transform: `translateX(${activeTabIndex * 100}%)`,
            }}
          />

          {(
            [
              { id: 'home', label: 'Início', Icon: Home },
              { id: 'settings', label: 'Config.', Icon: Settings },
              { id: 'about', label: 'Sobre', Icon: Info },
            ] as const
          ).map((item) => {
            const isActive = activeTab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className="relative z-10 h-full flex items-center justify-center gap-1.5 px-2 rounded-full cursor-pointer select-none"
              >
                {/* Círculo interno estilo Samsung Health */}
                <span
                  className={`w-6 h-6 rounded-full flex items-center justify-center transition-colors shrink-0 ${
                    isActive
                      ? 'bg-[#F5C438] text-[#050507] shadow-xs'
                      : 'bg-[#222B25] text-[#9AA89F]'
                  }`}
                >
                  <item.Icon className="w-3.5 h-3.5 stroke-[2.4]" />
                </span>
                <span
                  className={`text-[10.5px] leading-none truncate ${
                    isActive
                      ? 'font-bold text-white'
                      : 'font-medium text-[#A0ABA3]'
                  }`}
                >
                  {item.label}
                </span>
              </button>
            );
          })}
        </nav>
      </div>

      {/* Modal Gerenciar Tarefas (Hoje c/ hora -> Amanhã c/ hora -> Sem data e Atrasadas em vermelho sem data/hora) */}
      {isManageTasksModalOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-40">
          <div className="w-full max-w-sm rounded-[26px] bg-[#161C18] border border-[#344038] p-5 space-y-3 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-bold text-white">
                Hierarquia do Canvas de Tarefas
              </h3>
              <button
                type="button"
                data-testid="close-manage-tasks-modal"
                title="Fechar"
                onClick={() => setIsManageTasksModalOpen(false)}
                className="text-slate-400 hover:text-white p-1 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Botões rápidos para testar os 3 níveis da regra estrita */}
            <div className="space-y-1">
              <span className="text-[10px] font-mono text-slate-300 block">
                Simular Cenário no Relógio (Reflete Imediatamente):
              </span>
              <div className="grid grid-cols-3 gap-1">
                <button
                  type="button"
                  data-testid="tasks-tier-btn-today"
                  onClick={() => handleApplyTasksTierScenario('TODAY')}
                  className={`py-1.5 px-1.5 rounded-xl text-[10px] font-bold border cursor-pointer ${
                    resolvedTasksState.bucket === 'TODAY'
                      ? 'bg-[#F5C438] text-slate-950 border-[#FDE047]'
                      : 'bg-black/40 text-slate-200 border-slate-700'
                  }`}
                >
                  1. Hoje (c/ hora)
                </button>
                <button
                  type="button"
                  data-testid="tasks-tier-btn-tomorrow"
                  onClick={() => handleApplyTasksTierScenario('TOMORROW')}
                  className={`py-1.5 px-1.5 rounded-xl text-[10px] font-bold border cursor-pointer ${
                    resolvedTasksState.bucket === 'TOMORROW'
                      ? 'bg-[#F5C438] text-slate-950 border-[#FDE047]'
                      : 'bg-black/40 text-slate-200 border-slate-700'
                  }`}
                >
                  2. Amanhã (c/ hora)
                </button>
                <button
                  type="button"
                  data-testid="tasks-tier-btn-undated-overdue"
                  onClick={() =>
                    handleApplyTasksTierScenario('UNDATED_OVERDUE')
                  }
                  className={`py-1.5 px-1.5 rounded-xl text-[10px] font-bold border cursor-pointer ${
                    resolvedTasksState.bucket === 'UNDATED_OVERDUE'
                      ? 'bg-[#EF4444] text-white border-red-400'
                      : 'bg-black/40 text-red-300 border-red-500/40'
                  }`}
                >
                  3. Sem Data / Atraso
                </button>
              </div>
            </div>

            <form onSubmit={handleAddNewTask} className="space-y-1.5">
              <div className="flex gap-1.5">
                <input
                  type="text"
                  value={newTaskTitle}
                  onChange={(e) => setNewTaskTitle(e.target.value)}
                  placeholder="Nova tarefa..."
                  className="flex-1 bg-black/50 border border-slate-700 rounded-xl px-2.5 py-1.5 text-xs text-white"
                />
                {(newTaskCategory === 'TODAY' ||
                  newTaskCategory === 'TOMORROW') && (
                  <input
                    type="text"
                    value={newTaskDueHHMM}
                    onChange={(e) => setNewTaskDueHHMM(e.target.value)}
                    placeholder="14:00"
                    className="w-16 bg-black/50 border border-slate-700 rounded-xl px-2 py-1.5 text-xs text-sky-300 font-mono"
                  />
                )}
                <button
                  type="submit"
                  className="px-3 py-1.5 rounded-xl bg-[#F5C438] text-slate-950 font-bold text-xs cursor-pointer"
                >
                  +
                </button>
              </div>
              <div className="grid grid-cols-4 gap-1">
                {(
                  [
                    { id: 'TODAY', label: 'Hoje' },
                    { id: 'TOMORROW', label: 'Amanhã' },
                    { id: 'UNDATED', label: 'Sem data' },
                    { id: 'OVERDUE', label: 'Atrasada' },
                  ] as const
                ).map((cat) => (
                  <button
                    key={cat.id}
                    type="button"
                    onClick={() => setNewTaskCategory(cat.id)}
                    className={`py-1 rounded-lg text-[10px] font-semibold border cursor-pointer ${
                      newTaskCategory === cat.id
                        ? cat.id === 'OVERDUE'
                          ? 'bg-red-500/30 border-red-400 text-red-200'
                          : 'bg-emerald-500/25 border-emerald-400 text-emerald-200'
                        : 'bg-black/35 border-slate-800 text-slate-400'
                    }`}
                  >
                    {cat.label}
                  </button>
                ))}
              </div>
            </form>

            <div className="space-y-1.5 max-h-48 overflow-y-auto">
              {allTasks.map((t) => {
                const isOverdueTask =
                  t.scheduleCategory === 'OVERDUE' || Boolean(t.isOverdue);
                const catBadge =
                  t.scheduleCategory === 'TODAY'
                    ? `Hoje • ${t.dueHHMM || '09:00'}`
                    : t.scheduleCategory === 'TOMORROW'
                    ? `Amanhã • ${t.dueHHMM || '09:00'}`
                    : isOverdueTask
                    ? 'Atrasada (Sem data/hora)'
                    : 'Sem data';
                return (
                  <div
                    key={t.id}
                    onClick={(e) => handleCompleteTaskInWidget(t.id, e)}
                    className={`flex items-center justify-between gap-2 p-2 rounded-xl border cursor-pointer ${
                      t.completed
                        ? 'bg-black/20 border-slate-800/60 opacity-50'
                        : isOverdueTask
                        ? 'bg-red-950/25 border-red-500/40'
                        : 'bg-black/40 border-slate-800 hover:border-emerald-500/40'
                    }`}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span
                        className={`w-4 h-4 rounded border flex items-center justify-center text-[10px] shrink-0 ${
                          t.completed
                            ? 'bg-emerald-500 border-emerald-400 text-slate-950'
                            : isOverdueTask
                            ? 'border-red-400 text-transparent'
                            : 'border-sky-400 text-transparent'
                        }`}
                      >
                        ✓
                      </span>
                      <span
                        className={`text-xs font-semibold truncate ${
                          t.completed
                            ? 'line-through text-slate-400'
                            : isOverdueTask
                            ? 'text-[#F87171]'
                            : 'text-white'
                        }`}
                      >
                        {t.title}
                      </span>
                    </div>
                    <span
                      className={`text-[9.5px] font-mono shrink-0 ${
                        isOverdueTask ? 'text-red-300 font-bold' : 'text-sky-300'
                      }`}
                    >
                      {catBadge}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* Modal Calendários Sincronizados */}
      {isSyncedCalendarsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-40">
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
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-40">
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
