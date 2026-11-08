import Shared
import WidgetKit

/// The app's half of the widgets: tells the shared code where VectraWidgets reads its snapshot,
/// and asks WidgetKit to redraw once a new one is written.
final class WidgetBridge: IosWidgetBridge {
    func containerPath() -> String? { AppGroup.container?.path }

    func reloadAll() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
