import SwiftUI
import WidgetKit

// MARK: - Now and later today (medium)

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Today", provider: Provider()) { entry in
            ContainerView { TodayView(entry: entry) }
        }
        .configurationDisplayName("Now and later today")
        .description("The current or next class and the rest of your day.")
        .supportedFamilies([.systemMedium])
    }
}

/// Every system-size widget sits on the app canvas.
struct ContainerView<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        Themed { content }.containerBackground(for: .widget) { Themed { Canvas(glow: nil) } }.widgetURL(scheduleURL)
    }
}

struct TodayView: View {
    @Environment(\.palette) private var p
    let entry: Entry

    var body: some View {
        let now = entry.date
        let today = Budapest.day(now)
        let up = entry.snapshot.nextUp(now: now)
        if case .noData = up {
            EmptyMessage(text: "Open Vectra and sign in to see your day here.")
        } else {
            let featured = up.featured
            let day = featured?.day ?? today
            let later = Array(entry.snapshot.later(on: day, now: now, after: featured).prefix(3))
            HStack(alignment: .top, spacing: 12) {
                FeaturedCard(session: featured, live: featured?.isLive(at: now) ?? false, now: now)
                VStack(alignment: .leading, spacing: 8) {
                    Micro(text: day == today ? "Later today" : "Also \(Budapest.distance(from: today, to: day))")
                    if later.isEmpty {
                        Text(day == today ? "Nothing else today." : "Nothing else that day.")
                            .font(VFont.inter(12, 600)).foregroundStyle(p.secondary)
                    }
                    ForEach(later, id: \.self) { s in
                        HStack(alignment: .top, spacing: 8) {
                            Text(Budapest.clock(s.start)).font(VFont.mono(12, 700)).foregroundStyle(p.muted)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.title).font(VFont.inter(13, 800)).foregroundStyle(p.text).lineLimit(1)
                                StatusLabel(text: s.detail, kind: s.kind)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

struct FeaturedCard: View {
    @Environment(\.palette) private var p
    let session: Session?
    let live: Bool
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let s = session {
                let today = Budapest.day(now)
                StatusLabel(text: live ? "Now" : (s.day == today ? s.kind.label : "\(Budapest.distance(from: today, to: s.day)) · \(s.kind.label)"), kind: s.kind)
                Spacer(minLength: 6)
                Title(text: s.title)
                Group {
                    if live {
                        LiveProgress(session: s).padding(.top, 8)
                        (Text(s.end, style: .relative) + Text(" left")).font(VFont.inter(12, 600)).foregroundStyle(p.secondary).padding(.top, 6)
                    } else if s.day == today {
                        AccentPill { Text("in ") + Text(s.start, style: .relative) }.padding(.top, 8)
                    } else {
                        AccentPill { Text("\(Budapest.weekday(s.day)) \(Budapest.clock(s.start))") }.padding(.top, 8)
                    }
                }
            } else {
                RemixIcon(name: "RiCheckboxCircle", size: 20).foregroundStyle(p.tone(.seminar))
                Spacer()
                Title(text: "Nothing coming up")
            }
        }
        .padding(12)
        .frame(width: 140, alignment: .leading)
        .frame(maxHeight: .infinity, alignment: .leading)
        .glassCard(live: live ? session.map(p.course) : nil)
    }
}

// MARK: - This week (medium)

struct WeekWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Week", provider: Provider()) { entry in
            ContainerView { WeekView(entry: entry) }
        }
        .configurationDisplayName("This week")
        .description("Your week at a glance, with the next exam.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}

struct WeekView: View {
    @Environment(\.palette) private var p
    let entry: Entry

    var body: some View {
        if entry.snapshot?.signedIn != true {
            EmptyMessage(text: "Open Vectra and sign in to see your week here.").padding(16)
        } else {
            let today = Budapest.day(entry.date)
            let week = entry.snapshot.week(now: entry.date)
            let exam = entry.snapshot.exam(in: week.days, from: today)
            let classes = week.days.reduce(0) { $0 + $1.kinds.filter(\.isClass).count }
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(week.title).font(VFont.inter(18, 900)).tracking(-0.4).foregroundStyle(p.text)
                    HStack(alignment: .top, spacing: 2) {
                        ForEach(week.days) { DayColumn(day: $0) }
                    }
                }
                .padding(.horizontal, 16).padding(.top, 14)
                .frame(maxHeight: .infinity, alignment: .top)
                HStack {
                    Text(classes == 1 ? "1 class" : "\(classes) classes").foregroundStyle(p.secondary)
                    Spacer()
                    if let exam { StatusLabel(text: "Exam \(Budapest.weekday(exam.day)) \(Budapest.clock(exam.start))", kind: .exam) }
                }
                .font(VFont.inter(12, 600))
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(p.overlay)
                .overlay(alignment: .top) { Rectangle().fill(p.hairline).frame(height: 1) }
            }
        }
    }
}

private struct DayColumn: View {
    @Environment(\.palette) private var p
    let day: WeekDay

    var body: some View {
        VStack(spacing: 5) {
            Text(day.weekday.uppercased()).font(VFont.inter(10, 700)).tracking(1).foregroundStyle(day.isToday ? p.accent : p.muted)
            Text("\(day.dayOfMonth)")
                .font(VFont.inter(14, 800))
                .foregroundStyle(day.isToday ? p.onAccent : (day.isPast ? p.muted : p.text))
                .frame(width: 28, height: 28)
                .background { if day.isToday { Circle().fill(p.pill).shadow(color: p.accent.opacity(0.35), radius: 8, y: 3) } }
            if day.kinds.isEmpty {
                Text("Free").font(VFont.inter(12, 600)).foregroundStyle(p.muted)
            }
            ForEach(Array(day.kinds.prefix(4).enumerated()), id: \.offset) { _, kind in
                Capsule().fill(day.isPast ? p.hairline : p.tone(kind)).frame(height: 6).padding(.horizontal, 6)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Today (large)

struct AgendaWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Agenda", provider: Provider()) { entry in
            ContainerView { AgendaView(entry: entry) }
        }
        .configurationDisplayName("Today")
        .description("Today's classes, tasks and shifts.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

private enum AgendaRow: Hashable {
    case card(Session)
    case gap(Int)
}

struct AgendaView: View {
    @Environment(\.palette) private var p
    let entry: Entry

    private static let maxCards = 4
    /// Four cards and one break fill the large size; more breaks go, shortest first.
    private static let maxRows = 5

    var body: some View {
        if entry.snapshot?.signedIn != true {
            EmptyMessage(text: "Open Vectra and sign in to see today here.").padding(16)
        } else {
            content
        }
    }

    private var content: some View {
        let now = entry.date
        let today = Budapest.day(now)
        // A day with nothing on shows the next day that has something.
        let shownDay = entry.snapshot.sessions(on: today).isEmpty ? (entry.snapshot.firstDay(after: today) ?? today) : today
        let sessions = entry.snapshot.sessions(on: shownDay)
        let finished = sessions.filter { $0.end <= now }.count
        let visible = Array(sessions.dropFirst(min(max(sessions.count - Self.maxCards, 0), finished)).prefix(Self.maxCards))
        let rest = sessions.drop { $0 != visible.last }.dropFirst()
        let tomorrow = entry.snapshot.firstTimed(after: shownDay)
        let classes = sessions.filter { $0.kind.isClass || $0.kind == .exam }.count

        return VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Budapest.distance(from: today, to: shownDay)).font(VFont.inter(18, 900)).tracking(-0.4).foregroundStyle(p.text)
                        Text(Budapest.heading(shownDay)).font(VFont.inter(12, 600)).foregroundStyle(p.muted)
                    }
                    Spacer()
                    if classes > 0 { StatusLabel(text: classes == 1 ? "1 class" : "\(classes) classes", kind: .lecture) }
                }
                .padding(.horizontal, 4).padding(.bottom, 4)
                if sessions.isEmpty {
                    Text("Nothing on today.").font(VFont.inter(12, 600)).foregroundStyle(p.secondary).padding(.horizontal, 4)
                }
                ForEach(rows(visible), id: \.self) { row in
                    switch row {
                    case .card(let s): AgendaCard(session: s, now: now)
                    case .gap(let minutes):
                        Text("Break · \(durationLabel(Double(minutes) * 60))")
                            .font(VFont.inter(12, 600)).foregroundStyle(p.muted)
                            .padding(.horizontal, 12).padding(.vertical, 3)
                            .background(Capsule().fill(p.overlay))
                            .overlay(Capsule().strokeBorder(p.hairline, lineWidth: 1))
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(.horizontal, 12).padding(.top, 14)
            .frame(maxHeight: .infinity, alignment: .top)

            if let footer = rest.first.map({ ("Then: \($0.title)", $0.start) })
                ?? tomorrow.map({ ("\(Budapest.distance(from: today, to: $0.day)): \($0.title)", $0.start) }) {
                HStack {
                    Text(footer.0).foregroundStyle(p.secondary).lineLimit(1)
                    Spacer()
                    Text(Budapest.clock(footer.1)).font(VFont.mono(12, 600)).foregroundStyle(p.muted)
                }
                .font(VFont.inter(12, 600))
                .padding(.horizontal, 16).padding(.vertical, 9)
                .background(p.overlay)
                .overlay(alignment: .top) { Rectangle().fill(p.hairline).frame(height: 1) }
            }
        }
    }

    /// The cards with a break capsule where there's real time between two; the shortest breaks go first if it's too much.
    private func rows(_ visible: [Session]) -> [AgendaRow] {
        var rows: [AgendaRow] = []
        for (i, s) in visible.enumerated() {
            if i > 0, let gap = breakMinutes(visible[i - 1], s) { rows.append(.gap(gap)) }
            rows.append(.card(s))
        }
        while rows.count > Self.maxRows,
              let shortest = rows.enumerated().compactMap({ i, r -> (Int, Int)? in if case .gap(let m) = r { (i, m) } else { nil } }).min(by: { $0.1 < $1.1 }) {
            rows.remove(at: shortest.0)
        }
        return rows
    }
}

struct AgendaCard: View {
    @Environment(\.palette) private var p
    let session: Session
    let now: Date

    var body: some View {
        let live = session.isLive(at: now)
        let done = session.end <= now
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(session.title).font(VFont.inter(14, 800)).tracking(-0.2).foregroundStyle(done ? p.muted : p.text).lineLimit(1)
                if done {
                    Label { Text(["Done", session.location].filter { !$0.isEmpty }.joined(separator: " · ")) } icon: {
                        RemixIcon(name: "RiCheckboxCircle", size: 14).foregroundStyle(p.tone(.seminar))
                    }
                    .labelStyle(TightLabel()).font(VFont.inter(12, 600)).foregroundStyle(p.muted)
                } else {
                    StatusLabel(text: session.detail, kind: session.kind)
                }
                if live { LiveProgress(session: session, height: 4).padding(.top, 2) }
            }
            Spacer(minLength: 0)
            StackedTime(start: session.start, end: session.kind.isTimed ? session.end : nil)
        }
        .padding(.leading, 12).padding(.trailing, 6).padding(.vertical, 5)
        .glassCard(live: live ? p.course(session) : nil)
        .opacity(done ? 0.7 : 1)
    }
}
