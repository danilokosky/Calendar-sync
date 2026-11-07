import React, { useState } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  WEATHER_TRIGGER_CATALOG,
  formatCompactWeatherLocation,
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
    { id: 'SLEEP', label: 'Sono' },
  ] as const;

  const autoPrimaryOptions = [
    { id: 'EVENTS', label: 'Agenda' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'SUN', label: 'Sol' },
    { id: 'STEPS', label: 'Passos' },
    { id: 'SLEEP', label: 'Sono' },
  ] as const;

  const exposureMinuteOptions = [5, 15, 30, 45, 60] as const;

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
                    Janela de Contexto em Canvas Bitmap (v1.2.1)
                  </div>
                  <p className="text-[11px] text-[#C8D3CC] leading-relaxed">
                    6 modos (Auto, Agenda, Clima, Passos, Sol com curva noturna e Sono via Health Connect) com tempos de exposição configuráveis.
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
                    {config.contextWindowMode === 'DYNAMIC'
                      ? `Auto (Base: ${
                          autoPrimaryOptions.find(
                            (o) => o.id === (config.autoPrimaryContext ?? 'EVENTS')
                          )?.label ?? 'Agenda'
                        })`
                      : config.highFreq15MinRule
                        ? 'Turbo 1m (≤15m) Ativo'
                        : 'Padrão Screen-On'}
                  </span>
                </div>
                <div className="grid grid-cols-6 gap-1">
                  {contextModeOptions.map((mode) => (
                    <button
                      key={mode.id}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ contextWindowMode: mode.id })
                      }
                      className={`py-1.5 px-1.5 rounded-full text-[10.5px] font-semibold transition-all duration-150 cursor-pointer ${
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
                    Barra de Progresso
                  </div>
                  <div className="text-xs font-bold text-white truncate mt-0.5">
                    0% → 100% ({config.progressWindowMinutes === 30 ? '30m' : `${config.progressWindowMinutes / 60}h`})
                  </div>
                  <div className="text-[10px] font-mono text-amber-400 mt-0.5">
                    Próx: {nextEvent ? formatHHMM(nextEvent.startEpochMs) : '--:--'}
                  </div>
                </div>

                <div className="rounded-2xl bg-[#1B201D] p-2.5">
                  <div className="text-[10px] text-slate-400">
                    Nascer / Pôr do Sol
                  </div>
                  <div className="text-xs font-bold text-white mt-0.5 font-mono">
                    {config.solarSunriseHHMM ?? '05:27'} • {config.solarSunsetHHMM ?? '17:40'}
                  </div>
                  <div className="text-[10px] font-mono text-sky-400 mt-0.5">
                    Exposição: {config.autoSunExposureMinutes ?? 30}m
                  </div>
                </div>

                <div className="rounded-2xl bg-[#1B201D] p-2.5">
                  <div className="text-[10px] text-slate-400">
                    Resumo de Sono
                  </div>
                  <div className="text-xs font-bold text-white mt-0.5 font-mono">
                    7h 42m • {config.sleepScore ?? 86} pts
                  </div>
                  <div className="text-[10px] font-mono text-emerald-400 mt-0.5">
                    {config.sleepSyncEnabled ?? true ? `Ao acordar (${config.autoSleepExposureMinutes ?? 30}m)` : 'Desativado'}
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
                <div className="grid grid-cols-3 gap-1.5">
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

            {/* SEÇÃO 2: CANVAS PADRÃO PRINCIPAL, TEMPO UNIFICADO E PRIORIDADE */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                2. Canvas Padrão Principal, Tempo Unificado e Prioridade
              </div>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Ao tocar (Tap) no Canvas da Watch Face, o próximo Canvas aparece temporariamente e depois retorna ao <strong>Canvas Padrão Principal</strong>. Qualquer mudança de <strong>Clima, Agenda, Pôr/Nascer do Sol, Acordar ou Dormir</strong> tem <strong>Prioridade Máxima</strong>.
              </p>

              {/* Canvas Padrão Principal */}
              <div className="space-y-1.5">
                <span className="text-xs font-bold text-slate-200 block">
                  Canvas Padrão Principal (Aparência Padrão de Retorno)
                </span>
                <div className="grid grid-cols-5 gap-1.5">
                  {autoPrimaryOptions.map((opt) => (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ autoPrimaryContext: opt.id })
                      }
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        (config.autoPrimaryContext ?? 'EVENTS') === opt.id
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Tempo Unificado de Permanência de Canvas Não-Padrão */}
              <div className="space-y-1.5 pt-2.5 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Tempo Fixo de Canvas Não-Padrão (Toque no Canvas ou Gatilho)
                </span>
                <span className="text-[11px] text-slate-400 block">
                  Define por quanto tempo um Canvas que não seja o padrão fica fixo no relógio antes de retornar ao Canvas Padrão Principal
                </span>
                <div className="grid grid-cols-5 gap-1.5 pt-1">
                  {exposureMinuteOptions.map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ unifiedCanvasHoldMinutes: mins })
                      }
                      className={`py-1.5 rounded-full text-[11px] font-semibold transition-all cursor-pointer ${
                        (config.unifiedCanvasHoldMinutes ?? 30) === mins
                          ? 'bg-[#22C55E] text-[#052E16] font-bold border border-[#4ADE80]'
                          : 'bg-[#1D2420] text-[#E2E8F0] border border-[#38463E]'
                      }`}
                    >
                      {mins === 60 ? '1h' : `${mins}m`}
                    </button>
                  ))}
                </div>
              </div>

              {/* Notificar no Relógio ao Atualizar o Canvas */}
              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Notificar no Relógio ao Atualizar o Canvas
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Ativa ou desativa a notificação gerada pelo app do relógio quando o Canvas muda automaticamente
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

              {/* Notificar Compromisso da Agenda Faltando 15 Minutos (Opcional) */}
              <div className="flex items-center justify-between gap-4 pt-2.5 border-t border-[#232925]">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Notificar Compromisso Faltando 15 Minutos (Opcional)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Quando faltar 15 minutos para o compromisso, o Canvas troca para Agenda e exibe notificação opcional no relógio
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
            </div>

            {/* SEÇÃO 3: SINCRONIZAÇÃO DE AGENDA & BARRA DE PROGRESSO */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                3. Sincronização de Agenda e Barra de Progresso
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Calendários do Telefone
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Envia compromissos selecionados para o banco autônomo do relógio (troca o Canvas faltando 15 min)
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
                  Início da Barra de Progresso do Evento (0% → 100% até o início)
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

            {/* SEÇÃO 4: CLIMA E NASCER/PÔR DO SOL (LOCALIZAÇÃO UNIFICADA) */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                4. Clima e Nascer/Pôr do Sol (Localização Unificada)
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Busca Automática de Clima e Sol pela Localização
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Atualização fixa unificada:{' '}
                    <strong className="text-sky-300">
                      📍{' '}
                      {formatCompactWeatherLocation(
                        config.weatherLocationName ?? 'São Paulo, SP'
                      )}{' '}
                      ({config.weatherUpdatedHHMM ?? '09:00'})
                    </strong>{' '}
                    • Sol:{' '}
                    <strong className="text-amber-300 font-mono">
                      {config.solarSunriseHHMM ?? '05:27'} →{' '}
                      {config.solarSunsetHHMM ?? '17:40'}
                    </strong>
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
                onClick={() => {
                  const locationPresets = [
                    { loc: 'São Paulo, SP', rise: '05:27', set: '17:40' },
                    { loc: 'São Luís, Maranhão', rise: '05:51', set: '17:58' },
                    { loc: 'Curitiba, Paraná', rise: '05:38', set: '18:52' },
                    {
                      loc: 'São Bernardo do Campo, São Paulo',
                      rise: '05:26',
                      set: '17:41',
                    },
                    {
                      loc: 'Governador Valadares, Minas Gerais',
                      rise: '05:14',
                      set: '17:32',
                    },
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
                    weatherLocationName: formatCompactWeatherLocation(
                      nextPreset.loc
                    ),
                    weatherUpdatedHHMM: formatHHMM(referenceTimeMs),
                    solarSunriseHHMM: nextPreset.rise,
                    solarSunsetHHMM: nextPreset.set,
                  });
                  onTriggerManualSync();
                }}
                className="w-full py-2 px-4 rounded-2xl border border-[#3E5245] bg-[#1E2621] hover:bg-[#27322B] text-xs font-semibold text-sky-300 flex items-center justify-center gap-2 transition-colors cursor-pointer"
              >
                <CloudRain className="w-4 h-4 text-sky-400" />
                <span>
                  Atualizar Clima, Sol e Localização Agora (
                  {formatCompactWeatherLocation(
                    config.weatherLocationName ?? 'São Paulo, SP'
                  )}
                  )
                </span>
              </button>

              <div className="space-y-1.5 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200 block">
                  Intervalo de Atualização de Clima e Sol (Telefone)
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
                    Serviço Stand-Alone de Clima e Sol no Relógio (1h)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Permite que o relógio busque o clima e calcule o nascer/pôr do sol juntos pela própria localização
                  </div>
                </div>
                <ModernOneUiSwitch
                  checked={config.watchStandaloneWeatherEnabled ?? false}
                  ariaLabel="Serviço Stand-Alone de Clima e Sol no Relógio"
                  onChange={() => {
                    const next = !(
                      config.watchStandaloneWeatherEnabled ?? false
                    );
                    onUpdateConfig({
                      watchStandaloneWeatherEnabled: next,
                      watchStandaloneSolarEnabled: next,
                    });
                  }}
                />
              </div>
            </div>

            {/* SEÇÃO 5: MONITORAMENTO DE SONO (HEALTH CONNECT / SAMSUNG HEALTH) */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                5. Monitoramento de Sono (Health Connect / Samsung Health)
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Sincronizar Dados de Sono (Health Connect)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Lê estágios do sono (Leve, Profundo, REM, Acordado) via Health Connect ou Samsung Health ({config.sleepBedtimeHHMM ?? '23:18'} → {config.sleepWakeHHMM ?? '07:00'} • {config.sleepScore ?? 86} pts)
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
            </div>

            {/* SEÇÃO 6: SERVIÇO EM SEGUNDO PLANO */}
            <div className="rounded-[24px] bg-[#151816] border border-[#252B27] p-4 space-y-2.5 shadow-lg">
              <div className="text-[11px] font-bold tracking-wider uppercase text-emerald-400">
                6. Serviço em Segundo Plano
              </div>

              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-xs font-bold text-slate-100">
                    Acesso a Notificações Ativo
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Permite detectar gatilhos dos apps de clima e resumos matinais de sono
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
            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-4 space-y-3 text-xs text-slate-300 leading-relaxed shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="text-sm font-bold text-white">
                Ícones dos APKs &amp; Preview Oficial da Watch Face
              </div>
              <div className="grid grid-cols-2 gap-3 items-center pt-1">
                <div className="bg-[#0E110F] border border-[#252D28] rounded-2xl p-3 flex items-center gap-3">
                  <svg
                    viewBox="0 0 108 108"
                    className="w-12 h-12 shrink-0 rounded-full border border-slate-700"
                    aria-label="Ícone Vetorial ChronoWear (:mobile e :wear)"
                  >
                    <circle cx="54" cy="54" r="50" fill="#090D14" />
                    <circle
                      cx="54"
                      cy="54"
                      r="46"
                      fill="none"
                      stroke="#1E293B"
                      strokeWidth="3.5"
                    />
                    <path
                      d="M15,36 C11,47 11,61 15,72"
                      fill="none"
                      stroke="#F59E0B"
                      strokeWidth="4.5"
                      strokeLinecap="round"
                    />
                    <path
                      d="M93,36 C97,47 97,61 93,72"
                      fill="none"
                      stroke="#4ADE80"
                      strokeWidth="4.5"
                      strokeLinecap="round"
                    />
                    <rect
                      x="39"
                      y="17"
                      width="30"
                      height="6"
                      rx="3"
                      fill="#F5C438"
                    />
                    <rect
                      x="16"
                      y="33"
                      width="76"
                      height="42"
                      rx="8"
                      fill="#0F172A"
                      stroke="#334155"
                      strokeWidth="2"
                    />
                    <line
                      x1="22"
                      y1="58"
                      x2="86"
                      y2="58"
                      stroke="#60A5FA"
                      strokeWidth="2.2"
                      strokeLinecap="round"
                    />
                    <path
                      d="M22,66 C30,66 34,58 38,58 C45,42 63,42 70,58 C74,58 78,66 86,66"
                      fill="none"
                      stroke="#F8FAFC"
                      strokeWidth="2.8"
                      strokeLinecap="round"
                    />
                    <circle cx="54" cy="47" r="5" fill="#FACC15" />
                    <path
                      d="M38,91 C48,94 60,94 70,91"
                      fill="none"
                      stroke="#67E8F9"
                      strokeWidth="5"
                      strokeLinecap="round"
                    />
                  </svg>
                  <div className="min-w-0">
                    <div className="text-xs font-bold text-white truncate">
                      APKs :mobile &amp; :wear
                    </div>
                    <div className="text-[10px] text-emerald-400 font-mono truncate">
                      @drawable/ic_launcher_chronowear
                    </div>
                  </div>
                </div>

                <div className="bg-[#0E110F] border border-[#252D28] rounded-2xl p-3 flex items-center gap-3">
                  <img
                    src="/assets/aistudio/Screenshot_20261107_095843_One_UI_Watch_Home_0c7022c2.png"
                    alt="Preview do Seletor e Ícone do APK :watchface"
                    className="w-12 h-12 shrink-0 rounded-full border border-amber-400/50 object-cover"
                  />
                  <div className="min-w-0">
                    <div className="text-xs font-bold text-white truncate">
                      Seletor &amp; APK :watchface
                    </div>
                    <div className="text-[10px] text-amber-300 font-mono truncate">
                      @drawable/preview (PNG Anexo)
                    </div>
                  </div>
                </div>
              </div>
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
