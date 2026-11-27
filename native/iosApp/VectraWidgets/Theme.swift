import CoreText
import SwiftUI
import WidgetKit

// The app's tokens (shared/…/core/designsystem/theme/Color.kt) and the time-spine pieces every
// widget is built from. In the Home Screen's tinted and clear looks (accented rendering) the system
// draws the glass and the tint itself: the content goes white, and the parts marked
// widgetAccentable() (type words, rings, progress, today) take the tint.

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
    enum Look { case dark, light, accented }
    let look: Look

    var dark: Bool { look != .light }
    var accented: Bool { look == .accented }

    // The colour values (text, secondary, ..., tone) are generated into Tokens.swift from
    // native/tools/widget-tokens.json, the table the Android plate drawables also come from.
}

struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette(look: .dark)
}

/// Previews only: draws a look the preview can't ask the system for (tinted, clear).
struct LookOverrideKey: EnvironmentKey {
    static let defaultValue: Palette.Look? = nil
}

/// The app's 12-hour setting, for layouts whose time columns need more room.
struct Hour12Key: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var hour12: Bool {
        get { self[Hour12Key.self] }
        set { self[Hour12Key.self] = newValue }
    }

    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }

    var lookOverride: Palette.Look? {
        get { self[LookOverrideKey.self] }
        set { self[LookOverrideKey.self] = newValue }
    }
}

/// Sets the palette from the appearance and the rendering mode for everything inside.
struct Themed<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.widgetRenderingMode) private var mode
    @Environment(\.lookOverride) private var override
    @ViewBuilder let content: Content

    var body: some View {
        let look: Palette.Look = override ?? (mode == .fullColor ? (scheme == .dark ? .dark : .light) : .accented)
        content.environment(\.palette, Palette(look: look))
    }
}

/// The widget itself is the one glass card: a soft gradient with a brighter top edge. The tinted and
/// clear looks drop it for the system's glass.
struct Plate: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        ZStack {
            LinearGradient(colors: dark ? [PlateTokens.darkTop, PlateTokens.darkBottom] : [PlateTokens.lightTop, PlateTokens.lightBottom],
                           startPoint: .top, endPoint: .bottom)
            ContainerRelativeShape()
                .strokeBorder(LinearGradient(stops: [.init(color: dark ? PlateTokens.darkRimTop : PlateTokens.lightRimTop, location: 0),
                                                     .init(color: dark ? PlateTokens.darkRimMid : PlateTokens.lightRimMid, location: 0.3)],
                                             startPoint: .top, endPoint: .bottom), lineWidth: 1)
        }
    }
}

/// Where a tap on any Vectra widget lands: the Schedule tab.
let scheduleURL = URL(string: "vectra://open/schedule")!

/// Every Home Screen widget: the plate behind, our own margins, the Schedule tab on tap.
struct WidgetBox<Content: View>: View {
    var padding = EdgeInsets(top: 12, leading: 13, bottom: 12, trailing: 13)
    @ViewBuilder let content: Content

    var body: some View {
        Themed { content.padding(padding).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading) }
            .containerBackground(for: .widget) { Plate() }
            .widgetURL(scheduleURL)
    }
}

// MARK: - Text

/// The class type as the app writes it: an uppercase word in the type's colour.
struct TypeWord: View {
    @Environment(\.palette) private var p
    let kind: Kind

    var body: some View {
        Text(kind.label.uppercased()).font(VFont.mono(10, 800)).tracking(1.6).foregroundStyle(p.tone(kind)).lineLimit(1).widgetAccentable()
    }
}

/// A name. Long ones shrink a little rather than get cut off.
struct Name: View {
    @Environment(\.palette) private var p
    let text: String
    var size: CGFloat = 15
    var weight: CGFloat = 800
    var dim = false

    var body: some View {
        Text(text).font(VFont.inter(size, weight)).tracking(-0.2).foregroundStyle(dim ? p.muted : p.text)
            .lineLimit(3).minimumScaleFactor(0.85).fixedSize(horizontal: false, vertical: true)
    }
}

/// A quiet grey line (the teacher, a count).
struct Quiet: View {
    @Environment(\.palette) private var p
    let text: String
    var size: CGFloat = 11

    var body: some View {
        Text(text).font(VFont.inter(size, 600)).foregroundStyle(p.secondary).lineLimit(1).minimumScaleFactor(0.75)
    }
}

struct Mono: View {
    @Environment(\.palette) private var p
    let text: String
    var size: CGFloat = 12
    var weight: CGFloat = 700
    var color: Color?

