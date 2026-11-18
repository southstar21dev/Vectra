import SwiftUI
import WidgetKit

// MARK: - Now and later today (medium)

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Today", provider: Provider()) { entry in
            WidgetBox(padding: EdgeInsets(top: 11, leading: 12, bottom: 10, trailing: 15)) { TodayView(entry: entry) }
        }
        .configurationDisplayName("Now and later today")
        .description("The class on now and what comes after it, on one time line.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}

struct TodayView: View {
    let entry: Entry

    var body: some View {
        let now = entry.date
        let today = Budapest.day(now)
        if entry.snapshot?.signedIn != true {
            EmptyMessage(text: entry.snapshot.emptyMessage("Open Vectra and sign in to see your day here."))
        } else if entry.snapshot.isStale(at: now) {
            EmptyMessage(text: StaleMessage)
        } else {
            let items = upcoming(now: now, today: today)
            if items.isEmpty {
                Quiet(text: "Nothing coming up in the next two weeks.", size: 12)
            } else {
                ViewThatFits(in: .vertical) {
                    ForEach((1...min(items.count, 8)).reversed(), id: \.self) { n in
                        CompactSpine(items: trimmed(Array(items.prefix(n))), now: now).frame(maxHeight: .infinity, alignment: .top)
                    }
                }
            }
        }
    }

    /// What's left today, then the next day with something on under its name.
    private func upcoming(now: Date, today: String) -> [CompactItem] {
        let snap = entry.snapshot
        var items = snap.sessions(on: today).filter { $0.end > now }.map(CompactItem.session)
        // Then the days after, each under its name, until the widget is full.
        var seen = Set<String>()
        for day in (snap?.sessions ?? []).map(\.day) where day > today && seen.insert(day).inserted && items.count < 8 {
            items.append(.day("\(Budapest.distance(from: today, to: day)), \(Budapest.heading(day))"))
            items += snap.sessions(on: day).map(CompactItem.session)
        }
        return items
    }

    private func trimmed(_ items: [CompactItem]) -> [CompactItem] {
        var items = items
        while let last = items.last, case .day = last { items.removeLast() }
        return items
    }
}

enum CompactItem: Hashable {
    case session(Session)
    case day(String)
}

/// Rows on a thin time line: start over end on the left, a node, the type and room over the name.
struct CompactSpine: View {
    @Environment(\.palette) private var p
    let items: [CompactItem]
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                switch item {
                case .day(let label): Micro(text: label).padding(.leading, 60).padding(.top, i == 0 ? 0 : 2)
                case .session(let s): row(s, last: i == items.count - 1 || { if case .day = items[i + 1] { true } else { false } }())
                }
            }
        }
    }

    private func row(_ s: Session, last: Bool) -> some View {
        let live = s.isLive(at: now)
        return HStack(alignment: .top, spacing: 9) {
            VStack(alignment: .trailing, spacing: 1) {
                Mono(text: Budapest.clock(s.when))
                if s.kind.isTimed { Mono(text: Budapest.clock(s.end), size: 10, weight: 600, color: p.muted) }
            }
            .frame(width: 40, alignment: .trailing)
            Node(kind: s.kind, live: live, done: false)
                .frame(width: 11)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(alignment: .top) { if !last { SpineLine().padding(.top, 13).padding(.bottom, -7) } }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    TypeWord(kind: s.kind)
                    if live { Text("\(durationLabel(s.end.timeIntervalSince(now))) left").font(VFont.inter(10, 700)).foregroundStyle(p.tone(s.kind)).lineLimit(1).fixedSize().widgetAccentable() }
                    Spacer(minLength: 4)
                    if s.kind == .task {
                        Quiet(text: "Due", size: 11)
                    } else if !s.location.isEmpty {
                        RoomLine(room: s.location)
                    }
                }
                Name(text: s.title, size: 14, weight: 700)
                if live { Bar(fraction: s.progress(at: now), kind: s.kind).padding(.top, 3) }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// The dot on the time line: a ring for the class on now, a check once it's over.
struct Node: View {
    @Environment(\.palette) private var p
    let kind: Kind
    let live: Bool
    let done: Bool

    var body: some View {
        Group {
            if done {
                CheckDot(size: 12)
            } else if live {
                Circle().strokeBorder(p.tone(kind), lineWidth: 2.5).frame(width: 11, height: 11).widgetAccentable()
            } else {
                Circle().fill(p.tone(kind)).frame(width: 7, height: 7).widgetAccentable().padding(2)
            }
        }
        .padding(.top, 2)
    }
}

struct SpineLine: View {
    @Environment(\.palette) private var p
    var dashed = false

    var body: some View {
        if dashed {
            Rectangle().fill(.clear).frame(width: 1.5)
                .overlay(Line().stroke(p.line, style: StrokeStyle(lineWidth: 1.5, dash: [3, 3])))
        } else {
            Rectangle().fill(p.line).frame(width: 1.5)
        }
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            Path { $0.move(to: CGPoint(x: rect.midX, y: 0)); $0.addLine(to: CGPoint(x: rect.midX, y: rect.maxY)) }
        }
    }
}

