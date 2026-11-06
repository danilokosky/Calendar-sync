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
  const timeHHMMSS = `${formatTimeHHMM(currentWatchTimeMs)}:06`;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · SAMSUNG ONE UI REPLICA (v1.0.9)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Dock em cartucho duplo com contorno forte + bateria tubo de ensaio (cortes ao fundo + líquido 80% opaco)
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
          <span>Watch Face v1.0.9</span>
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

      {/* Center Circular Watch Bezel (450x450 logical representation matching Samsung 14:04:06) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[376px] h-[376px] sm:w-[396px] sm:h-[396px] rounded-full bg-black border-[8px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-2.5 px-7 select-none overflow-hidden"
        >
          {/* SVG Overlay Renderizando os Arcos Laterais, Ícones Inclinados e a Barra Inferior Estilo Tubo de Ensaio */}
          {watchScreenMode === 'wff' && (
            <svg
              viewBox="0 0 450 450"
              className="absolute inset-0 w-full h-full pointer-events-none z-0"
            >
              {/* SLOT 7: Arco Lateral Esquerdo Bicolor (250°..290°) + Única Bolinha Laranja */}
              <path
                d="M 18.0 225.0 A 207 207 0 0 0 30.5 295.8"
                fill="none"
                stroke="#2E3B38"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <path
                d="M 30.5 154.2 A 207 207 0 0 0 18.0 225.0"
                fill="none"
                stroke="#453D20"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <circle cx="18.0" cy="225.0" r="8" fill="#F97316" />

              {/* SLOT 8: Arco Lateral Direito (70°..110°) */}
              <path
                d="M 419.5 154.2 A 207 207 0 0 1 419.5 295.8"
                fill="none"
                stroke="#25302B"
                strokeWidth="18"
                strokeLinecap="round"
              />
              <path
                d="M 419.5 154.2 A 207 207 0 0 1 430.5 245.0"
                fill="none"
                stroke="#86EFAC"
                strokeWidth="18"
                strokeLinecap="round"
              />

              {/* BARRA INFERIOR DE BATERIA ESTILO TUBO DE ENSAIO (164°..196°) */}
              {/* 1. Borda externa do tubo (mais grossa: 22px) */}
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 282.0 424.0"
                fill="none"
                stroke="#22261A"
                strokeWidth="22"
                strokeLinecap="round"
              />
              {/* 2. Cartucho interno de fundo (17px, mais grosso que a barra de progresso) */}
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 282.0 424.0"
                fill="none"
                stroke="#3F4430"
                strokeWidth="17"
                strokeLinecap="round"
              />
              {/* 3. 9 Cortes radiais AO FUNDO (cortando o cartucho de fundo antes do líquido) */}
              <line x1="182" y1="414" x2="177" y2="439" stroke="#14170F" strokeWidth="3.2" />
              <line x1="192" y1="416" x2="189" y2="441" stroke="#14170F" strokeWidth="3.2" />
              <line x1="203" y1="418" x2="201" y2="443" stroke="#14170F" strokeWidth="3.2" />
              <line x1="214" y1="419" x2="213" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="225" y1="419" x2="225" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="236" y1="419" x2="237" y2="444" stroke="#14170F" strokeWidth="3.2" />
              <line x1="247" y1="418" x2="249" y2="443" stroke="#14170F" strokeWidth="3.2" />
              <line x1="258" y1="416" x2="261" y2="441" stroke="#14170F" strokeWidth="3.2" />
              <line x1="268" y1="414" x2="273" y2="439" stroke="#14170F" strokeWidth="3.2" />
              {/* 4. Líquido de progresso da bateria À FRENTE (11.5px, 80% opaco / 20% transparente) */}
              <path
                d="M 168.0 424.0 A 207 207 0 0 0 246.0 431.0"
                fill="none"
                stroke="#88D4DC"
                strokeOpacity="0.8"
                strokeWidth="11.5"
                strokeLinecap="round"
              />

              {/* Ícone de Bateria Dourado Inclinado a +26° na curva esquerda inferior */}
              <g transform="translate(130, 408) rotate(26) translate(-14, -16)">
                <rect x="10" y="2" width="8" height="4" rx="1.5" fill="#F5C438" />
                <rect x="6" y="6" width="16" height="22" rx="3.5" fill="#F5C438" />
              </g>

              {/* Percentual 62% Inclinado a -26° na curva direita inferior */}
              <g transform="translate(324, 406) rotate(-26)">
                <text
                  x="0"
                  y="6"
                  textAnchor="middle"
                  fill="#88D4DC"
                  fontSize="21"
                  fontWeight="bold"
                  fontFamily="monospace"
                >
                  62%
                </text>
              </g>
            </svg>
          )}

          {/* TOPO NO DPI SAMSUNG: SEX. 06 (25sp BOLD) + Relógio 14:04:06 (70sp NORMAL) */}
          <div className="text-center w-full z-10 pt-0.5">
            <div className="text-[18px] font-bold tracking-wide flex items-center justify-center gap-2 leading-none">
              <span className="text-[#F5C438]">{weekdayUpper}</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[50px] font-normal font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — Samsung 16:22:13 + v1.0.9 Cartridge Dock */}
          {watchScreenMode === 'wff' && (
            <>
              {/* Ícones e textos dos arcos laterais INCLINADOS (rotate +-28deg) seguindo a curvatura da tela */}
              <span className="absolute left-6 top-[102px] rotate-[28deg] text-[14px] font-bold text-[#F5C438] z-10">
                ⏳
              </span>
              <span className="absolute left-5 top-[278px] -rotate-[28deg] text-[13px] font-bold font-mono text-[#88D4DC] z-10">
                0
              </span>
              <span className="absolute right-6 top-[102px] -rotate-[28deg] w-5 h-5 rounded-full bg-[#F5C438] text-black text-[11px] font-bold flex items-center justify-center z-10">
                ✓
              </span>
              <span className="absolute right-5 top-[278px] rotate-[28deg] text-[13px] font-bold font-mono text-slate-200 z-10">
                0
              </span>

              {/* ÚNICO QUADRO CENTRAL (x=74, y=110, w=302, h=136) — Renderizado via Bitmap Transparente no Slot 1 */}
              <div className="w-[80%] rounded-[16px] bg-[#080A0E] border border-[#1B2430] px-3 py-1.5 space-y-0.5 z-10 -mt-1.5">
                {/* Cabeçalho Hoje (18sp BOLD Dourado) */}
                <div className="flex items-center gap-1.5">
                  <Calendar className="w-3.5 h-3.5 text-[#F5C438] shrink-0" />
                  <span className="text-[14px] font-bold text-[#F5C438] leading-none">
                    Hoje
                  </span>
                </div>

                {/* LINHA 1: Hora Branca + Nome Dourado + Timer Ciano + Barra 5px */}
                <div className="w-full space-y-0.5">
                  <div className="flex items-center justify-between gap-1 text-[14px] leading-tight">
                    <div className="flex items-center min-w-0">
                      <span className="w-[44px] font-mono font-bold text-white shrink-0">
                        {ev1 ? formatTimeHHMM(ev1.startEpochMs) : '--:--'}
                      </span>
                      <span className="font-bold text-[#F5C438] truncate">
                        {ev1 ? ev1.title : 'Sem eventos'}
                      </span>
                    </div>
                    <span className="font-mono font-bold text-[11px] text-[#67E8F9] shrink-0 ml-1">
                      {watchState.hasUpcomingEvent
                        ? watchState.formattedCountdown
                        : '--'}
                    </span>
                  </div>
                  <div className="w-full h-[4px] bg-[#252E3A] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-[#F5C438] rounded-full transition-all duration-200"
                      style={{
                        width: `${Math.max(10, Math.round(watchState.progressRatio * 100))}%`,
                      }}
                    />
                  </div>
                </div>

                {/* LINHA 2: Hora Branca + Título Dourado */}
                <div className="w-full flex items-center text-[14px] leading-tight pt-0.5">
                  <span className="w-[44px] font-mono font-bold text-white shrink-0">
                    {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#F5C438] truncate">
                    {ev2 ? ev2.title : 'Livre'}
                  </span>
                </div>

                {/* LINHA 3: Hora Branca + Título Dourado */}
                <div className="w-full flex items-center text-[14px] leading-tight">
                  <span className="w-[44px] font-mono font-bold text-white shrink-0">
                    {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#F5C438] truncate">
                    {ev3 ? ev3.title : 'Livre'}
                  </span>
                </div>
              </div>

              {/* DOCK EM CARTUCHO ENVOLVENTE (x=84, y=268, w=282, h=92) COM APENAS 2 COMPLICATIONS MAIORES E CONTORNO FORTE */}
              <div
                title="Cartucho Externo Envolvente (x=84, y=268, w=282, h=92, r=46)"
                className="rounded-full bg-[#161D1C] border-[1.5px] border-[#283533] px-2 py-1.5 flex items-center justify-center gap-2.5 z-10 mt-0.5 mb-10 shadow-inner"
              >
                {/* Slot #4: Cartucho Interno Esquerdo (x=92, y=275, w=127, h=78, r=39) */}
                <div
                  title="Slot #4: Complication Cartucho Esquerdo (Fundo #263230 + Contorno #455955)"
                  className="w-[104px] h-[56px] rounded-full bg-[#263230] border-[1.8px] border-[#455955] flex flex-col items-center justify-center px-2"
                >
                  <Footprints className="w-4 h-4 text-[#22C55E]" />
                  <span className="text-[15px] font-bold text-white leading-tight mt-0.5 tracking-tight">
                    2.435
                  </span>
                </div>

                {/* Slot #5: Cartucho Interno Direito (x=231, y=275, w=127, h=78, r=39) */}
                <div
                  title="Slot #5: Complication Cartucho Direito (Fundo #263230 + Contorno #455955)"
                  className="w-[104px] h-[56px] rounded-full bg-[#263230] border-[1.8px] border-[#455955] flex flex-col items-center justify-center px-2"
                >
                  <Sun className="w-4 h-4 text-[#F97316]" />
                  <span className="text-[15px] font-bold text-white leading-tight mt-0.5 tracking-tight">
                    17:40
                  </span>
                </div>
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
