import React, { useState } from 'react';
import {
  ComplicationProgressState,
  WatchCacheState,
  CalendarSource,
  SyncConfiguration,
  ContextWindowMode,
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
  CloudRain,
  Sparkles,
  BellRing,
  Zap,
} from 'lucide-react';

interface WearOsSmartwatchSimulatorProps {
  watchState: ComplicationProgressState;
  watchCache: WatchCacheState;
  calendars: CalendarSource[];
  currentWatchTimeMs: number;
  config: SyncConfiguration;
  hasActiveWatchWeatherAlert: boolean;
  onToggleWatchWeatherAlert: () => void;
  onUpdateConfig: (partial: Partial<SyncConfiguration>) => void;
  onToggleBluetooth: () => void;
  onAdvanceTimeMinutes: (minutes: number) => void;
  onResetTime: () => void;
}

export const WearOsSmartwatchSimulator: React.FC<WearOsSmartwatchSimulatorProps> = ({
  watchState,
  watchCache,
  currentWatchTimeMs,
  config,
  hasActiveWatchWeatherAlert,
  onToggleWatchWeatherAlert,
  onUpdateConfig,
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

  const effectiveMode = watchState.effectiveContextMode;

  const contextModes: { id: ContextWindowMode; label: string }[] = [
    { id: 'DYNAMIC', label: 'Auto' },
    { id: 'EVENTS', label: 'Eventos' },
    { id: 'WEATHER', label: 'Clima' },
    { id: 'STEPS', label: 'Passos' },
  ];

  const weeklyStepsData = [
    { day: 'Seg', steps: 6420, isToday: false },
    { day: 'Ter', steps: 8190, isToday: false },
    { day: 'Qua', steps: 7310, isToday: false },
    { day: 'Qui', steps: 9120, isToday: false },
    { day: 'Sex', steps: 8435, isToday: true },
    { day: 'Sáb', steps: 5840, isToday: false },
    { day: 'Dom', steps: 5140, isToday: false },
  ];

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col h-full justify-between">
      {/* Top Header & Autonomous Mode Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-slate-800">
        <div>
          <div className="text-xs font-mono text-sky-400 tracking-wide">
            MÓDULOS :wear &amp; :watchface · JANELA DE CONTEXTO CANVAS (v1.1.0)
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-0.5">
            Smartwatch &amp; Watch Face Format (WFF)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Única Complication de Imagem (604×272px) · Turbo 1 min (≤15m) + Leitor de Notificações do Relógio
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

      {/* Watch Mode Switcher & Quick Context Bar */}
      <div className="space-y-2 my-3">
        <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-xl border border-slate-800/80">
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
            <span>Watch Face v1.1.0</span>
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

        {/* Seletor Rápido da Janela de Contexto + Simulador de Notificação Exclusiva do Relógio */}
        <div className="flex flex-wrap items-center justify-between gap-2 bg-slate-950/90 border border-slate-800/80 rounded-xl px-3 py-2">
          <div className="flex items-center gap-1">
            <span className="text-[11px] font-mono text-slate-400 mr-1">Janela:</span>
            {contextModes.map((m) => {
              const active = config.contextWindowMode === m.id;
              return (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => onUpdateConfig({ contextWindowMode: m.id })}
                  className={`px-2.5 py-1 rounded-lg text-[11px] font-semibold transition-colors cursor-pointer ${
                    active
                      ? 'bg-amber-400 text-slate-950'
                      : 'bg-slate-900 text-slate-300 hover:bg-slate-800'
                  }`}
                >
                  {m.label}
                </button>
              );
            })}
          </div>

          <button
            type="button"
            onClick={onToggleWatchWeatherAlert}
            title="Simula o WearContextNotificationListenerService interceptando uma notificação de mudança climática no relógio"
            className={`px-2.5 py-1 rounded-lg text-[11px] font-semibold flex items-center gap-1.5 border transition-colors cursor-pointer ${
              hasActiveWatchWeatherAlert
                ? 'bg-sky-500/20 border-sky-400 text-sky-300'
                : 'bg-slate-900 border-slate-700 text-slate-300 hover:border-slate-600'
            }`}
          >
            <BellRing className="w-3 h-3 text-sky-400" />
            <span>
              {hasActiveWatchWeatherAlert
                ? 'Notif. Clima no Relógio: ATIVA'
                : 'Simular Notif. Clima (Relógio)'}
            </span>
          </button>
        </div>
      </div>

      {/* Center Circular Watch Bezel (450x450 logical representation matching Samsung 16:22:13) */}
      <div className="flex flex-col items-center justify-center my-1">
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

          {/* TOPO NO DPI SAMSUNG: SEX. 06 (25sp BOLD) + Relógio (70sp NORMAL) */}
          <div className="text-center w-full z-10 pt-0.5">
            <div className="text-[18px] font-bold tracking-wide flex items-center justify-center gap-2 leading-none">
              <span className="text-[#F5C438]">{weekdayUpper}</span>
              <span className="text-white">{dayOfMonth}</span>
            </div>
            <div className="text-[50px] font-normal font-mono tabular-nums tracking-tight text-white leading-none mt-0.5">
              {timeHHMMSS}
            </div>
          </div>

          {/* VIEW 1: Official Watch Face Format (WFF) — Janela de Contexto Dinâmica (Slot 1 SMALL_IMAGE) */}
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

              {/* ÚNICO QUADRO CENTRAL (x=74, y=110, w=302, h=136) — Renderizado 100% pelo Bitmap Transparente */}
              <div
                data-testid="watch-context-canvas"
                className="w-[80%] h-[112px] rounded-[16px] bg-[#080A0E] border border-[#1B2430] px-3 py-1.5 flex flex-col justify-between z-10 -mt-1.5 overflow-hidden"
              >
                {/* MODO 1: PRÓXIMOS EVENTOS (EVENTS) */}
                {effectiveMode === 'EVENTS' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <Calendar className="w-3.5 h-3.5 text-[#F5C438] shrink-0" />
                        <span className="text-[13px] font-bold text-[#F5C438] leading-none">
                          Hoje
                        </span>
                      </div>
                      {watchState.isWithinTurbo15MinWindow && (
                        <span className="text-[9px] font-mono font-bold px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 flex items-center gap-0.5">
                          <Zap className="w-2.5 h-2.5" />
                          1m Turbo
                        </span>
                      )}
                    </div>

                    {/* LINHA 1: Hora Branca + Nome Dourado + Timer Ciano + Barra 5px */}
                    <div className="w-full space-y-0.5">
                      <div className="flex items-center justify-between gap-1 text-[13px] leading-tight">
                        <div className="flex items-center min-w-0">
                          <span className="w-[42px] font-mono font-bold text-white shrink-0">
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
                    <div className="w-full flex items-center text-[13px] leading-tight">
                      <span className="w-[42px] font-mono font-bold text-white shrink-0">
                        {ev2 ? formatTimeHHMM(ev2.startEpochMs) : '--:--'}
                      </span>
                      <span className="font-bold text-[#F5C438] truncate">
                        {ev2 ? ev2.title : 'Livre'}
                      </span>
                    </div>

                    {/* LINHA 3: Hora Branca + Título Dourado */}
                    <div className="w-full flex items-center text-[13px] leading-tight">
                      <span className="w-[42px] font-mono font-bold text-white shrink-0">
                        {ev3 ? formatTimeHHMM(ev3.startEpochMs) : '--:--'}
                      </span>
                      <span className="font-bold text-[#F5C438] truncate">
                        {ev3 ? ev3.title : 'Livre'}
                      </span>
                    </div>
                  </>
                )}

                {/* MODO 2: CURVA DE TEMPERATURA NAS PRÓXIMAS HORAS (WEATHER) */}
                {effectiveMode === 'WEATHER' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <CloudRain className="w-3.5 h-3.5 text-[#67E8F9] shrink-0" />
                        <span className="text-[12px] font-bold text-[#F5C438] leading-none truncate">
                          Clima • Próximas Horas
                        </span>
                      </div>
                      <span className="text-[10px] font-mono font-bold text-[#67E8F9] shrink-0">
                        {hasActiveWatchWeatherAlert ? '🔔 Chuva 18h' : '29° → 23°'}
                      </span>
                    </div>

                    {/* Curva Bezier de Temperatura no Canvas 604x272 */}
                    <div className="relative w-full h-[54px] flex items-center">
                      <svg viewBox="0 0 240 54" className="w-full h-full overflow-visible">
                        <defs>
                          <linearGradient id="tempFill" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#67E8F9" stopOpacity="0.32" />
                            <stop offset="100%" stopColor="#67E8F9" stopOpacity="0.0" />
                          </linearGradient>
                        </defs>
                        <path
                          d="M 18 16 C 42 16, 50 22, 74 22 C 98 22, 106 36, 130 36 C 154 36, 162 42, 186 42 C 202 42, 212 31, 224 31 L 224 50 L 18 50 Z"
                          fill="url(#tempFill)"
                        />
                        <path
                          d="M 18 16 C 42 16, 50 22, 74 22 C 98 22, 106 36, 130 36 C 154 36, 162 42, 186 42 C 202 42, 212 31, 224 31"
                          fill="none"
                          stroke="#67E8F9"
                          strokeWidth="2.5"
                          strokeLinecap="round"
                        />
                        {[
                          { x: 18, y: 16, t: '29°', h: '14h' },
                          { x: 74, y: 22, t: '27°', h: '16h' },
                          { x: 130, y: 36, t: '24°', h: '18h' },
                          { x: 186, y: 42, t: '23°', h: '20h' },
                          { x: 224, y: 31, t: '25°', h: '22h' },
                        ].map((pt) => (
                          <g key={pt.h}>
                            <circle cx={pt.x} cy={pt.y} r="3" fill="#F5C438" />
                            <text
                              x={pt.x}
                              y={pt.y - 5}
                              textAnchor="middle"
                              fill="#FFFFFF"
                              fontSize="9"
                              fontWeight="bold"
                              fontFamily="monospace"
                            >
                              {pt.t}
                            </text>
                          </g>
                        ))}
                      </svg>
                    </div>

                    <div className="flex items-center justify-between text-[9px] font-mono text-slate-400 px-1 leading-none">
                      <span>14h</span>
                      <span>16h</span>
                      <span className="text-sky-300 font-bold">18h 🌧</span>
                      <span>20h</span>
                      <span>22h</span>
                    </div>
                  </>
                )}

                {/* MODO 3: PASSOS SEMANAIS (STEPS) */}
                {effectiveMode === 'STEPS' && (
                  <>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1.5">
                        <Footprints className="w-3.5 h-3.5 text-[#22C55E] shrink-0" />
                        <span className="text-[12px] font-bold text-[#F5C438] leading-none">
                          Passos Semanais
                        </span>
                      </div>
                      <span className="text-[10px] font-mono font-bold text-[#67E8F9]">
                        50.455 total
                      </span>
                    </div>

                    <div className="flex items-end justify-between gap-1.5 h-[56px] pt-2 px-1">
                      {weeklyStepsData.map((d) => {
                        const heightPct = Math.round((d.steps / 9500) * 100);
                        return (
                          <div
                            key={d.day}
                            className="flex-1 flex flex-col items-center justify-end h-full"
                          >
                            <span className="text-[8px] font-mono text-slate-300 mb-0.5 leading-none">
                              {(d.steps / 1000).toFixed(1)}k
                            </span>
                            <div className="w-full h-[32px] bg-[#1C2632] rounded-sm flex items-end overflow-hidden">
                              <div
                                className={`w-full rounded-sm ${
                                  d.isToday ? 'bg-[#F5C438]' : 'bg-[#22C55E]'
                                }`}
                                style={{ height: `${heightPct}%` }}
                              />
                            </div>
                          </div>
                        );
                      })}
                    </div>

                    <div className="flex items-center justify-between text-[9px] font-mono px-1 leading-none">
                      {weeklyStepsData.map((d) => (
                        <span
                          key={d.day}
                          className={
                            d.isToday ? 'text-[#F5C438] font-bold' : 'text-slate-400'
                          }
                        >
                          {d.day}
                        </span>
                      ))}
                    </div>
                  </>
                )}
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
                <div>Contexto Ativo: {effectiveMode}</div>
                <div>
                  Turbo ≤15m (1 min): {watchState.isWithinTurbo15MinWindow ? 'ATIVO ⚡' : 'Aguardando'}
                </div>
                <div className="text-amber-300 truncate">
                  1º: {watchState.nextEvent?.title || 'Nenhum'}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Time-Travel & Autonomous Offline Simulation Bar */}
      <div className="pt-3 border-t border-slate-800">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
            <Sparkles className="w-3.5 h-3.5 text-amber-400" />
            <span>Simulador de Passagem de Tempo (Teste Regra ≤15 min = 1 min)</span>
          </span>
          <button
            type="button"
            onClick={onResetTime}
            className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 cursor-pointer"
          >
            <RotateCcw className="w-3 h-3" />
            <span>Resetar (14:04)</span>
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
            onClick={() => onAdvanceTimeMinutes(135)}
            title="Avança 2h15m (de 14:04 para 16:19, faltando 11 min para Adolescentes às 16:30)"
            className="py-2 px-2.5 bg-amber-500/15 hover:bg-amber-500/25 border border-amber-500/40 rounded-lg text-xs font-mono text-amber-300 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <Zap className="w-3 h-3 text-amber-400" />
            <span>Ir p/ ≤15m</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(1)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-sky-400" />
            <span>+1 min</span>
          </button>
          <button
            type="button"
            onClick={() => onAdvanceTimeMinutes(360)}
            className="py-2 px-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded-lg text-xs font-mono text-slate-200 flex items-center justify-center gap-1 transition-colors cursor-pointer"
          >
            <FastForward className="w-3 h-3 text-sky-400" />
            <span>+6 horas</span>
          </button>
        </div>
      </div>
    </div>
  );
};
