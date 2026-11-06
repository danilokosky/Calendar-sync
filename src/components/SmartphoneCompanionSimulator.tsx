import React, { useState } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
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

  // Estados das chaves de Settings (padrão Amoled Watchfaces + estética Samsung Health)
  const [bgServiceEnabled, setBgServiceEnabled] = useState(true);
  const [notificationsSyncEnabled, setNotificationsSyncEnabled] = useState(true);
  const [mediaPlaybackSyncEnabled, setMediaPlaybackSyncEnabled] = useState(false);
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(true);
  const [calendarPermGranted, setCalendarPermGranted] = useState(true);

  // Modal "Synced calendars"
  const [isSyncedCalendarsDialogOpen, setIsSyncedCalendarsDialogOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newMinutesFromNow, setNewMinutesFromNow] = useState<number>(45);

  const selectedCalendarsCount = calendars.filter((c) => c.enabled).length;
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
              ? '✓ App Wear Instalado'
              : '⚠ App Wear Ausente'}
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
                {/* Mockup Circular do Relógio com os 4 Slots WFF */}
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
                    Sua agenda sincronizada sob controle
                  </div>
                  <p className="text-[11px] text-[#C8D3CC] leading-relaxed">
                    4 slots nativos WFF no relógio: timer regressivo com barra +
                    3 compromissos individuais alinhados ao aro circular.
                  </p>
                </div>
              </div>

              <div className="mt-3 pt-2.5 border-t border-white/10">
                {renderConnectionBanner()}
              </div>
            </div>

            {/* Card Escuro Samsung Health: Status de Instalação & Métricas */}
            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-3 shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="flex items-center justify-between">
                <h3 className="text-base font-bold text-white">Welcome!</h3>
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
                      App is installed on your wear device(s):
                    </div>
                    <div className="text-sm font-bold text-[#6EE7B7]">
                      Galaxy Watch Ultra (ZNDA)
                    </div>
                  </>
                )}
              </div>

              {/* Mini indicadores estilo cards Samsung Health */}
              <div className="pt-3 border-t border-[#242A26] grid grid-cols-2 gap-3">
                <div className="rounded-2xl bg-[#1B201D] p-3">
                  <div className="text-[10px] text-slate-400">
                    Próximo compromisso
                  </div>
                  <div className="text-xs font-bold text-white truncate mt-0.5">
                    {nextEvent ? nextEvent.title : 'Sem eventos'}
                  </div>
                  <div className="text-[11px] font-mono text-amber-400 mt-0.5">
                    {nextEvent ? formatHHMM(nextEvent.startEpochMs) : '--:--'}
                  </div>
                </div>

                <div className="rounded-2xl bg-[#1B201D] p-3">
                  <div className="text-[10px] text-slate-400">
                    Agendas &amp; Janela
                  </div>
                  <div className="text-xs font-bold text-white mt-0.5">
                    {selectedCalendarsCount} calendário(s)
                  </div>
                  <div className="text-[11px] font-mono text-sky-400 mt-0.5">
                    Leitura {config.syncIntervalMinutes}m • Barra {config.progressWindowMinutes}m
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 2: SETTINGS (Amoled Watchfaces + Cards Samsung Health)      */}
        {/* =============================================================== */}
        {activeTab === 'settings' && (
          <div
            key="tab-settings"
            className="space-y-3.5 flex-1 transition-all duration-300 ease-out"
          >
            {renderConnectionBanner()}

            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-4 shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />

              {/* 1. Background Service */}
              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-sm font-bold text-slate-100">
                    Background Service
                  </div>
                  <div className="text-xs text-slate-400">
                    Important - Notifications Access required
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={bgServiceEnabled}
                  aria-label="Background Service"
                  onClick={() => setBgServiceEnabled((v) => !v)}
                  className={`w-12 h-7 rounded-full p-1 transition-all duration-200 active:scale-90 flex items-center cursor-pointer shrink-0 ${
                    bgServiceEnabled
                      ? 'bg-[#BAE6FD]'
                      : 'bg-[#36393F]'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-all duration-200 ${
                      bgServiceEnabled
                        ? 'translate-x-5 bg-[#311B92]'
                        : 'translate-x-0 bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 2. Notifications Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#232925]">
                <div>
                  <div className="text-sm font-bold text-slate-100">
                    Notifications Sync
                  </div>
                  <div className="text-xs text-slate-400">
                    Only for Phone Notifications Complication
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={notificationsSyncEnabled}
                  aria-label="Notifications Sync"
                  onClick={() => setNotificationsSyncEnabled((v) => !v)}
                  className={`w-12 h-7 rounded-full p-1 transition-all duration-200 active:scale-90 flex items-center cursor-pointer shrink-0 ${
                    notificationsSyncEnabled
                      ? 'bg-[#BAE6FD]'
                      : 'bg-[#36393F]'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-all duration-200 ${
                      notificationsSyncEnabled
                        ? 'translate-x-5 bg-[#311B92]'
                        : 'translate-x-0 bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 3. Media Playback Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#232925]">
                <div>
                  <div className="text-sm font-bold text-slate-100">
                    Media Playback Sync
                  </div>
                  <div className="text-xs text-slate-400">
                    Now Playing Complication support
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={mediaPlaybackSyncEnabled}
                  aria-label="Media Playback Sync"
                  onClick={() => setMediaPlaybackSyncEnabled((v) => !v)}
                  className={`w-12 h-7 rounded-full p-1 transition-all duration-200 active:scale-90 flex items-center cursor-pointer shrink-0 ${
                    mediaPlaybackSyncEnabled
                      ? 'bg-[#BAE6FD]'
                      : 'bg-[#36393F]'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-all duration-200 ${
                      mediaPlaybackSyncEnabled
                        ? 'translate-x-5 bg-[#311B92]'
                        : 'translate-x-0 bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 4. Calendar Events Sync + Synced calendars */}
              <div className="space-y-3 pt-2 border-t border-[#232925]">
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <div className="text-sm font-bold text-slate-100">
                      Calendar Events Sync
                    </div>
                    <div className="text-xs text-slate-400">
                      Upcoming Event &amp; Event Timer Complication
                    </div>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={calendarSyncEnabled && calendarPermGranted}
                    aria-label="Calendar Events Sync"
                    onClick={handleToggleCalendarEventsSync}
                    className={`w-12 h-7 rounded-full p-1 transition-all duration-200 active:scale-90 flex items-center cursor-pointer shrink-0 ${
                      calendarSyncEnabled && calendarPermGranted
                        ? 'bg-[#BAE6FD]'
                        : 'bg-[#36393F]'
                    }`}
                  >
                    <span
                      className={`w-5 h-5 rounded-full transition-all duration-200 ${
                        calendarSyncEnabled && calendarPermGranted
                          ? 'translate-x-5 bg-[#311B92]'
                          : 'translate-x-0 bg-[#8E9297]'
                      }`}
                    />
                  </button>
                </div>

                <div>
                  <button
                    type="button"
                    onClick={() => setIsSyncedCalendarsDialogOpen(true)}
                    className="px-5 py-2.5 rounded-full border border-[#4B5850] bg-[#1E2420] hover:bg-[#28312B] active:scale-95 text-xs font-semibold text-slate-100 inline-flex items-center gap-2.5 transition-all duration-150 cursor-pointer"
                  >
                    <Calendar className="w-4 h-4 text-emerald-300" />
                    <span>Synced calendars ({selectedCalendarsCount})</span>
                  </button>
                </div>
              </div>

              {/* 5. Instant Calendar Change Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#232925]">
                <div>
                  <div className="text-sm font-bold text-slate-100">
                    Instant Calendar Change Sync
                  </div>
                  <div className="text-xs text-slate-400">
                    Relê automaticamente se houver alteração nos compromissos
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={config.autoSyncOnCalendarChange}
                  aria-label="Instant Calendar Change Sync"
                  onClick={() =>
                    onUpdateConfig({
                      autoSyncOnCalendarChange: !config.autoSyncOnCalendarChange,
                    })
                  }
                  className={`w-12 h-7 rounded-full p-1 transition-all duration-200 active:scale-90 flex items-center cursor-pointer shrink-0 ${
                    config.autoSyncOnCalendarChange
                      ? 'bg-[#BAE6FD]'
                      : 'bg-[#36393F]'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-all duration-200 ${
                      config.autoSyncOnCalendarChange
                        ? 'translate-x-5 bg-[#311B92]'
                        : 'translate-x-0 bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>
            </div>

            {/* Frequência de Leitura e Janela da Barra com seleção animada */}
            <div className="rounded-[26px] bg-[#151816] border border-[#252B27] p-4 space-y-3 shadow-lg">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-bold text-slate-200">
                  Frequência de Leitura:
                </span>
                <div className="flex items-center gap-1.5">
                  {([15, 30, 60] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ syncIntervalMinutes: mins })
                      }
                      className={`px-3 py-1 rounded-full text-[11px] font-semibold transition-all duration-200 active:scale-90 cursor-pointer ${
                        config.syncIntervalMinutes === mins
                          ? 'bg-[#BAE6FD] text-slate-950 scale-105'
                          : 'bg-[#222824] text-slate-300 hover:bg-[#2C342F]'
                      }`}
                    >
                      {mins}m
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex items-center justify-between gap-2 pt-2 border-t border-[#232925]">
                <span className="text-xs font-bold text-slate-200">
                  Barra Progresso:
                </span>
                <div className="flex items-center gap-1.5">
                  {([30, 60, 120, 180] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() =>
                        onUpdateConfig({ progressWindowMinutes: mins })
                      }
                      className={`px-2.5 py-1 rounded-full text-[11px] font-semibold transition-all duration-200 active:scale-90 cursor-pointer ${
                        config.progressWindowMinutes === mins
                          ? 'bg-[#BAE6FD] text-slate-950 scale-105'
                          : 'bg-[#222824] text-slate-300 hover:bg-[#2C342F]'
                      }`}
                    >
                      {mins === 30 ? '30m' : `${mins / 60}h`}
                    </button>
                  ))}
                </div>
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
            <div className="relative rounded-[26px] bg-[#151816] border border-[#252B27] p-5 space-y-3 text-xs text-slate-300 leading-relaxed shadow-lg">
              <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-[#F97316]" />
              <div className="text-sm font-bold text-white">
                Arquitetura Wear OS &amp; Watch Face Format
              </div>
              <p>
                • <strong>4 Slots WFF Individuais</strong>: A Watch Face usa 4
                ComplicationSlots independentes (Timer + Eventos #1, #2 e #3),
                evitando que o Wear OS junte os compromissos na mesma linha e
                ajustando a largura de cada cartão à curvatura redonda da tela.
              </p>
              <p>
                • <strong>Conexão Positiva Verificada</strong>: O status só
                exibe <code className="text-emerald-300">Connected</code> se o
                relógio estiver conectado via Bluetooth <strong>e</strong> com o
                APK <code className="text-sky-300">:wear</code> instalado.
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
                  className="px-3 py-1.5 rounded-xl bg-[#BAE6FD] text-slate-950 font-bold text-xs flex items-center gap-1 active:scale-95 transition-transform cursor-pointer"
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
      {/* Mantém APENAS as 3 opções: Início, Settings, About (sem relógio)  */}
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
              { id: 'settings', label: 'Settings', Icon: Settings, dot: true },
              { id: 'about', label: 'About', Icon: Info },
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

      {/* Modal Nativo Multi-Escolha "Synced calendars" */}
      {isSyncedCalendarsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20 transition-opacity duration-200">
          <div className="w-full max-w-sm rounded-[28px] bg-[#1A201C] border border-[#344038] p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white">
                Synced calendars
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
                        ? 'bg-[#BAE6FD] text-slate-950'
                        : 'border border-slate-600'
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
                className="px-5 py-2 rounded-full bg-[#BAE6FD] text-slate-950 font-bold text-xs active:scale-95 transition-transform cursor-pointer"
              >
                Salvar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
