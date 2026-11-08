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
export type AutoExposureMinutes = 5 | 15 | 30 | 45 | 60;
export type PrimaryContextMode =
  | 'EVENTS'
  | 'TASKS'
  | 'WEATHER'
  | 'STEPS'
  | 'SUN'
  | 'SLEEP';
export type ContextWindowMode = 'DYNAMIC' | PrimaryContextMode;

export type TaskScheduleCategory = 'TODAY' | 'TOMORROW' | 'UNDATED' | 'OVERDUE';

export interface TaskItem {
  id: string;
  title: string;
  dueHHMM: string;
  sourceLabel: 'Reminder' | 'Tasks';
  completed: boolean;
  scheduleCategory?: TaskScheduleCategory;
  isOverdue?: boolean;
  isTodayOrTimeless?: boolean;
}

export interface ResolvedTasksCanvasState {
  headerTitle: string;
  rightBadgeText: string;
  bucket: 'TODAY' | 'TOMORROW' | 'UNDATED_OVERDUE' | 'EMPTY';
  showTime: boolean;
  displayTasks: Array<
    TaskItem & {
      effectiveCategory: TaskScheduleCategory;
      displayTimeHHMM: string;
      isRedOverdue: boolean;
    }
  >;
  totalActiveCount: number;
}

export const DEFAULT_TASKS_LIST: TaskItem[] = [
  {
    id: 't1',
    title: 'Enviar relatório trimestral',
    dueHHMM: '14:00',
    sourceLabel: 'Reminder',
    completed: false,
    scheduleCategory: 'TODAY',
    isTodayOrTimeless: true,
  },
  {
    id: 't2',
    title: 'Reunião de revisão da sprint',
    dueHHMM: '16:30',
    sourceLabel: 'Tasks',
    completed: false,
    scheduleCategory: 'TODAY',
    isTodayOrTimeless: true,
  },
  {
    id: 't3',
    title: 'Apresentar protótipo Wear OS',
    dueHHMM: '10:00',
    sourceLabel: 'Reminder',
    completed: false,
    scheduleCategory: 'TOMORROW',
    isTodayOrTimeless: true,
  },
  {
    id: 't4',
    title: 'Alinhar entrega com cliente',
    dueHHMM: '15:30',
    sourceLabel: 'Tasks',
    completed: false,
    scheduleCategory: 'TOMORROW',
    isTodayOrTimeless: true,
  },
  {
    id: 't5',
    title: 'Pagar fatura do cartão corporativo',
    dueHHMM: '',
    sourceLabel: 'Reminder',
    completed: false,
    scheduleCategory: 'OVERDUE',
    isOverdue: true,
    isTodayOrTimeless: true,
  },
  {
    id: 't6',
    title: 'Organizar documentos da contabilidade',
    dueHHMM: '',
    sourceLabel: 'Tasks',
    completed: false,
    scheduleCategory: 'UNDATED',
    isTodayOrTimeless: true,
  },
  {
    id: 't7',
    title: 'Comprar café em grãos',
    dueHHMM: '18:00',
    sourceLabel: 'Reminder',
    completed: true,
    scheduleCategory: 'TODAY',
    isTodayOrTimeless: true,
  },
];

export function inferTaskScheduleCategory(task: TaskItem): TaskScheduleCategory {
  if (task.scheduleCategory) {
    return task.scheduleCategory;
  }
  if (task.isOverdue) {
    return 'OVERDUE';
  }
  const raw = (task.dueHHMM ?? '').trim();
  const lower = raw.toLowerCase();
  if (
    lower.includes('atras') ||
    lower.includes('ontem') ||
    lower.includes('vencid')
  ) {
    return 'OVERDUE';
  }
  if (lower.includes('amanh')) {
    return 'TOMORROW';
  }
  if (
    !raw ||
    lower === 'sem data' ||
    lower === 'sem horário' ||
    lower === 'sem horario' ||
    lower === 'hoje'
  ) {
    return 'UNDATED';
  }
  return 'TODAY';
}

