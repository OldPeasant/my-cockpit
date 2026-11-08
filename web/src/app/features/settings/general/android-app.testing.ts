import { AndroidAppVersion } from '../../../api/generated';

/** Where the Android app row asks for the version of the downloadable APK. */
export const ANDROID_VERSION_PATH = '/download/android/version';

/** A fictitious deployed APK. */
export function fakeAndroidApp(fields: Partial<AndroidAppVersion> = {}): AndroidAppVersion {
  return {
    versionName: '1.4.0',
    versionCode: 14,
    size: 12_897_485,
    updatedAt: '2026-11-02T09:30:00Z',
    ...fields,
  };
}
