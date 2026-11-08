import CoreText
import SwiftUI
import WidgetKit

// The app's tokens (shared/…/core/designsystem/theme/Color.kt) and the pieces of its design
// language the widgets reuse: the aurora canvas, glass cards, StatusLabel, the stacked time block
// and the raised accent pill.

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// The app's type: Inter and JetBrains Mono, the same variable font files the shared module ships
/// (the extension bundles them straight from composeResources), at the exact weights VType uses.
enum VFont {
    static func inter(_ size: CGFloat, _ weight: CGFloat) -> Font { make("Inter", size, weight) }
    static func mono(_ size: CGFloat, _ weight: CGFloat) -> Font { make("JetBrains Mono", size, min(weight, 800)) }

    private static let registered: Void = {
        for name in ["inter_variable", "jetbrains_mono_variable"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "ttf") {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }()

    private static func make(_ family: String, _ size: CGFloat, _ weight: CGFloat) -> Font {
        _ = registered
        let wght = 0x7767_6874 // 'wght'
        let descriptor = CTFontDescriptorCreateWithAttributes([
            kCTFontFamilyNameAttribute: family,
            kCTFontVariationAttribute: [wght: weight],
        ] as CFDictionary)
        return Font(CTFontCreateWithFontDescriptor(descriptor, size, nil))
    }
}

struct Palette {
    let dark: Bool

    var surface: Color { dark ? Color(hex: 0x07080A) : Color(hex: 0xF8FAFC) }
    var text: Color { dark ? Color(hex: 0xF8FAFC) : Color(hex: 0x0F172A) }
    var secondary: Color { dark ? Color(hex: 0x94A3B8) : Color(hex: 0x334155) }
    var muted: Color { dark ? Color(hex: 0x7C8AA0) : Color(hex: 0x64748B) }
    var hairline: Color { dark ? .white.opacity(0.08) : Color(hex: 0x0F172A, alpha: 0.08) }
    var overlay: Color { dark ? .white.opacity(0.035) : .black.opacity(0.03) }
    var glass: Color { dark ? Color(hex: 0x0F1015, alpha: 0.88) : .white.opacity(0.95) }
    var specular: Color { dark ? .white.opacity(0.22) : .white.opacity(0.8) }
    var accent: Color { dark ? Color(hex: 0x38BDF8) : Color(hex: 0x0284C7) }
    var onAccent: Color { dark ? Color(hex: 0x04121B) : .white }
    var pill: Color { dark ? Color(hex: 0x38BDF8) : Color(hex: 0x0274B3) }

    /// ScheduleCard's session tones: lecture sky, seminar emerald, lab purple, exam violet.
    func tone(_ kind: Kind) -> Color {
        switch kind {
        case .lecture, .other: accent
        case .seminar: dark ? Color(hex: 0x34D399) : Color(hex: 0x059669)
        case .lab, .shift: dark ? Color(hex: 0xC084FC) : Color(hex: 0x7C3AED)
        case .exam: dark ? Color(hex: 0xC4B5FD) : Color(hex: 0x6D28D9)
        case .task: dark ? Color(hex: 0xFBBF24) : Color(hex: 0xB45309)
        }
    }

    /// A class's own colour for its glow, the accent when it has none.
    func course(_ session: Session) -> Color {
        let hex = session.color.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard hex.count == 6, let value = UInt32(hex, radix: 16) else { return Color(hex: 0x38BDF8) }
        return Color(hex: value)
    }
}

struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette(dark: true)
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

/// Sets the palette from the system appearance for everything inside.
struct Themed<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    @ViewBuilder let content: Content

    var body: some View { content.environment(\.palette, Palette(dark: scheme == .dark)) }
}

/// The app canvas behind a widget: the surface with the aurora glows, lit in a course colour while
/// that class runs.
struct Canvas: View {
    @Environment(\.palette) private var p
    var glow: Color?

    var body: some View {
        ZStack {
            p.surface
            if let glow {
                RadialGradient(colors: [glow.opacity(p.dark ? 0.24 : 0.2), .clear], center: UnitPoint(x: 0.3, y: 0.2), startRadius: 0, endRadius: 220)
            } else {
                RadialGradient(colors: [p.accent.opacity(p.dark ? 0.16 : 0.12), .clear], center: UnitPoint(x: 0.1, y: 0), startRadius: 0, endRadius: 260)
                RadialGradient(colors: [Color(hex: 0xA855F7, alpha: p.dark ? 0.14 : 0.1), .clear], center: UnitPoint(x: 0.95, y: 1), startRadius: 0, endRadius: 240)
            }
        }
    }
}

