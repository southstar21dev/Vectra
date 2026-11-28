import Shared
import UIKit

/// The app's root: the shared Compose app full screen, with the system tab bar over it. The bar is a
/// real UITabBarController (Liquid Glass on iOS 26, the separate circular + like other iOS apps);
/// its pages are empty and transparent, so everything outside the bar is the Compose app.
final class RootViewController: UIViewController {
    private let compose = IosAppKt.MainViewController()
    private let tabs = TabBarHost()
    private let overlay = PassthroughView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(named: "LaunchBackground")

        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)

        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.tabs = tabs
        view.addSubview(overlay)
        // A regular-width iPad (and a Mac running the iPad app) would put the bar at the top, over the
        // page titles; the Compose pages leave room for it at the bottom, so keep it there as on iPhone.
        if traitCollection.userInterfaceIdiom == .pad {
            tabs.traitOverrides.horizontalSizeClass = .compact
        }
        addChild(tabs)
        tabs.view.frame = overlay.bounds
        tabs.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.addSubview(tabs.view)
        tabs.didMove(toParent: self)

        IosTabBar.shared.attach(host: tabs)
    }

    /// Widest the bottom bar's area grows on iPad and Mac (about a large iPhone's width).
    private static let wideBarMaxWidth: CGFloat = 440

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // In a wide window UIKit puts the tabs' pill at the left and the + at the far right of the bar's
        // area; a centred, phone-wide area keeps the pill and the + together in the middle.
        guard traitCollection.userInterfaceIdiom == .pad else { return }
        let bounds = overlay.bounds
        let width = min(bounds.width, Self.wideBarMaxWidth)
        tabs.view.autoresizingMask = []
        tabs.view.bounds = CGRect(x: 0, y: 0, width: width, height: bounds.height)
        tabs.view.center = CGPoint(x: bounds.midX, y: bounds.midY)
    }

    // Compose owns the status bar and the system gestures.
    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }
    override var childForHomeIndicatorAutoHidden: UIViewController? { compose }
    override var childForScreenEdgesDeferringSystemGestures: UIViewController? { compose }
}

/// Lets touches through to the Compose app everywhere except on the tab bar itself.
private final class PassthroughView: UIView {
    weak var tabs: TabBarHost?

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let tabs else { return nil }
        // iOS 26 lets the tap that dismisses a menu through to what's underneath; swallow it.
        if tabs.menuOpen { return self }
        guard tabs.isInteractive else { return nil }
        let bar = tabs.tabBar
        guard bar.point(inside: convert(point, to: bar), with: event) || tabs.sideActionButtonHit(convert(point, to: tabs.view)) else { return nil }
        return super.hitTest(point, with: event)
    }

    // The swallowed tap is the one that dismissed the menu; once it ends, the app takes touches again.
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { tabs?.menuOpen = false }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { tabs?.menuOpen = false }
}

/// A button whose menu reports when it opens and closes, so the host can shield the app from the dismissing tap.
private final class MenuButton: UIButton {
    var onMenuVisibility: ((Bool) -> Void)?

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willDisplayMenuFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
        onMenuVisibility?(true)
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willEndFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        onMenuVisibility?(false)
    }
}

/// An empty, see-through page: the content is the Compose app underneath.
private final class ClearPage: UIViewController {
    override func loadView() {
        view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
    }
}

final class TabBarHost: UITabBarController, UITabBarControllerDelegate, UIGestureRecognizerDelegate, @preconcurrency IosTabBarHost {
    /// The tabs, as the shared PrimaryNavItems describe them in every state the app pushes (labels, SF Symbols,
    /// which ones open something when held). Empty until the first render.
    private var items: [SystemNavbarTab] = []

    private var mainTabs: [UITab] = []

    /// Rebuilds the tabs when the shared list changed (it does once, at the first render).
    private func adopt(tabs newItems: [SystemNavbarTab]) {
        guard newItems.map({ [$0.label, $0.sfSymbol] }) != items.map({ [$0.label, $0.sfSymbol] }) else {
            items = newItems
            return
        }
        items = newItems
        mainTabs = newItems.enumerated().map { index, item in
            UITab(title: item.label, image: UIImage(systemName: item.sfSymbol), identifier: "tab.\(index).\(item.label)") { _ in ClearPage() }
        }
    }

    /// The + sits apart from the bar, in the slot iOS gives a search tab; it opens the page's actions.
    private lazy var actionTab: UISearchTab = {
        let tab = UISearchTab { _ in ClearPage() }
        tab.title = "Add"
        tab.image = UIImage(systemName: "plus")
        if #available(iOS 26.0, *) { tab.automaticallyActivatesSearch = false }
        return tab
    }()

