import Foundation
import Security

/// The App Group container the app and VectraWidgets share.
///
/// A sideloading store (SideStore, AltStore) re-signs the app with the installing user's free team
/// and renames every App Group to `<group>.<TeamID>`. Both sides look for that name first, then the
/// original, and use the first one the signature actually grants.
enum AppGroup {
    /// From Info.plist (`VECTRA_APP_GROUP` in project.yml): Debug builds use their own group, so a
    /// debug and a release install never read each other's snapshot.
    static let base = Bundle.main.object(forInfoDictionaryKey: "VectraAppGroup") as? String
        ?? "group.dev.southstar21.vectra"

    /// Where the app writes the widget snapshot; nil when the build carries no App Group.
    static let container: URL? = {
        var candidates: [String] = []
        if let team = teamID { candidates.append("\(base).\(team)") }
        candidates.append(base)
        return candidates.lazy
            .compactMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
            .first
    }()

    static let snapshotFile = "widget_snapshot.json"

    static var snapshotURL: URL? { container?.appendingPathComponent(snapshotFile) }

    /// The signing team, read from the access group the keychain gives this app's own items
    /// (`<TeamID>.<bundle id>`). There is no public API for it.
    private static let teamID: String? = {
        let account = "vectra.team-id-probe"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnAttributes as String: true,
        ]
        var result: CFTypeRef?
        var status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            var add = query
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = SecItemAdd(add as CFDictionary, &result)
        }
        guard status == errSecSuccess,
              let attributes = result as? [String: Any],
              let group = attributes[kSecAttrAccessGroup as String] as? String,
              let team = group.split(separator: ".").first
        else { return nil }
        return String(team)
    }()
}
