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
import watchfaceBuildGradleRaw from '../../watchface/build.gradle.kts?raw';
import watchfaceManifestRaw from '../../watchface/src/main/AndroidManifest.xml?raw';
import watchfaceInfoXmlRaw from '../../watchface/src/main/res/xml/watch_face_info.xml?raw';
import watchfaceStringsXmlRaw from '../../watchface/src/main/res/values/strings.xml?raw';
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
    title: 'Manifesto Exclusivo Wear OS (:wear) — Única Complication Canvas + NotificationListener',
    description:
      'Expõe apenas 1 Complication (ChronoWear • Janela de Contexto (Canvas), SMALL_IMAGE/PHOTO_IMAGE) e registra o WearContextNotificationListenerService para ler notificações no próprio relógio.',
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
    title: 'WearMainActivity.kt (Seletor de Contexto + Regra ≤15m + Permissão de Notificações)',
    description:
      'Permite alternar no próprio relógio o modo da Janela de Contexto (Dinâmico, Eventos, Clima, Passos), ativar o Turbo de 1 min (≤15m) e abrir a permissão de leitura de notificações.',
    content: wearMainActivityRaw,
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/data/PhoneDataListenerService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'PhoneDataListenerService.kt + WearContextNotificationListenerService',
    description:
      'Sincroniza configurações/eventos com o smartphone e intercepta notificações diretamente no relógio (ex: alerta de chuva ou mudança brusca de temperatura) para alternar o Canvas.',
    content: wearPhoneDataListenerRaw,
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/complication/NextEventComplicationService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'NextEventComplicationService.kt (Renderizador Canvas Bitmap 604×272px Multi-Contexto)',
    description:
      'Desenha em Bitmap ARGB_8888 transparente o contexto ativo: Próximos 3 Eventos com barra de 5px, Curva Bezier de Temperatura nas Próximas Horas ou Gráfico de Passos Semanais.',
    content: wearComplicationServiceRaw,
  },
  {
    path: 'watchface/build.gradle.kts',
    module: 'watchface',
    language: 'gradle',
    title: 'watchface/build.gradle.kts (isMinifyEnabled=true para hasCode=false)',
    description:
      'Habilita isMinifyEnabled=true no debug e release para remover classes.dex vazio, requisito obrigatório para o Wear OS reconhecer pacotes WFF.',
    content: watchfaceBuildGradleRaw,
  },
  {
    path: 'watchface/src/main/AndroidManifest.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watchface/src/main/AndroidManifest.xml (Manifesto WFF)',
    description:
      'Declara android:hasCode="false", standalone="true" e com.google.wear.watchface.format.version="1".',
    content: watchfaceManifestRaw,
  },
  {
    path: 'watchface/src/main/res/xml/watch_face_info.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watch_face_info.xml (Metadados Obrigatórios do Seletor de Watch Face)',
    description:
      'Define o recurso de Preview (@drawable/preview) e Editable="true" exigidos pelo seletor de Watch Faces do Wear OS.',
    content: watchfaceInfoXmlRaw,
  },
  {
    path: 'watchface/src/main/res/values/strings.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watchface/src/main/res/values/strings.xml',
    description:
      'Define @string/watch_face_name e os rótulos dos slots de Complication.',
    content: watchfaceStringsXmlRaw,
  },
  {
    path: 'watchface/src/main/res/raw/watchface.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watchface.xml (Watch Face Format Oficial Validado no XSD)',
    description:
      'Watch Face declarativa validada contra o XSD oficial do Google/Samsung (DefaultProviderPolicy, BoundingRoundBox, RoundRectangle e Transform).',
    content: watchfaceXmlRaw,
  },
];