    private var state: SystemNavbarState?
    private var hidden = true
    private var covered: CGFloat = 0
    /// The bar's shape at the last render, to redo the + when folding or unfolding turns it.
    private var renderedVertical: Bool?

    /// The bar runs down the side (an unfolded iPhone Duo) instead of along the bottom.
    private var isVertical: Bool { tabBar.bounds.height > tabBar.bounds.width }

    /// Down the side the + is a glass button of the app's own, just above the tabs. As a system tab it
    /// would shove the whole bar up or down each time it comes and goes with the page, and iOS draws
    /// that tab's glass where it can't be faded; this one simply fades in and out.
    private lazy var sideActionButton: MenuButton = {
        var config: UIButton.Configuration
        if #available(iOS 26.0, *) { config = .glass() } else { config = .gray() }
        config.image = UIImage(systemName: "plus", withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold))
        config.baseForegroundColor = .label
        config.cornerStyle = .capsule
        let button = MenuButton(configuration: config)
        button.showsMenuAsPrimaryAction = true
        button.onMenuVisibility = { [weak self] open in self?.menuVisibilityChanged(open) }
        button.accessibilityLabel = actionTab.title
        button.accessibilityHint = "Shows what you can add on this page"
        button.alpha = 0
        button.isHidden = true
        return button
    }()

    /// An invisible button over the + tab. A system tab can't carry a menu, so the tap on it lands here and
    /// opens the same native menu the side + shows.
    private lazy var actionMenuAnchor: MenuButton = {
        let button = MenuButton(type: .custom)
        button.onMenuVisibility = { [weak self] open in self?.menuVisibilityChanged(open) }
        button.showsMenuAsPrimaryAction = true
        button.isAccessibilityElement = false
        button.isHidden = true
        return button
    }()

    /// The + menu is up, or just left: the overlay then shields the app from the tap that dismissed it.
    var menuOpen = false

    private func menuVisibilityChanged(_ open: Bool) {
        if open {
            menuOpen = true
        } else {
            // The shielded tap's end releases it; this covers a dismissal that has no tap (an action ran).
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.menuOpen = false }
        }
    }

    var isInteractive: Bool { !hidden && covered < 0.01 && state != nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        mode = .tabBar
        view.backgroundColor = .clear
        if #available(iOS 26.0, *) { tabBarMinimizeBehavior = .never }
        setTabs(mainTabs, animated: false)
        // iOS 27 only sets a search tab apart when it activates search, which the + doesn't, so it would sit
        // in the bar as a plain item the selection lens slides to. Set once here, so the + arrives already
        // prominent: changing it alongside setTabs would animate the circle and the + as two separate steps.
        // prominentTabIdentifier only exists in the iOS 27 SDK (Xcode 27, Swift 6.4); CI's runner image may
        // still build with Xcode 26, where the name isn't known at compile time.
        #if compiler(>=6.4)
        if #available(iOS 27.0, *) { prominentTabIdentifier = actionTab.identifier }
        #endif
        view.alpha = 0
        view.addSubview(sideActionButton)
        view.addSubview(actionMenuAnchor)
        // Holding a tab (Transit: the nearby map grows out of it). Alongside the bar's own gestures,
        // so dragging the selection still works.
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(handleHold(_:)))
        hold.minimumPressDuration = 0.45
        hold.delegate = self
        view.addGestureRecognizer(hold)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    /// A hold has opened something (the nearby map): the tab's own selection, which UIKit delivers
    /// when the finger lifts, would navigate and close it again, so it is swallowed.
    private var holdSwallowsSelect = false

    @objc private func handleHold(_ recognizer: UILongPressGestureRecognizer) {
        if recognizer.state == .ended || recognizer.state == .cancelled || recognizer.state == .failed {
            // The selection can land just before or after this, so keep swallowing it briefly.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.holdSwallowsSelect = false }
            return
        }
        guard recognizer.state == .began, isInteractive, !isVertical else { return }
        let point = recognizer.location(in: view)
        guard let (index, frame) = tabItem(at: point) else { return }
        // Only a holdable tab's hold (Transit's) opens something that a following selection would close.
        holdSwallowsSelect = items.indices.contains(index) && items[index].holdable
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        // The Compose app fills the window, in pixels.
        let scale = traitCollection.displayScale
        let window = view.convert(frame, to: nil)
        IosTabBar.shared.hold(
            index: Int32(index),
            left: Float(window.minX * scale), top: Float(window.minY * scale),
            right: Float(window.maxX * scale), bottom: Float(window.maxY * scale)
        )
    }

    /// The main tab under [point] (in this controller's view) and its frame. The bar's buttons are
    /// private views, found by their titles; failing that, the bar's strip split evenly.
    private func tabItem(at point: CGPoint) -> (Int, CGRect)? {
        func search(_ node: UIView) -> (Int, CGRect)? {
            if node.isHidden || node === sideActionButton || node === actionMenuAnchor { return nil }
            let frame = node.convert(node.bounds, to: view)
            if frame.contains(point), frame.width < view.bounds.width / 2,
               let label = node.accessibilityLabel, let index = items.firstIndex(where: { $0.label == label }) {
                return (index, frame)
            }
            for sub in node.subviews { if let hit = search(sub) { return hit } }
            return nil
        }
        if let hit = search(view) { return hit }
        logViewTreeFallback("tab buttons not found by title; splitting the bar's strip evenly")
        var strip = tabBar.convert(tabBar.bounds, to: view)
        if tabs.contains(where: { $0 === actionTab }) { strip.size.width -= strip.height + 12 }
        guard strip.contains(point), strip.width > 0 else { return nil }
        let width = strip.width / CGFloat(mainTabs.count)
        let index = min(mainTabs.count - 1, max(0, Int((point.x - strip.minX) / width)))
        return (index, CGRect(x: strip.minX + width * CGFloat(index), y: strip.minY, width: width, height: strip.height))
    }

    private var loggedFallbacks = Set<String>()

    /// The hit testing and the side + lean on UIKit's private view tree. When a heuristic stops matching
    /// (an iOS release changed the tree), say so once per kind in the log instead of failing silently.
    private func logViewTreeFallback(_ message: String) {
        guard loggedFallbacks.insert(message).inserted else { return }
        NSLog("TabBarHost: %@", message)
    }

    // MARK: IosTabBarHost

    func render(state: SystemNavbarState) {
        loadViewIfNeeded()
        self.state = state
        adopt(tabs: state.tabs)

        // Along the bottom the + is a tab that comes and goes with the page. Down the side it is
        // sideActionButton instead, so the bar itself never changes.
        let vertical = isVertical
        renderedVertical = vertical
        let wanted = state.actions.isEmpty || vertical ? mainTabs : mainTabs + [actionTab]
        let animated = view.window != nil && view.alpha > 0
        if tabs.map(\.identifier) != wanted.map(\.identifier) {
            setTabs(wanted, animated: animated)
        }
        showSideActionButton(vertical && !state.actions.isEmpty, animated: animated)
        let menu = Self.actionsMenu(state.actions, accent: Self.color(argb: state.accentArgb))
        sideActionButton.menu = menu
        actionMenuAnchor.menu = menu
        placeActionMenuAnchor()
        let index = Int(state.selectedIndex)
        if mainTabs.indices.contains(index), selectedTab !== mainTabs[index] {
            selectedTab = mainTabs[index]
        }

        view.tintColor = Self.color(argb: state.accentArgb)
        view.overrideUserInterfaceStyle = state.dark ? .dark : .light
        setHidden(state.hidden)
    }

    func cover(fraction: Float) {
        covered = CGFloat(fraction)
        if isVertical {
            // Down the side, sliding left would drag the bar across the page (and a card leaves the
            // tabs showing around it): the bar fades out instead of staying there, dead to taps.
            view.transform = .identity
            view.alpha = shownAlpha
        } else {
            // A pushed screen slides in from the right; the bar leaves just ahead of its edge.
            // By the view's right edge: on iPad the bar's area is a centred strip, not the whole window.
            view.transform = CGAffineTransform(translationX: -(view.center.x + view.bounds.width / 2) * covered, y: 0)
            // Folding from the vertical layout while covered leaves its faded alpha behind.
            view.alpha = shownAlpha
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let state, let rendered = renderedVertical, rendered != isVertical {
            render(state: state)
            cover(fraction: Float(covered))
        }
        placeSideActionButton()
        placeActionMenuAnchor()
    }

    func remove() {
        state = nil
        setHidden(true)
    }

    // MARK: UITabBarControllerDelegate

    func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
        if tab === actionTab {
            // Reached without a touch on the anchor (VoiceOver, keyboard): open the menu from it.
            placeActionMenuAnchor()
            actionMenuAnchor.performPrimaryAction()
            return false
        }
        if let index = mainTabs.firstIndex(where: { $0 === tab }) {
            // Allowed (the bar shows the tab the hold already switched to), but not reported: it would close the map.
            if holdSwallowsSelect { return true }
            // Reselecting the current tab still goes to Compose, which scrolls it to the top.
            IosTabBar.shared.select(index: Int32(index))
        }
        return true
    }

    // MARK: Private

    /// Whether [point] (in this controller's view) is on the side + while it shows.
    func sideActionButtonHit(_ point: CGPoint) -> Bool {
        !sideActionButton.isHidden && sideActionButton.alpha > 0.01 && sideActionButton.frame.contains(point)
    }

    private func showSideActionButton(_ show: Bool, animated: Bool) {
        let button = sideActionButton
        guard show != (button.alpha > 0 && !button.isHidden) else { return }
        if show {
            placeSideActionButton()
            button.isHidden = false
        }
        let change = { button.alpha = show ? 1 : 0 }
        let done: (Bool) -> Void = { _ in if button.alpha == 0 { button.isHidden = true } }
        if animated {
            UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: change, completion: done)
        } else {
            change()
            done(true)
        }
    }

    /// Centred over the tabs' pill, a gap above it.
    private func placeSideActionButton() {
        guard isVertical, let pill = tabsPillFrame() else { return }
        let size: CGFloat = 52
        sideActionButton.frame = CGRect(x: pill.midX - size / 2, y: pill.minY - 12 - size, width: size, height: size)
    }

    /// Where the floating bar draws the tabs' pill. It lives in a container of the bar's own, not in
    /// `tabBar`, and UIKit doesn't hand it out: it is the tall, narrow view over the bar's strip.
    private func tabsPillFrame() -> CGRect? {
        let strip = tabBar.convert(tabBar.bounds, to: view)
        func find(_ node: UIView) -> CGRect? {
            if node === tabBar || node === sideActionButton || node === actionMenuAnchor || node.isHidden { return nil }
            let frame = node.convert(node.bounds, to: view)
            // Tall and narrow, but not the full-height strip iOS keeps beside the bar.
            if (40...80).contains(frame.width), frame.height >= 2 * frame.width, frame.height < strip.height * 0.8,
               strip.intersects(frame) { return frame }
            for sub in node.subviews { if let hit = find(sub) { return hit } }
            return nil
        }
        let pill = view.subviews.lazy.compactMap(find).first
        if pill == nil { logViewTreeFallback("tabs pill not found; the side + button keeps its last position") }
        return pill
    }

    /// Fully shown, less how far a pushed screen covers the tabs where the bar fades for it.
    private var shownAlpha: CGFloat { hidden ? 0 : (isVertical ? 1 - covered : 1) }

    private func setHidden(_ value: Bool) {
        guard value != hidden else { return }
        hidden = value
        UIView.animate(withDuration: value ? 0.16 : 0.22, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.view.alpha = self.shownAlpha
        }
    }

    /// The + menu as a native UIMenu. The shared list runs from the least to the most important action; a menu
    /// that opens above its button lists its first element nearest the button, so the primary one goes first.
    private static func actionsMenu(_ actions: [SystemNavbarAction], accent: UIColor) -> UIMenu {
        UIMenu(children: actions.reversed().map { action in
            UIAction(
                title: action.label,
                image: UIImage(systemName: action.sfSymbol)?.withTintColor(
                    action.id == actions.last?.id ? accent : .label, renderingMode: .alwaysOriginal
                ),
                attributes: action.disabled ? .disabled : []
            ) { _ in IosTabBar.shared.runAction(id: action.id) }
        })
    }

    /// Lays the invisible menu button over the + tab (hidden when there is none: no actions, or the bar
    /// runs down the side where sideActionButton is the +).
    private func placeActionMenuAnchor() {
        guard let state, !state.actions.isEmpty, !isVertical, tabs.contains(where: { $0 === actionTab }) else {
            actionMenuAnchor.isHidden = true
            return
        }
        if let plus = actionTabView() {
            actionMenuAnchor.frame = plus.convert(plus.bounds, to: view)
        } else {
            actionMenuAnchor.frame = tabBar.convert(CGRect(x: tabBar.bounds.maxX - 56, y: 0, width: 44, height: tabBar.bounds.height), to: view)
        }
        actionMenuAnchor.isHidden = false
    }

    /// The + in the bar. Like the tabs, a private view of the bar's, found by its title.
    private func actionTabView() -> UIView? {
        func search(_ node: UIView) -> UIView? {
            if node.isHidden || node === sideActionButton || node === actionMenuAnchor { return nil }
            if node.accessibilityLabel == actionTab.title, node.bounds.width < view.bounds.width / 2 { return node }
            for sub in node.subviews { if let hit = search(sub) { return hit } }
            return nil
        }
        return search(view)
    }

    private static func color(argb: Int32) -> UIColor {
        let v = UInt32(bitPattern: argb)
        return UIColor(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: CGFloat((v >> 24) & 0xFF) / 255
        )
    }
}
