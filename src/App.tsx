/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { useState, useMemo } from 'react';
import {
  CalendarSource,
  CalendarEventItem,
  SyncConfiguration,
  WatchCacheState,
  buildSyncPayload,
  evaluateWatchAutonomousState,
} from './utils/calendarSyncEngine';
import { SmartphoneCompanionSimulator } from './components/SmartphoneCompanionSimulator';
import { WearOsSmartwatchSimulator } from './components/WearOsSmartwatchSimulator';
import { AndroidCodeRepositoryExplorer } from './components/AndroidCodeRepositoryExplorer';
import { downloadAndroidRepoZip } from './utils/exportProjectZip';
import { Download } from 'lucide-react';

// Base simulated reference time: 09:00 local time
const INITIAL_BASE_TIME_MS = new Date(2026, 10, 5, 9, 0, 0).getTime();

const INITIAL_CALENDARS: CalendarSource[] = [
  {
    id: 'cal-work',
    name: 'Engenharia & Sprints (Google Workspace)',
    account: 'danilokosky@gmail.com',
    color: '#f59e0b',
    enabled: true,
  },
  {
    id: 'cal-tasks',
    name: 'Tarefas & Lembretes Diários',
    account: 'danilokosky@gmail.com',
    color: '#38bdf8',
    enabled: true,
  },
  {
    id: 'cal-personal',
    name: 'Pessoal & Família',
    account: 'danilokosky@gmail.com',
    color: '#10b981',
    enabled: true,
  },
  {
    id: 'cal-holidays',
    name: 'Feriados Nacionais (Opcional)',
    account: 'pt-br.brazilian#holiday',
    color: '#a855f7',
    enabled: false,
  },
];

const INITIAL_EVENTS: CalendarEventItem[] = [
  {
    id: 'ev-101',
    calendarId: 'cal-work',
    title: 'Daily Standup & Sync Wear OS',
    location: 'Google Meet',
    startEpochMs: INITIAL_BASE_TIME_MS + 36 * 60 * 1000, // 09:36 (+36 min -> 40% of 60m progress window)
    endEpochMs: INITIAL_BASE_TIME_MS + 66 * 60 * 1000,
  },
  {
    id: 'ev-102',
    calendarId: 'cal-tasks',
    title: 'Revisar XML Watch Face Format',
    location: 'Android Studio',
    startEpochMs: INITIAL_BASE_TIME_MS + 135 * 60 * 1000, // 11:15 (+2h 15m)
    endEpochMs: INITIAL_BASE_TIME_MS + 180 * 60 * 1000,
  },
  {
    id: 'ev-103',
    calendarId: 'cal-work',
    title: 'Alinhamento de Complications & Tiles',
    location: 'Sala 03',
    startEpochMs: INITIAL_BASE_TIME_MS + 270 * 60 * 1000, // 13:30 (+4h 30m)
    endEpochMs: INITIAL_BASE_TIME_MS + 330 * 60 * 1000,
  },
  {
    id: 'ev-104',
    calendarId: 'cal-personal',
    title: 'Treino de Corrida 5km',
    location: 'Parque Ibirapuera',
    startEpochMs: INITIAL_BASE_TIME_MS + 540 * 60 * 1000, // 18:00 (+9h)
    endEpochMs: INITIAL_BASE_TIME_MS + 600 * 60 * 1000,
  },
  {
    id: 'ev-105',
    calendarId: 'cal-work',
    title: 'Publicar Release APK no GitHub (Amanhã)',
    location: 'GitHub Actions',
    startEpochMs: INITIAL_BASE_TIME_MS + 25 * 60 * 60 * 1000, // Tomorrow 10:00
    endEpochMs: INITIAL_BASE_TIME_MS + 26 * 60 * 60 * 1000,
  },
  {
    id: 'ev-106',
    calendarId: 'cal-tasks',
    title: 'Planejamento da Próxima Semana (+3 dias)',
    location: 'Remoto',
    startEpochMs: INITIAL_BASE_TIME_MS + 74 * 60 * 60 * 1000, // +3 days 11:00
    endEpochMs: INITIAL_BASE_TIME_MS + 75 * 60 * 60 * 1000,
  },
];

