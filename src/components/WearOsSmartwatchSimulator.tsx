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
  const weekdayMap = ['DOM.', 'SEG.', 'TER.', 'QUA.', 'QUI.', 'SEX.', 'SÁB.'];
  const weekdayUpper = weekdayMap[watchDate.getDay()] || 'SEX.';
  const dayOfMonth = String(watchDate.getDate()).padStart(2, '0');
  const timeHHMMSS = `${formatTimeHHMM(currentWatchTimeMs)}:32`;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · DPI &amp; PROPORÇÃO SAMSUNG ONE UI (v1.0.6)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            DPI Samsung (Relógio 70sp, Eventos 19sp, Círculos 84px) + BoundingRoundBox 100% sem cortes
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
          <span>Watch Face Samsung DPI</span>
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

      {/* Center Circular Watch Bezel (450x450 logical representation with Samsung DPI & unclipped BoundingBoxes) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[372px] h-[372px] sm:w-[392px] sm:h-[392px] rounded-full bg-black border-[8px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-2.5 px-6 select-none overflow-hidden"
        >
          {/* SVG Overlay Renderizando os 3 Arcos Curvos Reais (<Arc>) do watchface.xml (Esquerdo, Direito e Inferior) */}
          {watchScreenMode === 'wff' && (
            <svg
              viewBox="0 0 450 450"
              className="absolute inset-0 w-full h-full pointer-events-none z-0"
            >
              {/* SLOT 7: Arco Lateral Esquerdo Curvo (250° a 290°, thickness=16) */}
              <path
                d="M 31.4 154.5 A 206 206 0 0 0 31.4 295.5"
                fill="none"
                stroke="#3B361F"
                strokeWidth="16"
                strokeLinecap="round"
              />
              <path
                d="M 31.4 154.5 A 206 206 0 0 0 19.0 228.0"
                fill="none"
                stroke="#F5C438"
                strokeWidth="16"
                strokeLinecap="round"
              />
              <circle cx="19.0" cy="228" r="8" fill="#F97316" />

              {/* SLOT 8: Arco Lateral Direito Curvo (70° a 110°, thickness=16) */}
              <path
                d="M 418.6 154.5 A 206 206 0 0 1 418.6 295.5"
                fill="none"
                stroke="#25302B"
                strokeWidth="16"
                strokeLinecap="round"
              />
              <path
                d="M 418.6 154.5 A 206 206 0 0 1 418.6 295.5"
                fill="none"
                stroke="#86EFAC"
                strokeWidth="16"
                strokeLinecap="round"
              />

              {/* SLOT 9: Arco Inferior Curvo de Bateria (162° a 198°, thickness=16) */}
              <path
                d="M 161.3 420.9 A 206 206 0 0 0 288.7 420.9"
                fill="none"
                stroke="#2B3024"
                strokeWidth="16"
                strokeLinecap="round"
              />
              <path
                d="M 161.3 420.9 A 206 206 0 0 0 248.0 429.5"
                fill="none"
                stroke="#88D4DC"
                strokeWidth="16"
                strokeLinecap="round"
              />
              {/* Ícone de Bateria Dourado à esquerda do arco inferior */}
              <rect x="126" y="392" width="8" height="4" rx="1.5" fill="#F5C438" />
              <rect x="122" y="396" width="16" height="24" rx="3.5" fill="#F5C438" />
            </svg>
          )}

          {/* TOPO NO DPI SAMSUNG: SEX. 06 (25sp BOLD) + Relógio 00:12:32 (70sp NORMAL amplo) */}
          <div className="text-center w-full z-10 pt-0.5">
            <div className="text-[18px] font-bold tracking-wide flex items-center justify-center gap-2 leading-none">
              <span className="text-[#F5C438]">{weekdayUpper}</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[50px] font-normal font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — DPI Samsung + BoundingRoundBox Completo */}
          {watchScreenMode === 'wff' && (
            <>
              {/* Ícones e textos dos arcos laterais na circunferência R=198px (100% visíveis sem cortes!) */}
              <span className="absolute left-8 top-[98px] text-[13px] font-bold text-[#F5C438] z-10">
                ⏳
              </span>
              <span className="absolute left-7 top-[266px] text-[13px] font-bold font-mono text-[#88D4DC] z-10">
                --
              </span>
              <span className="absolute right-8 top-[98px] w-5 h-5 rounded-full bg-[#F5C438] text-black text-[11px] font-bold flex items-center justify-center z-10">
                ✓
              </span>
              <span className="absolute right-6 top-[266px] text-[12px] font-bold font-mono text-slate-200 z-10">
                75%
              </span>

              {/* ÚNICO QUADRO CENTRAL (DPI Samsung 19sp BOLD para hora e eventos + Barra visível) */}
              <div className="w-[82%] rounded-[20px] bg-[#080A0E] border border-[#161D26] px-3.5 py-2 space-y-1 z-10 -mt-1">
                {/* Cabeçalho Hoje (18sp BOLD Dourado) */}
                <div className="flex items-center gap-2">
                  <Calendar className="w-4 h-4 text-[#F5C438] shrink-0" />
                  <span className="text-[15px] font-bold text-[#F5C438] leading-none">
                    Hoje
                  </span>
                </div>

                {/* LINHA 1 (Slots #1 + #10): Hora Branca 19sp + Nome Dourado 19sp + Timer Ciano + Barra 5px */}
                <div className="w-full space-y-1">
                  <div className="flex items-center justify-between gap-1 text-[15px] leading-tight">
                    <div className="flex items-center min-w-0">
                      <span className="w-[46px] font-mono font-bold text-white shrink-0">
                        {ev1 ? formatTimeHHMM(ev1.startEpochMs) : '--:--'}
                      </span>
                      <span className="font-bold text-[#F5C438] truncate">
                        {ev1 ? ev1.title : 'Sem eventos'}
                      </span>
                    </div>
                    <span className="font-mono font-bold text-[12px] text-[#67E8F9] shrink-0 ml-1">
                      {watchState.hasUpcomingEvent
                        ? watchState.formattedCountdown
                        : '--'}
                    </span>
                  </div>
                  {/* Barra de progresso fininha (5px) 100% dentro do BoundingRoundBox */}
                  <div className="w-full h-[4.5px] bg-[#252E3A] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-[#F5C438] rounded-full transition-all duration-200"
                      style={{
                        width: `${Math.max(10, Math.round(watchState.progressRatio * 100))}%`,
                      }}
                    />
                  </div>
                </div>

                {/* LINHA 2 (Slot #2): Hora Branca 19sp + Título Dourado 19sp */}
                <div className="w-full flex items-center text-[15px] leading-tight pt-0.5">
                  <span className="w-[46px] font-mono font-bold text-white shrink-0">
                    {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#F5C438] truncate">
                    {ev2 ? ev2.title : 'Livre'}
                  </span>
                </div>

                {/* LINHA 3 (Slot #3): Hora Branca 19sp + Título Dourado 19sp */}
                <div className="w-full flex items-center text-[15px] leading-tight">
                  <span className="w-[46px] font-mono font-bold text-white shrink-0">
                    {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#F5C438] truncate">
                    {ev3 ? ev3.title : 'Livre'}
                  </span>
                </div>
              </div>

              {/* SLOTS #4, #5 e #6: 3 Círculos no DPI da Samsung (84x84px, ícones 28px, texto 20sp) */}
              <div className="flex items-start justify-center gap-3 z-10 -mt-1">
                {/* Slot #4: Círculo Esquerdo */}
                <div
                  title="Slot #4: Complication Circular Esquerda"
                  className="w-[68px] h-[68px] rounded-full bg-[#1B1E22] flex flex-col items-center justify-center p-1"
                >
                  <Footprints className="w-4 h-4 text-[#22C55E]" />
                  <span className="text-[14px] font-bold text-white leading-tight mt-0.5">
                    1.551
                  </span>
                </div>

                {/* Slot #5: Círculo Central */}
                <div
                  title="Slot #5: Complication Circular Central"
                  className="w-[68px] h-[68px] mt-2.5 rounded-full bg-[#1B1E22] flex flex-col items-center justify-center p-1"
                >
                  <Sun className="w-4 h-4 text-[#F97316]" />
                  <span className="text-[14px] font-bold text-white leading-tight mt-0.5">
                    17:40
                  </span>
                </div>

                {/* Slot #6: Círculo Direito */}
                <div
                  title="Slot #6: Complication Circular Direita"
                  className="w-[68px] h-[68px] rounded-full bg-[#1B1E22] flex flex-col items-center justify-center p-1"
                >
                  <CloudMoon className="w-4 h-4 text-[#88D4DC]" />
                  <span className="text-[16px] font-bold text-white leading-tight mt-0.5">
                    27°
                  </span>
                </div>
              </div>

              {/* Texto percentual à direita do Arco Inferior de Bateria (Slot #9 — 22sp BOLD Ciano) */}
              <div className="w-full flex items-center justify-center z-10 pb-0.5">
                <span className="ml-40 text-[16px] font-bold font-mono text-[#88D4DC]">
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
