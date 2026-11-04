import Shared
import SwiftUI
import UIKit

/// The iOS shell. Everything the user sees is the shared Compose app (shared/), the same code
/// androidApp runs; this file only hosts it, as MainActivity does on Android.
@main
struct VectraIOSApp: App {
    init() {
        IosApp.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .background(Color("LaunchBackground"))
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
