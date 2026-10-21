const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/**
 * An attachment size for people: up to 1 decimal below 100, powers of 1024 (as file managers do).
 * Kept here so the mail feature does not depend on the internals of the files feature.
 */
export function formatBytes(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit === 0 || value >= 100 ? 0 : 1;
  return `${value.toFixed(digits).replace(/\.0$/, '')} ${UNITS[unit]}`;
}
