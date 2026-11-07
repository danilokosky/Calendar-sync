import React, { useState } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  WEATHER_TRIGGER_CATALOG,
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
    onClick={onChange}
    className={`w-[52px] h-[28px] rounded-full p-[3px] transition-all duration-200 active:scale-95 flex items-center cursor-pointer shrink-0 border ${
      checked
        ? 'bg-[#22C55E] border-[#4ADE80]'
        : 'bg-[#2A3441] border-[#64748B]'
    }`}
  >
    <span
      className={`w-[20px] h-[20px] rounded-full transition-transform duration-200 shadow-xs ${
        checked
          ? 'translate-x-[24px] bg-white'
          : 'translate-x-0 bg-[#CBD5E1]'
      }`}
    />
  </button>
);

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

  // Simula se o app versão Wear OS já foi instalado no relógio conectado
  const [isWearAppInstalledOnWatch, setIsWearAppInstalledOnWatch] = useState(true);

  // Estados das chaves de Settings
  const [bgServiceEnabled, setBgServiceEnabled] = useState(true);
  const [notificationsSyncEnabled, setNotificationsSyncEnabled] = useState(true);
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(true);
  const [calendarPermGranted, setCalendarPermGranted] = useState(true);

  // Modal "Synced calendars" e Modal "Apps de Clima por Categoria"
  const [isSyncedCalendarsDialogOpen, setIsSyncedCalendarsDialogOpen] = useState(false);
  const [isWeatherAppsDialogOpen, setIsWeatherAppsDialogOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newMinutesFromNow, setNewMinutesFromNow] = useState<number>(45);

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

  const upcomingEvents = events
    .filter((e) => e.startEpochMs > referenceTimeMs)
    .sort((a, b) => a.startEpochMs - b.startEpochMs);
  const nextEvent = upcomingEvents[0] || null;

  const formatHHMM = (epochMs: number) => {
    const d = new Date(epochMs);
    return d.toLocaleTimeString('pt-BR', {
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const handleToggleCalendarEventsSync = () => {
    if (!calendarSyncEnabled) {
      setCalendarPermGranted(true);
      setCalendarSyncEnabled(true);
      if (selectedCalendarsCount === 0) {
        setIsSyncedCalendarsDialogOpen(true);
      } else {
        onTriggerManualSync();
      }
    } else {
      setCalendarSyncEnabled(false);
    }
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

  // Agrupa os apps de clima por categoria (Provedores do Sistema, Clima e Meteorologia, Clima e Radar)
  const weatherCategories = Array.from(
    new Set(WEATHER_TRIGGER_CATALOG.map((app) => app.categoryName))
  );

  // Lógica de status: SÓ dá positivo se houver conexão Bluetooth E o app estiver instalado no relógio
  const renderConnectionBanner = () => {
    if (!isBluetoothConnected) {
      return (
        <div className="flex items-center justify-center gap-2 py-1.5 text-xs font-medium text-slate-400">
          <Bluetooth className="w-3.5 h-3.5 text-slate-500" />
          <span>Disconnected • Nenhum relógio Wear OS conectado</span>
        </div>
      );
    }

    if (!isWearAppInstalledOnWatch) {
      return (
        <div className="flex items-center justify-center gap-2 py-2 px-3 rounded-2xl bg-amber-500/12 border border-amber-500/35 text-xs font-medium text-amber-300">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0" />
          <span>
            Wearable conectado (Galaxy Watch Ultra) • App NÃO instalado no relógio
          </span>
        </div>
      );
    }

    return (
      <div className="flex items-center justify-center gap-2 py-1.5 text-xs font-semibold text-slate-100">
        <Bluetooth className="w-3.5 h-3.5 text-sky-400" />
        <span>Connected • Galaxy Watch Ultra (ZNDA)</span>
      </div>
    );
  };

  const activeTabIndex =
    activeTab === 'home' ? 0 : activeTab === 'settings' ? 1 : 2;

  const contextModeOptions = [
    { id: 'DYNAMIC', label: 'Auto' },
    { id: 'EVENTS', label: 'Agenda' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'STEPS', label: 'Passos' },
    { id: 'SUN', label: 'Sol' },
  ] as const;

  return (
    <div
      className="relative rounded-[36px] border border-[#2A332C] text-slate-100 shadow-2xl overflow-hidden flex flex-col justify-between min-h-[620px]"
      style={{
        background:
          'linear-gradient(180deg, #3E351B 0%, #17231B 24%, #0B0E0C 58%, #090B0A 100%)',
      }}
    >
      {/* Conteúdo Principal com Transições Fluidas estilo Samsung Health One UI */}
      <div className="px-5 pt-5 pb-24 flex-1 flex flex-col">
        {/* Barra superior estilo Samsung Health + Simulador de Instalação no Relógio */}
        <div className="flex items-center justify-between gap-2 pb-3 mb-3 border-b border-white/10">
          <div className="flex items-center gap-2">
            <span className="text-lg font-bold tracking-tight text-white">
              ChronoWear Sync
            </span>
            <span className="w-2 h-2 rounded-full bg-[#F97316]" />
          </div>

          <button
            type="button"
            onClick={() => setIsWearAppInstalledOnWatch((prev) => !prev)}
            className={`px-3 py-1 rounded-full text-[11px] font-semibold transition-all duration-200 active:scale-95 cursor-pointer ${
              isWearAppInstalledOnWatch
                ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-400/35'
                : 'bg-amber-500/20 text-amber-200 border border-amber-400/35'
            }`}
          >
            {isWearAppInstalledOnWatch
              ? 'App Wear Instalado'
              : 'App Wear Ausente'}
          </button>
        </div>

        {/* =============================================================== */}
        {/* ABA 1: INÍCIO (Visual Samsung Health + Amoled Watchfaces)       */}
        {/* =============================================================== */}
        {activeTab === 'home' && (
          <div
            key="tab-home"
            className="space-y-3.5 flex-1 flex flex-col transition-all duration-300 ease-out"
          >
            {/* Card Destaque Superior translúcido estilo Samsung Health */}
            <div className="relative rounded-[26px] bg-[#1F2C24]/90 border border-[#34463B] p-4 shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="flex items-center gap-4">
                {/* Mockup Circular do Relógio */}
                <div className="relative w-28 h-28 rounded-full bg-[#070A10] border-[5px] border-[#2A352F] shadow-inner flex flex-col items-center justify-center px-2.5 py-2 gap-1 shrink-0">
                  <div className="text-[11px] font-bold font-mono text-white leading-none">
                    10:08
                  </div>
                  <div className="w-full rounded-lg bg-[#111926] border border-[#243447] px-1.5 py-0.5 flex items-center justify-between">
                    <span className="text-[7px] text-slate-200 truncate">
                      {nextEvent ? nextEvent.title : 'Agenda'}
                    </span>
                    <span className="text-[7px] font-bold text-amber-400 ml-1">
                      {config.progressWindowMinutes}m
                    </span>
                  </div>
                  <div className="w-11/12 rounded-md bg-[#111926] px-1.5 py-0.5 flex items-center gap-1">
                    <span className="text-[6.5px] font-bold text-amber-300 bg-amber-500/20 px-1 rounded">
                      16:30
                    </span>
                    <span className="text-[6.5px] text-white truncate">
                      {upcomingEvents[0]?.title || 'Evento #1'}
                    </span>
                  </div>
                  <div className="w-4/5 rounded-md bg-[#0E1621] px-1.5 py-0.5 flex items-center gap-1">
                    <span className="text-[6px] font-bold text-sky-300 bg-sky-500/20 px-1 rounded">
                      18:00
                    </span>
                    <span className="text-[6px] text-slate-200 truncate">
                      {upcomingEvents[1]?.title || 'Evento #2'}
                    </span>
                  </div>
                  {/* Coroa lateral */}
                  <div className="absolute -right-2 top-1/2 -translate-y-1/2 w-1.5 h-5 rounded-r bg-gradient-to-b from-slate-300 via-slate-500 to-slate-300" />
                </div>

                <div className="min-w-0 flex-1 space-y-1.5">
                  <div className="text-sm font-bold text-white leading-snug">
                    Janela de Contexto em Canvas Bitmap (v1.1.2)
                  </div>
                  <p className="text-[11px] text-[#C8D3CC] leading-relaxed">
                    5 modos em alta densidade (Auto, Agenda, Clima, Passos e Nascer/Pôr do Sol) com atualização instantânea ao trocar opções.
                  </p>
                </div>
              </div>

              {/* Seletor rápido da Janela de Contexto direto na Home do Smartphone */}
              <div className="mt-3 pt-2.5 border-t border-white/10 space-y-2">
                <div className="flex items-center justify-between text-[11px]">
                  <span className="font-semibold text-emerald-300">
                    Contexto no Relógio:
                  </span>
                  <span className="font-mono text-[10px] text-amber-300">
                    {config.highFreq15MinRule ? 'Turbo 1m (≤15m) Ativo' : 'Padrão Screen-On'}
                  </span>
                </div>
                <div className="grid grid-cols-5 gap-1.5">
                  {contextModeOptions.map((mode) => (
                    <button
                      key={mode.id}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ contextWindowMode: mode.id })
                      }
                      className={`py-1.5 px-2 rounded-full text-[11px] font-semibold transition-all duration-150 cursor-pointer ${
                        config.contextWindowMode === mode.id
                          ? 'bg-[#22C55E] text-[#052E16] font-bold shadow-xs'
                          : 'bg-[#141D18] text-slate-200 border border-[#2D3E34] hover:bg-[#1C2821]'
                      }`}
                    >
                      {mode.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="mt-2.5 pt-2 border-t border-white/10">
                {renderConnectionBanner()}
              </div>
            </div>

            {/* Card Escuro Samsung Health: Status de Instalação & Métricas */}
            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-3 shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="flex items-center justify-between">
                <h3 className="text-base font-bold text-white">Status de Sincronização</h3>
                <span className="text-[11px] font-mono text-emerald-400 bg-emerald-500/10 px-2.5 py-0.5 rounded-full">
                  {syncedEventsCount} sincronizados
                </span>
              </div>

              <div className="space-y-1">
                {!isBluetoothConnected ? (
                  <>
                    <div className="text-xs font-semibold text-slate-300">
                      Status do dispositivo Wear OS:
                    </div>
                    <div className="text-xs font-bold text-slate-400">
                      Nenhum smartwatch conectado ao telefone.
                    </div>
                  </>
                ) : !isWearAppInstalledOnWatch ? (
                  <>
                    <div className="text-xs font-semibold text-amber-200">
                      Há um Wearable conectado, mas o app não está instalado no relógio:
                    </div>
                    <div className="text-xs font-bold text-amber-400">
                      Galaxy Watch Ultra (ZNDA) — Instale o APK da versão Wear OS no relógio.
                    </div>
                  </>
                ) : (
                  <>
                    <div className="text-xs font-semibold text-slate-300">
                      App instalado no seu relógio Wear OS:
                    </div>
                    <div className="text-sm font-bold text-[#6EE7B7]">
                      Galaxy Watch Ultra (ZNDA)
                    </div>
                  </>
                )}
              </div>

              {/* Mini indicadores estilo cards Samsung Health */}
              <div className="pt-3 border-t border-[#242A26] grid grid-cols-3 gap-2.5">
                <div className="rounded-2xl bg-[#1B201D] p-2.5">
                  <div className="text-[10px] text-slate-400">
                    Próximo evento
                  </div>
                  <div className="text-xs font-bold text-white truncate mt-0.5">
                    {nextEvent ? nextEvent.title : 'Sem eventos'}
                  </div>
                  <div className="text-[11px] font-mono text-amber-400 mt-0.5">
                    {nextEvent ? formatHHMM(nextEvent.startEpochMs) : '--:--'}
                  </div>
                </div>

                <div className="rounded-2xl bg-[#1B201D] p-2.5">
                  <div className="text-[10px] text-slate-400">
                    Clima &amp; Triggers
                  </div>
                  <div className="text-xs font-bold text-white mt-0.5">
                    {selectedWeatherApps.length} apps ativos
                  </div>
                  <div className="text-[10px] font-mono text-sky-400 mt-0.5">
                    Auto {config.phoneWeatherCheckIntervalMinutes ?? 60}m
                  </div>
                </div>

                <div className="rounded-2xl bg-[#1B201D] p-2.5">
                  <div className="text-[10px] text-slate-400">
                    Nascer / Pôr do Sol
                  </div>
                  <div className="text-xs font-bold text-white mt-0.5 font-mono">
                    {config.solarSunriseHHMM ?? '05:27'} • {config.solarSunsetHHMM ?? '17:40'}
                  </div>
                  <div className="text-[10px] font-mono text-emerald-400 mt-0.5">
                    {config.phoneSolarAutoSyncEnabled ?? true ? 'GPS Telefone' : 'Stand-Alone'}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 2: CONFIGURAÇÕES (Separada por Seções, Sem Emojis, Switches */}
        {/* Modernos de Alto Contraste)                                     */}
        {/* =============================================================== */}
        {activeTab === 'settings' && (
          <div
            key="tab-settings"
            className="space-y-3.5 flex-1 max-h-[480px] overflow-y-auto pr-1 transition-all duration-300 ease-out"
          >
            {renderConnectionBanner()}

            {/* SEÇÃO 1: CONTEXTO & ATUALIZAÇÃO DO CANVAS */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                1. Contexto e Atualização do Canvas
              </div>

              <div className="space-y-1.5">
                <span className="text-xs font-bold text-slate-100 block">
                  Função da Janela de Contexto (Canvas Bitmap)
                </span>
                <div className="grid grid-cols-5 gap-1.5">
                  {contextModeOptions.map((m) => (
                    <button
                      key={m.id}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ contextWindowMode: m.id })
                      }
                      className={`py-2 px-2 rounded-full text-[11px] font-semibold transition-all duration-150 cursor-pointer ${
                        config.contextWindowMode === m.id
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E] hover:bg-[#26302A]'
                      }`}
                    >
                      {m.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Regra Turbo 15 Minutos (1 em 1 min)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Atualiza o Canvas a cada minuto quando faltar 15 min ou menos para o evento
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

              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Atualizar Canvas ao Acordar a Tela
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Redesenha o quadro ao ligar a tela do relógio (com proteção de 25s)
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.updateOnScreenWake}
                  ariaLabel="Atualizar Canvas ao Acordar a Tela"
                  onChange={() =>
                    onUpdateConfig({
                      updateOnScreenWake: !config.updateOnScreenWake,
                    })
                  }
                />
              </div>
            </div>

            {/* SEÇÃO 2: SINCRONIZAÇÃO DE AGENDA */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                2. Sincronização de Agenda
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Calendários do Telefone
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Envia compromissos selecionados para o banco autônomo do relógio
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={calendarSyncEnabled && calendarPermGranted}
                  ariaLabel="Sincronizar Calendários do Telefone"
                  onChange={handleToggleCalendarEventsSync}
                />
              </div>

              <button
                type="button"
                onClick={() => setIsSyncedCalendarsDialogOpen(true)}
                className="w-full py-2.5 px-4 rounded-2xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-white flex items-center justify-center gap-2 transition-colors cursor-pointer"
              >
                <Calendar className="w-4 h-4 text-emerald-400" />
                <span>Selecionar Calendários Sincronizados ({selectedCalendarsCount})</span>
              </button>

              <div className="space-y-1.5 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Frequência de Leitura do Calendário
                </span>
                <div className="grid grid-cols-3 gap-1.5">
                  {([15, 30, 60] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ syncIntervalMinutes: mins })
                      }
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        config.syncIntervalMinutes === mins
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {mins === 60 ? '1h' : `${mins}m`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1.5 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Janela da Barra de Progresso do Evento
                </span>
                <div className="grid grid-cols-4 gap-1.5">
                  {([30, 60, 120, 180] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ progressWindowMinutes: mins })
                      }
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        config.progressWindowMinutes === mins
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {mins === 30 ? '30m' : `${mins / 60}h`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1.5 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Horizonte Autônomo de Eventos (Cache Offline)
                </span>
                <div className="grid grid-cols-4 gap-1.5">
                  {([2, 3, 5, 7] as const).map((days) => (
                    <button
                      key={days}
                      type="button"
                      onClick={() => onUpdateConfig({ horizonDays: days })}
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        config.horizonDays === days
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {days}d
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* SEÇÃO 3: INTELIGÊNCIA DE CLIMA & GATILHOS */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                3. Inteligência de Clima e Gatilhos
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Busca Periódica de Clima (Telefone)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Detecta mudança de temperatura ou condição climática (chuva, tempestade) e envia ao relógio
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.phoneWeatherAutoCheckEnabled ?? true}
                  ariaLabel="Busca Periódica de Clima (Telefone)"
                  onChange={() =>
                    onUpdateConfig({
                      phoneWeatherAutoCheckEnabled: !(
                        config.phoneWeatherAutoCheckEnabled ?? true
                      ),
                    })
                  }
                />
              </div>

              <div className="space-y-1.5 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Intervalo de Busca Automática de Clima
                </span>
                <div className="grid grid-cols-4 gap-1.5">
                  {([30, 60, 120, 180] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({
                          phoneWeatherCheckIntervalMinutes: mins,
                        })
                      }
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        (config.phoneWeatherCheckIntervalMinutes ?? 60) === mins
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {mins === 30 ? '30m' : `${mins / 60}h`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-2.5 pt-2.5 border-t border-[#232925]">
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <div className="text-xs font-bold text-slate-100">
                      Gatilho por Apps de Clima Instalados
                    </div>
                    <div className="text-[11px] text-slate-400">
                      Atualiza o clima quando um app da categoria de clima enviar notificação
                    </div>
                  </div>
                  <ModernOneUiSwitch
                    checked={notificationsSyncEnabled}
                    ariaLabel="Gatilho por Apps de Clima Instalados"
                    onChange={() => setNotificationsSyncEnabled((v) => !v)}
                  />
                </div>

                <button
                  type="button"
                  onClick={() => setIsWeatherAppsDialogOpen(true)}
                  className="w-full py-2.5 px-4 rounded-2xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-white flex items-center justify-center gap-2 transition-colors cursor-pointer"
                >
                  <CloudRain className="w-4 h-4 text-sky-400" />
                  <span>
                    Apps de Clima por Categoria ({selectedWeatherApps.length} selecionados)
                  </span>
                </button>
              </div>

              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Clima Stand-Alone no Relógio (1 em 1h)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Opcional: próprio relógio busca o clima de hora em hora via Wi-Fi/LTE
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.watchStandaloneWeatherEnabled ?? false}
                  ariaLabel="Clima Stand-Alone no Relógio"
                  onChange={() =>
                    onUpdateConfig({
                      watchStandaloneWeatherEnabled: !(
                        config.watchStandaloneWeatherEnabled ?? false
                      ),
                    })
                  }
                />
              </div>
            </div>

            {/* SEÇÃO 4: NASCER E PÔR DO SOL (LOCALIZAÇÃO) */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                4. Nascer e Pôr do Sol (Localização)
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Sol pelo Smartphone (GPS)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Calcula nascer ({config.solarSunriseHHMM ?? '05:27'}) e pôr do sol ({config.solarSunsetHHMM ?? '17:40'}) pela localização do telefone
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.phoneSolarAutoSyncEnabled ?? true}
                  ariaLabel="Sincronizar Sol pelo Smartphone"
                  onChange={() =>
                    onUpdateConfig({
                      phoneSolarAutoSyncEnabled: !(
                        config.phoneSolarAutoSyncEnabled ?? true
                      ),
                    })
                  }
                />
              </div>

              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sol Stand-Alone no Relógio
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Permite ao relógio calcular a curva solar autonomamente quando desconectado
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.watchStandaloneSolarEnabled ?? false}
                  ariaLabel="Sol Stand-Alone no Relógio"
                  onChange={() =>
                    onUpdateConfig({
                      watchStandaloneSolarEnabled: !(
                        config.watchStandaloneSolarEnabled ?? false
                      ),
                    })
                  }
                />
              </div>
            </div>

            {/* SEÇÃO 5: SERVIÇO EM SEGUNDO PLANO */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-2.5 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                5. Serviço em Segundo Plano
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Acesso a Notificações Ativo
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Permite detectar gatilhos dos apps de clima selecionados
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
        {/* ABA 3: ABOUT (Essencial e Informativa + Teste Rápido)           */}
        {/* =============================================================== */}
        {activeTab === 'about' && (
          <div
            key="tab-about"
            className="space-y-4 flex-1 transition-all duration-300 ease-out"
          >
            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-2.5 text-xs text-slate-300 leading-relaxed shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="text-sm font-bold text-white">
                ChronoWear Sync v1.1.2 — Recursos da Janela Canvas
              </div>
              <p>
                • <strong>Tipografia de Alto DPI no Relógio</strong>: Textos, horários e curvas ampliados no bitmap 604×272px para leitura imediata em Agenda, Clima, Passos e Sol.
              </p>
              <p>
                • <strong>Clima por Temperatura e Condição Climática</strong>: Cabeçalho limpo (&quot;Clima&quot;) e detecção automática de chuva, tempestade, garoa ou mudança brusca de temperatura.
              </p>
              <p>
                • <strong>Novo Contexto Nascer e Pôr do Sol</strong>: Curva astronômica com gradiente crepuscular, linha do horizonte e posição solar sincronizada pelo telefone ou Stand-Alone no relógio.
              </p>
            </div>

            {/* Simulador de Alteração na Agenda */}
            <div className="rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-3 shadow-lg">
              <div className="text-xs font-bold text-white">
                Testar Alteração nos Compromissos
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
                  className="px-3 py-1.5 rounded-xl bg-[#22C55E] text-[#052E16] font-bold text-xs flex items-center gap-1 active:scale-95 transition-transform cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                  <span>Criar</span>
                </button>
              </form>

              <div className="space-y-1.5 max-h-32 overflow-y-auto pr-1">
                {upcomingEvents.slice(0, 3).map((ev) => (
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

      {/* ================================================================= */}
      {/* RODAPÉ FLUTUANTE EM PÍLULA IDÊNTICO AO SAMSUNG HEALTH             */}
      {/* ================================================================= */}
      <div className="absolute bottom-4 left-0 right-0 px-5 pointer-events-none z-10">
        <nav
          aria-label="Navegação principal do app"
          className="relative pointer-events-auto mx-auto rounded-[36px] bg-[#242B26]/95 backdrop-blur-md border border-[#38423B] p-1.5 shadow-[0_14px_34px_rgba(0,0,0,0.65)] grid grid-cols-3"
        >
          {/* Pílula deslizante fluida estilo Samsung Health */}
          <div
            className="absolute top-1.5 bottom-1.5 left-1.5 w-[calc((100%-12px)/3)] rounded-[28px] bg-[#434E46] transition-transform duration-300 ease-[cubic-bezier(0.22,1,0.36,1)]"
            style={{
              transform: `translateX(${activeTabIndex * 100}%)`,
            }}
          />

          {(
            [
              { id: 'home', label: 'Início', Icon: Home },
              { id: 'settings', label: 'Configurações', Icon: Settings, dot: true },
              { id: 'about', label: 'Sobre', Icon: Info },
            ] as const
          ).map((item) => {
            const isActive = activeTab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className="relative z-10 flex flex-col items-center justify-center py-2 px-3 rounded-[28px] transition-transform duration-150 active:scale-95 cursor-pointer select-none"
              >
                <div className="flex flex-col items-center gap-0.5">
                  <div className="relative">
                    <item.Icon
                      className={`w-5 h-5 transition-colors duration-200 ${
                        isActive ? 'text-white' : 'text-[#A0ABA3]'
                      }`}
                    />
                    {'dot' in item && item.dot && (
                      <span className="absolute -top-0.5 -right-1.5 w-2 h-2 rounded-full bg-[#F97316]" />
                    )}
                  </div>
                  <span
                    className={`text-[11px] tracking-tight transition-colors duration-200 ${
                      isActive
                        ? 'font-bold text-white'
                        : 'font-medium text-[#A0ABA3]'
                    }`}
                  >
                    {item.label}
                  </span>
                </div>
              </button>
            );
          })}
        </nav>
      </div>

      {/* Modal Multi-Escolha "Synced calendars" */}
      {isSyncedCalendarsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20 transition-opacity duration-200">
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
                  className={`w-full flex items-center justify-between gap-3 p-3 rounded-2xl border text-left transition-all duration-150 active:scale-98 cursor-pointer ${
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
                className="px-5 py-2 rounded-full bg-[#22C55E] text-[#052E16] font-bold text-xs active:scale-95 transition-transform cursor-pointer"
              >
                Salvar
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal Multi-Escolha "Apps de Clima Instalados por Categoria" */}
      {isWeatherAppsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20 transition-opacity duration-200">
          <div className="w-full max-w-sm rounded-[28px] bg-[#1A201C] border border-[#344038] p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-bold text-white">
                  Apps de Clima por Categoria
                </h3>
                <p className="text-[11px] text-slate-400">
                  Apps verificados no telefone e agrupados por área de clima para disparar o Canvas
                </p>
              </div>
              <button
                type="button"
                onClick={() => setIsWeatherAppsDialogOpen(false)}
                aria-label="Fechar seleção de apps de clima"
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
                          className={`w-full flex items-center justify-between gap-3 p-2.5 rounded-2xl border text-left transition-all duration-150 cursor-pointer ${
                            isChecked
                              ? 'bg-[#26322B] border-[#4A6353]'
                              : 'bg-[#141815] border-[#242B26] opacity-75'
                          }`}
                        >
                          <div className="min-w-0">
                            <div className="flex items-center gap-1.5">
                              <span className="text-xs font-bold text-white truncate">
                                {appItem.displayName}
                              </span>
                              <span
                                className={`text-[9px] px-1.5 py-0.5 rounded font-semibold ${
                                  appItem.isInstalledOnDevice
                                    ? 'bg-emerald-500/20 text-emerald-300'
                                    : 'bg-slate-800 text-slate-400'
                                }`}
                              >
                                {appItem.isInstalledOnDevice
                                  ? 'Instalado'
                                  : 'Catálogo'}
                              </span>
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
                className="px-5 py-2 rounded-full bg-[#22C55E] text-[#052E16] font-bold text-xs active:scale-95 transition-transform cursor-pointer"
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
