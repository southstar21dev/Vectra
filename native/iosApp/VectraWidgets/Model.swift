import Foundation

// The snapshot the app writes (shared/…/features/widgets/WidgetSnapshot.kt) and the rules that pick
// what each widget shows (WidgetModel.kt, WidgetLabels.kt). Keep the three in step.

struct Snapshot: Decodable {
    let version: Int
    let generatedAtMs: Double
    let signedIn: Bool
    let sessions: [Session]

    static func load() -> Snapshot? {
        guard let url = AppGroup.snapshotURL,
              let data = try? Data(contentsOf: url),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data),
              snapshot.version == 1
        else { return nil }
        return snapshot
    }

    /// Days after its generation day the snapshot covers (`AheadDays` in WidgetPublisher.kt).
    static let aheadDays = 14

    /// False once `now` is past the last day the snapshot covers. It can no longer tell an empty
    /// day from one it never saw, so the widgets ask for the app instead of saying "No classes".
    func covers(_ now: Date) -> Bool {
        let generated = Date(timeIntervalSince1970: generatedAtMs / 1000)
        guard let last = Budapest.calendar.date(byAdding: .day, value: Snapshot.aheadDays, to: generated) else { return true }
        return Budapest.day(now) <= Budapest.day(last)
    }
}

enum Kind: String, Decodable {
    case lecture, seminar, lab, exam, task, shift, other

    /// Tasks are deadlines, not time spent somewhere: never "now" or "next".
    var isTimed: Bool { self != .task }
    var isClass: Bool { self == .lecture || self == .seminar || self == .lab || self == .other }

    var label: String {
        switch self {
        case .lecture: "Lecture"
        case .seminar: "Seminar"
        case .lab: "Lab"
        case .exam: "Exam"
        case .task: "Task"
        case .shift: "Work shift"
        case .other: "Class"
        }
    }
}

struct Session: Decodable, Hashable {
    let title: String
    let location: String
    let kind: Kind
    let startMs: Double
    let endMs: Double
    let day: String
    let color: String

    var start: Date { Date(timeIntervalSince1970: startMs / 1000) }
    var end: Date { Date(timeIntervalSince1970: endMs / 1000) }

    func isLive(at now: Date) -> Bool { kind.isTimed && start <= now && now < end }

    func progress(at now: Date) -> Double {
        min(max(now.timeIntervalSince(start) / max(end.timeIntervalSince(start), 1), 0), 1)
    }

    /// "Lecture · C.203".
    var detail: String { [kind.label, location].filter { !$0.isEmpty }.joined(separator: " · ") }
}

/// What a widget says when its snapshot has run out (audit 04 #17).
let StaleMessage = "Open Vectra to refresh your schedule."

enum NextUp {
    case noData
    /// Signed in, but the snapshot ended before today: the app has not run for two weeks.
    case stale
    case inClass(Session)
    case upcoming(Session)
    case examSoon(Session)
    case dayDone(hadToday: Bool, next: Session?)

    /// The session to feature: today's, or the next one on a later day.
    var featured: Session? {
        switch self {
        case .inClass(let s), .upcoming(let s), .examSoon(let s): s
        case .dayDone(_, let next): next
        case .noData, .stale: nil
        }
    }
}

/// Budapest wall-clock dates and times, whatever the phone's time zone.
enum Budapest {
    static let zone = TimeZone(identifier: "Europe/Budapest")!
    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        c.locale = Locale(identifier: "en_US_POSIX")
        return c
    }()

    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.calendar = calendar
        f.dateFormat = format
        return f
    }

    private static let ymd = formatter("yyyy-MM-dd")
    private static let hm = formatter("HH:mm")
    private static let weekdayShort = formatter("EEE")
    private static let heading = formatter("EEE d MMM")

    static func day(_ date: Date) -> String { ymd.string(from: date) }
    static func date(_ day: String) -> Date? { ymd.date(from: day) }
    static func clock(_ date: Date) -> String { hm.string(from: date) }
    static func weekday(_ day: String) -> String { date(day).map(weekdayShort.string) ?? "" }
    static func heading(_ day: String) -> String { date(day).map(heading.string) ?? "" }

    static func midnight(after date: Date) -> Date {
        calendar.nextDate(after: date, matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime) ?? date.addingTimeInterval(86_400)
    }

    /// "Today", "Tomorrow", "In 3 days".
    static func distance(from today: String, to day: String) -> String {
        guard let a = date(today), let b = date(day),
              let days = calendar.dateComponents([.day], from: a, to: b).day else { return day }
        switch days {
        case 0: return "Today"
        case 1: return "Tomorrow"
        default: return "In \(days) days"
        }
    }
}

