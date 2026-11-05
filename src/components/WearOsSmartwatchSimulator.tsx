import React, { useState } from 'react';
import {
  ComplicationProgressState,
  WatchCacheState,
  CalendarSource,
  formatTimeHHMM,
  formatDayRelative,
} from '../utils/calendarSyncEngine';
import { Bluetooth, BluetoothOff, FastForward, RotateCcw, Watch, Layers, Database } from 'lucide-react';

interface WearOsSmartwatchSimulatorProps {
  watchState: ComplicationProgressState;
  watchCache: WatchCacheState;
  calendars: CalendarSource[];
  currentWatchTimeMs: number;
  onToggleBluetooth: () => void;
  onAdvanceTimeMinutes: (minutes: number) => void;
  onResetTime: () => void;
}

export const WearOsSmartwatchSimulator: React.FC<WearOsSmartwatchSimulatorProps> = ({
  watchState,
  watchCache,
  calendars,
  currentWatchTimeMs,
  onToggleBluetooth,
  onAdvanceTimeMinutes,
  onResetTime,
}) => {
  const [watchScreenMode, setWatchScreenMode] = useState<'wff' | 'tile' | 'cache'>('wff');

  const progressPercent = Math.round(watchState.progressRatio * 100);

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · WEAR OS 4/5
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Cache Room local: <span className="font-mono tabular-nums text-slate-200">{watchCache.cachedEvents.length}</span> eventos salvos ({watchCache.syncedHorizonDays} dias)
          </p>
        </div>

        {/* Toggle Bluetooth / Offline Autonomous Test */}
        <button
          type="button"
          onClick={onToggleBluetooth}
          className={`px-3.5 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 border transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
            watchCache.isBluetoothConnected
              ? 'bg-emerald-500/15 border-emerald-500/50 text-emerald-300 hover:bg-emerald-500/25'
              : 'bg-amber-500/20 border-amber-500 text-amber-300 hover:bg-amber-500/30'
          }`}
        >
          {watchCache.isBluetoothConnected ? (
            <>
              <Bluetooth className="w-3.5 h-3.5" />
              <span>Conectado ao Smartphone</span>
            </>
          ) : (
            <>
              <BluetoothOff className="w-3.5 h-3.5" />
              <span>Modo Autônomo Offline Ativo</span>
            </>
          )}
        </button>
      </div>

      {/* Watch Mode Switcher */}
      <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-xl my-4 border border-slate-800/80">
        <button
          type="button"
          onClick={() => setWatchScreenMode('wff')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            watchScreenMode === 'wff'
              ? 'bg-slate-800 text-amber-400'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Watch className="w-3.5 h-3.5" />
          <span>Watch Face (WFF)</span>
        </button>
        <button
          type="button"
          onClick={() => setWatchScreenMode('tile')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            watchScreenMode === 'tile'
              ? 'bg-slate-800 text-amber-400'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Layers className="w-3.5 h-3.5" />
          <span>Tile Top 3</span>
        </button>
        <button
          type="button"
          onClick={() => setWatchScreenMode('cache')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap ${
            watchScreenMode === 'cache'
              ? 'bg-slate-800 text-amber-400'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Database className="w-3.5 h-3.5" />
          <span>Cache Room ({watchCache.cachedEvents.length})</span>
        </button>
      </div>

      {/* Center Circular Watch Bezel (450x450 logical representation) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[336px] h-[336px] sm:w-[356px] sm:h-[356px] rounded-full bg-[#070a12] border-[8px] border-slate-800 shadow-2xl flex flex-col items-center justify-between px-7 py-6 select-none overflow-hidden"
        >
          {/* Watch Hardware Top Status & Digital Clock */}
          <div className="text-center w-full">
            <div className="text-[10px] font-mono text-slate-400 tracking-wider flex items-center justify-center gap-1.5">
              <span>{formatDayRelative(currentWatchTimeMs, watchCache.lastSyncEpochMs).toUpperCase()}</span>
              <span>·</span>
              <span>{watchCache.isBluetoothConnected ? 'SYNC OK' : 'AUTÔNOMO OFFLINE'}</span>
            </div>
            <div className="text-3xl font-bold font-mono tabular-nums tracking-tight text-slate-50 leading-none mt-1">
              {formatTimeHHMM(currentWatchTimeMs)}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) with Top Countdown + RangedValue Bar + Large 3-Event Window */}
          {watchScreenMode === 'wff' && (
            <>
              {/* SLOT 101: Top Complication (RANGED_VALUE — Countdown + Progress Bar 1h/2h) */}
              <div className="w-full bg-slate-900/95 border border-slate-800 rounded-xl px-3.5 py-2">
                <div className="flex items-center justify-between text-[11px] font-mono tabular-nums">
                  <span className="text-amber-400 font-semibold truncate">
                    {watchState.hasUpcomingEvent
                      ? `Faltam ${watchState.formattedCountdown}`
                      : 'Sem eventos'}
                  </span>
                  <span className="text-slate-300">
                    {watchState.hasUpcomingEvent
                      ? `Início ${watchState.formattedStartTime}`
                      : '--:--'}
                  </span>
                </div>

                {/* Configurable Progress Bar (e.g., 1h or 2h window before event) */}
                <div className="mt-1.5 w-full h-2 bg-slate-800 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-amber-400 transition-transform duration-150 origin-left"
                    style={{
                      transform: `scaleX(${watchState.progressRatio})`,
                    }}
                  />
                </div>
                <div className="flex items-center justify-between text-[9px] font-mono text-slate-400 mt-1">
                  <span>
                    Janela {watchCache.progressWindowMinutes}m antes
                  </span>
                  <span className="tabular-nums text-amber-300 font-semibold">
                    {progressPercent}%
                  </span>
                </div>
              </div>

              {/* SLOT 102: Large Center Window — Always Next 3 Events */}
              <div className="w-full bg-slate-900/90 border border-slate-800/90 rounded-2xl px-3.5 py-2.5 flex-1 my-1.5 flex flex-col justify-between">
                <div className="flex items-center justify-between text-[10px] font-mono text-slate-400 border-b border-slate-800/80 pb-1">
                  <span>PRÓXIMOS 3 EVENTOS</span>
                  <span>SLOT WFF #102</span>
                </div>

                {watchState.upcomingThree.length === 0 ? (
                  <div className="text-xs text-slate-400 text-center my-auto">
                    Nenhum evento futuro no horizonte de {watchCache.syncedHorizonDays} dias.
                  </div>
                ) : (
                  <div className="space-y-1.5 my-auto">
                    {watchState.upcomingThree.map((ev, index) => {
                      const cal = calendars.find((c) => c.id === ev.calendarId);
                      return (
                        <div
                          key={ev.id}
                          className="flex items-center justify-between gap-2 text-xs leading-tight"
                        >
                          <div className="flex items-center gap-1.5 min-w-0">
                            <span
                              className="w-1.5 h-3.5 rounded-xs shrink-0"
                              style={{ backgroundColor: cal?.color || '#f59e0b' }}
                            />
                            <span className="font-mono tabular-nums text-[11px] text-amber-300 shrink-0">
                              {formatTimeHHMM(ev.startEpochMs)}
                            </span>
                            <span className="text-slate-100 font-medium truncate text-[11px]">
                              {ev.title}
                            </span>
                          </div>
                          <span className="text-[10px] font-mono text-slate-400 shrink-0">
                            #{index + 1}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          )}

          {/* VIEW 2: Wear OS Tile (UpNextThreeEventsTileService) */}
          {watchScreenMode === 'tile' && (
            <div className="w-full flex-1 my-2 flex flex-col justify-between bg-slate-900/60 border border-slate-800 rounded-2xl p-3">
              <div className="text-center">
                <span className="text-[10px] font-mono text-amber-400 font-semibold">
                  TILE NATIVO · PRÓXIMO EM {watchState.formattedCountdown}
                </span>
              </div>
              <div className="space-y-1.5 my-auto">
                {watchState.upcomingThree.map((ev) => (
                  <div
                    key={ev.id}
                    className="bg-slate-950/90 border border-slate-800 rounded-lg px-2.5 py-1.5"
                  >
                    <div className="flex items-center justify-between text-[10px] font-mono text-sky-400">
                      <span>{formatTimeHHMM(ev.startEpochMs)} – {formatTimeHHMM(ev.endEpochMs)}</span>
                      <span>{formatDayRelative(ev.startEpochMs, currentWatchTimeMs)}</span>
                    </div>
                    <div className="text-xs font-semibold text-slate-100 truncate">
                      {ev.title}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* VIEW 3: Watch Local Room Database Inspector */}
          {watchScreenMode === 'cache' && (
            <div className="w-full flex-1 my-2 flex flex-col justify-between bg-slate-950/90 border border-slate-800 rounded-2xl p-3 text-left overflow-y-auto">
              <div className="text-[10px] font-mono text-sky-400 border-b border-slate-800 pb-1">
                ROOM DB (AUTÔNOMO ATÉ +{watchCache.syncedHorizonDays} DIAS)
              </div>
              <div className="space-y-1 my-1 text-[11px] font-mono">
                <div> Status BT: {watchCache.isBluetoothConnected ? 'ONLINE' : 'OFFLINE (CACHE)'}</div>
                <div> Total no cache: {watchCache.cachedEvents.length} eventos</div>
                <div> Restantes futuros: {watchState.upcomingThree.length}+</div>
                <div className="text-amber-300 truncate">
                  1º: {watchState.nextEvent?.title || 'Nenhum'}
                </div>
              </div>
            </div>
          )}

          {/* Bottom Bezel Footer */}
          <div className="text-[9px] font-mono text-slate-400 text-center">
            WFF XML 450×450 · HORIZONTE {watchCache.syncedHorizonDays}D
          </div>
        </div>
      </div>

      {/* Time-Travel & Autonomous Offline Simulation Bar */}
      <div className="pt-4 border-t border-slate-800">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-slate-300">
            Simulador de Passagem de Tempo no Relógio (Teste Autônomo)
          </span>
          <button
            type="button"
            onClick={onResetTime}
            className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 cursor-pointer"
          >
            <RotateCcw className="w-3 h-3" />
            <span>Resetar Relógio</span>
          </button>
        </div>

        <div className="grid grid-cols-4 gap-2">
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(15)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-amber-400" />
            <span>+15 min</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(60)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-amber-400" />
            <span>+1 hora</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(360)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-sky-400" />
            <span>+6 horas</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(1440)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-sky-400" />
            <span>+1 dia</span>
          </button>
        </div>
      </div>
    </div>
  );
};