    var body: some View {
        Text(text).font(VFont.mono(size, weight)).foregroundStyle(color ?? p.text).monospacedDigit().lineLimit(1).fixedSize()
    }
}

struct Micro: View {
    @Environment(\.palette) private var p
    let text: String

    var body: some View {
        Text(text.uppercased()).font(VFont.inter(10, 700)).tracking(1).foregroundStyle(p.muted).lineLimit(1)
    }
}

/// Pin and room, people and group.
struct RoomLine: View {
    @Environment(\.palette) private var p
    let room: String
    var group = ""
    var size: CGFloat = 11

    var body: some View {
        HStack(spacing: 4) {
            if !room.isEmpty {
                RemixIcon(name: "RiMapPin", size: size + 1)
                Text(room).lineLimit(1).minimumScaleFactor(0.75)
            }
            if !group.isEmpty {
                RemixIcon(name: "RiGroup", size: size + 1).padding(.leading, room.isEmpty ? 0 : 4)
                Text(group).lineLimit(1).fixedSize()
            }
        }
        .font(VFont.inter(size, 600))
        .foregroundStyle(p.secondary)
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

// MARK: - Shapes

struct Hair: View {
    @Environment(\.palette) private var p
    var body: some View { Rectangle().fill(p.hairline).frame(height: 1) }
}

/// A flat progress line: the track and the fill in the type's colour.
struct Bar: View {
    @Environment(\.palette) private var p
    let fraction: Double
    let kind: Kind
    var height: CGFloat = 3

    var body: some View {
        Capsule().fill(p.track).frame(height: height)
            .overlay(alignment: .leading) {
                GeometryReader { g in
                    Capsule().fill(p.tone(kind)).frame(width: max(g.size.width * fraction, height)).widgetAccentable()
                }
            }
    }
}

/// The countdown ring: a track and an arc in the type's colour, with the content inside.
struct Ring<Inner: View>: View {
    @Environment(\.palette) private var p
    let fraction: Double
    let kind: Kind
    var size: CGFloat = 50
    var width: CGFloat = 4.5
    @ViewBuilder let inner: Inner

    var body: some View {
        ZStack {
            Circle().stroke(p.track, lineWidth: width)
            Circle().trim(from: 0, to: max(min(fraction, 1), 0.001))
                .stroke(p.tone(kind), style: StrokeStyle(lineWidth: width, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .widgetAccentable()
            inner
        }
        .frame(width: size, height: size)
    }
}

/// A ring's number with its unit word under it.
struct RingValue: View {
    @Environment(\.palette) private var p
    let value: String
    let unit: String

    var body: some View {
        VStack(spacing: 0) {
            Text(value).font(VFont.mono(value.count > 3 ? 14 : 18, 800)).foregroundStyle(p.text).minimumScaleFactor(0.6).lineLimit(1)
            Text(unit).font(VFont.inter(8, 700)).tracking(0.6).foregroundStyle(p.muted).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 6)
    }
}

/// The green check of a finished class.
struct CheckDot: View {
    @Environment(\.palette) private var p
    var size: CGFloat = 12

    var body: some View {
        if p.accented {
            // Tinted and clear draw in one ink, so a filled dot would swallow its check: a ring with the check inside.
            Circle().strokeBorder(p.text, lineWidth: 1.2).frame(width: size, height: size)
                .overlay(RemixIcon(name: "RiCheck", size: size * 0.7).foregroundStyle(p.text))
        } else {
            Circle().fill(p.check).frame(width: size, height: size)
                .overlay(RemixIcon(name: "RiCheck", size: size * 0.75).foregroundStyle(p.onCheck))
        }
    }
}

/// "12:30  Microeconomics": the time in the type's colour, the name beside it.
struct MiniRow: View {
    @Environment(\.palette) private var p
    let time: String
    let title: String
    let kind: Kind?
    var strong = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(time).font(VFont.mono(11, 700)).monospacedDigit().foregroundStyle(kind.map(p.tone) ?? p.muted).fixedSize().widgetAccentable()
            Text(title).font(VFont.inter(12, 700)).foregroundStyle(strong ? p.text : p.soft).lineLimit(3).minimumScaleFactor(0.85).fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct EmptyMessage: View {
    @Environment(\.palette) private var p
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RemixIcon(name: "RiCalendar", size: 20).foregroundStyle(p.accent).widgetAccentable()
            Name(text: "Vectra", size: 16)
            Text(text).font(VFont.inter(12, 600)).foregroundStyle(p.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}
