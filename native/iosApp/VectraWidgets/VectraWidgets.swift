import SwiftUI
import WidgetKit

@main
struct VectraWidgets: WidgetBundle {
    var body: some Widget {
        NextClassWidget()
        TodayWidget()
        WeekWidget()
        AgendaWidget()
    }
}

// MARK: - Timeline

struct Entry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
}

/// One entry now and one at every start, end and midnight ahead. Countdowns and progress bars tick
/// on their own in between (Text(.relative), ProgressView(timerInterval:)).
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
    /// What the widget gallery shows before the app has written anything.
    static var sample: Snapshot {
        let now = Date().timeIntervalSince1970 * 1000
        let today = Budapest.day(.now)
        func s(_ title: String, _ location: String, _ kind: Kind, _ from: Double, _ to: Double, _ color: String = "") -> Session {
            Session(title: title, location: location, kind: kind, startMs: now + from * 60_000, endMs: now + to * 60_000, day: today, color: color)
        }
        return Snapshot(version: 1, generatedAtMs: now, signedIn: true, sessions: [
            s("Microeconomics", "C.203", .lecture, 25, 115, "#38bdf8"),
            s("Corporate Finance", "S.304", .seminar, 145, 235, "#10b981"),
            s("Problem set 3", "", .task, 265, 266),
        ])
    }
}

/// Where a tap on any Vectra widget lands: the Schedule tab.
let scheduleURL = URL(string: "vectra://open/schedule")!

private struct Container<Content: View>: View {
    let glow: Color?
    @ViewBuilder let content: Content

    var body: some View {
        Themed { content }
            .containerBackground(for: .widget) { Themed { Canvas(glow: glow) } }
            .widgetURL(scheduleURL)
    }
}

// MARK: - Next class (small and Lock Screen)

struct NextClassWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextClass", provider: Provider()) { entry in NextClassView(entry: entry) }
            .configurationDisplayName("Next class")
            .description("The class you're in or the next one, with a countdown.")
            .supportedFamilies([.systemSmall, .accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}

struct NextClassView: View {
    @Environment(\.widgetFamily) private var family
    let entry: Entry

    var body: some View {
        let up = entry.snapshot.nextUp(now: entry.date)
        switch family {
        case .accessoryRectangular: Accessory { RectangularAccessory(up: up, now: entry.date) }
        case .accessoryCircular: Accessory { CircularAccessory(up: up, now: entry.date) }
        case .accessoryInline: Accessory { InlineAccessory(up: up) }
        default:
            let glow: Color? = if case .inClass(let s) = up { Palette(dark: true).course(s) } else { nil }
            Container(glow: glow) { SmallNextClass(up: up, now: entry.date) }
        }
    }
}

private struct Accessory<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View { content.containerBackground(for: .widget) { Color.clear }.widgetURL(scheduleURL) }
}

