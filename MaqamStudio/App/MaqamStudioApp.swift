import SwiftUI

@main
struct MaqamStudioApp: App {
    @StateObject private var l10n: L10n
    @StateObject private var model: AppModel

    init() {
        let l10n = L10n()
        _l10n = StateObject(wrappedValue: l10n)
        _model = StateObject(wrappedValue: AppModel(l10n: l10n))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(l10n)
                .environmentObject(model)
                .environmentObject(model.library)
                .task { model.start() }
        }
    }
}
