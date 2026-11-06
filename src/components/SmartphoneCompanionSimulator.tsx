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

  // Estados das chaves de Settings (padrão Amoled Watchfaces)
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
        <div className="flex items-center justify-center gap-2 py-2 text-xs font-medium text-slate-400">
          <Bluetooth className="w-3.5 h-3.5 text-slate-500" />
          <span>Disconnected • Nenhum relógio Wear OS conectado</span>
        </div>
      );
    }

    if (!isWearAppInstalledOnWatch) {
      return (
        <div className="flex items-center justify-center gap-2 py-2 px-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs font-medium text-amber-300">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0" />
          <span>
            Wearable conectado (Galaxy Watch Ultra) • App NÃO instalado no relógio
          </span>
        </div>
      );
    }

    return (
      <div className="flex items-center justify-center gap-2 py-2 text-xs font-medium text-slate-200">
        <Bluetooth className="w-3.5 h-3.5 text-sky-400" />
        <span>Connected • Galaxy Watch Ultra (ZNDA)</span>
      </div>
    );
  };

  return (
    <div className="relative rounded-[32px] border border-[#252930] bg-[#111315] text-slate-100 shadow-2xl overflow-hidden flex flex-col justify-between min-h-[720px]">
      {/* Conteúdo Principal (Sem barra redundante no topo — navegação exclusiva pelo rodapé de 3 opções) */}
      <div className="px-5 pt-5 pb-4 flex-1 flex flex-col justify-between">
        {/* Barra de teste rápido do estado de instalação no relógio */}
        <div className="flex items-center justify-between gap-2 pb-3 mb-2 border-b border-[#1F2329] text-[11px] text-slate-400">
          <span>Simular estado do App no Relógio:</span>
          <button
            type="button"
            onClick={() => setIsWearAppInstalledOnWatch((prev) => !prev)}
            className={`px-2.5 py-1 rounded-full font-medium transition-colors cursor-pointer ${
              isWearAppInstalledOnWatch
                ? 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/30'
                : 'bg-amber-500/15 text-amber-300 border border-amber-500/30'
            }`}
          >
            {isWearAppInstalledOnWatch
              ? '✓ App Wear Instalado'
              : '⚠ App Wear Não Instalado'}
          </button>
        </div>

        {/* =============================================================== */}
        {/* ABA 1: HOME (Essencial e Informativa, padrão Amoled Watchfaces) */}
        {/* =============================================================== */}
        {activeTab === 'home' && (
          <div className="space-y-4 flex-1 flex flex-col justify-between">
            <h2 className="text-xl font-bold tracking-tight text-white text-center">
              Calendar Sync Complication
            </h2>

            {/* Mockup Circular do Relógio no Topo */}
            <div className="flex flex-col items-center pt-1">
              <div className="relative w-52 h-52 rounded-full bg-[#141718] border-[10px] border-[#2A2F33] shadow-inner flex flex-col items-center justify-center px-5 py-4 gap-2">
                <div className="w-full rounded-2xl bg-[#2B3330] border border-[#3D4844] px-3 py-2 flex items-center gap-2.5">
                  <Calendar className="w-4 h-4 text-sky-300 shrink-0" />
                  <div className="min-w-0">
                    <div className="text-[11px] font-semibold text-white truncate">
                      Upcoming Event
                    </div>
                    <div className="text-[9px] text-slate-300 truncate">
                      Calendar Sync Complication
                    </div>
                  </div>
                </div>

                <div className="w-full rounded-2xl bg-[#2B3330] border border-[#3D4844] px-3 py-2.5 flex items-center gap-2.5">
                  <Calendar className="w-4 h-4 text-emerald-300 shrink-0" />
                  <div className="min-w-0">
                    <div className="text-[11px] font-semibold text-white truncate">
                      Event Timer ({config.progressWindowMinutes}m)
                    </div>
                    <div className="text-[9px] text-slate-300 truncate">
                      {calendarSyncEnabled && calendarPermGranted
                        ? `${syncedEventsCount} evento(s) sincronizado(s)`
                        : 'Calendar Sync Complication'}
                    </div>
                  </div>
                </div>

                {/* Coroa lateral */}
                <div className="absolute -right-3.5 top-1/2 -translate-y-1/2 w-2 h-8 rounded-r-md bg-gradient-to-b from-slate-300 via-slate-500 to-slate-300" />
              </div>

              {renderConnectionBanner()}
            </div>

            {/* Card Welcome! (Limpo, essencial e sem ícone de relógio redundante) */}
            <div className="rounded-[24px] bg-[#1B1D21] border border-[#2A2E35] p-5 space-y-3">
              <h3 className="text-lg font-bold text-white">Welcome!</h3>
              <p className="text-xs text-slate-300 leading-relaxed">
                Este app companion envia seus próximos compromissos e contagem
                regressiva para as Complications do seu relógio Wear OS.
              </p>

              <div className="pt-1 space-y-1">
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
                    <div className="text-xs font-semibold text-slate-200">
                      App is installed on your wear device(s):
                    </div>
                    <div className="text-sm font-bold text-emerald-400">
                      Galaxy Watch Ultra (ZNDA)
                    </div>
                  </>
                )}
              </div>

              <div className="pt-2 border-t border-[#2A2E35] flex items-center justify-between text-xs text-slate-400">
                <span className="truncate">
                  {nextEvent
                    ? `Próximo: ${nextEvent.title} às ${formatHHMM(nextEvent.startEpochMs)}`
                    : 'Sem próximo compromisso'}
                </span>
                <span className="font-mono text-slate-300 shrink-0 ml-2">
                  {selectedCalendarsCount} agenda(s)
                </span>
              </div>
            </div>
          </div>
        )}

        {/* =============================================================== */}
        {/* ABA 2: SETTINGS (Idêntica à print 3 do Amoled Watchfaces)       */}
        {/* =============================================================== */}
        {activeTab === 'settings' && (
          <div className="space-y-3.5 flex-1">
            <h2 className="text-xl font-bold tracking-tight text-white text-center">
              Settings
            </h2>

            {renderConnectionBanner()}

            <div className="rounded-[24px] bg-[#1B1D21] border border-[#2A2E35] p-5 space-y-4">
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
                  className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center cursor-pointer shrink-0 ${
                    bgServiceEnabled
                      ? 'bg-[#BAE6FD] justify-end'
                      : 'bg-[#36393F] justify-start'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-colors ${
                      bgServiceEnabled ? 'bg-[#311B92]' : 'bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 2. Notifications Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#26292E]">
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
                  className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center cursor-pointer shrink-0 ${
                    notificationsSyncEnabled
                      ? 'bg-[#BAE6FD] justify-end'
                      : 'bg-[#36393F] justify-start'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-colors ${
                      notificationsSyncEnabled ? 'bg-[#311B92]' : 'bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 3. Media Playback Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#26292E]">
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
                  className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center cursor-pointer shrink-0 ${
                    mediaPlaybackSyncEnabled
                      ? 'bg-[#BAE6FD] justify-end'
                      : 'bg-[#36393F] justify-start'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-colors ${
                      mediaPlaybackSyncEnabled ? 'bg-[#311B92]' : 'bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>

              {/* 4. Calendar Events Sync + Synced calendars */}
              <div className="space-y-3 pt-2 border-t border-[#26292E]">
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
                    className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center cursor-pointer shrink-0 ${
                      calendarSyncEnabled && calendarPermGranted
                        ? 'bg-[#BAE6FD] justify-end'
                        : 'bg-[#36393F] justify-start'
                    }`}
                  >
                    <span
                      className={`w-5 h-5 rounded-full transition-colors ${
                        calendarSyncEnabled && calendarPermGranted
                          ? 'bg-[#311B92]'
                          : 'bg-[#8E9297]'
                      }`}
                    />
                  </button>
                </div>

                <div>
                  <button
                    type="button"
                    onClick={() => setIsSyncedCalendarsDialogOpen(true)}
                    className="px-5 py-2.5 rounded-full border border-[#4B5563] bg-[#1B1D21] hover:bg-[#252A30] text-xs font-semibold text-slate-100 inline-flex items-center gap-2.5 transition-colors cursor-pointer"
                  >
                    <Calendar className="w-4 h-4 text-slate-300" />
                    <span>Synced calendars ({selectedCalendarsCount})</span>
                  </button>
                </div>
              </div>

              {/* 5. Instant Calendar Change Sync */}
              <div className="flex items-center justify-between gap-4 pt-2 border-t border-[#26292E]">
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
                  className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center cursor-pointer shrink-0 ${
                    config.autoSyncOnCalendarChange
                      ? 'bg-[#BAE6FD] justify-end'
                      : 'bg-[#36393F] justify-start'
                  }`}
                >
                  <span
                    className={`w-5 h-5 rounded-full transition-colors ${
                      config.autoSyncOnCalendarChange
                        ? 'bg-[#311B92]'
                        : 'bg-[#8E9297]'
                    }`}
                  />
                </button>
              </div>
            </div>

            {/* Frequência de Leitura e Janela da Barra */}
            <div className="rounded-[24px] bg-[#1B1D21] border border-[#2A2E35] p-4 space-y-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-bold text-slate-200">
                  Frequência de Leitura:
                </span>
                <div className="flex items-center gap-1.5">
                  {([15, 30, 60] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() => onUpdateConfig({ syncIntervalMinutes: mins })}
                      className={`px-3 py-1 rounded-full text-[11px] font-semibold transition-colors cursor-pointer ${
                        config.syncIntervalMinutes === mins
                          ? 'bg-[#BAE6FD] text-slate-950'
                          : 'bg-[#262A30] text-slate-300 hover:bg-[#31363E]'
                      }`}
                    >
                      {mins}m
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex items-center justify-between gap-2 pt-2 border-t border-[#26292E]">
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
                      className={`px-2.5 py-1 rounded-full text-[11px] font-semibold transition-colors cursor-pointer ${
                        config.progressWindowMinutes === mins
                          ? 'bg-[#BAE6FD] text-slate-950'
                          : 'bg-[#262A30] text-slate-300 hover:bg-[#31363E]'
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
        {/* ABA 3: ABOUT (Essencial e Informativa)                          */}
        {/* =============================================================== */}
        {activeTab === 'about' && (
          <div className="space-y-4 flex-1">
            <h2 className="text-xl font-bold tracking-tight text-white text-center">
              About
            </h2>

            <div className="rounded-[24px] bg-[#1B1D21] border border-[#2A2E35] p-5 space-y-3 text-xs text-slate-300 leading-relaxed">
              <div className="text-sm font-bold text-white">
                Verificação Real de Instalação no Wear OS
              </div>
              <p>
                • <strong>Conexão Positiva Verificada</strong>: O status só exibe{' '}
                <code className="text-emerald-300">Connected</code> se o relógio
                estiver conectado via Bluetooth <strong>e</strong> com o APK da
                versão Wear OS instalado (verificado via{' '}
                <code className="text-sky-300">CapabilityClient</code> +
                handshake). Caso contrário, alerta que há o Wearable conectado,
                mas o app não está instalado no relógio.
              </p>
              <p>
                • <strong>APK Wear Puro</strong>: O módulo{' '}
                <code className="text-amber-300">:wear</code> é compilado
                exclusivamente para Wear OS (
                <code>android.hardware.type.watch required=&quot;true&quot;</code>
                ).
              </p>
            </div>

            {/* Simulador de Alteração na Agenda */}
            <div className="rounded-[24px] bg-[#1B1D21] border border-[#2A2E35] p-5 space-y-3">
              <div className="text-xs font-bold text-white">
                Testar Alteração nos Compromissos
              </div>
              <form onSubmit={handleCreateQuickEvent} className="flex gap-2">
                <input
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Novo evento na agenda..."
                  className="flex-1 bg-[#111315] border border-[#2E343B] rounded-xl px-3 py-1.5 text-xs text-white focus:outline-none focus:border-sky-400"
                />
                <select
                  value={newMinutesFromNow}
                  onChange={(e) => setNewMinutesFromNow(Number(e.target.value))}
                  aria-label="Minutos até o evento"
                  className="bg-[#111315] border border-[#2E343B] rounded-xl px-2 py-1.5 text-xs text-slate-200"
                >
                  <option value={20}>+20m</option>
                  <option value={45}>+45m</option>
                  <option value={90}>+1h30</option>
                </select>
                <button
                  type="submit"
                  className="px-3 py-1.5 rounded-xl bg-[#BAE6FD] text-slate-950 font-bold text-xs flex items-center gap-1 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                  <span>Criar</span>
                </button>
              </form>

              <div className="space-y-1.5 max-h-32 overflow-y-auto pr-1">
                {upcomingEvents.slice(0, 3).map((ev) => (
                  <div
                    key={ev.id}
                    className="flex items-center justify-between bg-[#14171A] border border-[#262A30] rounded-xl px-3 py-1.5 text-xs"
                  >
                    <span className="truncate text-slate-200">
                      <strong className="text-sky-300 font-mono mr-1.5">
                        {formatHHMM(ev.startEpochMs)}
                      </strong>
                      {ev.title}
                    </span>
                    <button
                      type="button"
                      onClick={() => onDeleteEvent(ev.id)}
                      className="text-slate-500 hover:text-rose-400 p-1 cursor-pointer"
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
      {/* RODAPÉ ÚNICO COM APENAS AS 3 OPÇÕES: Home, Settings, About        */}
      {/* (Sem barra superior redundante e sem botão de relógio no rodapé)  */}
      {/* ================================================================= */}
      <div className="bg-[#181C1F] border-t border-[#24292E] px-4 py-2.5 grid grid-cols-3 gap-2">
        <button
          type="button"
          onClick={() => setActiveTab('home')}
          className="flex flex-col items-center justify-center gap-1 py-1 cursor-pointer"
        >
          <div
            className={`px-5 py-1 rounded-full transition-colors ${
              activeTab === 'home'
                ? 'bg-[#153843] text-[#BAE6FD]'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Home className="w-4 h-4" />
          </div>
          <span
            className={`text-[11px] ${
              activeTab === 'home'
                ? 'font-bold text-white'
                : 'font-medium text-slate-400'
            }`}
          >
            Home
          </span>
        </button>

        <button
          type="button"
          onClick={() => setActiveTab('settings')}
          className="flex flex-col items-center justify-center gap-1 py-1 cursor-pointer"
        >
          <div
            className={`px-5 py-1 rounded-full transition-colors ${
              activeTab === 'settings'
                ? 'bg-[#153843] text-[#BAE6FD]'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Settings className="w-4 h-4" />
          </div>
          <span
            className={`text-[11px] ${
              activeTab === 'settings'
                ? 'font-bold text-white'
                : 'font-medium text-slate-400'
            }`}
          >
            Settings
          </span>
        </button>

        <button
          type="button"
          onClick={() => setActiveTab('about')}
          className="flex flex-col items-center justify-center gap-1 py-1 cursor-pointer"
        >
          <div
            className={`px-5 py-1 rounded-full transition-colors ${
              activeTab === 'about'
                ? 'bg-[#153843] text-[#BAE6FD]'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Info className="w-4 h-4" />
          </div>
          <span
            className={`text-[11px] ${
              activeTab === 'about'
                ? 'font-bold text-white'
                : 'font-medium text-slate-400'
            }`}
          >
            About
          </span>
        </button>
      </div>

      {/* Modal Nativo Multi-Escolha "Synced calendars" */}
      {isSyncedCalendarsDialogOpen && (
        <div className="absolute inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-5 z-20">
          <div className="w-full max-w-sm rounded-[26px] bg-[#1F2428] border border-[#37414A] p-5 space-y-4 shadow-2xl">
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
                  className={`w-full flex items-center justify-between gap-3 p-3 rounded-2xl border text-left transition-colors cursor-pointer ${
                    cal.enabled
                      ? 'bg-[#283238] border-[#4B636E]'
                      : 'bg-[#181B1E] border-[#2A2F35] opacity-70'
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
                className="px-4 py-2 rounded-full bg-[#BAE6FD] text-slate-950 font-bold text-xs cursor-pointer"
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
