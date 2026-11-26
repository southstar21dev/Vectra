import SwiftUI
import WidgetKit

// MARK: - Timeline

struct Entry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
}

/// One entry now, one a minute while something counts down, and one at every start, end and midnight.
struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry { Entry(date: .now, snapshot: .sample) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let stored = Snapshot.load()
        completion(Entry(date: .now, snapshot: context.isPreview && stored == nil ? .sample : stored))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let snapshot = Snapshot.load()
        let now = Date()
        let dates = [now] + snapshot.refreshDates(after: now)
        completion(Timeline(entries: dates.map { Entry(date: $0, snapshot: snapshot) }, policy: .after(dates.last ?? now.addingTimeInterval(3600))))
    }
}

extension Snapshot {
    /// What the widget gallery shows before the app has written anything: a full day.
    static var sample: Snapshot {
        let now = Date()
        let today = Budapest.day(now)
        let tomorrow = Budapest.day(now.addingTimeInterval(86_400))
        func s(_ title: String, _ location: String, _ kind: Kind, _ from: Double, _ to: Double, _ teacher: String = "", _ group: String = "", day: String? = nil) -> Session {
            let base = now.timeIntervalSince1970 * 1000
            return Session(title: title, location: location, kind: kind, startMs: base + from * 60_000, endMs: base + to * 60_000,
                           day: day ?? today, color: "", teacherName: teacher, groupCode: group)
        }
        return Snapshot(version: 1, generatedAtMs: now.timeIntervalSince1970 * 1000, signedIn: true, sessions: [
            s("Calculus II", "B 012", .lecture, -125, -35, "Dr. Tóth Anna"),
            s("Data Structures", "A 105", .lecture, -35, 55, "Dr. Kovács Péter"),
            s("Microeconomics", "E 201", .seminar, 145, 235, "Szabó Eszter", "G04"),
            s("Statistics Lab", "QB 112", .lab, 250, 340, "Nagy Gábor", "L2"),
            s("Problem set 3", "", .task, 600, 601),
            s("Algorithms", "B 012", .lecture, 1300, 1390, "Dr. Varga Béla", day: tomorrow),
        ], updateRequired: nil, aheadDays: nil, hour12: nil)
    }
}

// MARK: - Next class (small and Lock Screen)

struct NextClassWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextClass", provider: Provider()) { entry in NextClassView(entry: entry) }
            .configurationDisplayName("Next class")
            .description("The class you're in or the next one, with what comes after.")
            .supportedFamilies([.systemSmall, .accessoryRectangular, .accessoryCircular, .accessoryInline])
            .contentMarginsDisabled()
    }
}

struct NextClassView: View {
    @Environment(\.widgetFamily) private var family
    let entry: Entry

    var body: some View {
        let up = entry.snapshot.nextUp(now: entry.date)
        let locked = entry.snapshot?.updateRequired ?? false
        switch family {
        case .accessoryRectangular: Accessory { RectangularAccessory(up: up, now: entry.date, locked: locked) }
        case .accessoryCircular: Accessory { CircularAccessory(up: up, now: entry.date) }
        case .accessoryInline: Accessory { InlineAccessory(up: up, locked: locked) }
        default: WidgetBox { SmallNextClass(snapshot: entry.snapshot, up: up, now: entry.date, locked: locked) }
        }
    }
}

private struct Accessory<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View { content.containerBackground(for: .widget) { Color.clear }.widgetURL(scheduleURL) }
}

struct SmallNextClass: View {
    let snapshot: Snapshot?
    let up: NextUp
    let now: Date
    var locked = false

