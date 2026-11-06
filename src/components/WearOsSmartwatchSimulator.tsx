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
            MÓDULOS :wear &amp; :watchface · GEOMETRIA RADIAL 450×450 (v1.0.5)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Arcos curvos reais (&lt;Arc&gt;) no bisel + 3 círculos em curva + único quadro central com colunas 100% alinhadas
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
          <span>Watch Face Radial</span>
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

      {/* Center Circular Watch Bezel (450x450 logical representation with true curved <Arc> bezel tracks) */}
      <div className="flex flex-col items-center justify-center my-2">
        <div
          data-testid="wear-watch-face"
          className="relative w-[368px] h-[368px] sm:w-[384px] sm:h-[384px] rounded-full bg-black border-[8px] border-[#18202C] shadow-2xl flex flex-col items-center justify-between py-3 px-6 select-none overflow-hidden"
        >
          {/* SVG Overlay Renderizando os 3 Arcos Curvos Reais (<Arc>) do watchface.xml (Esquerdo, Direito e Inferior) */}
          {watchScreenMode === 'wff' && (
            <svg
              viewBox="0 0 450 450"
              className="absolute inset-0 w-full h-full pointer-events-none z-0"
            >
              {/* SLOT 7: Arco Lateral Esquerdo Curvo (250° a 290°) */}
              <path
                d="M 29.5 153.8 A 208 208 0 0 0 29.5 296.2"
                fill="none"
                stroke="#333627"
                strokeWidth="14"
                strokeLinecap="round"
              />
              <path
                d="M 29.5 153.8 A 208 208 0 0 0 17.5 232.0"
                fill="none"
                stroke="#FACC15"
                strokeWidth="14"
                strokeLinecap="round"
              />
              {/* Ponto indicador no arco esquerdo */}
              <circle cx="17.5" cy="232" r="6.5" fill="#F97316" />

              {/* SLOT 8: Arco Lateral Direito Curvo (70° a 110°) */}
              <path
                d="M 420.5 153.8 A 208 208 0 0 1 420.5 296.2"
                fill="none"
                stroke="#25302B"
                strokeWidth="14"
                strokeLinecap="round"
              />
              <path
                d="M 420.5 153.8 A 208 208 0 0 1 432.5 232.0"
                fill="none"
                stroke="#86EFAC"
                strokeWidth="14"
                strokeLinecap="round"
              />
              <circle cx="432.5" cy="232" r="6" fill="#FACC15" />

              {/* SLOT 9: Arco Inferior Curvo de Bateria (162° a 198°) */}
              <path
                d="M 160.7 422.8 A 208 208 0 0 0 289.3 422.8"
                fill="none"
                stroke="#2B3024"
                strokeWidth="14"
                strokeLinecap="round"
              />
              <path
                d="M 160.7 422.8 A 208 208 0 0 0 245.0 432.0"
                fill="none"
                stroke="#86EFAC"
                strokeWidth="14"
                strokeLinecap="round"
              />
              {/* Ícone de Bateria Dourado à esquerda do arco inferior */}
              <rect x="131" y="405" width="6" height="3" rx="1" fill="#FACC15" />
              <rect x="128" y="408" width="12" height="18" rx="3" fill="#FACC15" />
            </svg>
          )}

          {/* TOPO: SEX. 06 (Sempre em Caixa Alta e 1 único ponto) + Relógio 00:12:32 */}
          <div className="text-center w-full z-10 pt-0.5">
            <div className="text-[14px] font-bold tracking-wide flex items-center justify-center gap-1.5 leading-none">
              <span className="text-[#FACC15]">{weekdayUpper}</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[40px] font-light font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — Único Quadro Central + Arcos Curvos + 3 Círculos em Curva */}
          {watchScreenMode === 'wff' && (
            <>
              {/* Rótulos dos Arcos Laterais (Recuados para dentro da corda circular, sem cortar no bisel) */}
              <span className="absolute left-5 top-[106px] text-[10px] font-bold text-[#FACC15] z-10">
                ⏳
              </span>
              <span className="absolute left-6 top-[256px] text-[11px] font-bold font-mono text-[#67E8F9] z-10">
                1
              </span>
              <span className="absolute right-5 top-[106px] w-4 h-4 rounded-full bg-[#FACC15] text-black text-[9px] font-bold flex items-center justify-center z-10">
                ✓
              </span>
              <span className="absolute right-5 top-[256px] text-[10px] font-bold font-mono text-slate-200 z-10">
                62%
              </span>

              {/* ÚNICO QUADRO DA TELA: Quadro Central Padronizado dos 3 Próximos Eventos */}
              <div className="w-[80%] rounded-[18px] bg-[#0B0E12] border border-[#1B232E] px-3.5 py-2.5 space-y-1.5 shadow-inner z-10 -mt-1">
                {/* Cabeçalho Hoje com Ícone de Calendário Dourado real e espaçamento limpo */}
                <div className="flex items-center gap-2">
                  <Calendar className="w-3.5 h-3.5 text-[#FACC15] shrink-0" />
                  <span className="text-[12px] font-bold text-[#FACC15] leading-none">
                    Hoje
                  </span>
                </div>

                {/* LINHA 1 (Slots #1 + #10): Hora Branca (w=42px) + Título Dourado (#FACC15) + Timer Ciano + Barra Fina */}
                <div className="w-full space-y-1">
                  <div className="flex items-center justify-between gap-1 text-[12.5px] leading-tight">
                    <div className="flex items-center min-w-0">
                      <span className="w-[42px] font-mono font-bold text-white shrink-0">
                        {ev1 ? formatTimeHHMM(ev1.startEpochMs) : '--:--'}
                      </span>
                      <span className="font-bold text-[#FACC15] truncate">
                        {ev1 ? ev1.title : 'Sem eventos'}
                      </span>
                    </div>
                    <span className="font-mono font-bold text-[11px] text-[#67E8F9] shrink-0 ml-1">
                      {watchState.hasUpcomingEvent
                        ? watchState.formattedCountdown
                        : '--'}
                    </span>
                  </div>
                  {/* Barra fininha (3.5px) acoplada logo abaixo do Evento #1 */}
                  <div className="w-full h-[3.5px] bg-[#232B36] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-[#FACC15] rounded-full transition-all duration-200"
                      style={{
                        width: `${Math.max(8, Math.round(watchState.progressRatio * 100))}%`,
                      }}
                    />
                  </div>
                </div>

                {/* LINHA 2 (Slot #2): Mesma coluna de Hora Branca (w=42px) + Mesma coluna de Título Dourado (#FACC15) */}
                <div className="w-full flex items-center text-[12.5px] leading-tight pt-0.5">
                  <span className="w-[42px] font-mono font-bold text-white shrink-0">
                    {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#FACC15] truncate">
                    {ev2 ? ev2.title : 'Livre'}
                  </span>
                </div>

                {/* LINHA 3 (Slot #3): Mesma coluna de Hora Branca (w=42px) + Mesma coluna de Título Dourado (#FACC15) */}
                <div className="w-full flex items-center text-[12.5px] leading-tight">
                  <span className="w-[42px] font-mono font-bold text-white shrink-0">
                    {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                  </span>
                  <span className="font-bold text-[#FACC15] truncate">
                    {ev3 ? ev3.title : 'Livre'}
                  </span>
                </div>
              </div>

              {/* SLOTS #4, #5 e #6: 3 Círculos Distribuídos em Curva (Laterais mais altos, Central mais baixo acompanhando o aro) */}
              <div className="flex items-start justify-center gap-3.5 z-10 -mt-1">
                {/* Slot #4: Círculo Esquerdo (y=246) */}
                <div
                  title="Slot #4: Complication Circular Esquerda"
                  className="w-[62px] h-[62px] rounded-full bg-[#191C20] flex flex-col items-center justify-center p-1"
                >
                  <Footprints className="w-3.5 h-3.5 text-[#67E8F9]" />
                  <span className="text-[12px] font-bold text-white leading-tight mt-0.5">
                    1.486
                  </span>
                </div>

                {/* Slot #5: Círculo Central na Curva (y=266 -> deslocado +14px acompanhando a curvatura redonda) */}
                <div
                  title="Slot #5: Complication Circular Central"
                  className="w-[62px] h-[62px] mt-3.5 rounded-full bg-[#191C20] flex flex-col items-center justify-center p-1"
                >
                  <Sun className="w-3.5 h-3.5 text-[#FACC15]" />
                  <span className="text-[12px] font-bold text-white leading-tight mt-0.5">
                    06:12
                  </span>
                </div>

                {/* Slot #6: Círculo Direito (y=246) */}
                <div
                  title="Slot #6: Complication Circular Direita"
                  className="w-[62px] h-[62px] rounded-full bg-[#191C20] flex flex-col items-center justify-center p-1"
                >
                  <CloudMoon className="w-4 h-4 text-[#67E8F9]" />
                  <span className="text-[13px] font-bold text-white leading-tight mt-0.5">
                    31°
                  </span>
                </div>
              </div>

              {/* Texto percentual à direita do Arco Inferior de Bateria (Slot #9) */}
              <div className="w-full flex items-center justify-center z-10 pb-0.5">
                <span className="ml-36 text-[13px] font-bold font-mono text-[#67E8F9]">
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