/**
 * Resolve estritamente qual grupo de tarefas NÃO concluídas deve aparecer no Canvas do relógio e do smartphone:
 * 1. Somente atividades agendadas para HOJE (com hora HH:mm e check de não feito).
 * 2. Se NÃO houver atividades agendadas para hoje, apresenta as atividades agendadas para AMANHÃ (com hora HH:mm e check de não feito).
 * 3. Se NÃO houver atividades para hoje nem para amanhã, apresenta as atividades SEM DATA DEFINIDA e as ATRASADAS:
 *    - Sem data definida: NÃO apresenta data nem hora, apenas o check de não feito + título.
 *    - Em atraso: NÃO apresenta data nem hora, apenas o check de não feito + título em VERMELHO.
 */
export function resolveTasksCanvasState(
  tasks: TaskItem[]
): ResolvedTasksCanvasState {
  const uncompleted = tasks.filter(
    (t) => !t.completed && (t.isTodayOrTimeless ?? true)
  );

  const normalized = uncompleted.map((t) => {
    const effectiveCategory = inferTaskScheduleCategory(t);
    const rawTime = (t.dueHHMM ?? '').trim();
    const matchHHMM = rawTime.match(/(\d{1,2}:\d{2})/);
    const cleanHHMM = matchHHMM ? matchHHMM[1].padStart(5, '0') : rawTime;
    return {
      ...t,
      effectiveCategory,
      displayTimeHHMM:
        effectiveCategory === 'TODAY' || effectiveCategory === 'TOMORROW'
          ? cleanHHMM || '09:00'
          : '',
      isRedOverdue: effectiveCategory === 'OVERDUE' || Boolean(t.isOverdue),
    };
  });

  const todayTasks = normalized.filter((t) => t.effectiveCategory === 'TODAY');
  if (todayTasks.length > 0) {
    return {
      headerTitle: 'Tarefas • Hoje',
      rightBadgeText: `${todayTasks.length} hoje`,
      bucket: 'TODAY',
      showTime: true,
      displayTasks: todayTasks,
      totalActiveCount: todayTasks.length,
    };
  }

  const tomorrowTasks = normalized.filter(
    (t) => t.effectiveCategory === 'TOMORROW'
  );
  if (tomorrowTasks.length > 0) {
    return {
      headerTitle: 'Tarefas • Amanhã',
      rightBadgeText: `${tomorrowTasks.length} amanhã`,
      bucket: 'TOMORROW',
      showTime: true,
      displayTasks: tomorrowTasks,
      totalActiveCount: tomorrowTasks.length,
    };
  }

  const undatedOrOverdue = normalized.filter(
    (t) =>
      t.effectiveCategory === 'OVERDUE' || t.effectiveCategory === 'UNDATED'
  );
  // Ordena para destacar atrasadas em vermelho e sem data
  undatedOrOverdue.sort((a, b) => {
    if (a.isRedOverdue !== b.isRedOverdue) {
      return a.isRedOverdue ? -1 : 1;
    }
    return 0;
  });

  if (undatedOrOverdue.length > 0) {
    return {
      headerTitle: 'Tarefas • Sem data',
      rightBadgeText: `${undatedOrOverdue.length} pendentes`,
      bucket: 'UNDATED_OVERDUE',
      showTime: false,
      displayTasks: undatedOrOverdue,
      totalActiveCount: undatedOrOverdue.length,
    };
  }

  return {
    headerTitle: 'Tarefas • Hoje',
    rightBadgeText: '0 ativas',
    bucket: 'EMPTY',
    showTime: false,
    displayTasks: [],
    totalActiveCount: 0,
  };
}

/**
 * Retorna a lista ativa atual respeitando a hierarquia estrita:
 * Hoje (com hora) -> senão Amanhã (com hora) -> senão Sem data e Atrasadas (sem data/hora, atrasadas em vermelho).
 */