    var body: some View {
        switch up {
        case .noData: EmptyMessage(text: locked ? UpdateRequiredMessage : "Open Vectra and sign in to see your classes here.")
        case .stale: EmptyMessage(text: StaleMessage)
        case .inClass(let s):
            let left = ringLabel(s.end.timeIntervalSince(now), hoursWord: "LEFT")
            SmallLayout(head: RingHead(fraction: s.progress(at: now), kind: s.kind, value: left.value, unit: left.unit) {
                TypeWord(kind: s.kind)
                SideTime(lead: "till", time: s.clock(s.end))
                RoomLine(room: s.location)
            }, title: s.title, sub: s.teacher.isEmpty ? s.group : s.teacher, rows: afterRows(s), fallback: "Nothing else coming up")
        case .upcoming(let s):
            let wait = s.start.timeIntervalSince(now)
            let label = ringLabel(wait, hoursWord: "TO GO")
            SmallLayout(head: RingHead(fraction: max(0, 1 - wait / 3600), kind: s.kind, value: label.value, unit: label.unit) {
                TypeWord(kind: s.kind)
                SideTime(lead: nil, time: s.clock(s.start), tail: "–" + s.clock(s.end))
                RoomLine(room: s.place)
            }, title: s.title, sub: s.teacher, rows: afterRows(s), fallback: "Nothing else coming up")
        case .examSoon(let exam):
            let label = ringLabel(exam.start.timeIntervalSince(now), hoursWord: "TO GO")
            SmallLayout(head: RingHead(fraction: max(0, 1 - exam.start.timeIntervalSince(now) / Snapshot.examHeadsUp), kind: .exam, value: label.value, unit: label.unit) {
                TypeWord(kind: .exam)
                SideTime(lead: Budapest.weekday(exam.day), time: exam.clock(exam.start), mono: true)
                RoomLine(room: exam.location)
            }, title: exam.title, sub: "Lasts \(durationLabel(exam.end.timeIntervalSince(exam.start)))", rows: snapshot.exams(after: exam, limit: 3).map { MiniItem(time: Budapest.dayMonth($0.day), title: $0.title, kind: .exam) }, fallback: "No more exams soon")
        case .dayDone(let hadToday, let next):
            DayDoneSmall(snapshot: snapshot, hadToday: hadToday, next: next, now: now)
        }
    }
}

extension SmallNextClass {
    /// What comes after `s`: the rest of its day, then the next days' first classes with their weekday.
    func afterRows(_ s: Session) -> [MiniItem] {
        let sameDay = snapshot.later(on: s.day, now: now, after: s).filter { $0 != s }.map(MiniItem.init)
        let later = (snapshot?.sessions ?? []).filter { $0.day > s.day }.prefix(4).map {
            MiniItem(time: "\(Budapest.weekday($0.day)) \($0.clock($0.when))", title: $0.title, kind: $0.kind)
        }
        return Array((sameDay + later).prefix(4))
    }
}

struct MiniItem: Hashable {
    let time: String
    let title: String
    let kind: Kind?

    init(time: String, title: String, kind: Kind?) {
        self.time = time
        self.title = title
        self.kind = kind
    }

    init(_ s: Session) { self.init(time: s.clock(s.when), title: s.title, kind: s.kind) }
}

/// A time beside the small widgets' ring. It is one line when it fits; in 12-hour mode ("10:45 AM") it
/// can be wider than the room left, so its parts then stack instead of being cut.
struct SideTime: View {
    let lead: String?
    let time: String
    var tail: String?
    /// The lead is a weekday, drawn like the time rather than as a quiet word.
    var mono = false

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: tail == nil ? 4 : 0) { parts }
            VStack(alignment: .leading, spacing: 0) { parts }
        }
    }

    @ViewBuilder private var parts: some View {
        if let lead { if mono { Mono(text: lead, size: 11) } else { Quiet(text: lead) } }
        Mono(text: time, size: 11)
        if let tail { Mono(text: tail, size: 11) }
    }
}

/// The ring on the left, three short lines beside it.
struct RingHead<Side: View>: View {
    let fraction: Double
    let kind: Kind
    let value: String
    let unit: String
    @ViewBuilder let side: Side