// MARK: - This week (medium)

struct WeekWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Week", provider: Provider()) { entry in
            WidgetBox(padding: EdgeInsets(top: 11, leading: 10, bottom: 10, trailing: 13)) { WeekView(entry: entry) }
        }
        .configurationDisplayName("This week")
        .description("Your week as a small timetable, with the next exam.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}

struct WeekView: View {
    @Environment(\.palette) private var p
    let entry: Entry

    var body: some View {
        if entry.snapshot?.signedIn != true {
            EmptyMessage(text: entry.snapshot.emptyMessage("Open Vectra and sign in to see your week here."))
        } else if entry.snapshot.isStale(at: entry.date) {
            EmptyMessage(text: StaleMessage)
        } else {
            let today = Budapest.day(entry.date)
            let week = entry.snapshot.week(now: entry.date)
            let grid = entry.snapshot.weekGrid(now: entry.date)
            let classes = week.days.reduce(0) { $0 + $1.kinds.filter(\.isClass).count }
            let exam = entry.snapshot.exam(in: week.days, from: today)
            VStack(spacing: 7) {
                Timetable(grid: grid, now: entry.date)
                HStack(spacing: 6) {
                    Quiet(text: (classes == 1 ? "1 class" : "\(classes) classes") + (grid.weekendFree ? ", weekend free" : ""))
                    Spacer(minLength: 6)
                    if let exam {
                        TypeWord(kind: .exam)
                        Text("\(exam.title), \(Budapest.weekday(exam.day))").font(VFont.inter(11, 600)).foregroundStyle(p.secondary).lineLimit(1).minimumScaleFactor(0.7)
                        Mono(text: Budapest.clock(exam.start), size: 11, color: p.secondary)
                    } else if week.title == "Next week" {
                        Quiet(text: "Next week")
                    }
                }
            }
        }
    }
}

/// Mon to Fri (plus a weekend day with something on) as columns, classes as blocks placed by time.
struct Timetable: View {
    @Environment(\.palette) private var p
    let grid: WeekGrid
    let now: Date

    var body: some View {
        let hours = Array(stride(from: grid.fromHour, through: grid.toHour, by: grid.toHour - grid.fromHour > 8 ? 2 : 1))
        HStack(alignment: .top, spacing: 3) {
            GeometryReader { g in
                ForEach(hours, id: \.self) { h in
                    Text(String(format: "%02d", h)).font(VFont.mono(9, 700)).foregroundStyle(p.muted)
                        .position(x: g.size.width / 2, y: y(h * 60, g.size.height))
                }
            }
            .frame(width: 16)
            .padding(.top, 24)
            ForEach(grid.columns) { column in
                VStack(spacing: 5) {
                    DayHead(day: column.day)
                    GeometryReader { g in
                        ZStack(alignment: .topLeading) {
                            ForEach(hours, id: \.self) { h in
                                Rectangle().fill(p.hairline).frame(height: 1).offset(y: y(h * 60, g.size.height))
                            }
                            ForEach(column.blocks, id: \.self) { b in block(b, g.size) }
                            if column.day.isToday { nowTick(g.size) }
                        }
                    }
                    .padding(.horizontal, 3)
                }
            }
        }
    }

    private func y(_ minute: Int, _ height: CGFloat) -> CGFloat {
        CGFloat(minute - grid.fromHour * 60) / CGFloat((grid.toHour - grid.fromHour) * 60) * height
    }

    private func block(_ b: WeekBlock, _ size: CGSize) -> some View {
        let top = y(b.startMin, size.height)
        let height = max(y(b.endMin, size.height) - top - 1.5, 3)
        return RoundedRectangle(cornerRadius: 4, style: .continuous)
            .fill(b.done ? p.track : p.tone(b.kind).opacity(p.accented ? 0.9 : 0.92))
            .overlay(alignment: .leading) {
                if height >= 11 {
                    Text(b.label).font(VFont.mono(8.5, 800)).foregroundStyle(b.done ? p.muted : p.onPill).lineLimit(1).minimumScaleFactor(0.6).padding(.leading, 6)
                }
            }
            .widgetAccentable(!b.done)
            .frame(width: size.width, height: height)
            .offset(y: top + 0.5)
    }

    private func nowTick(_ size: CGSize) -> some View {
        let at = y(Budapest.minuteOfDay(now), size.height)
        return HStack(spacing: 0) {
            Circle().fill(p.text).frame(width: 6, height: 6)
            Capsule().fill(p.text).frame(width: 6, height: 2)
        }
        .offset(x: -5, y: at - 3)
        .opacity(at >= 0 && at <= size.height ? 1 : 0)
    }
}

private struct DayHead: View {
    @Environment(\.palette) private var p
    let day: WeekDay

    var body: some View {
        Text("\(day.weekday) \(day.dayOfMonth)")
            .font(VFont.inter(11, 800))
            .lineLimit(1).minimumScaleFactor(0.7)
            .foregroundStyle(day.isToday ? p.onPill : (day.isPast ? p.muted : p.text))
            .frame(maxWidth: .infinity, minHeight: 19)
            .background { if day.isToday { Capsule().fill(p.pill).widgetAccentable() } }
    }
}

// MARK: - Today (large)

struct AgendaWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Agenda", provider: Provider()) { entry in
            WidgetBox(padding: EdgeInsets(top: 14, leading: 16, bottom: 13, trailing: 18)) { AgendaView(entry: entry) }
        }
        .configurationDisplayName("Today")
        .description("Your whole day on one time line, with breaks and what's due.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

struct AgendaView: View {
    @Environment(\.palette) private var p
    let entry: Entry

    var body: some View {
        if entry.snapshot?.signedIn != true {
            EmptyMessage(text: entry.snapshot.emptyMessage("Open Vectra and sign in to see today here."))
        } else if entry.snapshot.isStale(at: entry.date) {
            EmptyMessage(text: StaleMessage)
        } else {
            content
        }
    }

    private var content: some View {
        let now = entry.date
        let today = Budapest.day(now)
        let day = entry.snapshot.dayShown(today)
        let rows = entry.snapshot.agendaRows(day, now: now)
        let tally = entry.snapshot.done(day, now: now)
        let tasks = entry.snapshot.openTasks(day, now: now)
        return VStack(alignment: .leading, spacing: 0) {
            AgendaHeader(day: day, today: today, done: tally.done, total: tally.total)
            if rows.isEmpty {
                Quiet(text: "Nothing on today.", size: 12).padding(.top, 10)
            }
            ViewThatFits(in: .vertical) {
                // One flat list: a ForEach nested in ViewThatFits trips a SwiftUI precondition.
                ForEach(Array(AgendaFit.candidates(rows).flatMap { c in [(c, false), (c, true)] }.enumerated()), id: \.offset) { _, v in
                    AgendaFit(shown: v.0.shown, hidden: v.0.hidden, compact: v.1, now: now, today: today, task: taskLine(tasks), next: nextLine(day: day, today: today))
                }
            }
            .padding(.top, 9)
            Spacer(minLength: 0)
        }
    }

    private func taskLine(_ tasks: [Session]) -> AgendaFooter.Line? {
        guard let t = tasks.first else { return nil }
        let more = tasks.count > 1 ? " and \(tasks.count - 1) more" : ""
        return .init(icon: "RiTask", kind: .task, text: "\(t.title), due\(more)", time: Budapest.clock(t.end))
    }

    /// The next day's first class, for when no later day fits on the line itself.
    private func nextLine(day: String, today: String) -> AgendaFooter.Line? {
        guard let next = entry.snapshot.firstTimed(after: day) else { return nil }
        let count = entry.snapshot.sessions(on: next.day).count
        let more = count > 1 ? " and \(count - 1) more" : ""
        return .init(icon: "RiCalendar", kind: nil, text: "\(Budapest.distance(from: today, to: next.day)), \(next.title)\(more)", time: Budapest.clock(next.start))
    }
}

private struct AgendaHeader: View {
    @Environment(\.palette) private var p
    let day: String
    let today: String
    let done: Int
    let total: Int

    var body: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                Text((day == today ? Budapest.weekdayLong(day) : Budapest.distance(from: today, to: day)).uppercased())
                    .font(VFont.inter(11, 700)).tracking(1.1).foregroundStyle(p.accent).widgetAccentable()
                Text(Budapest.dayMonth(day)).font(VFont.inter(24, 800)).tracking(-0.7).foregroundStyle(p.text)
            }
            Spacer()
            if total > 0 {
                VStack(alignment: .trailing, spacing: 4) {
                    Quiet(text: "\(done) of \(total) done", size: 12)
                    HStack(spacing: 3) {
                        ForEach(0..<min(total, 8), id: \.self) { i in
                            Capsule().fill(i < done ? p.check : p.track).frame(width: 12, height: 4).widgetAccentable(i < done)
                        }
                    }
                }
            }
        }
    }
}

