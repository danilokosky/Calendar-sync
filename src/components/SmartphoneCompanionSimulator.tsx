import React, { useState } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  SyncHorizonDays,
  ProgressWindowMinutes,
  formatTimeHHMM,
  formatDayRelative,
} from '../utils/calendarSyncEngine';
import {
  Bluetooth,
  Calendar,
  Settings,
  Home,
  Info,
  Watch,
  Check,
  X,
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
  onAddEvent: (newEvent: Omit<CalendarEventItem, 'id'>) => void;
  onDeleteEvent: (id: string) => void;
  onTriggerManualSync: () => void;
}

export const SmartphoneCompanionSimulator: React.FC<SmartphoneCompanionSimulatorProps> = ({
  calendars,
  events,
  config,
  referenceTimeMs,
  lastSyncTimeMs,
  syncedEventsCount,
  isBluetoothConnected,
  onToggleCalendar,
  onUpdateConfig,
  onTriggerManualSync,
}) => {
  const [activeBottomTab, setActiveBottomTab] = useState<'home' | 'settings' | 'about'>('settings');
  const [showSyncedCalendarsModal, setShowSyncedCalendarsModal] = useState(false);

  // Toggles idênticos ao Amoled Watchfaces
  const [backgroundServiceEnabled, setBackgroundServiceEnabled] = useState(true);
  const [notificationsSyncEnabled, setNotificationsSyncEnabled] = useState(true);
  const [mediaPlaybackSyncEnabled, setMediaPlaybackSyncEnabled] = useState(false);
  const [calendarEventsSyncEnabled, setCalendarEventsSyncEnabled] = useState(true);
  const [crashReportsEnabled, setCrashReportsEnabled] = useState(false);

  const selectedCalendarsCount = calendars.filter((c) => c.enabled).length;

  const horizonOptions: { value: SyncHorizonDays; label: string }[] = [
    { value: 2, label: '2 dias' },
    { value: 3, label: '3 dias' },
    { value: 5, label: '5 dias' },
    { value: 7, label: '7 dias' },
  ];

  const progressWindowOptions: { value: ProgressWindowMinutes; label: string }[] = [
    { value: 30, label: '30m' },
    { value: 60, label: '1 hora' },
    { value: 120, label: '2 horas' },
    { value: 180, label: '3 horas' },
  ];

  // Switch no estilo Material 3 / Amoled Watchfaces (Trilho claro azulado quando ligado + círculo roxo/escuro)
  const renderAmoledSwitch = (checked: boolean, onChange: () => void, label: string) => (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onChange}
      className={`w-13 h-7 rounded-full transition-colors flex items-center px-1 shrink-0 cursor-pointer border ${
        checked
          ? 'bg-[#BAE6FD] border-[#BAE6FD] justify-end'
          : 'bg-[#2A2D32] border-[#525866] justify-start'
      }`}
    >
      <span
        className={`w-5 h-5 rounded-full transition-transform ${
          checked ? 'bg-[#311B61]' : 'bg-[#8E95A5]'
        }`}
      />
    </button>
  );

  return (
    <div
      className="rounded-[32px] border border-[#2C352E] p-5 sm:p-6 flex flex-col h-full justify-between relative overflow-hidden shadow-2xl"
      style={{
        background: 'linear-gradient(180deg, #3D351B 0%, #16231B 28%, #0B0E0C 100%)',
      }}
    >
      {/* TOP BAR ESTILO SAMSUNG HEALTH */}
      <div>
        <div className="flex items-center justify-between gap-3 px-1 pb-3">
          <h2 className="text-xl font-bold text-white tracking-tight">
            {activeBottomTab === 'settings'
              ? 'Settings'
              : activeBottomTab === 'about'
              ? 'About ChronoWear'
              : 'ChronoWear Sync'}
          </h2>
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-mono px-2.5 py-1 rounded-full bg-[#232D26] border border-[#38473C] text-[#A7F3D0]">
              APK :mobile
            </span>
          </div>
        </div>

        {/* Barra de Pílulas de Categoria estilo Samsung Health */}
        <div className="grid grid-cols-4 gap-1 p-1.5 rounded-full bg-[#252C25]/90 border border-[#384238] mb-4">
          <button
            type="button"
            onClick={() => setActiveBottomTab('home')}
            className={`py-2 px-3 rounded-full text-xs font-medium transition-colors cursor-pointer ${
              activeBottomTab === 'home'
                ? 'bg-[#525B52] text-white font-semibold shadow-xs'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            ⊞ Início
          </button>
          <button
            type="button"
            onClick={() => setActiveBottomTab('settings')}
            className={`py-2 px-3 rounded-full text-xs font-medium transition-colors cursor-pointer ${
              activeBottomTab === 'settings'
                ? 'bg-[#525B52] text-white font-semibold shadow-xs'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            ⚙ Settings
          </button>
          <button
            type="button"
            onClick={() => setShowSyncedCalendarsModal(true)}
            className="py-2 px-3 rounded-full text-xs font-medium text-slate-300 hover:text-white transition-colors cursor-pointer truncate"
          >
            📅 Agendas ({selectedCalendarsCount})
          </button>
          <button
            type="button"
            onClick={() => setActiveBottomTab('about')}
            className={`py-2 px-3 rounded-full text-xs font-medium transition-colors cursor-pointer ${
              activeBottomTab === 'about'
                ? 'bg-[#525B52] text-white font-semibold shadow-xs'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            ⓘ Leitura
          </button>
        </div>

        {/* Status Bluetooth idêntico ao print do Amoled Watchfaces */}
        <div className="flex items-center justify-center gap-2 text-xs sm:text-sm text-slate-200 py-1.5 mb-3">
          <Bluetooth className="w-4 h-4 text-[#BAE6FD]" />
          <span>
            {isBluetoothConnected
              ? 'Connected • Galaxy Watch Ultra (ZNDA)'
              : `Autonomous Cache (${config.horizonDays}d) • Galaxy Watch Ultra`}
          </span>
        </div>
      </div>

      {/* =====================================================================
          TELA 1: INÍCIO (Estilo Samsung Health + Amoled Watchfaces Home)
         ===================================================================== */}
      {activeBottomTab === 'home' && (
        <div className="space-y-4 flex-1 flex flex-col justify-between my-1">
          {/* Mockup Circular do Relógio no Topo (igual à print 2: Phone Battery Complication) */}
          <div className="flex flex-col items-center">
            <div className="w-52 h-52 rounded-full bg-[#111513] border-[8px] border-[#2A322D] shadow-inner flex flex-col items-center justify-center p-4 space-y-2">
              <div className="w-full rounded-2xl bg-[#27312B] border border-[#3B493F] px-3 py-2">
                <div className="text-xs font-bold text-white truncate">
                  Event Timer ({config.progressWindowMinutes}m)
                </div>
                <div className="text-[10px] text-[#A7F3D0] truncate">
                  Countdown &amp; Progress Complication
                </div>
              </div>
              <div className="w-full rounded-2xl bg-[#2D3831] border border-[#47554B] px-3 py-2">
                <div className="text-xs font-bold text-white truncate">
                  Next 3 Calendar Events
                </div>
                <div className="text-[10px] text-slate-300 truncate">
                  {syncedEventsCount} compromissos ({config.horizonDays}d cache)
                </div>
              </div>
            </div>
          </div>

          {/* Card Welcome! / Sua agenda sob controle (Fusão Print 1 + Print 2) */}
          <div className="rounded-[26px] bg-[#1C241E]/95 border border-[#2F3D33] p-5 space-y-2.5">
            <h3 className="text-xl font-bold text-white">Welcome!</h3>
            <p className="text-xs sm:text-sm text-slate-300 leading-relaxed">
              Este companion app lê os compromissos dos calendários escolhidos por você e envia os próximos eventos + temporizador de progresso ({config.progressWindowMinutes} min) para o relógio.
            </p>
            <div className="pt-1 flex items-center justify-between gap-3">
              <div>
                <div className="text-xs text-slate-400 font-semibold">
                  App is installed on your wear device(s):
                </div>
                <div className="text-sm font-bold text-[#BAE6FD] mt-0.5">
                  Galaxy Watch Ultra (ZNDA) • Último envio {formatTimeHHMM(lastSyncTimeMs)}
                </div>
              </div>
            </div>
          </div>

          {/* Card estilo "Pontuação energia" do Samsung Health */}
          <div className="rounded-[26px] bg-[#141715] border border-[#262B28] p-4 flex items-center justify-between gap-4">
            <div>
              <div className="text-xs text-slate-400">Eventos Sincronizados</div>
              <div className="text-3xl font-bold text-white font-mono tabular-nums mt-0.5">
                {syncedEventsCount}
              </div>
              <div className="text-xs font-semibold text-[#60A5FA] mt-1">
                Horizonte {config.horizonDays} dias
              </div>
            </div>
            <div className="text-right space-y-1 text-xs text-slate-300">
              <div>Leitura periódica: <strong>{config.syncIntervalMinutes} min</strong></div>
              <div>Ao alterar agenda: <strong>{config.autoSyncOnCalendarChange ? 'Ativo' : 'Inativo'}</strong></div>
              <div>Calendários ativos: <strong>{selectedCalendarsCount}</strong></div>
            </div>
          </div>
        </div>
      )}

      {/* =====================================================================
          TELA 2: SETTINGS (Idêntica à print 3 do Amoled Watchfaces)
         ===================================================================== */}
      {activeBottomTab === 'settings' && (
        <div className="space-y-3.5 flex-1 overflow-y-auto pr-1">
          {/* Card Principal de Switches (Estilo Amoled Watchfaces Settings) */}
          <div className="rounded-[26px] bg-[#1A1C1E] border border-[#2C3034] p-5 space-y-4">
            {/* 1. Background Service */}
            <div className="flex items-center justify-between gap-4">
              <div>
                <div className="text-base font-bold text-white">Background Service</div>
                <div className="text-xs text-slate-400">
                  Important - Background &amp; Notifications Access required
                </div>
              </div>
              {renderAmoledSwitch(
                backgroundServiceEnabled,
                () => setBackgroundServiceEnabled((v) => !v),
                'Background Service'
              )}
            </div>

            {/* 2. Notifications Sync */}
            <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-800/70">
              <div>
                <div className="text-base font-bold text-white">Notifications Sync</div>
                <div className="text-xs text-slate-400">
                  Only for Phone Notifications &amp; Service Status
                </div>
              </div>
              {renderAmoledSwitch(
                notificationsSyncEnabled,
                () => setNotificationsSyncEnabled((v) => !v),
                'Notifications Sync'
              )}
            </div>

            {/* 3. Media Playback Sync */}
            <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-800/70">
              <div>
                <div className="text-base font-bold text-white">Media Playback Sync</div>
                <div className="text-xs text-slate-400">Now Playing Complication support</div>
              </div>
              {renderAmoledSwitch(
                mediaPlaybackSyncEnabled,
                () => setMediaPlaybackSyncEnabled((v) => !v),
                'Media Playback Sync'
              )}
            </div>

            {/* 4. Calendar Events Sync + Botão Pílula "Synced calendars" */}
            <div className="space-y-3 pt-1 border-t border-slate-800/70">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-base font-bold text-white">Calendar Events Sync</div>
                  <div className="text-xs text-slate-400">
                    Upcoming Event &amp; Event Timer Complication ({selectedCalendarsCount} agendas)
                  </div>
                </div>
                {renderAmoledSwitch(
                  calendarEventsSyncEnabled,
                  () => setCalendarEventsSyncEnabled((v) => !v),
                  'Calendar Events Sync'
                )}
              </div>

              {/* Botão pílula com borda fina idêntico ao "Synced calendars" da print 3 */}
              <div>
                <button
                  type="button"
                  onClick={() => setShowSyncedCalendarsModal(true)}
                  className="px-5 py-2.5 rounded-full bg-[#1A1C1E] hover:bg-[#25282C] border border-[#4B5563] text-xs sm:text-sm font-semibold text-slate-100 flex items-center gap-2.5 transition-colors cursor-pointer"
                >
                  <Calendar className="w-4 h-4 text-[#BAE6FD]" />
                  <span>Synced calendars ({selectedCalendarsCount})</span>
                </button>
              </div>
            </div>

            {/* 5. Instant Calendar Change Sync (ContentObserver quando há alteração nos compromissos) */}
            <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-800/70">
              <div>
                <div className="text-base font-bold text-white">Instant Calendar Change Sync</div>
                <div className="text-xs text-slate-400">
                  Relê os compromissos automaticamente ao criar, editar ou apagar evento
                </div>
              </div>
              {renderAmoledSwitch(
                config.autoSyncOnCalendarChange,
                () =>
                  onUpdateConfig({
                    autoSyncOnCalendarChange: !config.autoSyncOnCalendarChange,
                  }),
                'Instant Calendar Change Sync'
              )}
            </div>

            {/* 6. Crash Reports */}
            <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-800/70">
              <div>
                <div className="text-base font-bold text-white">Crash Reports</div>
                <div className="text-xs text-slate-400">Help us identify and fix issues</div>
              </div>
              {renderAmoledSwitch(
                crashReportsEnabled,
                () => setCrashReportsEnabled((v) => !v),
                'Crash Reports'
              )}
            </div>
          </div>

          {/* Card Complementar: Frequência da Leitura, Barra de Progresso (1h/2h) e Horizonte Autônomo */}
          <div className="rounded-[26px] bg-[#1A1C1E] border border-[#2C3034] p-4 space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
              <div>
                <div className="text-[11px] font-semibold text-slate-300 mb-1">
                  Frequência de Leitura:
                </div>
                <div className="grid grid-cols-3 gap-1">
                  {([15, 30, 60] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() => onUpdateConfig({ syncIntervalMinutes: mins })}
                      className={`py-1.5 px-2 rounded-full text-[11px] font-mono transition-colors cursor-pointer ${
                        config.syncIntervalMinutes === mins
                          ? 'bg-[#BAE6FD] text-slate-950 font-bold'
                          : 'bg-[#262B28] text-slate-300'
                      }`}
                    >
                      {mins}m
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="text-[11px] font-semibold text-slate-300 mb-1">
                  Barra Progresso:
                </div>
                <div className="grid grid-cols-4 gap-1">
                  {progressWindowOptions.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => onUpdateConfig({ progressWindowMinutes: opt.value })}
                      className={`py-1.5 px-1 rounded-full text-[10px] font-medium transition-colors cursor-pointer ${
                        config.progressWindowMinutes === opt.value
                          ? 'bg-[#BAE6FD] text-slate-950 font-bold'
                          : 'bg-[#262B28] text-slate-300'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="text-[11px] font-semibold text-slate-300 mb-1">
                  Cache Autônomo:
                </div>
                <div className="grid grid-cols-4 gap-1">
                  {horizonOptions.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => onUpdateConfig({ horizonDays: opt.value })}
                      className={`py-1.5 px-1 rounded-full text-[10px] font-medium transition-colors cursor-pointer ${
                        config.horizonDays === opt.value
                          ? 'bg-[#4ADE80] text-slate-950 font-bold'
                          : 'bg-[#262B28] text-slate-300'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* =====================================================================
          TELA 3: ABOUT / EXPLICAÇÃO DA LEITURA E EVENTOS
         ===================================================================== */}
      {activeBottomTab === 'about' && (
        <div className="rounded-[26px] bg-[#1A1C1E] border border-[#2C3034] p-5 space-y-3 flex-1 text-xs sm:text-sm text-slate-300 leading-relaxed">
          <h3 className="text-base font-bold text-white">
            Como funciona a leitura do calendário e detecção de alterações
          </h3>
          <p>
            • <strong>Permissões apenas pelo usuário:</strong> O aplicativo só requisita <code className="text-[#BAE6FD]">READ_CALENDAR</code> ou <code className="text-[#BAE6FD]">POST_NOTIFICATIONS</code> quando você ativa as chaves correspondentes em <strong>Settings</strong>.
          </p>
          <p>
            • <strong>Leitura de compromissos:</strong> A consulta é feita em <code className="text-[#BAE6FD]">CalendarContract.Instances</code> expandindo eventos recorrentes nos próximos <strong>{config.horizonDays} dias</strong> apenas para as agendas marcadas em <strong>Synced calendars</strong>.
          </p>
          <p>
            • <strong>Alteração nos compromissos:</strong> Com <strong>Instant Calendar Change Sync</strong> ativo, um <code className="text-[#BAE6FD]">ContentObserver</code> e o <code className="text-[#BAE6FD]">CalendarChangeReceiver</code> (<code className="text-[#BAE6FD]">ACTION_PROVIDER_CHANGED</code>) detectam qualquer criação, edição ou exclusão na agenda e disparam a leitura imediatamente.
          </p>
          <div className="pt-2 border-t border-slate-800 space-y-1">
            <div className="text-xs font-semibold text-white">Próximos eventos na fila atual:</div>
            {events.slice(0, 4).map((ev) => (
              <div key={ev.id} className="text-xs text-slate-400 font-mono">
                • {formatDayRelative(ev.startEpochMs, referenceTimeMs)} {formatTimeHHMM(ev.startEpochMs)} — {ev.title}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* =====================================================================
          MODAL "SYNCED CALENDARS" (Disparado pelo botão pílula Synced calendars)
         ===================================================================== */}
      {showSyncedCalendarsModal && (
        <div className="absolute inset-0 z-20 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="w-full max-w-md rounded-[28px] bg-[#1A1C1E] border border-[#374151] p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div>
                <h3 className="text-base font-bold text-white">Synced calendars</h3>
                <p className="text-xs text-slate-400">
                  Selecione quais calendários do smartphone serão sincronizados
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowSyncedCalendarsModal(false)}
                className="p-1.5 rounded-full bg-slate-800 text-slate-300 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-2 max-h-60 overflow-y-auto">
              {calendars.map((cal) => {
                const count = events.filter((e) => e.calendarId === cal.id).length;
                return (
                  <label
                    key={cal.id}
                    className="flex items-center justify-between p-3 rounded-2xl bg-[#111315] border border-slate-800/90 cursor-pointer hover:border-slate-700"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <span
                        className="w-3.5 h-3.5 rounded-full shrink-0"
                        style={{ backgroundColor: cal.color }}
                      />
                      <div className="min-w-0">
                        <div className="text-xs font-bold text-white truncate">{cal.name}</div>
                        <div className="text-[11px] text-slate-400 truncate">
                          {cal.account} • {count} eventos
                        </div>
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={cal.enabled}
                      onChange={() => onToggleCalendar(cal.id)}
                      className="sr-only"
                    />
                    <div
                      className={`w-6 h-6 rounded-lg flex items-center justify-center border ${
                        cal.enabled
                          ? 'bg-[#BAE6FD] border-[#BAE6FD] text-slate-950'
                          : 'border-slate-600 text-transparent'
                      }`}
                    >
                      <Check className="w-4 h-4" />
                    </div>
                  </label>
                );
              })}
            </div>

            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => {
                  setShowSyncedCalendarsModal(false);
                  onTriggerManualSync();
                }}
                className="px-5 py-2 rounded-full bg-[#BAE6FD] text-slate-950 text-xs font-bold cursor-pointer"
              >
                Salvar e Sincronizar
              </button>
            </div>
          </div>
        </div>
      )}

      {/* =====================================================================
          DOCK FLUTUANTE INFERIOR ESTILO SAMSUNG HEALTH + BOTÃO CIRCULAR LATERAL
         ===================================================================== */}
      <div className="pt-4 flex items-center gap-3">
        <div className="flex-1 grid grid-cols-3 gap-1 p-1.5 rounded-full bg-[#232A25]/95 border border-[#37413A] shadow-lg">
          <button
            type="button"
            onClick={() => setActiveBottomTab('home')}
            className={`py-2 px-3 rounded-full flex flex-col items-center justify-center gap-0.5 transition-colors cursor-pointer ${
              activeBottomTab === 'home'
                ? 'bg-[#3F4A41] text-white font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Home className="w-4 h-4" />
            <span className="text-[11px]">Home</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveBottomTab('settings')}
            className={`py-2 px-3 rounded-full flex flex-col items-center justify-center gap-0.5 transition-colors cursor-pointer ${
              activeBottomTab === 'settings'
                ? 'bg-[#3F4A41] text-white font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Settings className="w-4 h-4" />
            <span className="text-[11px]">Settings</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveBottomTab('about')}
            className={`py-2 px-3 rounded-full flex flex-col items-center justify-center gap-0.5 transition-colors cursor-pointer ${
              activeBottomTab === 'about'
                ? 'bg-[#3F4A41] text-white font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Info className="w-4 h-4" />
            <span className="text-[11px]">About</span>
          </button>
        </div>

        {/* Botão Circular de Sincronização Rápida com Ícone de Relógio (como nas prints 1 e 2) */}
        <button
          type="button"
          onClick={onTriggerManualSync}
          title="Sincronizar Agora com o Smartwatch"
          className="w-13 h-13 rounded-2xl bg-[#BAE6FD] hover:bg-[#7DD3FC] text-slate-950 flex items-center justify-center shadow-lg transition-colors cursor-pointer shrink-0"
        >
          <Watch className="w-6 h-6" />
        </button>
      </div>
    </div>
  );
};