    var body: some View {
        HStack(spacing: 10) {
            Ring(fraction: fraction, kind: kind) { RingValue(value: value, unit: unit) }
            VStack(alignment: .leading, spacing: 3) { side }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The small widgets' shape: the ring head, the name and a quiet line, then as many of the rows
/// as fit under a hairline.
struct SmallLayout<Head: View>: View {
    @Environment(\.palette) private var p
    let head: Head
    let title: String
    let sub: String
    let rows: [MiniItem]
    let fallback: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            head
            Name(text: title).padding(.top, 7)
            if !sub.isEmpty { Quiet(text: sub) }
            Spacer(minLength: 5)
            Hair()
            Spacer(minLength: 5)
            if rows.isEmpty {
                Quiet(text: fallback)
            } else {
                ViewThatFits(in: .vertical) {
                    rowStack(4)
                    rowStack(3)
                    rowStack(2)
                    rowStack(1)
                }
            }
        }
    }

    private func rowStack(_ n: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(rows.prefix(n)), id: \.self) { MiniRow(time: $0.time, title: $0.title, kind: $0.kind) }
        }
    }
}

struct DayDoneSmall: View {
    @Environment(\.palette) private var p
    let snapshot: Snapshot?
    let hadToday: Bool
    let next: Session?
    let now: Date

    var body: some View {
        let today = Budapest.day(now)
        let tally = snapshot.done(today, now: now)
        let tasks = snapshot.openTasks(today, now: now).count
        let nextDay = next.map { snapshot.sessions(on: $0.day) } ?? []
        // The next day first, then the days after it, so the widget stays full.
        let upcoming = next.map { n in Array((snapshot?.sessions ?? []).filter { $0.day >= n.day }.prefix(6)) } ?? []
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Ring(fraction: hadToday ? 1 : 0, kind: .seminar) {
                    RemixIcon(name: hadToday ? "RiCheck" : "RiCalendar", size: 20).foregroundStyle(hadToday ? p.tone(.seminar) : p.muted).widgetAccentable()
                }
                VStack(alignment: .leading, spacing: 2) {
                    Name(text: hadToday ? "All done" : "Free today", size: 14)
                    if hadToday { Quiet(text: "\(tally.done) of \(tally.total) classes") }
                    Quiet(text: tasks == 0 ? "No tasks left" : (tasks == 1 ? "1 task left" : "\(tasks) tasks left"))
                }
            }
            Spacer(minLength: 6)
            Hair()
            Spacer(minLength: 6)
            if let next {
                HStack(alignment: .firstTextBaseline) {
                    Micro(text: Budapest.distance(from: today, to: next.day))
                    Spacer(minLength: 4)
                    Quiet(text: nextDay.count == 1 ? "1 item" : "\(nextDay.count) items", size: 10)
                }
                ViewThatFits(in: .vertical) {
                    rows(upcoming, next.day, 4)
                    rows(upcoming, next.day, 3)
                    rows(upcoming, next.day, 2)
                    rows(upcoming, next.day, 1)
                }
                .padding(.top, 5)
            } else {
                Quiet(text: "Nothing in the next two weeks.")
            }
        }
    }

    private func rows(_ list: [Session], _ firstDay: String, _ n: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(list.prefix(n).enumerated()), id: \.offset) { i, s in
                MiniRow(time: s.day == firstDay ? s.clock(s.when) : "\(Budapest.weekday(s.day)) \(s.clock(s.when))", title: s.title, kind: s.kind, strong: i == 0)
            }
        }
    }
}

// Lock Screen: drawn in the system's single tint, so the design lives in the type and layout.

