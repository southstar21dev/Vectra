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
        addChild(tabs)
        tabs.view.frame = overlay.bounds
        tabs.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.addSubview(tabs.view)
        tabs.didMove(toParent: self)

        IosTabBar.shared.attach(host: tabs)
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
        // iOS 26 lets the tap that dismisses a popover through to what's underneath; swallow it.
        if tabs.menuOpen {
            // Gone already (an action ran, or it dismissed without telling us): stop shielding.
            if tabs.presentedViewController == nil { tabs.menuOpen = false } else { return self }
        }
        guard tabs.isInteractive else { return nil }
        let bar = tabs.tabBar
        guard bar.point(inside: convert(point, to: bar), with: event) || tabs.sideActionButtonHit(convert(point, to: tabs.view)) else { return nil }
        return super.hitTest(point, with: event)
    }

    // The shielded tap is the one that dismissed the menu; once it ends, the app takes touches again.
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { tabs?.menuOpen = false }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { tabs?.menuOpen = false }
}

/// An empty, see-through page: the content is the Compose app underneath.
private final class ClearPage: UIViewController {
    override func loadView() {
        view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
    }
}

final class TabBarHost: UITabBarController, UITabBarControllerDelegate, UIPopoverPresentationControllerDelegate, IosTabBarHost {
    // Same order as the shared PrimaryNavItems.
    private static let items: [(title: String, symbol: String)] = [
        ("Dashboard", "square.grid.2x2"),
        ("Schedule", "calendar"),
        ("Transit", "tram"),
        ("Settings", "gearshape"),
    ]

    private lazy var mainTabs: [UITab] = Self.items.enumerated().map { index, item in
        UITab(title: item.title, image: UIImage(systemName: item.symbol), identifier: "tab.\(index)") { _ in ClearPage() }
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
    private lazy var sideActionButton: UIButton = {
        var config: UIButton.Configuration
        if #available(iOS 26.0, *) { config = .glass() } else { config = .gray() }
        config.image = UIImage(systemName: "plus", withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold))
        config.baseForegroundColor = .label
        config.cornerStyle = .capsule
        let button = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in self?.presentActions() })
        button.accessibilityLabel = actionTab.title
        button.alpha = 0
        button.isHidden = true
        return button
    }()

    /// The + menu is showing (the overlay then shields the Compose app from the dismissing tap).
    var menuOpen = false

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
    }

    // MARK: IosTabBarHost

    func render(state: SystemNavbarState) {
        loadViewIfNeeded()
        self.state = state

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
            view.transform = CGAffineTransform(translationX: -view.bounds.width * covered, y: 0)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let state, let rendered = renderedVertical, rendered != isVertical {
            render(state: state)
            cover(fraction: Float(covered))
        }
        placeSideActionButton()
    }

    func remove() {
        state = nil
        setHidden(true)
    }

    // MARK: UITabBarControllerDelegate

    func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
        if tab === actionTab {
            presentActions()
            return false
        }
        if let index = mainTabs.firstIndex(where: { $0 === tab }) {
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
            if node === tabBar || node === sideActionButton || node.isHidden { return nil }
            let frame = node.convert(node.bounds, to: view)
            // Tall and narrow, but not the full-height strip iOS keeps beside the bar.
            if (40...80).contains(frame.width), frame.height >= 2 * frame.width, frame.height < strip.height * 0.8,
               strip.intersects(frame) { return frame }
            for sub in node.subviews { if let hit = find(sub) { return hit } }
            return nil
        }
        return view.subviews.lazy.compactMap(find).first
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

    private func presentActions() {
        guard let actions = state?.actions, !actions.isEmpty, presentedViewController == nil else { return }
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        for action in actions {
            let item = UIAlertAction(title: action.label, style: .default) { [weak self] _ in
                self?.menuOpen = false
                IosTabBar.shared.runAction(id: action.id)
            }
            item.isEnabled = !action.disabled
            sheet.addAction(item)
        }
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in self?.menuOpen = false })
        if let popover = sheet.popoverPresentationController {
            if isVertical {
                popover.sourceView = sideActionButton
                popover.sourceRect = sideActionButton.bounds
            } else {
                popover.sourceView = tabBar
                popover.sourceRect = CGRect(x: tabBar.bounds.maxX - 56, y: 0, width: 44, height: tabBar.bounds.height)
            }
            popover.delegate = self
        }
        menuOpen = true
        present(sheet, animated: true)
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        // Let the dismissing tap finish before the app takes touches again.
        DispatchQueue.main.async { self.menuOpen = false }
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
