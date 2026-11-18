import Foundation

// The time-spine layout the widgets share. Mirrors shared/…/features/widgets/WidgetSpine.kt; keep
// the two in step.

enum SpineRow: Hashable {
    case past(Session)
    case now(Session)
    case next(Session)
    case gap(Int)
    /// A later day's name, before its rows.
    case day(String)

    var session: Session? {
        switch self {
        case .past(let s), .now(let s), .next(let s): s
        case .gap, .day: nil
        }
    }

    var isFiller: Bool { session == nil }
    var isDay: Bool { if case .day = self { true } else { false } }
}

extension Optional where Wrapped == Snapshot {
    /// Today while anything is on it, else the next day that has something.
    func dayShown(_ today: String) -> String {
        sessions(on: today).isEmpty ? (firstDay(after: today) ?? today) : today
    }

    /// The day's timed sessions as spine rows, with a break wherever there is real time between two.
    func spine(_ day: String, now: Date) -> [SpineRow] {
        let timed = sessions(on: day).filter(\.kind.isTimed)
        var rows: [SpineRow] = []
        for (i, s) in timed.enumerated() {
            if i > 0, let gap = breakMinutes(timed[i - 1], s) { rows.append(.gap(gap)) }
            rows.append(s.end <= now ? .past(s) : (s.start <= now ? .now(s) : .next(s)))
        }
        return rows
    }

    /// `day`'s spine, then the next few days that have something, each under its name.
    func agendaRows(_ day: String, now: Date, laterDays: Int = 3) -> [SpineRow] {
        var seen = Set<String>()
        let later = (self?.sessions ?? []).filter { $0.day > day && $0.kind.isTimed }.map(\.day).filter { seen.insert($0).inserted }.prefix(laterDays)
        return spine(day, now: now) + later.flatMap { [SpineRow.day($0)] + spine($0, now: now) }
    }

    func openTasks(_ day: String, now: Date) -> [Session] {
        sessions(on: day).filter { $0.kind == .task && $0.end > now }
    }

    /// Timed sessions on `day` already over, and how many there are.
    func done(_ day: String, now: Date) -> (done: Int, total: Int) {
        let timed = sessions(on: day).filter(\.kind.isTimed)
        return (timed.filter { $0.end <= now }.count, timed.count)
    }

    func exams(after exam: Session, limit: Int) -> [Session] {
        Array((self?.sessions ?? []).filter { $0.kind == .exam && $0.start > exam.start }.prefix(limit))
    }

    /// The week timetable with the hours it spans (whole hours, at least six).
    func weekGrid(now: Date) -> WeekGrid {
        let all = (self?.signedIn ?? false) ? self!.sessions : []
        let columns = week(now: now).days.map { d in
            WeekColumn(day: d, blocks: all.filter { $0.day == d.date && $0.kind.isTimed }.map { s in
                let start = Budapest.minuteOfDay(s.start)
                let end = min(start + Int(s.end.timeIntervalSince(s.start) / 60), 24 * 60)
                return WeekBlock(kind: s.kind, startMin: start, endMin: end, label: courseAbbrev(s.title), done: s.end <= now)
            })
        }
        let blocks = columns.flatMap(\.blocks)
        var from = blocks.map { $0.startMin / 60 }.min() ?? 8
        var to = blocks.map { ($0.endMin + 59) / 60 }.max() ?? 18
        if to - from < 6 {
            to = min(from + 6, 24)
            from = max(to - 6, 0)
        }
        return WeekGrid(columns: columns, fromHour: from, toHour: to)
    }
}

struct WeekBlock: Hashable {
    let kind: Kind
    let startMin: Int
    let endMin: Int
    let label: String
    let done: Bool
}

struct WeekColumn: Identifiable {
    let day: WeekDay
    let blocks: [WeekBlock]
    var id: String { day.date }
}

struct WeekGrid {
    let columns: [WeekColumn]
    let fromHour: Int
    let toHour: Int
    var weekendFree: Bool { columns.count <= 5 }
}

/// A ring's big number and the word under it: "55" MIN, "2:25" (hours and minutes), or days.
func ringLabel(_ seconds: TimeInterval, hoursWord: String) -> (value: String, unit: String) {
    let minutes = max(Int((seconds / 60).rounded(.up)), 0)
    if minutes < 60 { return ("\(minutes)", "MIN") }
    if minutes < 24 * 60 { return ("\(minutes / 60):\(String(format: "%02d", minutes % 60))", hoursWord) }
    let days = (minutes + 24 * 60 - 1) / (24 * 60)
    return ("\(days)", days == 1 ? "DAY" : "DAYS")
}

private let minorWords: Set<String> = ["and", "of", "the", "to", "in", "for", "a", "an", "és", "az"]

/// A course's label for a small timetable block: "Data Structures" DS, "Microeconomics" MIC.
func courseAbbrev(_ title: String) -> String {
    let words = title.split(whereSeparator: { " -/,".contains($0) }).map(String.init).filter { !minorWords.contains($0.lowercased()) }
    let main = words.filter { w in w.count >= 3 && (w.first?.isLetter ?? false) && !w.allSatisfy { "IVX".contains($0) } }
    if main.isEmpty { return String(title.prefix(3)).uppercased() }
    if main.count == 1 { return String(main[0].prefix(3)).uppercased() }
    return main.prefix(3).map { String($0.prefix(1)).uppercased() }.joined()
}

extension Budapest {
    static func minuteOfDay(_ date: Date) -> Int {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    /// "Wednesday".
    static func weekdayLong(_ day: String) -> String { date(day).map(longWeekday.string) ?? "" }

    /// "18 Nov".
    static func dayMonth(_ day: String) -> String { date(day).map(dayMonthFormat.string) ?? "" }

    private static let longWeekday: DateFormatter = make("EEEE")
    private static let dayMonthFormat: DateFormatter = make("d MMM")

    private static func make(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.calendar = calendar
        f.dateFormat = format
        return f
    }
}
