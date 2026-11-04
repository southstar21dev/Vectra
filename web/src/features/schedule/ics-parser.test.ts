import { describe, expect, it } from 'vitest';
import { parseICSContent, parseICSDurationMs, parseICSTimestamp } from './ics-parser';

describe('ICS Parser & irregular class schedule sync', () => {
  it('includes exams by default and preserves each exam date independently from classes', () => {
    const icsContent = `BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:exam-1\nDTSTART:20261001T100000\nDTEND:20261001T120000\nSUMMARY:Accounting (Írásbeli) - Dr. Smith - Vizsga\nLOCATION:A.101\nEND:VEVENT\nBEGIN:VEVENT\nUID:exam-2\nDTSTART:20261015T100000\nDTEND:20261015T120000\nSUMMARY:Accounting (Írásbeli) - Dr. Smith - Vizsga\nLOCATION:A.101\nEND:VEVENT\nBEGIN:VEVENT\nUID:class-1\nDTSTART:20261001T100000\nDTEND:20261001T120000\nSUMMARY:Accounting ( - ACCT101-E01) - Dr. Smith - Tanóra\nLOCATION:A.101\nEND:VEVENT\nEND:VCALENDAR`;
    const result = parseICSContent(icsContent);
    const exams = result.valid.filter((event) => event.event_type === 'exam');
    const classes = result.valid.filter((event) => event.event_type === 'class');
    expect(exams).toHaveLength(2);
    expect(exams.map((event) => [event.semester_start, event.semester_end])).toEqual([
      ['2026-10-01', '2026-10-01'],
      ['2026-10-15', '2026-10-15'],
    ]);
    expect(classes).toHaveLength(1);
    expect(parseICSContent(icsContent, { includeExams: false }).valid.every((event) => event.event_type !== 'exam')).toBe(true);
  });

  it('keeps repeated same-day exam slots distinct by time', () => {
    const icsContent = `BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:exam-am\nDTSTART:20261001T090000\nDTEND:20261001T100000\nSUMMARY:Accounting (Írásbeli) - Dr. Smith - Vizsga\nEND:VEVENT\nBEGIN:VEVENT\nUID:exam-pm\nDTSTART:20261001T130000\nDTEND:20261001T140000\nSUMMARY:Accounting (Írásbeli) - Dr. Smith - Vizsga\nEND:VEVENT\nEND:VCALENDAR`;
    expect(parseICSContent(icsContent).valid).toHaveLength(2);
  });


  it('correctly splits classes that only meet on specific irregular weeks into separate active ranges', () => {
    // 4 sessions of Organisational interpersonal skills dev G04 across the semester
    // Week 2: 2026-09-15
    // Week 6: 2026-10-13
    // Week 9: 2026-11-03
    // Week 13: 2026-12-01
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:g04-session-1
DTSTART:20260915T080000
DTEND:20260915T093000
SUMMARY:Organisational interpersonal skills dev ( - 7SO20NGMA01M-G04) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
BEGIN:VEVENT
UID:g04-session-2
DTSTART:20261013T080000
DTEND:20261013T093000
SUMMARY:Organisational interpersonal skills dev ( - 7SO20NGMA01M-G04) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
BEGIN:VEVENT
UID:g04-session-3
DTSTART:20261103T080000
DTEND:20261103T093000
SUMMARY:Organisational interpersonal skills dev ( - 7SO20NGMA01M-G04) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
BEGIN:VEVENT
UID:g04-session-4
DTSTART:20261201T080000
DTEND:20261201T093000
SUMMARY:Organisational interpersonal skills dev ( - 7SO20NGMA01M-G04) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);

    // It should not collapse all 4 non-consecutive dates into a single full-semester range!
    // Instead, it should create distinct clusters corresponding to the actual meeting dates.
    const g04Events = result.valid.filter((e) =>
      e.course_name.toLowerCase().includes('organisational')
    );

    expect(g04Events.length).toBe(4);
    expect(g04Events[0].semester_start).toBe('2026-09-15');
    expect(g04Events[0].semester_end).toBe('2026-09-15');
    expect(g04Events[1].semester_start).toBe('2026-10-13');
    expect(g04Events[1].semester_end).toBe('2026-10-13');
    expect(g04Events[2].semester_start).toBe('2026-11-03');
    expect(g04Events[2].semester_end).toBe('2026-11-03');
    expect(g04Events[3].semester_start).toBe('2026-12-01');
    expect(g04Events[3].semester_end).toBe('2026-12-01');

    // Test filtering on an off-week: Week 3 (Sep 21-27) should NOT include any G04 event!
    const week3Range = { startStr: '2026-09-21', endStr: '2026-09-27' };
    const week3Matches = g04Events.filter((e) => {
      if (e.semester_start && week3Range.endStr < e.semester_start) return false;
      if (e.semester_end && week3Range.startStr > e.semester_end) return false;
      return true;
    });
    expect(week3Matches.length).toBe(0);

    // Week 2 (Sep 14-20) should include exactly 1 event
    const week2Range = { startStr: '2026-09-14', endStr: '2026-09-20' };
    const week2Matches = g04Events.filter((e) => {
      if (e.semester_start && week2Range.endStr < e.semester_start) return false;
      if (e.semester_end && week2Range.startStr > e.semester_end) return false;
      return true;
    });
    expect(week2Matches.length).toBe(1);
  });

  it('keeps continuous weekly classes grouped in a single semester range', () => {
    // 5 consecutive weekly sessions of Calculus
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:calc-1
DTSTART:20260908T080000
DTEND:20260908T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
LOCATION:Auditorium
END:VEVENT
BEGIN:VEVENT
UID:calc-2
DTSTART:20260915T080000
DTEND:20260915T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
LOCATION:Auditorium
END:VEVENT
BEGIN:VEVENT
UID:calc-3
DTSTART:20260922T080000
DTEND:20260922T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
LOCATION:Auditorium
END:VEVENT
BEGIN:VEVENT
UID:calc-4
DTSTART:20260929T080000
DTEND:20260929T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
LOCATION:Auditorium
END:VEVENT
BEGIN:VEVENT
UID:calc-5
DTSTART:20261006T080000
DTEND:20261006T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
LOCATION:Auditorium
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);
    const calcEvents = result.valid.filter((e) => e.course_name.includes('Calculus'));

    // Consecutive weekly sessions (7 days apart) must stay clustered together
    expect(calcEvents.length).toBe(1);
    expect(calcEvents[0].semester_start).toBe('2026-09-08');
    expect(calcEvents[0].semester_end).toBe('2026-10-06');
  });

  it('correctly handles intensive 1-week block courses (like G10)', () => {
    // 3 sessions on the SAME day of the intensive week (e.g. Mon 08:00, Mon 09:50, Mon 11:40)
    // or same slot on consecutive weeks. Let's test a slot in 1 week:
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:g10-1
DTSTART:20260907T080000
DTEND:20260907T093000
SUMMARY:Organisational interpersonal skills dev ( - G10) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);
    const g10Events = result.valid.filter((e) => e.course_code.includes('G10'));

    expect(g10Events.length).toBe(1);
    expect(g10Events[0].semester_start).toBe('2026-09-07');
    expect(g10Events[0].semester_end).toBe('2026-09-07');
  });

  it('correctly parses course codes with or without hyphen inside parentheses', () => {
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:evt-1
DTSTART:20260915T080000
DTEND:20260915T093000
SUMMARY:Organisational interpersonal skills dev (G04) - Dr. Smith - Tanóra
LOCATION:C.101
END:VEVENT
BEGIN:VEVENT
UID:evt-2
DTSTART:20260916T080000
DTEND:20260916T093000
SUMMARY:Microeconomics ( - ECON101-E02) - Dr. Jones - Tanóra
LOCATION:C.202
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);
    expect(result.valid[0].course_code).toBe('G04');
    expect(result.valid[0].course_name).toBe('Organisational interpersonal skills dev');
    expect(result.valid[0].instructor).toBe('Dr. Smith');

    expect(result.valid[1].course_code).toBe('ECON101-E02');
    expect(result.valid[1].course_name).toBe('Microeconomics');
    expect(result.valid[1].instructor).toBe('Dr. Jones');
  });

  it('correctly splits bi-weekly classes into separate week clusters', () => {
    // 3 bi-weekly sessions (every 14 days)
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:bi-1
DTSTART:20260915T100000
DTEND:20260915T113000
SUMMARY:Business Ethics ( - ETH101-G01) - Tanóra
LOCATION:E.201
END:VEVENT
BEGIN:VEVENT
UID:bi-2
DTSTART:20260929T100000
DTEND:20260929T113000
SUMMARY:Business Ethics ( - ETH101-G01) - Tanóra
LOCATION:E.201
END:VEVENT
BEGIN:VEVENT
UID:bi-3
DTSTART:20261013T100000
DTEND:20261013T113000
SUMMARY:Business Ethics ( - ETH101-G01) - Tanóra
LOCATION:E.201
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);
    const biEvents = result.valid.filter((e) => e.course_name.includes('Business Ethics'));

    expect(biEvents.length).toBe(3);
    expect(biEvents[0].semester_start).toBe('2026-09-15');
    expect(biEvents[0].semester_end).toBe('2026-09-15');
    expect(biEvents[1].semester_start).toBe('2026-09-29');
    expect(biEvents[1].semester_end).toBe('2026-09-29');
    expect(biEvents[2].semester_start).toBe('2026-10-13');
    expect(biEvents[2].semester_end).toBe('2026-10-13');
  });

  it('treats English-titled exams and exam-type parentheticals as exams', () => {
    // English-titled Neptun exams never carry the Hungarian "- Vizsga" suffix,
    // so they used to be imported as weekly classes and drew a CLASS badge.
    const icsContent = `BEGIN:VCALENDAR
BEGIN:VEVENT
UID:en-exam
DTSTART:20261110T095000
DTEND:20261110T112000
SUMMARY:Elements of Accounting Midterm Exam - Feladat - Tanóra
END:VEVENT
BEGIN:VEVENT
UID:oral-exam
DTSTART:20261112T100000
DTEND:20261112T110000
SUMMARY:Legal Studies (Szóbeli) - Dr. Kiss - Vizsga
END:VEVENT
BEGIN:VEVENT
UID:plain-class
DTSTART:20261103T080000
DTEND:20261103T093000
SUMMARY:Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra
END:VEVENT
END:VCALENDAR`;

    const result = parseICSContent(icsContent);
    const exam = result.valid.find((event) => event.course_name.includes('Midterm Exam'));
    const oral = result.valid.find((event) => event.course_name.startsWith('Legal Studies'));
    const plain = result.valid.find((event) => event.course_name === 'Calculus I');

    expect(exam?.event_type).toBe('exam');
    expect(exam?.instructor).toBe('Feladat');
    expect([exam?.semester_start, exam?.semester_end]).toEqual(['2026-11-10', '2026-11-10']);
    expect(oral?.event_type).toBe('exam');
    expect(plain?.event_type).toBe('class');
    expect(plain?.course_code).toBe('MATH101-E01');
  });

  it('reads the Neptun code parenthetical when the title also has a subtitle', () => {
    // The title's own "(Probability and Statistics)" came first in the string, so
    // matching the first parenthetical read it as the code: the name kept a
    // dangling "( - E01)", the instructor began mid-parenthetical, and the card
    // lost its group badge.
    const icsContent = `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Neptun//Corvinus//EN
BEGIN:VEVENT
UID:sub-1
DTSTART:20260922T080000
DTEND:20260922T093000
SUMMARY:Data Analysis II. (Probability and Statistics) ( - E01) - Takács Tibor - Tanóra
LOCATION:E.0.IV.ea.
END:VEVENT
END:VCALENDAR`;

    const [event] = parseICSContent(icsContent).valid;

    expect(event.course_code).toBe('E01');
    expect(event.course_name).toBe('Data Analysis II. (Probability and Statistics)');
    expect(event.instructor).toBe('Takács Tibor');
    expect(event.location).toBe('E.0.IV.ea.');
  });
});