struct RectangularAccessory: View {
    let up: NextUp
    let now: Date
    var locked = false

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            switch up {
            case .inClass(let s):
                Text([s.kind.label.uppercased(), s.location].filter { !$0.isEmpty }.joined(separator: ", ")).font(VFont.mono(10, 800)).foregroundStyle(.secondary)
                Text(s.title).font(VFont.inter(15, 800)).lineLimit(1).minimumScaleFactor(0.7).widgetAccentable()
                Bar(fraction: s.progress(at: now), kind: s.kind, height: 4).padding(.top, 3)
                Text("\(durationLabel(s.end.timeIntervalSince(now))) left").font(VFont.inter(11, 600)).foregroundStyle(.secondary)
            case .upcoming(let s), .examSoon(let s):
                Text(s.detail).font(VFont.inter(12, 600)).foregroundStyle(.secondary).lineLimit(1)
                Text(s.title).font(VFont.inter(15, 800)).lineLimit(1).minimumScaleFactor(0.7).widgetAccentable()
                if s.day == Budapest.day(now) {
                    (Text(s.clock(s.start) + ", in ") + Text(s.start, style: .relative)).font(VFont.mono(12, 700)).lineLimit(1).minimumScaleFactor(0.7)
                } else {
                    Text(Budapest.distance(from: Budapest.day(now), to: s.day) + " " + s.clock(s.start)).font(VFont.mono(12, 700)).lineLimit(1).minimumScaleFactor(0.7)
                }
            case .dayDone(let hadToday, let next):
                Text(hadToday ? "Done for today" : "No classes today").font(VFont.inter(12, 600)).foregroundStyle(.secondary)
                if let next {
                    Text(next.title).font(VFont.inter(15, 800)).lineLimit(1).minimumScaleFactor(0.7).widgetAccentable()
                    Text(Budapest.distance(from: Budapest.day(now), to: next.day) + " " + next.clock(next.start)).font(VFont.mono(12, 700)).lineLimit(1).minimumScaleFactor(0.7)
                }
            case .noData:
                Text("Vectra").font(VFont.inter(15, 800))
                Text(locked ? UpdateRequiredMessage : "Sign in to see your classes").font(VFont.inter(12, 500))
            case .stale:
                Text("Vectra").font(VFont.inter(15, 800))
                Text("Open Vectra to refresh").font(VFont.inter(12, 500))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct CircularAccessory: View {
    let up: NextUp
    let now: Date

    var body: some View {
        switch up {
        case .inClass(let s):
            let l = ringLabel(s.end.timeIntervalSince(now), hoursWord: "LEFT")
            ring(s.progress(at: now), l)
        case .upcoming(let s):
            // The ring fills over the last hour before class.
            let wait = s.start.timeIntervalSince(now)
            ring(max(0, 1 - wait / 3600), ringLabel(wait, hoursWord: "TO GO"))
        default:
            ZStack {
                AccessoryWidgetBackground()
                RemixIcon(name: "RiCalendar", size: 20)
            }
        }
    }

    private func ring(_ fraction: Double, _ label: (value: String, unit: String)) -> some View {
        ZStack {
            AccessoryWidgetBackground()
            Circle().stroke(.white.opacity(0.25), lineWidth: 4).padding(3)
            Circle().trim(from: 0, to: max(fraction, 0.001)).stroke(.white, style: StrokeStyle(lineWidth: 4, lineCap: .round)).rotationEffect(.degrees(-90)).padding(3)
            VStack(spacing: -1) {
                Text(label.value).font(VFont.mono(label.value.count > 3 ? 11 : 15, 800)).minimumScaleFactor(0.6)
                Text(label.unit).font(VFont.inter(7, 700)).foregroundStyle(.secondary)
            }
        }
    }
}

struct InlineAccessory: View {
    let up: NextUp
    var locked = false

    var body: some View {
        switch up {
        case .inClass(let s): Text("Until \(s.clock(s.end)), \(s.location.isEmpty ? s.title : s.location)")
        case .upcoming(let s): Text("\(s.clock(s.start)) \(s.title)")
        case .examSoon(let s): Text("Exam \(Budapest.weekday(s.day)) \(s.clock(s.start))")
        case .dayDone(_, let next): Text(next.map { "\(Budapest.weekday($0.day)) \($0.clock($0.start)) \($0.title)" } ?? "No classes coming up")
        case .noData: Text(locked ? "Update Vectra" : "Vectra")
        case .stale: Text("Open Vectra to refresh")
        }
    }
}
