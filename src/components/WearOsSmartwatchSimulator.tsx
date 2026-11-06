import React, { useState } from 'react';
import {
  ComplicationProgressState,
  WatchCacheState,
  CalendarSource,
  formatTimeHHMM,
  formatDayRelative,
} from '../utils/calendarSyncEngine';
import {
  Bluetooth,
  BluetoothOff,
  FastForward,
  RotateCcw,
  Watch,
  Layers,
  Database,
  Calendar,
  Battery,
  CloudMoon,
  Footprints,
  Sun,
} from 'lucide-react';

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

  const watchDate = new Date(currentWatchTimeMs);
  const weekdayShort = watchDate
    .toLocaleDateString('pt-BR', { weekday: 'short' })
    .replace('.', '')
    .toUpperCase();
  const dayOfMonth = String(watchDate.getDate()).padStart(2, '0');
  const timeHHMMSS = `${formatTimeHHMM(currentWatchTimeMs)}:32`;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · LAYOUT ONE UI WATCH (9 SLOTS WFF)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Quadro central padronizado (3 eventos alinhados sem repetição) + 2 barras laterais + 3 círculos + barra inferior
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
          <span>Watch Face (9 Slots)</span>
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

      {/* Center Circular Watch Bezel (450x450 logical representation matching Samsung One UI Watch reference) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[360px] h-[360px] sm:w-[380px] sm:h-[380px] rounded-full bg-black border-[8px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-3.5 px-3 select-none overflow-hidden"
        >
          {/* TOPO: SEX. 06 + Relógio Digital 00:12:32 em destaque com DPI equilibrado */}
          <div className="text-center w-full z-10">
            <div className="text-[13px] font-bold tracking-wide flex items-center justify-center gap-1.5 leading-none">
              <span className="text-[#FACC15]">{weekdayShort}.</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[36px] font-medium font-mono tabular-nums tracking-tight text-white leading-none mt-1">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — Quadro Central Padronizado + Complications Laterais, Circulares e Inferior */}
          {watchScreenMode === 'wff' && (
            <>
              {/* Linha Central: Barra Lateral Esquerda (Slot #7) + Quadro Padronizado 3 Eventos (Slots #1, #2, #3) + Barra Lateral Direita (Slot #8) */}
              <div className="w-full flex items-center justify-between gap-1.5 px-1 z-10">
                {/* SLOT #7: Barra Lateral Esquerda (Range / Short Text) */}
                <div
                  title="Slot #7: Barra Lateral Esquerda (Range / Texto Curto)"
                  className="w-7 h-[118px] flex flex-col items-center justify-between py-1 shrink-0"
                >
                  <span className="text-[9px] font-bold text-[#FACC15]">⏳</span>
                  <div className="relative w-2.5 h-20 rounded-full bg-[#2F3429] overflow-hidden flex flex-col justify-end">
                    <div className="w-full h-1/2 bg-[#EAB308]/60 rounded-full" />
                    <span className="absolute top-1/2 -translate-y-1/2 left-0.5 w-2 h-2 rounded-full bg-[#F97316]" />
                  </div>
                  <span className="text-[9px] font-mono font-bold text-[#67E8F9]">
                    --
                  </span>
                </div>

                {/* QUADRO CENTRAL PADRONIZADO (Mesma largura e alinhamento para os 3 eventos, SEM repetir o #1!) */}
                <div className="flex-1 min-w-0 rounded-[14px] bg-[#0B0E13] border border-[#1A222D] px-3 py-2 space-y-1.5 shadow-inner">
                  {/* Cabeçalho Hoje */}
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-1.5 text-[11px] font-bold text-[#FACC15]">
                      <Calendar className="w-3 h-3 text-[#FACC15]" />
                      <span>Hoje</span>
                    </div>
                    <span className="text-[9px] font-mono text-slate-400">
                      {watchCache.isBluetoothConnected ? 'SYNC' : 'OFFLINE'}
                    </span>
                  </div>

                  {/* SLOT #1: Evento Principal #1 + Contagem Regressiva + Barra de Progresso Fininha Acoplada */}
                  <div className="w-full space-y-1">
                    <div className="flex items-center justify-between gap-1.5 text-[12px] leading-tight">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span className="font-mono font-bold text-white shrink-0">
                          {ev1 ? formatTimeHHMM(ev1.startEpochMs) : '--:--'}
                        </span>
                        <span className="font-bold text-white truncate">
                          {ev1 ? ev1.title : 'Sem eventos'}
                        </span>
                      </div>
                      <span className="font-mono font-bold text-[11px] text-[#67E8F9] shrink-0">
                        {watchState.hasUpcomingEvent
                          ? watchState.formattedCountdown
                          : '--'}
                      </span>
                    </div>
                    {/* Barra de progresso fininha acoplada ao Evento Principal #1 */}
                    <div className="w-full h-[4px] bg-[#232B36] rounded-full overflow-hidden">
                      <div
                        className="h-full bg-[#FACC15] rounded-full transition-all duration-200"
                        style={{
                          width: `${Math.max(8, Math.round(watchState.progressRatio * 100))}%`,
                        }}
                      />
                    </div>
                  </div>

                  {/* SLOT #2: Próximo Evento #2 (Alinhado no mesmo quadro padrão, sem repetir o #1) */}
                  <div className="w-full flex items-center gap-1.5 text-[12px] leading-tight">
                    <span className="font-mono font-bold text-white shrink-0">
                      {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                    </span>
                    <span className="font-bold text-[#FACC15] truncate">
                      {ev2 ? ev2.title : 'Livre'}
                    </span>
                  </div>

                  {/* SLOT #3: Próximo Evento #3 (Alinhado no mesmo quadro padrão) */}
                  <div className="w-full flex items-center gap-1.5 text-[12px] leading-tight">
                    <span className="font-mono font-bold text-white shrink-0">
                      {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                    </span>
                    <span className="font-bold text-[#FACC15] truncate">
                      {ev3 ? ev3.title : 'Livre'}
                    </span>
                  </div>
                </div>

                {/* SLOT #8: Barra Lateral Direita (Range / Short Text) */}
                <div
                  title="Slot #8: Barra Lateral Direita (Range / Texto Curto)"
                  className="w-7 h-[118px] flex flex-col items-center justify-between py-1 shrink-0"
                >
                  <span className="w-3.5 h-3.5 rounded-full bg-[#FACC15] text-black text-[9px] font-bold flex items-center justify-center">
                    ✓
                  </span>
                  <div className="relative w-2.5 h-20 rounded-full bg-[#25302B] overflow-hidden flex flex-col justify-end">
                    <div className="w-full h-2/3 bg-[#67E8F9]/60 rounded-full" />
                  </div>
                  <span className="text-[8px] font-bold text-slate-300">
                    OK
                  </span>
                </div>
              </div>

              {/* SLOTS #4, #5 e #6: 3 Círculos de Complication Alinhados Abaixo do Quadro Central */}
              <div className="flex items-center justify-center gap-3.5 z-10">
                {/* Slot #4: Círculo Esquerdo */}
                <div
                  title="Slot #4: Complication Circular Esquerda"
                  className="w-[58px] h-[58px] rounded-full bg-[#181B1F] border border-[#262B31] flex flex-col items-center justify-center p-1"
                >
                  <Footprints className="w-3 h-3 text-[#67E8F9]" />
                  <span className="text-[11px] font-bold text-white leading-tight mt-0.5">
                    4.820
                  </span>
                </div>

                {/* Slot #5: Círculo Central */}
                <div
                  title="Slot #5: Complication Circular Central"
                  className="w-[58px] h-[58px] rounded-full bg-[#181B1F] border border-[#262B31] flex flex-col items-center justify-center p-1"
                >
                  <Sun className="w-3 h-3 text-[#FACC15]" />
                  <span className="text-[11px] font-bold text-white leading-tight mt-0.5">
                    06:12
                  </span>
                </div>

                {/* Slot #6: Círculo Direito (ex: Clima 27°) */}
                <div
                  title="Slot #6: Complication Circular Direita"
                  className="w-[58px] h-[58px] rounded-full bg-[#181B1F] border border-[#262B31] flex flex-col items-center justify-center p-1"
                >
                  <CloudMoon className="w-3.5 h-3.5 text-[#67E8F9]" />
                  <span className="text-[12px] font-bold text-white leading-tight mt-0.5">
                    27°
                  </span>
                </div>
              </div>

              {/* SLOT #9: Barra Inferior (Bateria / Range / Texto Curto) */}
              <div
                title="Slot #9: Barra Inferior (Bateria / Range / Texto)"
                className="flex items-center justify-center gap-2 z-10 pb-0.5"
              >
                <Battery className="w-3.5 h-3.5 text-[#FACC15] fill-[#FACC15]" />
                <div className="flex items-center gap-[3px] bg-[#1C2018] px-2 py-1 rounded-full">
                  {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((seg) => (
                    <span
                      key={seg}
                      className={`w-[3px] h-2.5 rounded-full ${
                        seg <= 6 ? 'bg-[#86EFAC]' : 'bg-[#3A3F31]'
                      }`}
                    />
                  ))}
                </div>
                <span className="text-xs font-bold font-mono text-[#67E8F9]">
                  62%
                </span>
              </div>
            </>
          )}

          {/* VIEW 2: Wear OS Tile (UpNextThreeEventsTileService) */}
          {watchScreenMode === 'tile' && (
            <div className="w-[84%] flex-1 my-2 flex flex-col justify-between bg-slate-900/60 border border-slate-800 rounded-2xl p-3">
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
                      <span>
                        {formatTimeHHMM(ev.startEpochMs)} – {formatTimeHHMM(ev.endEpochMs)}
                      </span>
                      <span>
                        {formatDayRelative(ev.startEpochMs, currentWatchTimeMs)}
                      </span>
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
            <div className="w-[84%] flex-1 my-2 flex flex-col justify-between bg-slate-950/90 border border-slate-800 rounded-2xl p-3 text-left overflow-y-auto">
              <div className="text-[10px] font-mono text-sky-400 border-b border-slate-800 pb-1">
                ROOM DB (AUTÔNOMO ATÉ +{watchCache.syncedHorizonDays} DIAS)
              </div>
              <div className="space-y-1 my-1 text-[11px] font-mono">
                <div>
                  Status BT: {watchCache.isBluetoothConnected ? 'ONLINE' : 'OFFLINE (CACHE)'}
                </div>
                <div>Total no cache: {watchCache.cachedEvents.length} eventos</div>
                <div>Restantes futuros: {watchState.upcomingThree.length}+</div>
                <div className="text-amber-300 truncate">
                  1º: {watchState.nextEvent?.title || 'Nenhum'}
                </div>
              </div>
            </div>
          )}
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