describe('ICS timestamps and text escapes', () => {
  const wrap = (lines: string[]) => ['BEGIN:VCALENDAR', 'BEGIN:VEVENT', ...lines, 'END:VEVENT', 'END:VCALENDAR'].join('\r\n');

  it('reads floating and Budapest TZID times as Budapest wall clock', () => {
    expect(parseICSTimestamp('20251006T080000')?.timeStr).toBe('08:00');
    expect(parseICSTimestamp('DTSTART;TZID=Europe/Budapest:20251006T080000')?.timeStr).toBe('08:00');
    expect(parseICSTimestamp('DTSTART;TZID=Europe/Budapest:20251006T080000')?.dayOfWeek).toBe(1);
  });

  it('converts other TZIDs and UTC stamps into Budapest time', () => {
    // 06:00 UTC on 6 Oct 2025 is 08:00 in Budapest (CEST, UTC+2).
    expect(parseICSTimestamp('DTSTART;TZID=UTC:20251006T060000')?.timeStr).toBe('08:00');
    expect(parseICSTimestamp('20251006T060000Z')?.timeStr).toBe('08:00');
    // An unknown zone name falls back to Budapest.
    expect(parseICSTimestamp('DTSTART;TZID=Central Europe Standard Time:20251006T080000')?.timeStr).toBe('08:00');
  });

  it('keeps the TZID on DTSTART/DTEND and unescapes text properties', () => {
    const result = parseICSContent(wrap([
      'DTSTART;TZID=Europe/Budapest:20251006T080000',
      'DTEND;TZID=Europe/Budapest:20251006T093000',
      'SUMMARY:Calculus I ( - MATH101-E01) - Dr. Example - Tanóra',
      'LOCATION:Building C\\, Room 101',
    ]));
    expect(result.valid[0].start_time).toBe('08:00');
    expect(result.valid[0].end_time).toBe('09:30');
    expect(result.valid[0].location).toBe('Building C, Room 101');
  });
});