struct WeekDay: Identifiable {
    let date: String
    let weekday: String
    let dayOfMonth: Int
    let kinds: [Kind]
    let isToday: Bool
    let isPast: Bool
    var id: String { date }
}

extension Optional where Wrapped == Snapshot {
    private var live: [Session] { (self?.signedIn ?? false) ? self!.sessions : [] }

    /// Signed in, but the snapshot no longer reaches `now`.
    func isStale(at now: Date) -> Bool { self.map { $0.signedIn && !$0.covers(now) } ?? false }

    func nextUp(now: Date) -> NextUp {
        guard let snap = self, snap.signedIn else { return .noData }
        guard snap.covers(now) else { return .stale }
        let today = Budapest.day(now)
        let timed = snap.sessions.filter { $0.kind.isTimed }
        if let s = timed.first(where: { $0.day == today && $0.start <= now && now < $0.end }) { return .inClass(s) }
        if let s = timed.first(where: { $0.day == today && $0.start > now }) { return .upcoming(s) }
        let later = timed.filter { $0.day > today }
        if let exam = later.first(where: { $0.kind == .exam && $0.start.timeIntervalSince(now) <= 7 * 86_400 }) { return .examSoon(exam) }
        return .dayDone(hadToday: timed.contains { $0.day == today }, next: later.first)
    }

    func sessions(on day: String) -> [Session] { live.filter { $0.day == day } }

    /// What's still to come on `day` after `shown`.
    func later(on day: String, now: Date, after shown: Session?) -> [Session] {
        sessions(on: day).filter { $0 != shown && $0.end > now && (shown == nil || $0.start >= shown!.start) }
    }

    func firstDay(after day: String) -> String? { live.first { $0.day > day }?.day }

    func firstTimed(after day: String) -> Session? { live.first { $0.day > day && $0.kind.isTimed } }

    /// Monday to Friday (next week's at the weekend), plus a weekend day with something on it.
    func week(now: Date) -> (title: String, days: [WeekDay]) {
        let cal = Budapest.calendar
        let today = Budapest.day(now)
        let weekday = cal.component(.weekday, from: now) // 1 = Sunday
        let weekend = weekday == 1 || weekday == 7
        let sinceMonday = (weekday + 5) % 7
        let monday = cal.date(byAdding: .day, value: -sinceMonday + (weekend ? 7 : 0), to: cal.startOfDay(for: now))!
        let days: [WeekDay] = (0..<7).compactMap { offset in
            let date = cal.date(byAdding: .day, value: offset, to: monday)!
            let ymd = Budapest.day(date)
            let kinds = live.filter { $0.day == ymd && $0.kind != .task }.map(\.kind)
            if offset >= 5 && kinds.isEmpty { return nil }
            return WeekDay(date: ymd, weekday: Budapest.weekday(ymd), dayOfMonth: cal.component(.day, from: date),
                           kinds: kinds, isToday: ymd == today, isPast: ymd < today)
        }
        return (weekend ? "Next week" : "This week", days)
    }

    func exam(in days: [WeekDay], from today: String) -> Session? {
        let dates = Set(days.map(\.date))
        return live.first { $0.kind == .exam && $0.day >= today && dates.contains($0.day) }
    }

    /// The moments a widget's text changes: each start and end ahead, and midnight.
    func refreshDates(after now: Date) -> [Date] {
        let edges = live.flatMap { [$0.start, $0.end] }.filter { $0 > now }
        let horizon = now.addingTimeInterval(36 * 3600)
        return Array(Set(edges.filter { $0 <= horizon } + [Budapest.midnight(after: now)])).sorted()
    }
}

/// Minutes of free time between two sessions when it's worth a "Break" capsule.
func breakMinutes(_ before: Session, _ after: Session) -> Int? {
    let minutes = Int(after.start.timeIntervalSince(before.end) / 60)
    return minutes >= 15 ? minutes : nil
}

/// "1 h 10 min", "45 min".
func durationLabel(_ seconds: TimeInterval) -> String {
    let minutes = max(Int((seconds / 60).rounded(.up)), 1)
    let h = minutes / 60, m = minutes % 60
    if h == 0 { return "\(m) min" }
    return m == 0 ? "\(h) h" : "\(h) h \(m) min"
}
