// The one hard rule behind the "no uninstall feature" design: a blocking
// schedule can NEVER cover a full week. At least one minute somewhere in the
// 7-day week must stay free, so there is always a window to reach system
// settings / uninstall. Mirrors the Android ScheduleGuard.
//
// Ranges use the same shape as everywhere else: { days:[0-6, 0=Sun], start:"HH:MM", end:"HH:MM" }.
const MINUTES_PER_DAY = 1440;
const MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY; // 10080

function toMin(hhmm) {
  const [h, m] = String(hhmm).split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
}

function markRange(blocked, range) {
  const days = range.days || [];
  if (!days.length) return;
  const s = toMin(range.start), e = toMin(range.end);
  for (let day = 0; day < 7; day++) {
    if (!days.includes(day)) continue;
    const base = day * MINUTES_PER_DAY;
    // End is INCLUSIVE: a range that ENDS at 23:59 covers the whole day, so it's
    // rejected; you must end at 23:58 or earlier to keep a free minute (23:59).
    if (s === e) {
      for (let m = 0; m < MINUTES_PER_DAY; m++) blocked[base + m] = true;       // all day
    } else if (s < e) {
      for (let m = s; m <= e; m++) blocked[base + m] = true;
    } else {
      // crosses midnight: [s,24:00) today, [0,e] tomorrow
      for (let m = s; m < MINUTES_PER_DAY; m++) blocked[base + m] = true;
      const nextBase = ((day + 1) % 7) * MINUTES_PER_DAY;
      for (let m = 0; m <= e; m++) blocked[nextBase + m] = true;
    }
  }
}

function weeklyFreeMinutes(ranges) {
  if (!ranges || !ranges.length) return MINUTES_PER_WEEK;
  const blocked = new Array(MINUTES_PER_WEEK).fill(false);
  for (const r of ranges) markRange(blocked, r);
  let free = 0;
  for (let i = 0; i < MINUTES_PER_WEEK; i++) if (!blocked[i]) free++;
  return free;
}

function leavesWeeklyGap(ranges) {
  return weeklyFreeMinutes(ranges) >= 1;
}

// Returns an error string if the schedule would seal the whole week, else null.
function scheduleError(scheduleEnabled, ranges) {
  if (!scheduleEnabled) return null;
  const active = (ranges || []).filter(r => (r.days || []).length > 0);
  if (!active.length) return null;
  if (!leavesWeeklyGap(active)) {
    return 'This schedule would block all day, every day. Leave at least one free ' +
      'minute in the week (for example end a day at 23:58) - that minute is the only ' +
      'time you can turn things off or uninstall.';
  }
  return null;
}

module.exports = { weeklyFreeMinutes, leavesWeeklyGap, scheduleError, MINUTES_PER_WEEK };