describe('ICS recurrence, durations and edge cases', () => {
  const calendar = (...events: string[][]) =>
    ['BEGIN:VCALENDAR', ...events.flatMap((lines) => ['BEGIN:VEVENT', ...lines, 'END:VEVENT']), 'END:VCALENDAR'].join('\r\n');
  const CLASS = 'SUMMARY:Calculus I ( - MATH101-E01) - Dr. Example - Tanóra';

  it('expands a weekly RRULE with COUNT into one clustered range like a pre-expanded feed', () => {
    const result = parseICSContent(calendar([
      'UID:calc',
      'DTSTART;TZID=Europe/Budapest:20250908T080000',
      'DTEND;TZID=Europe/Budapest:20250908T093000',
      'RRULE:FREQ=WEEKLY;COUNT=12',
      CLASS,
    ]));
    expect(result.valid).toHaveLength(1);
    expect(result.valid[0]).toMatchObject({
      day_of_week: 1,
      start_time: '08:00',
      end_time: '09:30',
      semester: '2025/26/1',
      semester_start: '2025-09-08',
      semester_end: '2025-11-24',
    });
  });

  it('keeps the Budapest wall-clock time across the DST change', () => {
    const result = parseICSContent(calendar([
      'UID:dst',
      'DTSTART;TZID=Europe/Budapest:20251020T080000',
      'DTEND;TZID=Europe/Budapest:20251020T093000',
      'RRULE:FREQ=WEEKLY;UNTIL=20251110T235959Z',
      CLASS,
    ]));
    expect(result.valid).toHaveLength(1);
    expect(result.valid[0].start_time).toBe('08:00');
    expect(result.valid[0].semester_end).toBe('2025-11-10');
  });

  it('honours UNTIL, INTERVAL and EXDATE (gaps split the range like Neptun skipped weeks)', () => {
    const result = parseICSContent(calendar([
      'UID:biweekly',
      'DTSTART:20250908T080000',
      'DTEND:20250908T093000',
      'RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20251020',
      'EXDATE:20250922T080000',
      CLASS,
    ]));
    // Sep 8, (Sep 22 excluded), Oct 6, Oct 20 — every gap is > 8 days.
    expect(result.valid.map((e) => e.semester_start)).toEqual(['2025-09-08', '2025-10-06', '2025-10-20']);
  });

  it('expands BYDAY into one row per weekday and supports FREQ=DAILY', () => {
    const weekly = parseICSContent(calendar([
      'UID:mw',
      'DTSTART:20250908T100000',
      'DTEND:20250908T113000',
      'RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4',
      CLASS,
    ]));
    expect(weekly.valid.map((e) => [e.day_of_week, e.semester_start, e.semester_end])).toEqual([
      [1, '2025-09-08', '2025-09-15'],
      [3, '2025-09-10', '2025-09-17'],
    ]);

    const daily = parseICSContent(calendar([
      'UID:block',
      'DTSTART:20250908T080000',
      'DTEND:20250908T093000',
      'RRULE:FREQ=DAILY;COUNT=5',
      CLASS,
    ]));
    expect(daily.valid.map((e) => e.day_of_week).sort()).toEqual([1, 2, 3, 4, 5]);
  });

  it('lets a RECURRENCE-ID override replace its occurrence', () => {
    const result = parseICSContent(calendar(
      ['UID:s', 'DTSTART:20250908T080000', 'DTEND:20250908T093000', 'RRULE:FREQ=WEEKLY;COUNT=2', CLASS],
      ['UID:s', 'RECURRENCE-ID:20250915T080000', 'DTSTART:20250915T120000', 'DTEND:20250915T133000', CLASS]
    ));
    expect(result.valid.map((e) => [e.semester_start, e.start_time]).sort()).toEqual([
      ['2025-09-08', '08:00'],
      ['2025-09-15', '12:00'],
    ]);
  });

  it('uses DURATION when DTEND is missing', () => {
    expect(parseICSDurationMs('PT1H30M')).toBe(90 * 60_000);
    expect(parseICSDurationMs('P1D')).toBe(86_400_000);
    expect(parseICSDurationMs('P')).toBeNull();
    const result = parseICSContent(calendar(['DTSTART:20250908T080000', 'DURATION:PT1H30M', CLASS]));
    expect(result.valid[0].end_time).toBe('09:30');
  });

  it('skips cancelled and all-day events', () => {
    const result = parseICSContent(calendar(
      ['DTSTART:20250908T080000', 'DTEND:20250908T093000', 'STATUS:CANCELLED', CLASS],
      ['DTSTART;VALUE=DATE:20250909', 'DTEND;VALUE=DATE:20250910', 'SUMMARY:Registration week'],
      ['DTSTART:20250910', 'DTEND:20250911', 'SUMMARY:Dean\'s day']
    ));
    expect(result.valid).toHaveLength(0);
    expect(result.errors).toHaveLength(0);
  });

  it('clamps an event ending at midnight to the end of its day', () => {
    const result = parseICSContent(calendar(['DTSTART:20250908T220000', 'DTEND:20250909T000000', CLASS]));
    expect(result.valid[0].start_time).toBe('22:00');
    expect(result.valid[0].end_time).toBe('23:59');
  });

  it('reads a quoted TZID that contains a colon', () => {
    const info = parseICSTimestamp('DTSTART;TZID="Europe/Budapest:legacy":20251006T080000');
    expect(info?.dateStr).toBe('2025-10-06');
    expect(info?.timeStr).toBe('08:00');
    const result = parseICSContent(calendar([
      'DTSTART;TZID="UTC":20251006T060000',
      'DTEND;TZID="UTC":20251006T073000',
      CLASS,
    ]));
    expect(result.valid[0].start_time).toBe('08:00');
    expect(result.valid[0].end_time).toBe('09:30');
  });

  it('ignores properties of nested alarms', () => {
    const result = parseICSContent(calendar([
      'DTSTART:20250908T080000',
      'DTEND:20250908T093000',
      CLASS,
      'LOCATION:C.101',
      'BEGIN:VALARM',
      'DESCRIPTION:Reminder',
      'LOCATION:nowhere',
      'END:VALARM',
    ]));
    expect(result.valid[0].location).toBe('C.101');
  });
});