export default function App() {
  const [calendars, setCalendars] = useState<CalendarSource[]>(INITIAL_CALENDARS);
  const [phoneEvents, setPhoneEvents] = useState<CalendarEventItem[]>(INITIAL_EVENTS);
  const [config, setConfig] = useState<SyncConfiguration>({
    horizonDays: 5,
    progressWindowMinutes: 60, // 1 hora antes por padrão
    syncIntervalMinutes: 15,
    autoSyncOnCalendarChange: true,
  });

  // Watch clock can be fast-forwarded to test autonomous offline behavior
  const [currentWatchTimeMs, setCurrentWatchTimeMs] = useState<number>(INITIAL_BASE_TIME_MS);
  const [isBluetoothConnected, setIsBluetoothConnected] = useState<boolean>(true);
  const [lastSyncTimeMs, setLastSyncTimeMs] = useState<number>(INITIAL_BASE_TIME_MS);

  // Snapshot stored on the Watch Room DB when Bluetooth was last synced
  const [offlineFrozenCache, setOfflineFrozenCache] = useState<CalendarEventItem[]>(() =>
    buildSyncPayload(INITIAL_EVENTS, INITIAL_CALENDARS, INITIAL_BASE_TIME_MS, 5)
  );
  const [offlineFrozenHorizonDays, setOfflineFrozenHorizonDays] = useState<SyncConfiguration['horizonDays']>(5);
  const [offlineFrozenProgressWindow, setOfflineFrozenProgressWindow] = useState<SyncConfiguration['progressWindowMinutes']>(60);

  // Live synced payload when Bluetooth is connected
  const liveSyncedEvents = useMemo(
    () => buildSyncPayload(phoneEvents, calendars, lastSyncTimeMs, config.horizonDays),
    [phoneEvents, calendars, lastSyncTimeMs, config.horizonDays]
  );

  // Effective Watch Cache State: if connected, reflects latest phone sync; if disconnected, uses frozen autonomous Room cache
  const watchCache: WatchCacheState = useMemo(() => {
    if (isBluetoothConnected) {
      return {
        cachedEvents: liveSyncedEvents,
        lastSyncEpochMs: lastSyncTimeMs,
        syncedHorizonDays: config.horizonDays,
        progressWindowMinutes: config.progressWindowMinutes,
        isBluetoothConnected: true,
      };
    }
    return {
      cachedEvents: offlineFrozenCache,
      lastSyncEpochMs: lastSyncTimeMs,
      syncedHorizonDays: offlineFrozenHorizonDays,
      progressWindowMinutes: offlineFrozenProgressWindow,
      isBluetoothConnected: false,
    };
  }, [
    isBluetoothConnected,
    liveSyncedEvents,
    lastSyncTimeMs,
    config.horizonDays,
    config.progressWindowMinutes,
    offlineFrozenCache,
    offlineFrozenHorizonDays,
    offlineFrozenProgressWindow,
  ]);

  // Evaluate watch face state at `currentWatchTimeMs`
  const watchState = useMemo(
    () => evaluateWatchAutonomousState(watchCache, currentWatchTimeMs),
    [watchCache, currentWatchTimeMs]
  );

  const handleToggleCalendar = (id: string) => {
    setCalendars((prev) =>
      prev.map((c) => (c.id === id ? { ...c, enabled: !c.enabled } : c))
    );
  };

  const handleUpdateConfig = (partial: Partial<SyncConfiguration>) => {
    setConfig((prev) => ({ ...prev, ...partial }));
  };

  const handleAddEvent = (newEvent: Omit<CalendarEventItem, 'id'>) => {
    const created: CalendarEventItem = {
      ...newEvent,
      id: `ev-${Date.now()}`,
    };
    setPhoneEvents((prev) =>
      [...prev, created].sort((a, b) => a.startEpochMs - b.startEpochMs)
    );
  };

  const handleDeleteEvent = (id: string) => {
    setPhoneEvents((prev) => prev.filter((e) => e.id !== id));
  };

  const handleTriggerManualSync = () => {
    if (!isBluetoothConnected) return;
    setLastSyncTimeMs(currentWatchTimeMs);
    const freshPayload = buildSyncPayload(
      phoneEvents,
      calendars,
      currentWatchTimeMs,
      config.horizonDays
    );
    setOfflineFrozenCache(freshPayload);
    setOfflineFrozenHorizonDays(config.horizonDays);
    setOfflineFrozenProgressWindow(config.progressWindowMinutes);
  };

  const handleToggleBluetooth = () => {
    if (isBluetoothConnected) {
      // Freeze the current synced cache into the watch's local Room DB before disconnecting
      setOfflineFrozenCache(liveSyncedEvents);
      setOfflineFrozenHorizonDays(config.horizonDays);
      setOfflineFrozenProgressWindow(config.progressWindowMinutes);
      setIsBluetoothConnected(false);
    } else {
      setIsBluetoothConnected(true);
      setLastSyncTimeMs(currentWatchTimeMs);
    }
  };

  const handleAdvanceTimeMinutes = (minutes: number) => {
    setCurrentWatchTimeMs((prev) => prev + minutes * 60 * 1000);
  };

  const handleResetTime = () => {
    setCurrentWatchTimeMs(INITIAL_BASE_TIME_MS);
    setLastSyncTimeMs(INITIAL_BASE_TIME_MS);
  };

  const handleExportZip = () => {
    downloadAndroidRepoZip(config, calendars);
  };

  return (
    <div className="min-h-screen bg-[#090d16] text-slate-100 flex flex-col">
      {/* Top Bar Contract: 3 zones separated by gap-8 */}
      <header className="flex items-center justify-between gap-8 px-6 py-4 border-b border-slate-800/90 bg-slate-950/90 sticky top-0 z-30">
        <a
          href="#"
          className="text-lg font-bold tracking-tight text-slate-100 whitespace-nowrap shrink-0 font-display"
        >
          ChronoWear Studio
        </a>

        <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-400">
          <a
            href="#bancada-simulacao"
            className="hover:text-slate-100 transition-colors whitespace-nowrap shrink-0"
          >
            Bancada Smartphone &amp; Relógio
          </a>
          <a
            href="#arquitetura-wff"
            className="hover:text-slate-100 transition-colors whitespace-nowrap shrink-0"
          >
            Solução Watch Face Format
          </a>
          <a
            href="#codigo-android"
            className="hover:text-slate-100 transition-colors whitespace-nowrap shrink-0"
          >
            Código Kotlin &amp; XML
          </a>
          <a
            href="#guia-github"
            className="hover:text-slate-100 transition-colors whitespace-nowrap shrink-0"
          >
            Fluxo GitHub APK
          </a>
        </nav>

        <div className="flex items-center gap-3 shrink-0">
          <button
            type="button"
            onClick={handleExportZip}
            className="px-4 py-2 text-xs font-semibold text-slate-950 bg-amber-500 rounded-lg hover:bg-amber-400 transition-colors whitespace-nowrap shrink-0 flex items-center gap-2 cursor-pointer"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Exportar Repositório (.ZIP)</span>
          </button>
        </div>
      </header>

      <main className="max-w-7xl w-full mx-auto px-6 py-5 space-y-8 flex-1">
        {/* Intro & Architectural Summary */}
        <section className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 border-b border-slate-800/80 pb-5">
          <div className="max-w-3xl space-y-1">
            <div className="text-xs font-mono text-amber-400">
              PROJETO ANDROID MULTI-MÓDULO · :mobile · :wear · :watchface
            </div>
            <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-50 text-balance">
              Sincronizador de Calendário &amp; Tarefas com Complications, Barra de Progresso e Cache Autônomo
            </h1>
            <p className="text-xs sm:text-sm text-slate-400 leading-relaxed">
              Simule o <strong>APK de Smartphone</strong> e o <strong>APK de Relógio Wear OS</strong>: selecione os calendários, configure a barra de progresso (1h ou 2h antes), desconecte o Bluetooth para testar o modo autônomo de até 1 semana e exporte o repositório para o GitHub.
            </p>
          </div>

          <div className="grid grid-cols-3 gap-4 bg-slate-900 border border-slate-800 rounded-xl px-4 py-3 shrink-0">
            <div>
              <div className="text-[11px] text-slate-400">Próximo Evento</div>
              <div className="text-base font-bold font-mono tabular-nums text-amber-400">
                {watchState.formattedCountdown}
              </div>
            </div>
            <div className="border-l border-slate-800 pl-4">
              <div className="text-[11px] text-slate-400">Barra ({config.progressWindowMinutes}m)</div>
              <div className="text-base font-bold font-mono tabular-nums text-slate-100">
                {Math.round(watchState.progressRatio * 100)}%
              </div>
            </div>
            <div className="border-l border-slate-800 pl-4">
              <div className="text-[11px] text-slate-400">Horizonte Cache</div>
              <div className="text-base font-bold font-mono tabular-nums text-sky-400">
                {watchCache.syncedHorizonDays} dias
              </div>
            </div>
          </div>
        </section>

        {/* Main Interactive Dual-Simulator Workbench */}
        <section id="bancada-simulacao" className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-stretch">
          <div className="lg:col-span-6">
            <SmartphoneCompanionSimulator
              calendars={calendars}
              events={phoneEvents}
              config={config}
              referenceTimeMs={currentWatchTimeMs}
              lastSyncTimeMs={lastSyncTimeMs}
              syncedEventsCount={liveSyncedEvents.length}
              isBluetoothConnected={isBluetoothConnected}
              onToggleCalendar={handleToggleCalendar}
              onUpdateConfig={handleUpdateConfig}
              onAddEvent={handleAddEvent}
              onDeleteEvent={handleDeleteEvent}
              onTriggerManualSync={handleTriggerManualSync}
            />
          </div>

          <div className="lg:col-span-6">
            <WearOsSmartwatchSimulator
              watchState={watchState}
              watchCache={watchCache}
              calendars={calendars}
              currentWatchTimeMs={currentWatchTimeMs}
              onToggleBluetooth={handleToggleBluetooth}
              onAdvanceTimeMinutes={handleAdvanceTimeMinutes}
              onResetTime={handleResetTime}
            />
          </div>
        </section>

        {/* Official Solution to Watch Face Format (WFF) Limitations */}
        <section id="arquitetura-wff" className="bg-slate-900 border border-slate-800 rounded-2xl p-6 sm:p-8 space-y-6">
          <div className="max-w-3xl">
            <h2 className="text-xl font-bold text-slate-100">
              Como Resolvemos as Limitações do Watch Face Format (WFF) de Forma Oficial
            </h2>
            <p className="text-sm text-slate-400 mt-1">
              No Wear OS 4 e Wear OS 5, o Google exige que as Watch Faces usem o formato XML declarativo (WFF), que não permite rodar código Kotlin arbitrário dentro da Watch Face. Nossa arquitetura resolve isso combinando 3 recursos oficiais:
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="bg-slate-950/70 border border-slate-800 rounded-xl p-5 space-y-2">
              <div className="text-xs font-mono text-amber-400">
                01. COMPLICATION SLOT #101 (TOPO)
              </div>
              <h3 className="text-base font-bold text-slate-100">
                Temporizador + Barra RangedValue (1h / 2h)
              </h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Usamos <code className="text-slate-200">RangedValueComplicationData</code> combinado com <code className="text-slate-200">TimeDifferenceComplicationText</code>. O sistema operacional atualiza o contador regressivo sozinho a cada minuto e preenche a barra de 0% a 100% quando entra na janela configurada (1h ou 2h antes do evento).
              </p>
            </div>

            <div className="bg-slate-950/70 border border-slate-800 rounded-xl p-5 space-y-2">
              <div className="text-xs font-mono text-sky-400">
                02. COMPLICATION SLOT #102 + TILE NATIVO
              </div>
              <h3 className="text-base font-bold text-slate-100">
                Janela Grande com os Próximos 3 Eventos
              </h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Em Watch Faces de terceiros, complications comuns costumam truncar textos longos. Por isso criamos nossa própria Watch Face WFF (<code className="text-slate-200">watchface.xml</code>) com um <code className="text-slate-200">PartText</code> de 390×196px (<code className="text-slate-200">maxLines=&quot;4&quot;</code>) e um <strong>Tile dedicado</strong> para exibir sempre os próximos 3 eventos.
              </p>
            </div>

            <div className="bg-slate-950/70 border border-slate-800 rounded-xl p-5 space-y-2">
              <div className="text-xs font-mono text-emerald-400">
                03. OPERAÇÃO AUTÔNOMA OFFLINE (ROOM)
              </div>
              <h3 className="text-base font-bold text-slate-100">
                Horizonte de 2, 3, 5 ou 7 Dias no Relógio
              </h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Em vez de enviar apenas o próximo evento do dia, o smartphone sincroniza a janela inteira de dias futuros. Se o relógio ficar sem Bluetooth, o <code className="text-slate-200">AutonomousCountdownScheduler</code> dispara na hora exata de término de cada evento e promove o próximo da fila localmente.
              </p>
            </div>
          </div>
        </section>

        {/* Full Source Code Explorer */}
        <AndroidCodeRepositoryExplorer onDownloadZip={handleExportZip} />

        {/* GitHub Workflow & APK Installation Guide */}
        <section id="guia-github" className="bg-slate-900 border border-slate-800 rounded-2xl p-6 sm:p-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-center">
            <div className="lg:col-span-8 space-y-2">
              <h2 className="text-lg font-bold text-slate-100">
                Como Publicar no GitHub e Instalar os APKs no Smartphone e no Relógio
              </h2>
              <p className="text-xs sm:text-sm text-slate-400 leading-relaxed">
                Clique em <strong>Exportar Repositório (.ZIP)</strong>, extraia na sua pasta e suba para um repositório no GitHub. O arquivo <code className="text-amber-300">.github/workflows/android-build.yml</code> já está configurado para compilar os 3 APKs na nuvem do GitHub Actions sem precisar configurar o SDK manualmente.
              </p>
              <div className="pt-2 flex flex-wrap items-center gap-4 text-xs font-mono text-slate-300">
                <span>1. git init &amp;&amp; git push origin main</span>
                <span>·</span>
                <span>2. Baixe os APKs na aba Actions</span>
                <span>·</span>
                <span>3. Instale via adb install nos dispositivos</span>
              </div>
            </div>

            <div className="lg:col-span-4 flex lg:justify-end">
              <button
                type="button"
                onClick={handleExportZip}
                className="w-full sm:w-auto px-5 py-3 bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs rounded-xl flex items-center justify-center gap-2 transition-colors cursor-pointer"
              >
                <Download className="w-4 h-4" />
                <span>Baixar Repositório Completo (.ZIP)</span>
              </button>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-slate-800/80 py-6 px-6 text-center text-xs text-slate-400">
        ChronoWear Android &amp; Wear OS Project · Arquitetura Multi-Módulo (:mobile, :wear, :watchface) pronta para GitHub
      </footer>
    </div>
  );
}
