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
import { RefreshCw, Plus, Trash2, Calendar, Sliders, Check } from 'lucide-react';

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
  const [activeSubTab, setActiveSubTab] = useState<'calendars' | 'events' | 'sync'>('calendars');
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
    { value: 7, label: '1 semana (7d)' },
  ];

  const progressWindowOptions: { value: ProgressWindowMinutes; label: string }[] = [
    { value: 30, label: '30 min antes' },
    { value: 60, label: '1 hora antes' },
    { value: 120, label: '2 horas antes' },
    { value: 180, label: '3 horas antes' },
  ];

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full">
      {/* Header of Smartphone APK */}
      <div className="flex items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-amber-400 tracking-wide">
            MÓDULO :mobile · APK SMARTPHONE
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            ChronoWear Companion (Android)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
             Último envio Data Layer: <span className="font-mono tabular-nums text-slate-200">{formatTimeHHMM(lastSyncTimeMs)}</span> · {syncedEventsCount} eventos no pacote
          </p>
        </div>

        <button
          type="button"
          onClick={onTriggerManualSync}
          disabled={!isBluetoothConnected}
          className={`px-3.5 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition-colors whitespace-nowrap shrink-0 ${
            isBluetoothConnected
              ? 'bg-amber-500 text-slate-950 hover:bg-amber-400 cursor-pointer'
              : 'bg-slate-800 text-slate-500 cursor-not-allowed'
          }`}
          title={
            isBluetoothConnected
              ? 'Disparar CalendarSyncWorker agora'
              : 'Bluetooth desconectado — Relógio operando em modo autônomo'
          }
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Sincronizar Agora</span>
        </button>
      </div>

      {/* Sub-navigation inside phone app */}
      <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-xl my-4 border border-slate-800/80">
        <button
          type="button"
          onClick={() => setActiveSubTab('calendars')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            activeSubTab === 'calendars'
              ? 'bg-slate-800 text-amber-400 shadow-xs'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Calendar className="w-3.5 h-3.5" />
          <span>Calendários ({calendars.filter((c) => c.enabled).length})</span>
        </button>
        <button
          type="button"
          onClick={() => setActiveSubTab('sync')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            activeSubTab === 'sync'
              ? 'bg-slate-800 text-amber-400 shadow-xs'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Sliders className="w-3.5 h-3.5" />
          <span>Horizonte & Barra</span>
        </button>
        <button
          type="button"
          onClick={() => setActiveSubTab('events')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            activeSubTab === 'events'
              ? 'bg-slate-800 text-amber-400 shadow-xs'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Plus className="w-3.5 h-3.5" />
          <span>Agenda ({events.length})</span>
        </button>
      </div>

      {/* Tab 1: Calendars & Background Sync Rules */}
      {activeSubTab === 'calendars' && (
        <div className="space-y-5 flex-1 flex flex-col justify-between">
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-slate-300">
                1. Selecionar Calendários e Listas de Tarefas (CalendarContract)
              </h3>
              <span className="text-xs text-slate-400 font-mono">
                 Filtro ativo no Worker
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
                        className="w-3 h-3 rounded-sm shrink-0"
                        style={{ backgroundColor: cal.color }}
                      />
                      <div className="min-w-0">
                        <div className="text-sm font-medium text-slate-100 truncate">
                          {cal.name}
                        </div>
                        <div className="text-xs text-slate-400 truncate">
                          {cal.account} · <span className="font-mono tabular-nums">{count}</span> eventos na fila
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

          {/* Quick Preview of Horizon & Progress Window */}
          <div className="pt-4 border-t border-slate-800 space-y-3">
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Janela da Barra de Progresso no Relógio:</span>
              <span className="font-mono font-semibold text-amber-400">
                {config.progressWindowMinutes === 60
                  ? '1 hora antes (60 min)'
                  : config.progressWindowMinutes === 120
                  ? '2 horas antes (120 min)'
                  : `${config.progressWindowMinutes} min antes`}
              </span>
            </div>
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Cache Autônomo enviando para o Relógio:</span>
              <span className="font-mono font-semibold text-sky-400">
                Próximos {config.horizonDays} dias ({syncedEventsCount} eventos)
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Tab 2: Horizon & Progress Bar Configuration */}
      {activeSubTab === 'sync' && (
        <div className="space-y-5 flex-1">
          <div>
            <label className="block text-xs font-semibold text-slate-200 mb-1">
              Janela da Barra de Progresso Automática (Complication RangedValue)
            </label>
            <p className="text-xs text-slate-400 mb-3">
              Define com quanta antecedência a barra de progresso da Watch Face começa a encher até o início do próximo evento.
            </p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {progressWindowOptions.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => onUpdateConfig({ progressWindowMinutes: opt.value })}
                  className={`py-2.5 px-3 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap ${
                    config.progressWindowMinutes === opt.value
                      ? 'bg-amber-500/15 border-amber-500 text-amber-300 font-semibold'
                      : 'bg-slate-950 border-slate-800 text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="pt-4 border-t border-slate-800">
            <label className="block text-xs font-semibold text-slate-200 mb-1">
              Horizonte de Cache Autônomo Offline no Relógio
            </label>
            <p className="text-xs text-slate-400 mb-3">
              Mesmo sem conexão com o smartphone, o relógio guarda os eventos futuros deste período em banco Room local e avança a fila sozinho.
            </p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {horizonOptions.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => onUpdateConfig({ horizonDays: opt.value })}
                  className={`py-2.5 px-3 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap ${
                    config.horizonDays === opt.value
                      ? 'bg-sky-500/15 border-sky-500 text-sky-300 font-semibold'
                      : 'bg-slate-950 border-slate-800 text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="pt-4 border-t border-slate-800">
            <label className="block text-xs font-semibold text-slate-200 mb-2">
              Frequência do WorkManager em Segundo Plano
            </label>
            <div className="grid grid-cols-3 gap-2">
              {([15, 30, 60] as const).map((mins) => (
                <button
                  key={mins}
                  type="button"
                  onClick={() => onUpdateConfig({ syncIntervalMinutes: mins })}
                  className={`py-2 px-3 rounded-lg text-xs font-mono border transition-colors ${
                    config.syncIntervalMinutes === mins
                      ? 'bg-slate-800 border-slate-600 text-slate-100 font-semibold'
                      : 'bg-slate-950 border-slate-800 text-slate-400 hover:text-slate-200'
                  }`}
                >
                  A cada {mins} min
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Tab 3: Events Manager & Injector */}
      {activeSubTab === 'events' && (
        <div className="space-y-4 flex-1 flex flex-col">
          <form onSubmit={handleCreateEvent} className="p-3.5 bg-slate-950 border border-slate-800 rounded-xl space-y-3">
            <div className="text-xs font-semibold text-slate-200">
              Adicionar Evento de Teste no Smartphone
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <div>
                <label htmlFor="ev-title" className="block text-[11px] text-slate-400 mb-1">
                  Título do Evento / Tarefa
                </label>
                <input
                  id="ev-title"
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Ex: Revisão de Sprint"
                  className="w-full px-3 py-1.5 text-xs bg-slate-900 border border-slate-700 rounded-lg text-slate-100 focus:outline-none focus:border-amber-500"
                />
              </div>
              <div>
                <label htmlFor="ev-cal" className="block text-[11px] text-slate-400 mb-1">
                  Calendário de Origem
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
                  Começa daqui a (min)
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
                className="py-1.5 px-3 bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs rounded-lg transition-colors cursor-pointer"
              >
                + Inserir Evento
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
                    className="p-1.5 text-slate-500 hover:text-rose-400 transition-colors"
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
