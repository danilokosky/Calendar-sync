/// <reference types="vite/client" />

import settingsGradleRaw from '../../settings.gradle.kts?raw';
import readmeRaw from '../../README.md?raw';
import workflowRaw from '../../.github/workflows/android-build.yml?raw';
import mobileManifestRaw from '../../mobile/src/main/AndroidManifest.xml?raw';
import mobileActivityRaw from '../../mobile/src/main/java/com/chronowear/mobile/ui/MobileSyncConfigActivity.kt?raw';
import mobileEngineRaw from '../../mobile/src/main/java/com/chronowear/mobile/sync/CalendarSyncEngine.kt?raw';
import mobileWorkerRaw from '../../mobile/src/main/java/com/chronowear/mobile/sync/CalendarSyncWorker.kt?raw';
import wearBuildGradleRaw from '../../wear/build.gradle.kts?raw';
import wearManifestRaw from '../../wear/src/main/AndroidManifest.xml?raw';
import wearCapabilitiesXmlRaw from '../../wear/src/main/res/values/wear.xml?raw';
import wearMainActivityRaw from '../../wear/src/main/java/com/chronowear/wear/ui/WearMainActivity.kt?raw';
import wearPhoneDataListenerRaw from '../../wear/src/main/java/com/chronowear/wear/data/PhoneDataListenerService.kt?raw';
import wearComplicationServiceRaw from '../../wear/src/main/java/com/chronowear/wear/complication/NextEventComplicationService.kt?raw';
import watchfaceXmlRaw from '../../watchface/src/main/res/raw/watchface.xml?raw';

export interface ProjectSourceFile {
  path: string;
  module: 'root' | 'mobile' | 'wear' | 'watchface' | 'github';
  language: 'kotlin' | 'xml' | 'gradle' | 'yaml' | 'markdown';
  title: string;
  description: string;
  content: string;
}

export const ANDROID_PROJECT_FILES: ProjectSourceFile[] = [
  {
    path: 'settings.gradle.kts',
    module: 'root',
    language: 'gradle',
    title: 'Configuração Multi-Módulo Gradle',
    description:
      'Define os 3 módulos instaláveis: :mobile (APK Smartphone), :wear (APK exclusivo Wear OS) e :watchface (Watch Face Format WFF).',
    content: settingsGradleRaw,
  },
  {
    path: 'README.md',
    module: 'root',
    language: 'markdown',
    title: 'Documentação Oficial para o GitHub',
    description:
      'Instruções de compilação dos APKs (:mobile, :wear, :watchface), permissões controladas pelo usuário e verificação de app instalado no relógio.',
    content: readmeRaw,
  },
  {
    path: '.github/workflows/android-build.yml',
    module: 'github',
    language: 'yaml',
    title: 'GitHub Actions CI/CD — Build APKs',
    description:
      'Compila automaticamente os 3 APKs separados (Smartphone, Wear OS puro e Watch Face) no GitHub Actions.',
    content: workflowRaw,
  },
  {
    path: 'mobile/src/main/AndroidManifest.xml',
    module: 'mobile',
    language: 'xml',
    title: 'Manifesto do Smartphone (:mobile)',
    description:
      'Declara permissões controladas pelo usuário, serviço de segundo plano e receptor de confirmação de instalação no relógio (/chronowear/pong_watch_app).',
    content: mobileManifestRaw,
  },
  {
    path: 'mobile/src/main/java/com/chronowear/mobile/ui/MobileSyncConfigActivity.kt',
    module: 'mobile',
    language: 'kotlin',
    title: 'MobileSyncConfigActivity.kt (3 Abas no Rodapé + Verificação Wear)',
    description:
      'Interface limpa com apenas as 3 abas no rodapé (Home, Settings, About) e verificação real se o app está instalado no relógio conectado.',
    content: mobileActivityRaw,
  },
  {
    path: 'mobile/src/main/java/com/chronowear/mobile/sync/CalendarSyncEngine.kt',
    module: 'mobile',
    language: 'kotlin',
    title: 'CalendarSyncEngine.kt (Leitura da Agenda + Verificação Capability/Ping)',
    description:
      'Verifica se o relógio conectado possui o app instalado (CONNECTED_WITH_APP_INSTALLED vs WEARABLE_CONNECTED_APP_MISSING) e sincroniza CalendarContract.Instances.',
    content: mobileEngineRaw,
  },
  {
    path: 'mobile/src/main/java/com/chronowear/mobile/sync/CalendarSyncWorker.kt',
    module: 'mobile',
    language: 'kotlin',
    title: 'CalendarSyncWorker.kt (Sincronização Periódica e por Alteração)',
    description:
      'Executa a leitura periódica em segundo plano e reage às alterações de compromissos.',
    content: mobileWorkerRaw,
  },
  {
    path: 'wear/build.gradle.kts',
    module: 'wear',
    language: 'gradle',
    title: 'wear/build.gradle.kts (Compilação Exclusiva Wear OS)',
    description:
      'Configuração Gradle dedicada exclusivamente para o relógio Wear OS com androidx.wear:wear:1.3.0.',
    content: wearBuildGradleRaw,
  },
  {
    path: 'wear/src/main/AndroidManifest.xml',
    module: 'wear',
    language: 'xml',
    title: 'Manifesto Exclusivo Wear OS (:wear)',
    description:
      'Exige android.hardware.type.watch (required="true" com tools:replace) e biblioteca com.google.android.wearable para ser reconhecido como APK puramente Wear OS.',
    content: wearManifestRaw,
  },
  {
    path: 'wear/src/main/res/values/wear.xml',
    module: 'wear',
    language: 'xml',
    title: 'wear.xml (Declaração Oficial de Capability no Relógio)',
    description:
      'Anuncia a capability chronowear_wear_app_installed para que o smartphone saiba se o app está instalado no relógio conectado.',
    content: wearCapabilitiesXmlRaw,
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/ui/WearMainActivity.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'WearMainActivity.kt (Interface Essencial no Relógio)',
    description:
      'Tela enxuta e direta para o smartwatch: mostra apenas o status de conexão, o próximo evento com contagem regressiva e o botão de sincronizar.',
    content: wearMainActivityRaw,
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/data/PhoneDataListenerService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'PhoneDataListenerService.kt (Handshake de Instalação + Cache)',
    description:
      'Responde ao ping do smartphone confirmando que o app está instalado no relógio e grava os eventos recebidos para as Complications.',
    content: wearPhoneDataListenerRaw,
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/complication/NextEventComplicationService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'NextEventComplicationService.kt (Complications RangedValue + LongText)',
    description:
      'Fornece a Complication de contagem regressiva, barra de progresso (1h/2h) e próximos eventos.',
    content: wearComplicationServiceRaw,
  },
  {
    path: 'watchface/src/main/res/raw/watchface.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watchface.xml (Watch Face Format Oficial WFF 450x450)',
    description:
      'Watch Face declarativa com slot para barra de progresso + contagem regressiva e slot amplo para os próximos 3 eventos.',
    content: watchfaceXmlRaw,
  },
];
