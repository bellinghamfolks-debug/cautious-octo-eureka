import SwiftUI

/// Applies the chosen language to everything below it, and shows errors.
struct RootView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        MainView()
            .environment(\.locale, l10n.locale)
            .environment(\.layoutDirection, l10n.layoutDirection)
            // Re-create the view tree when the language changes, so every
            // cached string and accessibility label is rebuilt at once.
            .id(l10n.language)
            .alert(item: errorBinding) { wrapped in
                if wrapped.error.offersSettings {
                    return Alert(
                        title: Text(l10n(wrapped.error.titleKey)),
                        message: Text(wrapped.error.message(l10n)),
                        primaryButton: .default(Text(l10n("action.opensettings"))) {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        },
                        secondaryButton: .cancel(Text(l10n("action.close"))))
                }
                return Alert(title: Text(l10n(wrapped.error.titleKey)),
                             message: Text(wrapped.error.message(l10n)),
                             dismissButton: .default(Text(l10n("action.ok"))))
            }
    }

    private struct IdentifiedError: Identifiable {
        let id = UUID()
        let error: AppError
    }

    private var errorBinding: Binding<IdentifiedError?> {
        Binding(
            get: { model.error.map { IdentifiedError(error: $0) } },
            set: { if $0 == nil { model.error = nil } })
    }
}
