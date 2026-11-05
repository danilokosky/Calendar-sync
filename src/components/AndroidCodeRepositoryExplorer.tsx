import React, { useState } from 'react';
import { ANDROID_PROJECT_FILES, ProjectSourceFile } from '../data/androidProjectFiles';
import { Copy, Check, FileCode, Download } from 'lucide-react';

interface AndroidCodeRepositoryExplorerProps {
  onDownloadZip: () => void;
}

export const AndroidCodeRepositoryExplorer: React.FC<AndroidCodeRepositoryExplorerProps> = ({
  onDownloadZip,
}) => {
  const [selectedFilePath, setSelectedFilePath] = useState<string>(
    'wear/src/main/java/com/chronowear/wear/complication/NextEventComplicationService.kt'
  );
  const [copied, setCopied] = useState(false);
  const [moduleFilter, setModuleFilter] = useState<'all' | ProjectSourceFile['module']>('all');

  const filteredFiles =
    moduleFilter === 'all'
      ? ANDROID_PROJECT_FILES
      : ANDROID_PROJECT_FILES.filter((f) => f.module === moduleFilter);

  const activeFile =
    ANDROID_PROJECT_FILES.find((f) => f.path === selectedFilePath) || ANDROID_PROJECT_FILES[0];

  const handleCopyCode = async () => {
    await navigator.clipboard.writeText(activeFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };

  return (
    <section id="codigo-android" className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden">
      {/* Top Header of Code Explorer */}
      <div className="p-6 border-b border-slate-800 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-100">
            Código-Fonte Multi-Módulo Android &amp; Wear OS (Pronto para o GitHub)
          </h2>
          <p className="text-sm text-slate-400 mt-1">
            Inspecione os arquivos Kotlin, o manifesto WFF (<code className="text-amber-300">watchface.xml</code>) e o workflow GitHub Actions que gera os 3 APKs automaticamente.
          </p>
        </div>

        <button
          type="button"
          onClick={onDownloadZip}
          className="px-4 py-2.5 bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs rounded-lg flex items-center gap-2 transition-colors cursor-pointer whitespace-nowrap shrink-0"
        >
          <Download className="w-4 h-4" />
          <span>Baixar Projeto GitHub (.ZIP)</span>
        </button>
      </div>

      {/* Filter Bar */}
      <div className="px-6 py-3 bg-slate-950/60 border-b border-slate-800 flex flex-wrap items-center gap-2">
        {(
          [
            { id: 'all', label: 'Todos os Módulos' },
            { id: 'mobile', label: ':mobile (APK Smartphone)' },
            { id: 'wear', label: ':wear (Complications + Tile + Room)' },
            { id: 'watchface', label: ':watchface (WFF XML)' },
            { id: 'github', label: 'GitHub Actions CI' },
          ] as const
        ).map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setModuleFilter(tab.id)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors whitespace-nowrap cursor-pointer ${
              moduleFilter === tab.id
                ? 'bg-slate-800 text-amber-400'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Split Explorer */}
      <div className="grid grid-cols-1 lg:grid-cols-12">
        {/* Left File Tree */}
        <div className="lg:col-span-4 border-b lg:border-b-0 lg:border-r border-slate-800 bg-slate-950/40 divide-y divide-slate-800/60">
          {filteredFiles.map((file) => {
            const isSelected = file.path === activeFile.path;
            return (
              <button
                key={file.path}
                type="button"
                onClick={() => setSelectedFilePath(file.path)}
                className={`w-full text-left px-5 py-3.5 transition-colors flex items-start gap-3 cursor-pointer ${
                  isSelected
                    ? 'bg-slate-800/80 text-amber-300'
                    : 'hover:bg-slate-900/70 text-slate-300'
                }`}
              >
                <FileCode className="w-4 h-4 mt-0.5 shrink-0 text-amber-400" />
                <div className="min-w-0">
                  <div className="text-xs font-mono font-semibold truncate">
                    {file.path}
                  </div>
                  <div className="text-xs text-slate-400 mt-0.5 line-clamp-2">
                    {file.title}
                  </div>
                </div>
              </button>
            );
          })}
        </div>

        {/* Right Code Viewer */}
        <div className="lg:col-span-8 flex flex-col bg-[#070a12]">
          <div className="px-5 py-3.5 border-b border-slate-800 flex items-center justify-between gap-4 bg-slate-950">
            <div className="min-w-0">
              <div className="text-xs font-mono text-amber-400 truncate">
                {activeFile.path}
              </div>
              <div className="text-xs text-slate-400 mt-0.5">
                {activeFile.description}
              </div>
            </div>

            <button
              type="button"
              onClick={handleCopyCode}
              className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium flex items-center gap-1.5 shrink-0 transition-colors cursor-pointer"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-400" />
                  <span>Copiado</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  <span>Copiar Arquivo</span>
                </>
              )}
            </button>
          </div>

          <pre className="p-5 text-xs font-mono text-slate-200 overflow-x-auto leading-relaxed max-h-[520px] overflow-y-auto">
            <code>{activeFile.content}</code>
          </pre>
        </div>
      </div>
    </section>
  );
};