struct SmallNextClass: View {
    @Environment(\.palette) private var p
    let up: NextUp
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch up {
            case .noData: EmptyMessage(text: "Open Vectra and sign in to see your classes here.")
            case .upcoming(let s):
                HStack {
                    StatusLabel(text: s.kind.label, kind: s.kind)
                    Spacer()
                    Text(Budapest.clock(s.start)).font(VFont.mono(12, 600)).foregroundStyle(p.muted)
                }
                Spacer()
                Title(text: s.title)
                AccentPill { Text("in ") + Text(s.start, style: .relative) }.padding(.top, 8)
                Spacer()
                Place(location: s.location)
            case .inClass(let s):
                StatusLabel(text: "Now · \(s.kind.label)", kind: s.kind)
                Spacer()
                Title(text: s.title)
                LiveProgress(session: s).padding(.top, 10)
                HStack {
                    (Text(s.end, style: .relative) + Text(" left")).foregroundStyle(p.secondary)
                    Spacer()
                    Text(Budapest.clock(s.end)).font(VFont.mono(12, 600)).foregroundStyle(p.muted)
                }
                .font(VFont.inter(12, 600)).padding(.top, 6)
                Spacer()
                Place(location: s.location)
            case .examSoon(let exam):
                StatusLabel(text: "Exam", kind: .exam)
                Spacer()
                Text(Budapest.distance(from: Budapest.day(now), to: exam.day))
                    .font(VFont.inter(24, 900)).tracking(-0.5).foregroundStyle(p.tone(.exam))
                Title(text: exam.title)
                Spacer()
                Label {
                    Text([Budapest.weekday(exam.day) + " " + Budapest.clock(exam.start), exam.location].filter { !$0.isEmpty }.joined(separator: " · "))
                        .lineLimit(1)
                } icon: { RemixIcon(name: "RiCalendar", size: 14) }
                    .labelStyle(TightLabel()).font(VFont.inter(12, 600)).foregroundStyle(p.secondary)
            case .dayDone(let hadToday, let next):
                HStack(spacing: 8) {
                    RemixIcon(name: "RiCheckboxCircle", size: 22).foregroundStyle(p.tone(.seminar))
                    Title(text: hadToday ? "Done for today" : "No classes today")
                }
                Spacer()
                if let next {
                    Micro(text: Budapest.distance(from: Budapest.day(now), to: next.day))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(next.title).font(VFont.inter(12, 700)).foregroundStyle(p.text).lineLimit(1)
                        Text([Budapest.clock(next.start), next.location].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(VFont.inter(12, 600)).foregroundStyle(p.muted).lineLimit(1)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassCard()
                    .padding(.top, 6)
                } else {
                    Text("Nothing coming up in the next two weeks.").font(VFont.inter(12, 600)).foregroundStyle(p.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

struct Title: View {
    @Environment(\.palette) private var p
    let text: String

    var body: some View {
        // Shrinks a long single word ("Microeconomics") rather than breaking it mid-word.
        Text(text).font(VFont.inter(16, 800)).tracking(-0.3).foregroundStyle(p.text).lineLimit(2).minimumScaleFactor(0.75)
    }
}

struct EmptyMessage: View {
    @Environment(\.palette) private var p
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RemixIcon(name: "RiCalendar", size: 20).foregroundStyle(p.accent)
            Title(text: "Vectra")
            Text(text).font(VFont.inter(12, 600)).foregroundStyle(p.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

// Lock Screen: drawn in the system's single tint, so the design lives in the type and layout.

struct RectangularAccessory: View {
    let up: NextUp
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            switch up {
            case .inClass(let s):
                Text(["Now", s.location].filter { !$0.isEmpty }.joined(separator: " · ")).font(VFont.inter(12, 600)).foregroundStyle(.secondary)
                Text(s.title).font(VFont.inter(15, 800)).lineLimit(1).widgetAccentable()
                LiveProgress(session: s, height: 4).padding(.top, 3)
            case .upcoming(let s), .examSoon(let s):
                Text(s.detail).font(VFont.inter(12, 600)).foregroundStyle(.secondary).lineLimit(1)
                Text(s.title).font(VFont.inter(15, 800)).lineLimit(1).widgetAccentable()
                if s.day == Budapest.day(now) {
                    (Text(Budapest.clock(s.start) + " · in ") + Text(s.start, style: .relative)).font(VFont.mono(12, 700))
                } else {
                    Text(Budapest.distance(from: Budapest.day(now), to: s.day) + " " + Budapest.clock(s.start)).font(VFont.mono(12, 700))
                }
            case .dayDone(let hadToday, let next):
                Text(hadToday ? "Done for today" : "No classes today").font(VFont.inter(12, 600)).foregroundStyle(.secondary)
                if let next {
                    Text(next.title).font(VFont.inter(15, 800)).lineLimit(1).widgetAccentable()
                    Text(Budapest.distance(from: Budapest.day(now), to: next.day) + " " + Budapest.clock(next.start)).font(VFont.mono(12, 700))
                }
            case .noData:
                Text("Vectra").font(VFont.inter(15, 800))
                Text("Sign in to see your classes").font(VFont.inter(12, 500))
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
            ProgressView(timerInterval: s.start...s.end, countsDown: true) { EmptyView() } currentValueLabel: {
                Text(Budapest.clock(s.end)).font(VFont.mono(11, 700))
            }
            .progressViewStyle(.circular)
        case .upcoming(let s):
            // The ring empties over the last hour before class.
            ProgressView(timerInterval: s.start.addingTimeInterval(-3600)...s.start, countsDown: true) { EmptyView() } currentValueLabel: {
                Text(Budapest.clock(s.start)).font(VFont.mono(11, 700))
            }
            .progressViewStyle(.circular)
        default:
            ZStack {
                AccessoryWidgetBackground()
                RemixIcon(name: "RiCalendar", size: 20)
            }
        }
    }
}

struct InlineAccessory: View {
    let up: NextUp

    var body: some View {
        switch up {
        case .inClass(let s): Text("Until \(Budapest.clock(s.end)) · \(s.location.isEmpty ? s.title : s.location)")
        case .upcoming(let s): Text("\(Budapest.clock(s.start)) \(s.title)")
        case .examSoon(let s): Text("Exam \(Budapest.weekday(s.day)) \(Budapest.clock(s.start))")
        case .dayDone(_, let next): Text(next.map { "\(Budapest.weekday($0.day)) \(Budapest.clock($0.start)) \($0.title)" } ?? "No classes coming up")
        case .noData: Text("Vectra")
        }
    }
}
