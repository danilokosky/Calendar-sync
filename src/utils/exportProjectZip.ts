import JSZip from 'jszip';
import { ANDROID_PROJECT_FILES } from '../data/androidProjectFiles';
import { CalendarSource, SyncConfiguration } from './calendarSyncEngine';

export async function downloadAndroidRepoZip(
  config: SyncConfiguration,
  calendars: CalendarSource[]
): Promise<void> {
  const zip = new JSZip();

  for (const file of ANDROID_PROJECT_FILES) {
    zip.file(file.path, file.content);
  }

  // Also add a pre-configured JSON file with the user's current simulator settings
  const defaultConfigJson = JSON.stringify(
    {
      generatedAt: new Date().toISOString(),
      defaultHorizonDays: config.horizonDays,
      defaultProgressWindowMinutes: config.progressWindowMinutes,
      syncIntervalMinutes: config.syncIntervalMinutes,
      autoSyncOnCalendarChange: config.autoSyncOnCalendarChange,
      preconfiguredCalendars: calendars,
    },
    null,
    2
  );

  zip.file('mobile/src/main/assets/chronowear_defaults.json', defaultConfigJson);

  const blob = await zip.generateAsync({ type: 'blob' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'chronowear-android-wearos-github-repo.zip';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