export function filterActiveTasksForWatch(tasks: TaskItem[]): TaskItem[] {
  return resolveTasksCanvasState(tasks).displayTasks;
}

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
  autoPrimaryContext?: PrimaryContextMode;
  unifiedCanvasHoldMinutes?: AutoExposureMinutes;
  notifyWatchOnCanvasChange?: boolean;
  notifyAgenda15MinEnabled?: boolean;
  weatherLocationName?: string;
  weatherUpdatedHHMM?: string;
  autoAgendaExposureMinutes?: AutoExposureMinutes;
  autoWeatherExposureMinutes?: AutoExposureMinutes;
  autoSunExposureMinutes?: AutoExposureMinutes;
  autoSleepExposureMinutes?: AutoExposureMinutes;
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
  sleepSyncEnabled?: boolean;
  sleepHasDataToday?: boolean;
  sleepDurationMinutes?: number;
  sleepScore?: number;
  sleepBedtimeHHMM?: string;
  sleepWakeHHMM?: string;
  tasksSyncEnabled?: boolean;
  tasks?: TaskItem[];
  hasActiveWatchTaskAlert?: boolean;
  autoFallbackAgendaToTasks?: boolean;
  homeWidgetsOrder?: PrimaryContextMode[];
  expandedWidgets?: PrimaryContextMode[];
}

export interface WatchCacheState {
  cachedEvents: CalendarEventItem[];
  lastSyncEpochMs: number;
  syncedHorizonDays: SyncHorizonDays;
  progressWindowMinutes: ProgressWindowMinutes;
  isBluetoothConnected: boolean;
  contextWindowMode?: ContextWindowMode;
  autoPrimaryContext?: PrimaryContextMode;
  unifiedCanvasHoldMinutes?: AutoExposureMinutes;
  notifyWatchOnCanvasChange?: boolean;
  notifyAgenda15MinEnabled?: boolean;
  weatherLocationName?: string;
  weatherUpdatedHHMM?: string;
  autoAgendaExposureMinutes?: AutoExposureMinutes;
  autoWeatherExposureMinutes?: AutoExposureMinutes;
  autoSunExposureMinutes?: AutoExposureMinutes;
  autoSleepExposureMinutes?: AutoExposureMinutes;
  highFreq15MinRule?: boolean;
  updateOnScreenWake?: boolean;
  hasActiveWatchWeatherAlert?: boolean;
  hasActiveWatchTaskAlert?: boolean;
  tasksSyncEnabled?: boolean;
  tasks?: TaskItem[];
  autoFallbackAgendaToTasks?: boolean;
  watchStandaloneWeatherEnabled?: boolean;
  watchStandaloneSolarEnabled?: boolean;
  solarSunriseHHMM?: string;
  solarSunsetHHMM?: string;
  sleepSyncEnabled?: boolean;
  sleepHasDataToday?: boolean;
  sleepBedtimeHHMM?: string;
  sleepWakeHHMM?: string;
}

export interface ComplicationProgressState {
  hasUpcomingEvent: boolean;
  hasEventsToday: boolean;
  agendaDayHeader: string;
  dayEvents: CalendarEventItem[];
  nextEvent: CalendarEventItem | null;
  upcomingThree: CalendarEventItem[];
  remainingMs: number;
  formattedCountdown: string;
  formattedStartTime: string;
  progressRatio: number; // 0.0 to 1.0
  isWithinProgressWindow: boolean;
  isWithinTurbo15MinWindow: boolean;
  effectiveContextMode: PrimaryContextMode;
  isOngoing: boolean;
  autonomousCacheValidUntilMs: number;
  isOperatingAutonomously: boolean;
}

const OFFICIAL_IBGE_UFS = new Set([
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO',
  'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI',
  'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
]);

const BRAZIL_STATE_UF_ORDERED: Array<[string, string]> = [
  ['mato grosso do sul', 'MS'],
  ['rio grande do norte', 'RN'],
  ['rio grande do sul', 'RS'],
  ['distrito federal', 'DF'],
  ['espirito santo', 'ES'],
  ['santa catarina', 'SC'],
  ['rio de janeiro', 'RJ'],
  ['minas gerais', 'MG'],
  ['mato grosso', 'MT'],
  ['sao paulo', 'SP'],
  ['maranhao', 'MA'],
  ['pernambuco', 'PE'],
  ['tocantins', 'TO'],
  ['amazonas', 'AM'],
  ['rondonia', 'RO'],
  ['roraima', 'RR'],
  ['alagoas', 'AL'],
  ['paraiba', 'PB'],
  ['sergipe', 'SE'],
  ['parana', 'PR'],
  ['amapa', 'AP'],
  ['bahia', 'BA'],
  ['ceara', 'CE'],
  ['goias', 'GO'],
  ['piaui', 'PI'],
  ['acre', 'AC'],
  ['para', 'PA'],
];

