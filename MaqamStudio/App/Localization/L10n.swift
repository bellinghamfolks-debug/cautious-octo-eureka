import Foundation
import SwiftUI

/// Arabic and English text, switchable while the app runs.
///
/// iOS normally fixes an app's language at launch. Maqam Studio loads the
/// `.lproj` bundle for the chosen language itself, so changing language
/// re-renders every view immediately, flips layout direction, and changes
/// how numbers are written and spoken, with no restart.
///
/// Not actor-isolated: lookups and formatting are pure and are needed from
/// VoiceOver callbacks; `setLanguage` is only ever called from the UI.
final class L10n: ObservableObject {
    enum Language: String, CaseIterable, Identifiable, Codable {
        case arabic = "ar"
        case english = "en"

        var id: String { rawValue }

        /// The language's own name, so it is recognisable whichever is active.
        var nativeName: String {
            switch self {
            case .arabic: return "العربية"
            case .english: return "English"
            }
        }
    }

    static let storageKey = "maqamstudio.language"

    @Published private(set) var language: Language
    private var bundle: Bundle

    init(language: Language? = nil, defaults: UserDefaults = .standard) {
        let stored = defaults.string(forKey: Self.storageKey).flatMap(Language.init(rawValue:))
        let chosen = language ?? stored ?? Self.systemPreferred()
        self.language = chosen
        self.bundle = Self.bundle(for: chosen)
    }

    func setLanguage(_ newLanguage: Language, defaults: UserDefaults = .standard) {
        guard newLanguage != language else { return }
        bundle = Self.bundle(for: newLanguage)
        language = newLanguage
        defaults.set(newLanguage.rawValue, forKey: Self.storageKey)
    }

    var locale: Locale { Locale(identifier: language == .arabic ? "ar" : "en") }
    var layoutDirection: LayoutDirection { language == .arabic ? .rightToLeft : .leftToRight }
    /// Separator between items read as a list ("، " in Arabic).
    var listSeparator: String { language == .arabic ? "، " : ", " }

    /// The text for `key`. A missing key returns the key itself so it is
    /// obvious in testing; LocalizationTests fails the build on any such gap.
    func callAsFunction(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    /// The text for `key` with `%@` placeholders filled in order.
    func callAsFunction(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: callAsFunction(key), locale: locale, arguments: arguments)
    }

    // MARK: Numbers, read correctly aloud in both languages

    func number(_ value: Double, fractionDigits: Int = 0) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = fractionDigits
        formatter.maximumFractionDigits = fractionDigits
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    func percent(_ fraction: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .percent
        formatter.maximumFractionDigits = 0
        return formatter.string(from: NSNumber(value: fraction)) ?? "\(Int(fraction * 100))%"
    }

    func decibels(_ value: Double) -> String {
        if value <= -150 { return callAsFunction("units.silence") }
        return callAsFunction("units.decibels", number(value, fractionDigits: 1))
    }

    /// "3:07" on screen.
    func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        let minutes = total / 60
        let rest = total % 60
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.minimumIntegerDigits = 2
        return "\(number(Double(minutes))):\(formatter.string(from: NSNumber(value: rest)) ?? String(rest))"
    }

    /// "3 minutes 7 seconds" for VoiceOver, which reads "3:07" badly in Arabic.
    func spokenDuration(_ seconds: Double) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
        formatter.unitsStyle = .full
        formatter.zeroFormattingBehavior = .dropLeading
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        formatter.calendar = calendar
        return formatter.string(from: max(0, seconds.rounded(.down))) ?? clock(seconds)
    }

    func date(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    // MARK: Bundles

    private static func bundle(for language: Language) -> Bundle {
        if let path = Bundle.main.path(forResource: language.rawValue, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return bundle
        }
        return .main
    }

    private static func systemPreferred() -> Language {
        let preferred = Locale.preferredLanguages.first ?? "ar"
        return preferred.hasPrefix("en") ? .english : .arabic
    }
}
