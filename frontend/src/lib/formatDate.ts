const relativeFormatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
const absoluteFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 60 * 60],
  ['month', 30 * 24 * 60 * 60],
  ['week', 7 * 24 * 60 * 60],
  ['day', 24 * 60 * 60],
  ['hour', 60 * 60],
  ['minute', 60],
  ['second', 1],
];

export function formatRelative(iso: string, now: Date = new Date()): string {
  const then = new Date(iso).getTime();
  const diffSeconds = (then - now.getTime()) / 1000;
  const abs = Math.abs(diffSeconds);
  for (const [unit, seconds] of units) {
    if (abs >= seconds || unit === 'second') {
      return relativeFormatter.format(Math.round(diffSeconds / seconds), unit);
    }
  }
  return relativeFormatter.format(0, 'second');
}

export function formatAbsolute(iso: string): string {
  return absoluteFormatter.format(new Date(iso));
}