function stripAccents(input: string): string {
  return input.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

/**
 * Resolve exclusivamente a sigla oficial IBGE de 2 letras de cada estado brasileiro
 * (ex: São Paulo -> SP, Paraná -> PR, Maranhão -> MA), sem jamais usar abreviação lógica
 * das 2 primeiras letras ("SA" ou "PA" para Paraná).
 */
export function resolveOfficialBrazilStateUf(
  rawState: string,
  fallbackCity = ''
): string {
  const trimmed = rawState.trim();
  const upper = trimmed.toUpperCase();
  if (OFFICIAL_IBGE_UFS.has(upper)) return upper;

  const cleaned = stripAccents(trimmed)
    .toLowerCase()
    .replace(/^(state of|estado d[eao]|uf|unidade federativa|provincia d[eao])\s+/, '')
    .replace(/\s+(state|uf|brasil|brazil)$/, '')
    .trim();

  for (const [stateName, uf] of BRAZIL_STATE_UF_ORDERED) {
    if (cleaned === stateName || cleaned.includes(stateName)) {
      return uf;
    }
  }

  if (fallbackCity.trim()) {
    const cleanCity = stripAccents(fallbackCity).toLowerCase().trim();
    for (const [stateName, uf] of BRAZIL_STATE_UF_ORDERED) {
      if (cleanCity === stateName) return uf;
    }
  }

  return upper.length === 2 && /^[A-Z]{2}$/.test(upper) ? upper : '';
}

/**
 * Formata o nome do local para o subtítulo do Canvas de Clima:
 * 1. Converte o nome do estado para a sigla oficial IBGE de 2 letras (ex: "São Paulo" -> "SP", "Maranhão" -> "MA", "Paraná" -> "PR").
 * 2. Se o nome da cidade for extenso (> 12 caracteres) e ocupar mais da metade do quadro,
 *    mantém a primeira palavra inteira e metade da segunda com reticências (ex: "São Bernardo do Campo" -> "São Bern..."),
 *    ou abrevia palavras únicas muito longas (ex: "Pindamonhangaba" -> "Pindamonh...").
 */
export function formatCompactWeatherLocation(
  rawCityOrCombined: string,
  rawState = ''
): string {
  const cleaned = rawCityOrCombined.trim();
  if (!cleaned) return 'Local, BR';

  let cityPart = cleaned;
  let statePart = rawState.trim();

  if (!statePart) {
    if (cleaned.includes(',')) {
      const lastComma = cleaned.lastIndexOf(',');
      cityPart = cleaned.slice(0, lastComma).trim();
      statePart = cleaned.slice(lastComma + 1).trim();
    } else if (cleaned.includes(' - ')) {
      const lastDash = cleaned.lastIndexOf(' - ');
      cityPart = cleaned.slice(0, lastDash).trim();
      statePart = cleaned.slice(lastDash + 3).trim();
    }
  }

  const ufSigla = resolveOfficialBrazilStateUf(statePart, cityPart);

  const trimmedCity = cityPart.trim();
  const cleanCity = trimmedCity.endsWith('...')
    ? trimmedCity.slice(0, -3)
    : trimmedCity;

  let compactCity: string;
  if (trimmedCity.endsWith('...') && trimmedCity.length <= 16) {
    compactCity = trimmedCity;
  } else if (cleanCity.length > 12) {
    const words = cleanCity.split(/\s+/).filter(Boolean);
    const connectors = new Set(['de', 'da', 'do', 'das', 'dos']);
    if (words.length >= 2) {
      const firstWord = words[0];
      if (connectors.has(words[1].toLowerCase()) && words.length >= 3) {
        const thirdWord = words[2];
        const halfThird = thirdWord.slice(
          0,
          Math.max(2, Math.floor((thirdWord.length + 1) / 2))
        );
        compactCity = `${firstWord} ${words[1]} ${halfThird}...`;
      } else {
        const secondWord = words[1];
        const halfSecond = secondWord.slice(
          0,
          Math.max(2, Math.floor((secondWord.length + 1) / 2))
        );
        compactCity = `${firstWord} ${halfSecond}...`;
      }
    } else {
      compactCity = `${cleanCity.slice(0, 9)}...`;
    }
  } else {
    compactCity = cleanCity;
  }

  return ufSigla ? `${compactCity}, ${ufSigla}` : compactCity;
}

export function parseHHMMToMinutesOfDay(hhmm: string | undefined, fallbackMinutes: number): number {
  if (!hhmm) return fallbackMinutes;
  const parts = hhmm.split(':');
  if (parts.length !== 2) return fallbackMinutes;
  const h = Number(parts[0]);
  const m = Number(parts[1]);
  if (Number.isNaN(h) || Number.isNaN(m)) return fallbackMinutes;
  return Math.max(0, Math.min(1439, h * 60 + m));
}

/**
 * Calcula a posição (x, y) do Sol na curva astronômica de 24h (Dia acima do horizonte e Noite abaixo do horizonte):
 * - A curva cruza a linha do horizonte (horizonY = 44) exatamente em sunriseX = 66 e sunsetX = 194.
 * - No meio-dia solar (noonX = 130), atinge o pico em y = 18.
 * - À noite (antes do nascer [2..66] e após o pôr do sol [194..258]), a curva desce abaixo do horizonte até y = 66.
 */
export function computeSolarCurvePoint(
  currentMinutesOfDay: number,
  sunriseMinutesOfDay = 327, // 05:27
  sunsetMinutesOfDay = 1060  // 17:40
): {
  sunX: number;
  sunY: number;
  horizonY: number;
  sunriseX: number;
  noonX: number;
  sunsetX: number;
  isBelowHorizon: boolean;
} {
  const graphLeft = 2;
  const sunriseX = 66;
  const noonX = 130;
  const sunsetX = 194;
  const graphRight = 258;
  const horizonY = 44;
  const amplitude = 22;
  const halfDaySpan = sunsetX - noonX; // 64px

  const safeRise = Math.max(1, sunriseMinutesOfDay);
  const safeSet = Math.max(safeRise + 60, sunsetMinutesOfDay);
  const cur = Math.max(0, Math.min(1440, currentMinutesOfDay));

  let sunX: number;
  if (cur <= safeRise) {
    const frac = cur / safeRise;
    sunX = graphLeft + frac * (sunriseX - graphLeft);
  } else if (cur <= safeSet) {
    const frac = (cur - safeRise) / (safeSet - safeRise);
    sunX = sunriseX + frac * (sunsetX - sunriseX);
  } else {
    const remNight = Math.max(1, 1440 - safeSet);
    const frac = (cur - safeSet) / remNight;
    sunX = sunsetX + frac * (graphRight - sunsetX);
  }

  const angle = (Math.PI / 2) * ((sunX - noonX) / halfDaySpan);
  const sunY = Number((horizonY - amplitude * Math.cos(angle)).toFixed(2));

  return {
    sunX: Number(sunX.toFixed(2)),
    sunY,
    horizonY,
    sunriseX,
    noonX,
    sunsetX,
    isBelowHorizon: sunY > horizonY,
  };
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

export interface AutoModeResolutionOptions {
  autoPrimaryContext?: PrimaryContextMode;
  unifiedCanvasHoldMinutes?: AutoExposureMinutes;
  autoAgendaExposureMinutes?: AutoExposureMinutes;
  autoWeatherExposureMinutes?: AutoExposureMinutes;
  autoSunExposureMinutes?: AutoExposureMinutes;
  autoSleepExposureMinutes?: AutoExposureMinutes;
  currentMinutesOfDay?: number;
  sunriseMinutesOfDay?: number;
  sunsetMinutesOfDay?: number;
  sleepSyncEnabled?: boolean;
  bedtimeMinutesOfDay?: number;
  wakeMinutesOfDay?: number;
  tasksSyncEnabled?: boolean;
  hasActiveWatchTaskAlert?: boolean;
  autoFallbackAgendaToTasks?: boolean;
  hasEventsToday?: boolean;
  pendingTasksCount?: number;
}

export function isSameCalendarDay(epochA: number, epochB: number): boolean {
  const dA = new Date(epochA);
  const dB = new Date(epochB);
  return (
    dA.getFullYear() === dB.getFullYear() &&
    dA.getMonth() === dB.getMonth() &&
    dA.getDate() === dB.getDate()
  );
}

export function resolveEffectiveWatchContextMode(
  configuredMode: ContextWindowMode,
  hasUpcomingEvent: boolean,
  remainingMs: number,
  hasActiveWatchWeatherAlert: boolean,
  options?: AutoModeResolutionOptions
): PrimaryContextMode {
  if (configuredMode !== 'DYNAMIC') {
    return configuredMode;
  }

  const primaryContext: PrimaryContextMode = options?.autoPrimaryContext ?? 'EVENTS';
  const unifiedHoldMins: AutoExposureMinutes =
    options?.unifiedCanvasHoldMinutes ??
    options?.autoAgendaExposureMinutes ??
    options?.autoWeatherExposureMinutes ??
    options?.autoSunExposureMinutes ??
    options?.autoSleepExposureMinutes ??
    30;

  // 1. Compromisso da Agenda faltando 15 minutos ou menos (<= 15m):
  // O Canvas troca para Agenda com prioridade máxima e (se ativado) gera notificação no relógio
  if (hasUpcomingEvent && remainingMs > 0 && remainingMs <= 15 * 60 * 1000) {
    return 'EVENTS';
  }

  // 2. Prioridade Máxima: Lembrete de Tarefa (Samsung Reminder / Google Tasks)
  if ((options?.tasksSyncEnabled ?? true) && options?.hasActiveWatchTaskAlert) {
    return 'TASKS';
  }

  // 3. Prioridade Máxima: Mudança Climática / Chuva ou Alerta de Clima Ativo
  if (hasActiveWatchWeatherAlert) {
    return 'WEATHER';
  }

  // 4. Prioridade Máxima: Nascer e Pôr do Sol (dentro da janela unificada)
  if (options?.currentMinutesOfDay != null) {
    const curMins = options.currentMinutesOfDay;
    const riseMins = options.sunriseMinutesOfDay ?? 327;
    const setMins = options.sunsetMinutesOfDay ?? 1060;
    const sunExp = options.autoSunExposureMinutes ?? unifiedHoldMins;
    const halfSunWin = Math.max(5, Math.floor(sunExp / 2));
    if (
      Math.abs(curMins - riseMins) <= halfSunWin ||
      Math.abs(curMins - setMins) <= halfSunWin
    ) {
      return 'SUN';
    }

    // 5. Prioridade Máxima: Acordar ou Dormir (Resumo de Sono)
    const sleepEnabled = options.sleepSyncEnabled ?? true;
    const wakeMins = options.wakeMinutesOfDay ?? 420; // 07:00
    const bedMins = options.bedtimeMinutesOfDay ?? 1398; // 23:18
    const sleepExp = options.autoSleepExposureMinutes ?? unifiedHoldMins;
    if (
      sleepEnabled &&
      ((curMins >= wakeMins && curMins <= wakeMins + sleepExp) ||
        (curMins >= bedMins && curMins <= bedMins + sleepExp))
    ) {
      return 'SLEEP';
    }
  }

  // 6. Regra Inteligente de Foco em Agenda:
  // Se o foco principal for Agenda (EVENTS) e NÃO houver eventos para hoje (mesmo que haja para amanhã):
  // - Se houver tarefas ativas pendentes (Google Tasks / Samsung Reminder) e a opção estiver ativa, mostra TASKS;
  // - Caso não haja tarefas ativas, mantém o foco na Agenda (EVENTS) configurada pelo usuário.
  if (primaryContext === 'EVENTS') {
    const hasToday = options?.hasEventsToday ?? hasUpcomingEvent;
    if (!hasToday) {
      const autoFallbackTasks = options?.autoFallbackAgendaToTasks ?? true;
      const tasksEnabled = options?.tasksSyncEnabled ?? true;
      const pendingTasks = options?.pendingTasksCount ?? 0;
      if (autoFallbackTasks && tasksEnabled && pendingTasks > 0) {
        return 'TASKS';
      }
    }
    return 'EVENTS';
  }

  return primaryContext;
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

  const todayEvents = futureEvents.filter((ev) =>
    isSameCalendarDay(currentWatchTimeMs, ev.startEpochMs)
  );
  const hasEventsToday = todayEvents.length > 0;

  let agendaDayHeader = 'Hoje';
  let dayEvents: CalendarEventItem[] = [];

  if (hasEventsToday) {
    agendaDayHeader = 'Hoje';
    dayEvents = todayEvents.slice(0, 3);
  } else if (futureEvents.length > 0) {
    const firstNext = futureEvents[0];
    const rel = formatDayRelative(firstNext.startEpochMs, currentWatchTimeMs);
    if (rel === 'Amanhã') {
      agendaDayHeader = 'Amanhã';
    } else {
      const dEv = new Date(firstNext.startEpochMs);
      const weekdays = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];
      agendaDayHeader = `${weekdays[dEv.getDay()]}, ${String(dEv.getDate()).padStart(2, '0')}`;
    }
    dayEvents = futureEvents
      .filter((ev) => isSameCalendarDay(firstNext.startEpochMs, ev.startEpochMs))
      .slice(0, 3);
  } else {
    agendaDayHeader = 'Hoje';
    dayEvents = [];
  }

  const upcomingThree = futureEvents.slice(0, 3);
  const nextEvent = dayEvents[0] ?? upcomingThree[0] ?? null;

  const autonomousCacheValidUntilMs =
    cache.lastSyncEpochMs + cache.syncedHorizonDays * 24 * 60 * 60 * 1000;

  const configuredMode: ContextWindowMode = cache.contextWindowMode ?? 'DYNAMIC';
  const highFreq15MinRule = cache.highFreq15MinRule ?? true;
  const hasWeatherAlert = cache.hasActiveWatchWeatherAlert ?? false;
  const unifiedHold = cache.unifiedCanvasHoldMinutes ?? 30;

  const tasksList = cache.tasks ?? DEFAULT_TASKS_LIST;
  const pendingTasksCount = filterActiveTasksForWatch(tasksList).length;

  const d = new Date(currentWatchTimeMs);
  const currentMinutesOfDay = d.getHours() * 60 + d.getMinutes();
  const autoOptions: AutoModeResolutionOptions = {
    autoPrimaryContext: cache.autoPrimaryContext ?? 'EVENTS',
    unifiedCanvasHoldMinutes: unifiedHold,
    autoAgendaExposureMinutes: cache.autoAgendaExposureMinutes ?? unifiedHold,
    autoWeatherExposureMinutes: cache.autoWeatherExposureMinutes ?? unifiedHold,
    autoSunExposureMinutes: cache.autoSunExposureMinutes ?? unifiedHold,
    autoSleepExposureMinutes: cache.autoSleepExposureMinutes ?? unifiedHold,
    currentMinutesOfDay,
    sunriseMinutesOfDay: parseHHMMToMinutesOfDay(cache.solarSunriseHHMM, 327),
    sunsetMinutesOfDay: parseHHMMToMinutesOfDay(cache.solarSunsetHHMM, 1060),
    sleepSyncEnabled: cache.sleepSyncEnabled ?? true,
    bedtimeMinutesOfDay: parseHHMMToMinutesOfDay(cache.sleepBedtimeHHMM, 1398),
    wakeMinutesOfDay: parseHHMMToMinutesOfDay(cache.sleepWakeHHMM, 420),
    tasksSyncEnabled: cache.tasksSyncEnabled ?? true,
    hasActiveWatchTaskAlert: cache.hasActiveWatchTaskAlert ?? false,
    autoFallbackAgendaToTasks: cache.autoFallbackAgendaToTasks ?? true,
    hasEventsToday,
    pendingTasksCount,
  };

  if (!nextEvent) {
    return {
      hasUpcomingEvent: false,
      hasEventsToday: false,
      agendaDayHeader: 'Hoje',
      dayEvents: [],
      nextEvent: null,
      upcomingThree: [],
      remainingMs: 0,
      formattedCountdown: 'Não há eventos',
      formattedStartTime: '--:--',
      progressRatio: 0,
      isWithinProgressWindow: false,
      isWithinTurbo15MinWindow: false,
      effectiveContextMode: resolveEffectiveWatchContextMode(
        configuredMode,
        false,
        0,
        hasWeatherAlert,
        autoOptions
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
    hasEventsToday,
    agendaDayHeader,
    dayEvents,
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
      hasWeatherAlert,
      autoOptions
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