/// The glass card: translucent fill, a brighter top edge fading into the sides.
struct GlassCard: ViewModifier {
    @Environment(\.palette) private var p
    var radius: CGFloat = 16
    /// A course colour: the card is lit from inside and rimmed in it (the class in progress).
    var live: Color?

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .background {
                ZStack {
                    shape.fill(p.glass)
                    if let live {
                        shape.fill(RadialGradient(colors: [live.opacity(0.2), .clear], center: .center, startRadius: 0, endRadius: 120))
                    }
                }
            }
            .overlay {
                if let live {
                    shape.strokeBorder(live.opacity(0.44), lineWidth: 1)
                } else {
                    shape.strokeBorder(LinearGradient(stops: [.init(color: p.specular, location: 0), .init(color: p.hairline, location: 0.35)],
                                                      startPoint: .top, endPoint: .bottom), lineWidth: 1)
                }
            }
            .shadow(color: live?.opacity(0.27) ?? .black.opacity(p.dark ? 0.35 : 0.08), radius: live == nil ? 10 : 14, y: live == nil ? 6 : 0)
    }
}

extension View {
    func glassCard(radius: CGFloat = 16, live: Color? = nil) -> some View { modifier(GlassCard(radius: radius, live: live)) }
}

/// StatusLabel: a tone dot (or a personal item's icon) beside a quiet grey label.
struct StatusLabel: View {
    @Environment(\.palette) private var p
    let text: String
    let kind: Kind

    var body: some View {
        HStack(spacing: 6) {
            ToneMark(kind: kind)
            Text(text).font(VFont.inter(12, 600)).foregroundStyle(p.secondary).lineLimit(1)
        }
    }
}

struct ToneMark: View {
    @Environment(\.palette) private var p
    let kind: Kind

    var body: some View {
        switch kind {
        case .task: RemixIcon(name: "RiTask", size: 14).foregroundStyle(p.tone(kind))
        case .shift: RemixIcon(name: "RiBriefcase", size: 14).foregroundStyle(p.tone(kind))
        default:
            Circle().fill(p.tone(kind).opacity(0.2)).frame(width: 14, height: 14)
                .overlay(Circle().fill(p.tone(kind)).frame(width: 8, height: 8))
        }
    }
}

/// The stacked start/end block on the right of the app's class cards.
struct StackedTime: View {
    @Environment(\.palette) private var p
    let start: Date
    let end: Date?

    var body: some View {
        VStack(spacing: 4) {
            Text(Budapest.clock(start)).foregroundStyle(p.text)
            if let end {
                Rectangle().fill(p.hairline).frame(width: 14, height: 1)
                Text(Budapest.clock(end)).foregroundStyle(p.secondary)
            }
        }
        .font(VFont.mono(12, 700))
        .padding(.horizontal, 12).padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(p.overlay))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(p.hairline, lineWidth: 1))
    }
}

/// The raised accent pill (pillDepth Accent).
struct AccentPill<Label: View>: View {
    @Environment(\.palette) private var p
    @ViewBuilder let label: Label

    var body: some View {
        label
            .font(VFont.inter(12, 800))
            .foregroundStyle(p.onAccent)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(p.pill))
            .overlay(Capsule().strokeBorder(.white.opacity(0.35), lineWidth: 1))
            .shadow(color: p.accent.opacity(0.35), radius: 8, y: 3)
    }
}

/// A progress bar that fills on its own between two dates, no timeline entries needed.
struct LiveProgress: View {
    @Environment(\.palette) private var p
    let session: Session
    var height: CGFloat = 6

    var body: some View {
        ProgressView(timerInterval: session.start...session.end, countsDown: false) { EmptyView() } currentValueLabel: { EmptyView() }
            .progressViewStyle(.linear)
            .tint(p.accent)
            .frame(height: height)
    }
}

struct Place: View {
    @Environment(\.palette) private var p
    let location: String

    var body: some View {
        if !location.isEmpty {
            Label { Text(location).lineLimit(1) } icon: { RemixIcon(name: "RiMapPin", size: 14) }
                .font(VFont.inter(12, 600))
                .foregroundStyle(p.secondary)
                .labelStyle(TightLabel())
        }
    }
}

/// A Remix icon from the extension's asset catalog (the app's icon set), tinted by foregroundStyle.
struct RemixIcon: View {
    let name: String
    let size: CGFloat

    var body: some View {
        Image(name).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size)
    }
}

struct TightLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) { configuration.icon; configuration.title }
    }
}

struct Micro: View {
    @Environment(\.palette) private var p
    let text: String

    var body: some View {
        Text(text.uppercased()).font(VFont.inter(10, 700)).tracking(1).foregroundStyle(p.muted).lineLimit(1)
    }
}