/// One way to fit the day. The candidates go from every row to fewer: finished classes leave
/// first, oldest first, then the last ones, which the footer names instead.
struct AgendaFit: View {
    let shown: [SpineRow]
    let hidden: [Session]
    /// Rooms left out under every class but the one on now, before a whole class has to go.
    let compact: Bool
    let now: Date
    let today: String
    let task: AgendaFooter.Line?
    let next: AgendaFooter.Line?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(shown.enumerated()), id: \.offset) { i, row in
                    if case .day(let d) = row {
                        Micro(text: "\(Budapest.distance(from: today, to: d)), \(Budapest.heading(d))").padding(.leading, 62).padding(.top, 2)
                    } else {
                        AgendaRow(row: row, now: now, last: i == shown.count - 1 || shown[i + 1].isDay, compact: compact)
                    }
                }
            }
            let then = hidden.first.map { s -> AgendaFooter.Line in
                let more = hidden.count > 1 ? " and \(hidden.count - 1) more" : ""
                return .init(icon: nil, kind: s.kind, text: "Then \(s.title)\(more)", time: Budapest.clock(s.start))
            }
            let showsLaterDays = shown.contains { $0.isDay }
            let lines = [then, task, showsLaterDays || !hidden.isEmpty ? nil : next].compactMap { $0 }
            if !lines.isEmpty { AgendaFooter(lines: lines) }
        }
    }

    static func candidates(_ rows: [SpineRow]) -> [(shown: [SpineRow], hidden: [Session])] {
        var out: [(shown: [SpineRow], hidden: [Session])] = []
        var current = rows
        while let last = current.last, last.isFiller { current.removeLast() }
        out.append((current, []))
        // Later days' rows go first, one at a time from the end (a day's name goes with its last row).
        while current.contains(where: { $0.isDay }) {
            current.removeLast()
            while let last = current.last, last.isFiller { current.removeLast() }
            out.append((current, []))
        }
        // Finished classes go one at a time, but the last one stays while nothing else is left.
        while let i = current.firstIndex(where: { if case .past = $0 { true } else { false } }),
              current.contains(where: { r in if case .past = r { false } else { r.session != nil } }) || current.filter({ $0.session != nil }).count > 3 {
            current.remove(at: i)
            while let first = current.first, case .gap = first { current.removeFirst() }
            out.append((current, []))
        }
        var hidden: [Session] = []
        while current.filter({ $0.session != nil }).count > 1 {
            while let last = current.last, case .gap = last { current.removeLast() }
            if let last = current.popLast(), let s = last.session { hidden.insert(s, at: 0) }
            while let last = current.last, case .gap = last { current.removeLast() }
            out.append((current, hidden))
        }
        return out
    }
}

