import Shared
import SwiftUI
import UIKit

/// The iOS shell. Everything the user sees is the shared Compose app (shared/), the same code
/// androidApp runs, except the tab bar, which is the system's (TabBarHost.swift).
@main
struct VectraIOSApp: App {
    init() {
        // Before start(): the first snapshot is published as soon as the schedule loads.
        IosWidgets.shared.attach(bridge: WidgetBridge())
        IosApp.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .background(Color("LaunchBackground"))
                // Widget taps (vectra://open/schedule) land on the screen they show.
                .onOpenURL { IosApp.shared.openUrl(url: $0.absoluteString) }
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        RootViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
