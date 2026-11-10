# ChronoWear — Sincronizador Autônomo de Calendário & Tarefas (Android + Wear OS)

Este repositório contém a árvore Gradle multi-módulo real na raiz (pronta para compilar via **GitHub Actions** ou **Android Studio**) além do simulador interativo web:

- **`:mobile` (`/mobile`)**: APK para Smartphone Android com seleção de calendários (`CalendarContract.Calendars`), sincronização periódica via `WorkManager` (`CalendarSyncWorker`) e envio de horizonte configurável (**2, 3, 5 ou 7 dias**) + janela de barra de progresso (**30m, 1h, 2h, 3h**) via Wearable `DataClient`.
- **`:wear` (`/wear`)**: APK para Smartwatch Wear OS com banco SQLite local (`WatchAutonomousRepository`), reagendamento autônomo via `AlarmManager` (`AutonomousCountdownScheduler`), `NextEventComplicationService` (`RANGED_VALUE` + `LONG_TEXT`) e `UpNextThreeEventsTileService` (Tile com os **próximos 3 eventos**).
- **`:watchface` (`/watchface`)**: APK Watch Face Format (WFF) declarativo (`watchface.xml`) projetado para exibir no topo o tempo restante + barra de progresso e no centro uma janela ampla com os **próximos 3 eventos**.

## Como gerar os 3 APKs automaticamente no GitHub

Ao fazer `git push` deste projeto para o seu repositório no GitHub:
1. O workflow `.github/workflows/android-build.yml` é disparado automaticamente.
2. Ele configura o **JDK 17** e o **Gradle 8.7** via `gradle/actions/setup-gradle@v4` (não depende de `gradlew` local).
3. Na aba **Actions** do seu repositório GitHub, basta baixar os 3 artefatos gerados:
   - `chronowear-mobile-apk`
   - `chronowear-wear-apk`
   - `chronowear-watchface-wff-apk`

## Credencial OAuth 2.0 Android (Google Tasks API)

- **Projeto Google Cloud**: `gen-lang-client-0127839135`
- **Nome do pacote (`applicationId`)**: `com.chronowear.sync`
- **Impressão digital do certificado SHA-1**: `EC:66:EE:98:17:74:F1:C2:B2:10:D7:1D:19:0E:02:8D:F3:98:4D:71`