private struct AgendaRow: View {
    @Environment(\.palette) private var p
    let row: SpineRow
    let now: Date
    let last: Bool
    var compact = false

    var body: some View {
        switch row {
        case .gap(let minutes):
            HStack(spacing: 10) {
                Color.clear.frame(width: 40, height: 1)
                SpineLine(dashed: true).frame(width: 12, height: 16)
                Text("Break, \(durationLabel(Double(minutes) * 60))").font(VFont.inter(11, 600)).foregroundStyle(p.muted).lineLimit(1)
            }
        case .day: EmptyView()
        case .past(let s): session(s, done: true, live: false)
        case .now(let s): session(s, done: false, live: true)
        case .next(let s): session(s, done: false, live: false)
        }
    }

    private func session(_ s: Session, done: Bool, live: Bool) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .trailing, spacing: 1) {
                Mono(text: Budapest.clock(s.start), color: done ? p.muted : p.text)
                if !done { Mono(text: Budapest.clock(s.end), size: 11, weight: 600, color: p.muted) }
            }
            .frame(width: 40, alignment: .trailing)
            Node(kind: s.kind, live: live, done: done)
                .frame(width: 12)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(alignment: .top) { if !last { SpineLine().padding(.top, 14).padding(.bottom, -8) } }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    TypeWord(kind: s.kind)
                    if live {
                        Text("\(durationLabel(s.end.timeIntervalSince(now))) left").font(VFont.inter(11, 700)).foregroundStyle(p.tone(s.kind)).lineLimit(1).fixedSize().widgetAccentable()
                    }
                }
                Name(text: s.title, size: 14, weight: 700, dim: done)
                if !done, !(compact && !live), !s.location.isEmpty || !s.group.isEmpty { RoomLine(room: s.location, group: s.group).padding(.top, 1) }
                if live { Bar(fraction: s.progress(at: now), kind: s.kind).padding(.top, 5) }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

struct AgendaFooter: View {
    struct Line {
        let icon: String?
        let kind: Kind?
        let text: String
        let time: String
    }

    @Environment(\.palette) private var p
    let lines: [Line]

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Hair().padding(.bottom, 2)
            ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                HStack(spacing: 8) {
                    if let icon = l.icon {
                        RemixIcon(name: icon, size: 13).foregroundStyle(l.kind.map(p.tone) ?? p.muted).widgetAccentable()
                    } else if let kind = l.kind {
                        Circle().fill(p.tone(kind)).frame(width: 6, height: 6).padding(.horizontal, 3.5).widgetAccentable()
                    }
                    Text(l.text).font(VFont.inter(12, 600)).foregroundStyle(p.secondary).lineLimit(2).minimumScaleFactor(0.85).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 6)
                    Mono(text: l.time, size: 11, color: p.muted)
                }
            }
        }
        .padding(.top, 8)
    }
}
