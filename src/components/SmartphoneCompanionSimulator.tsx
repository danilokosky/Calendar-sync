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
  RefreshCw,
  Plus,
  Trash2,
  Calendar,
  Sliders,
  Check,
  ShieldCheck,
  LayoutDashboard,
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
  onAddEvent,
  onDeleteEvent,
  onTriggerManualSync,
}) => {
  const [activeSubTab, setActiveSubTab] = useState<'painel' | 'calendarios' | 'configuracoes'>('configuracoes');
  const [permCalendarGranted, setPermCalendarGranted] = useState(true);
  const [permNotificationGranted, setPermNotificationGranted] = useState(true);
  const [permBatteryUnrestricted, setPermBatteryUnrestricted] = useState(true);
  const [backgroundServiceEnabled, setBackgroundServiceEnabled] = useState(true);
  const [debounceSeconds, setDebounceSeconds] = useState<2 | 5 | 15 | 30>(5);

  const [newTitle, setNewTitle] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newCalId, setNewCalId] = useState(calendars[0]?.id || 'cal-work');
  const [offsetMinutes, setOffsetMinutes] = useState<number>(35);
  const [durationMinutes, setDurationMinutes] = useState<number>(45);
  const [formError, setFormError] = useState('');

  const handleCreateEvent = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) {
      setFormError('Informe o título do evento ou tarefa.');
      return;
    }
    setFormError('');
    const startEpochMs = referenceTimeMs + offsetMinutes * 60 * 1000;
    const endEpochMs = startEpochMs + durationMinutes * 60 * 1000;

    onAddEvent({
      calendarId: newCalId,
      title: newTitle.trim(),
      location: newLocation.trim() || 'Remoto / Google Meet',
      startEpochMs,
      endEpochMs,
    });
    setNewTitle('');
    setNewLocation('');
  };

  const horizonOptions: { value: SyncHorizonDays; label: string }[] = [
    { value: 2, label: '2 dias' },
    { value: 3, label: '3 dias' },
    { value: 5, label: '5 dias' },
    { value: 7, label: '7 dias' },
  ];

  const progressWindowOptions: { value: ProgressWindowMinutes; label: string }[] = [
    { value: 30, label: '30 min' },
    { value: 60, label: '1 hora' },
    { value: 120, label: '2 horas' },
    { value: 180, label: '3 horas' },
  ];

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Header of Smartphone APK */}
      <div>
        <div className="flex items-center justify-between gap-4 pb-4 border-b border-slate-800">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-mono text-amber-400 tracking-wide">
                MÓDULO :mobile · APK SMARTPHONE
              </span>
              <span
                className={`text-[10px] font-mono px-2 py-0.5 rounded border ${
                  backgroundServiceEnabled && permCalendarGranted
                    ? 'bg-emerald-500/10 border-emerald-500/40 text-emerald-300'
                    : 'bg-amber-500/10 border-amber-500/40 text-amber-300'
                }`}
              >
                {backgroundServiceEnabled && permCalendarGranted ? '2º PLANO ATIVO' : '2º PLANO INATIVO'}
              </span>
            </div>
            <h2 className="text-lg font-bold text-slate-100 mt-0.5">
              ChronoWear Studio (Android)
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Última leitura: <span className="font-mono tabular-nums text-slate-200">{formatTimeHHMM(lastSyncTimeMs)}</span> · {syncedEventsCount} compromissos ({config.autoSyncOnCalendarChange ? `Periódica ${config.syncIntervalMinutes}m + Ao alterar` : `Periódica ${config.syncIntervalMinutes}m`})
            </p>
          </div>

          <button
            type="button"
            onClick={onTriggerManualSync}
            disabled={!isBluetoothConnected || !permCalendarGranted}
            className={`px-3.5 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition-colors whitespace-nowrap shrink-0 ${
              isBluetoothConnected && permCalendarGranted
                ? 'bg-amber-500 text-slate-950 hover:bg-amber-400 cursor-pointer'
                : 'bg-slate-800 text-slate-500 cursor-not-allowed'
            }`}
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Ler Agenda Agora</span>
          </button>
        </div>

        {/* 3-Tab Bottom-Style Navigation (Painel, Calendários, Configurações) */}
        <div className="grid grid-cols-3 gap-1.5 p-1 bg-slate-950 rounded-xl my-4 border border-slate-800/80">
          <button
            type="button"
            onClick={() => setActiveSubTab('painel')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              activeSubTab === 'painel'
                ? 'bg-amber-500 text-slate-950 font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <LayoutDashboard className="w-3.5 h-3.5" />
            <span>1. Painel ({events.length})</span>
          </button>
          <button
            type="button"
            onClick={() => setActiveSubTab('calendarios')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              activeSubTab === 'calendarios'
                ? 'bg-amber-500 text-slate-950 font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Calendar className="w-3.5 h-3.5" />
            <span>2. Calendários ({calendars.filter((c) => c.enabled).length})</span>
          </button>
          <button
            type="button"
            onClick={() => setActiveSubTab('configuracoes')}
            className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
              activeSubTab === 'configuracoes'
                ? 'bg-amber-500 text-slate-950 font-semibold'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Sliders className="w-3.5 h-3.5" />
            <span>3. Configurações</span>
          </button>
        </div>
      </div>

      {/* TAB 3: CONFIGURAÇÕES (PERMISSÕES PELO USUÁRIO, SERVIÇO 2º PLANO, MODO DE LEITURA E FREQUÊNCIA) */}
      {activeSubTab === 'configuracoes' && (
        <div className="space-y-4 flex-1 overflow-y-auto pr-1">
          {/* Card 1: Permissões Controladas pelo Usuário */}
          <div className="p-3.5 bg-slate-950/90 border border-slate-800 rounded-xl space-y-2.5">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-mono text-amber-400 font-semibold">
                1. PERMISSÕES (CONCEDIDAS APENAS PELO USUÁRIO)
              </span>
              <ShieldCheck className="w-4 h-4 text-amber-400" />
            </div>

            <div className="divide-y divide-slate-800/80 text-xs">
              <div className="py-2 flex items-center justify-between gap-2">
                <div>
                  <div className="font-semibold text-slate-200">
                    Permissão para Ler o Calendário (READ_CALENDAR)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Autoriza ler os calendários e compromissos via CalendarContract.Instances
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setPermCalendarGranted((v) => !v)}
                  className={`px-3 py-1.5 rounded-lg text-[11px] font-mono font-semibold shrink-0 cursor-pointer transition-colors ${
                    permCalendarGranted
                      ? 'bg-emerald-500/15 border border-emerald-500/40 text-emerald-300'
                      : 'bg-amber-500 text-slate-950'
                  }`}
                >
                  {permCalendarGranted ? 'Concedida' : 'Conceder'}
                </button>
              </div>

              <div className="py-2 flex items-center justify-between gap-2">
                <div>
                  <div className="font-semibold text-slate-200">
                    Permissão para Serviço em 2º Plano (Sem Restrição de Bateria)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + FOREGROUND_SERVICE_DATA_SYNC
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setPermBatteryUnrestricted((v) => !v)}
                  className={`px-3 py-1.5 rounded-lg text-[11px] font-mono font-semibold shrink-0 cursor-pointer transition-colors ${
                    permBatteryUnrestricted
                      ? 'bg-emerald-500/15 border border-emerald-500/40 text-emerald-300'
                      : 'bg-sky-400 text-slate-950'
                  }`}
                >
                  {permBatteryUnrestricted ? 'Sem Restrição' : 'Autorizar 2º Plano'}
                </button>
              </div>

              <div className="pt-2 flex items-center justify-between gap-2">
                <div>
                  <div className="font-semibold text-slate-200">
                    Notificação do Serviço Persistente (POST_NOTIFICATIONS)
                  </div>
                  <div className="text-[11px] text-slate-400">
                    Exibe o status da última leitura e botão rápido na barra de notificações
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setPermNotificationGranted((v) => !v)}
                  className={`px-3 py-1.5 rounded-lg text-[11px] font-mono font-semibold shrink-0 cursor-pointer transition-colors ${
                    permNotificationGranted
                      ? 'bg-emerald-500/15 border border-emerald-500/40 text-emerald-300'
                      : 'bg-amber-500 text-slate-950'
                  }`}
                >
                  {permNotificationGranted ? 'Concedida' : 'Permitir'}
                </button>
              </div>
            </div>
          </div>

          {/* Card 2: Switch do Serviço em Segundo Plano & Leitura Automática ao Alterar Compromissos */}
          <div className="p-3.5 bg-slate-950/90 border border-slate-800 rounded-xl space-y-3">
            <div className="text-[11px] font-mono text-sky-400 font-semibold">
              2. SERVIÇO EM 2º PLANO, MODO DE LEITURA E FREQUÊNCIA
            </div>

            {/* Switch Principal do Serviço em Segundo Plano */}
            <label className="flex items-center justify-between gap-3 cursor-pointer py-1">
              <div>
                <div className="text-xs font-semibold text-slate-100">
                  Switch de Ativação do Serviço em Segundo Plano
                </div>
                <div className="text-[11px] text-slate-400">
                  Mantém o ForegroundService e o WorkManager ativos em segundo plano
                </div>
              </div>
              <input
                type="checkbox"
                checked={backgroundServiceEnabled}
                onChange={() => setBackgroundServiceEnabled((v) => !v)}
                className="sr-only"
              />
              <div
                className={`w-10 h-5 rounded-full transition-colors flex items-center px-0.5 shrink-0 ${
                  backgroundServiceEnabled ? 'bg-emerald-500 justify-end' : 'bg-slate-800 justify-start'
                }`}
              >
                <div className="w-4 h-4 rounded-full bg-slate-950" />
              </div>
            </label>

            {/* Switch: Ler Compromissos quando houver alteração na Agenda */}
            <label className="flex items-center justify-between gap-3 cursor-pointer py-1 border-t border-slate-800/80 pt-2.5">
              <div>
                <div className="text-xs font-semibold text-slate-100">
                  Ler compromissos automaticamente ao alterar a Agenda
                </div>
                <div className="text-[11px] text-slate-400">
                  Escuta ContentObserver (CalendarContract.Events) e ACTION_PROVIDER_CHANGED: criar, editar ou apagar evento dispara nova leitura na hora
                </div>
              </div>
              <input
                type="checkbox"
                checked={config.autoSyncOnCalendarChange}
                onChange={() =>
                  onUpdateConfig({ autoSyncOnCalendarChange: !config.autoSyncOnCalendarChange })
                }
                className="sr-only"
              />
              <div
                className={`w-10 h-5 rounded-full transition-colors flex items-center px-0.5 shrink-0 ${
                  config.autoSyncOnCalendarChange ? 'bg-amber-500 justify-end' : 'bg-slate-800 justify-start'
                }`}
              >
                <div className="w-4 h-4 rounded-full bg-slate-950" />
              </div>
            </label>

            {/* Tempo de Debounce após alteração + Frequência Periódica */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
              <div>
                <div className="text-[11px] font-semibold text-slate-300 mb-1.5">
                  Frequência da Leitura Periódica:
                </div>
                <div className="grid grid-cols-3 gap-1.5">
                  {([15, 30, 60] as const).map((mins) => (
                    <button
                      key={mins}
                      type="button"
                      onClick={() => onUpdateConfig({ syncIntervalMinutes: mins })}
                      className={`py-1.5 px-2 rounded-lg text-[11px] font-mono border transition-colors cursor-pointer ${
                        config.syncIntervalMinutes === mins
                          ? 'bg-amber-500/20 border-amber-500 text-amber-300 font-semibold'
                          : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {mins} min
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="text-[11px] font-semibold text-slate-300 mb-1.5">
                  Espera após Alterar Evento (Debounce):
                </div>
                <div className="grid grid-cols-4 gap-1">
                  {([2, 5, 15, 30] as const).map((sec) => (
                    <button
                      key={sec}
                      type="button"
                      onClick={() => setDebounceSeconds(sec)}
                      className={`py-1.5 px-1.5 rounded-lg text-[11px] font-mono border transition-colors cursor-pointer ${
                        debounceSeconds === sec
                          ? 'bg-sky-500/20 border-sky-500 text-sky-300 font-semibold'
                          : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {sec}s
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* Card 3: Janela da Barra de Progresso & Horizonte Autônomo */}
          <div className="p-3.5 bg-slate-950/90 border border-slate-800 rounded-xl space-y-2.5">
            <div className="text-[11px] font-mono text-emerald-400 font-semibold">
              3. PARÂMETROS DA WATCH FACE &amp; CACHE AUTÔNOMO
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <div className="text-[11px] text-slate-300 font-semibold mb-1.5">
                  Barra de Progresso (Antes do Evento):
                </div>
                <div className="grid grid-cols-4 gap-1">
                  {progressWindowOptions.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => onUpdateConfig({ progressWindowMinutes: opt.value })}
                      className={`py-1.5 px-1.5 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${
                        config.progressWindowMinutes === opt.value
                          ? 'bg-amber-500/20 border-amber-500 text-amber-300 font-semibold'
                          : 'bg-slate-900 border-slate-800 text-slate-400'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="text-[11px] text-slate-300 font-semibold mb-1.5">
                  Horizonte Autônomo Offline:
                </div>
                <div className="grid grid-cols-4 gap-1">
                  {horizonOptions.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => onUpdateConfig({ horizonDays: opt.value })}
                      className={`py-1.5 px-1.5 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${
                        config.horizonDays === opt.value
                          ? 'bg-emerald-500/20 border-emerald-500 text-emerald-300 font-semibold'
                          : 'bg-slate-900 border-slate-800 text-slate-400'
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

      {/* TAB 2: SELEÇÃO DE CALENDÁRIOS PELO USUÁRIO */}
      {activeSubTab === 'calendarios' && (
        <div className="space-y-4 flex-1 flex flex-col justify-between">
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-slate-200">
                Escolha quais Calendários do Smartphone serão lidos e sincronizados
              </h3>
              <span className="text-xs text-amber-400 font-mono">
                {calendars.filter((c) => c.enabled).length} de {calendars.length} ativos
              </span>
            </div>

            <div className="divide-y divide-slate-800/70 border border-slate-800 rounded-xl overflow-hidden bg-slate-950/60">
              {calendars.map((cal) => {
                const count = events.filter((e) => e.calendarId === cal.id).length;
                return (
                  <label
                    key={cal.id}
                    className="flex items-center justify-between px-4 py-3.5 hover:bg-slate-900/60 transition-colors cursor-pointer"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <span
                        className="w-3 h-3 rounded-full shrink-0"
                        style={{ backgroundColor: cal.color }}
                      />
                      <div className="min-w-0">
                        <div className="text-sm font-medium text-slate-100 truncate">
                          {cal.name}
                        </div>
                        <div className="text-xs text-slate-400 truncate">
                          {cal.account} · <span className="font-mono tabular-nums">{count}</span> compromissos no horizonte
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-3 shrink-0">
                      <input
                        type="checkbox"
                        checked={cal.enabled}
                        onChange={() => onToggleCalendar(cal.id)}
                        aria-label={`Sincronizar calendário ${cal.name}`}
                        className="sr-only"
                      />
                      <div
                        className={`w-9 h-5 rounded-full transition-colors flex items-center px-0.5 ${
                          cal.enabled ? 'bg-amber-500 justify-end' : 'bg-slate-800 justify-start'
                        }`}
                      >
                        <div className="w-4 h-4 rounded-full bg-slate-950 flex items-center justify-center">
                          {cal.enabled && <Check className="w-2.5 h-2.5 text-amber-400" />}
                        </div>
                      </div>
                    </div>
                  </label>
                );
              })}
            </div>
          </div>

          <div className="p-3 bg-slate-950/80 border border-slate-800 rounded-xl text-xs text-slate-400">
            <strong>Como a leitura é feita:</strong> O app consulta <code className="text-slate-200">CalendarContract.Instances</code> expandindo eventos recorrentes nos próximos <strong>{config.horizonDays} dias</strong> apenas para os calendários ativados acima.
          </div>
        </div>
      )}

      {/* TAB 1: PAINEL & TESTE DE ALTERAÇÃO DE COMPROMISSOS */}
      {activeSubTab === 'painel' && (
        <div className="space-y-4 flex-1 flex flex-col">
          <form onSubmit={handleCreateEvent} className="p-3.5 bg-slate-950 border border-slate-800 rounded-xl space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-200">
                Simular Novo Compromisso na Agenda (Dispara ContentObserver)
              </span>
              <span className="text-[10px] font-mono text-amber-400">
                {config.autoSyncOnCalendarChange ? `Auto-Sync ON (${debounceSeconds}s)` : 'Auto-Sync OFF'}
              </span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <div>
                <label htmlFor="ev-title" className="block text-[11px] text-slate-400 mb-1">
                  Título do Compromisso
                </label>
                <input
                  id="ev-title"
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Ex: Reunião de Arquitetura"
                  className="w-full px-3 py-1.5 text-xs bg-slate-900 border border-slate-700 rounded-lg text-slate-100 focus:outline-none focus:border-amber-500"
                />
              </div>
              <div>
                <label htmlFor="ev-cal" className="block text-[11px] text-slate-400 mb-1">
                  Calendário
                </label>
                <select
                  id="ev-cal"
                  value={newCalId}
                  onChange={(e) => setNewCalId(e.target.value)}
                  className="w-full px-3 py-1.5 text-xs bg-slate-900 border border-slate-700 rounded-lg text-slate-100 focus:outline-none focus:border-amber-500"
                >
                  {calendars.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2.5 items-end">
              <div>
                <label htmlFor="ev-offset" className="block text-[11px] text-slate-400 mb-1">
                  Inicia em (min)
                </label>
                <input
                  id="ev-offset"
                  type="number"
                  min={5}
                  max={10080}
                  value={offsetMinutes}
                  onChange={(e) => setOffsetMinutes(Number(e.target.value))}
                  className="w-full px-3 py-1.5 text-xs font-mono bg-slate-900 border border-slate-700 rounded-lg text-slate-100"
                />
              </div>
              <div>
                <label htmlFor="ev-duration" className="block text-[11px] text-slate-400 mb-1">
                  Duração (min)
                </label>
                <input
                  id="ev-duration"
                  type="number"
                  min={10}
                  max={480}
                  value={durationMinutes}
                  onChange={(e) => setDurationMinutes(Number(e.target.value))}
                  className="w-full px-3 py-1.5 text-xs font-mono bg-slate-900 border border-slate-700 rounded-lg text-slate-100"
                />
              </div>
              <button
                type="submit"
                className="py-1.5 px-3 bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs rounded-lg transition-colors cursor-pointer flex items-center justify-center gap-1"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Criar Evento</span>
              </button>
            </div>
            {formError && <p className="text-xs text-rose-400">{formError}</p>}
          </form>

          <div className="space-y-1.5 max-h-56 overflow-y-auto pr-1">
            {events.map((ev) => {
              const cal = calendars.find((c) => c.id === ev.calendarId);
              return (
                <div
                  key={ev.id}
                  className="flex items-center justify-between px-3 py-2 rounded-lg bg-slate-950/70 border border-slate-800/80 text-xs"
                >
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span
                      className="w-2 h-2 rounded-full shrink-0"
                      style={{ backgroundColor: cal?.color || '#94a3b8' }}
                    />
                    <div className="min-w-0">
                      <div className="font-medium text-slate-200 truncate">{ev.title}</div>
                      <div className="text-[11px] text-slate-400 font-mono tabular-nums">
                        {formatDayRelative(ev.startEpochMs, referenceTimeMs)} · {formatTimeHHMM(ev.startEpochMs)} – {formatTimeHHMM(ev.endEpochMs)} · {cal?.name}
                      </div>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => onDeleteEvent(ev.id)}
                    aria-label={`Remover evento ${ev.title}`}
                    className="p-1.5 text-slate-500 hover:text-rose-400 transition-colors cursor-pointer"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
