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
  currentWatchTimeMs,
  onToggleBluetooth,
  onAdvanceTimeMinutes,
  onResetTime,
}) => {
  const [watchScreenMode, setWatchScreenMode] = useState<'wff' | 'tile' | 'cache'>('wff');

  const ev1 = watchState.upcomingThree[0] || null;
  const ev2 = watchState.upcomingThree[1] || null;
  const ev3 = watchState.upcomingThree[2] || null;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · 4 SLOTS WFF CIRCULARES
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
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
            watchScreenMode === 'wff'
              ? 'bg-slate-800 text-amber-400'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Watch className="w-3.5 h-3.5" />
          <span>Watch Face (4 Slots)</span>
        </button>
        <button
          type="button"
          onClick={() => setWatchScreenMode('tile')}
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
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
          className={`py-2 px-3 rounded-lg text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap cursor-pointer ${
            watchScreenMode === 'cache'
              ? 'bg-slate-800 text-amber-400'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Database className="w-3.5 h-3.5" />
          <span>Cache Room ({watchCache.cachedEvents.length})</span>
        </button>
      </div>

      {/* Center Circular Watch Bezel (450x450 logical representation with circular-tapered cards) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[348px] h-[348px] sm:w-[366px] sm:h-[366px] rounded-full bg-[#05070B] border-[8px] border-[#182232] shadow-2xl flex flex-col items-center justify-between py-4 px-4 select-none overflow-hidden"
        >
          {/* Watch Hardware Top Status & Digital Clock */}
          <div className="text-center w-full pt-0.5">
            <div className="text-[10px] font-bold text-amber-400 tracking-wider flex items-center justify-center gap-1.5">
              <span>{formatDayRelative(currentWatchTimeMs, watchCache.lastSyncEpochMs).toUpperCase()}</span>
              <span>•</span>
              <span>{watchCache.isBluetoothConnected ? 'SYNC OK' : 'OFFLINE'}</span>
            </div>
            <div className="text-[34px] font-bold font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {formatTimeHHMM(currentWatchTimeMs)}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — 4 Dedicated Circular-Fitted Slots */}
          {watchScreenMode === 'wff' && (
            <div className="w-full flex flex-col items-center gap-1.5 my-auto">
              {/* SLOT 1 (w=354/450 = 86%): Próximo Evento + Pílula Contagem Regressiva + Barra RangedValue */}
              <div className="w-[86%] bg-[#101722] border border-[#243244] rounded-[16px] px-3 py-2 shadow-inner">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-bold text-slate-100 truncate">
                    {watchState.hasUpcomingEvent && watchState.nextEvent
                      ? `${ watchState.formattedStartTime } • ${ watchState.nextEvent.title }`
                      : 'Agenda livre'}
                  </span>
                  <span className="px-2 py-0.5 rounded-full bg-[#2B1F0C] text-[#FBBF24] font-mono font-bold text-[11px] shrink-0">
                    {watchState.hasUpcomingEvent
                      ? watchState.formattedCountdown
                      : '--'}
                  </span>
                </div>

                {/* Barra de Progresso RangedValue (1h / 2h antes) */}
                <div className="mt-1.5 w-full h-2 bg-[#1E293B] rounded-full overflow-hidden">
                  <div
                    className="h-full bg-[#F59E0B] rounded-full transition-all duration-200"
                    style={{
                      width: `${Math.max(6, Math.round(watchState.progressRatio * 100))}%`,
                    }}
                  />
                </div>
              </div>

              {/* SLOT 2 (w=390/450 = 94%): Compromisso #1 Individual com Pílula Âmbar */}
              <div className="w-[94%] bg-[#111926] border border-[#243447] rounded-[14px] px-2.5 py-1.5 flex items-center gap-2">
                <span className="px-2 py-0.5 rounded-[9px] bg-[#2B2110] text-[#FBBF24] font-mono font-bold text-[11px] shrink-0">
                  {ev1 ? formatTimeHHMM(ev1.startEpochMs) : '--:--'}
                </span>
                <span className="text-xs font-bold text-white truncate">
                  {ev1 ? ev1.title : 'Sem evento #1'}
                </span>
              </div>

              {/* SLOT 3 (w=362/450 = 86%): Compromisso #2 Individual com Pílula Azul-Ciano */}
              <div className="w-[86%] bg-[#0E1621] border border-[#1E2D40] rounded-[14px] px-2.5 py-1.5 flex items-center gap-2">
                <span className="px-2 py-0.5 rounded-[9px] bg-[#0D2538] text-[#38BDF8] font-mono font-bold text-[11px] shrink-0">
                  {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                </span>
                <span className="text-xs font-medium text-slate-200 truncate">
                  {ev2 ? ev2.title : 'Livre'}
                </span>
              </div>

              {/* SLOT 4 (w=302/450 = 72%): Compromisso #3 Inscrito na Curva Inferior (Sem Cortar Cantos!) */}
              <div className="w-[72%] bg-[#0C131C] border border-[#1B2838] rounded-[14px] px-2.5 py-1.5 flex items-center gap-2">
                <span className="px-2 py-0.5 rounded-[9px] bg-[#0D2821] text-[#34D399] font-mono font-bold text-[11px] shrink-0">
                  {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                </span>
                <span className="text-[11px] font-medium text-slate-300 truncate">
                  {ev3 ? ev3.title : 'Livre'}
                </span>
              </div>
            </div>
          )}

          {/* VIEW 2: Wear OS Tile (UpNextThreeEventsTileService) */}
          {watchScreenMode === 'tile' && (
            <div className="w-[86%] flex-1 my-2 flex flex-col justify-between bg-slate-900/60 border border-slate-800 rounded-2xl p-3">
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
            <div className="w-[86%] flex-1 my-2 flex flex-col justify-between bg-slate-950/90 border border-slate-800 rounded-2xl p-3 text-left overflow-y-auto">
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
          <div className="text-[9px] font-mono font-bold text-slate-500 text-center pb-0.5">
            AGENDA • WFF 450×450
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
