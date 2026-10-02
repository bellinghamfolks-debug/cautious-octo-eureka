import SwiftUI
import XCTest
@testable import MaqamStudio

final class LocalizationTests: XCTestCase {
    private func table(_ language: String) throws -> [String: String] {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "Localizable", withExtension: "strings",
                                                subdirectory: nil, localization: language),
                                "\(language).lproj/Localizable.strings is not in the app")
        return try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: String])
    }

    private func isolatedDefaults() -> UserDefaults {
        let name = "maqam-l10n-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: name) }
        return UserDefaults(suiteName: name)!
    }

    func testBothLanguagesHaveTheSameKeysAndNoEmptyText() throws {
        let arabic = try table("ar")
        let english = try table("en")
        XCTAssertEqual(Set(arabic.keys), Set(english.keys))
        XCTAssertGreaterThan(arabic.count, 100)
        for (key, value) in arabic { XCTAssertFalse(value.trimmingCharacters(in: .whitespaces).isEmpty, "ar \(key)") }
        for (key, value) in english { XCTAssertFalse(value.trimmingCharacters(in: .whitespaces).isEmpty, "en \(key)") }
    }

    func testPlaceholdersMatchBetweenLanguages() throws {
        let arabic = try table("ar")
        let english = try table("en")
        let pattern = try NSRegularExpression(pattern: "%(\\d+\\$)?@")
        func count(_ text: String) -> Int {
            pattern.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        }
        for (key, value) in arabic {
            XCTAssertEqual(count(value), count(english[key] ?? ""), key)
        }
    }

    func testSwitchingLanguageChangesTextAndDirectionWithoutRestart() {
        let l10n = L10n(language: .arabic, defaults: isolatedDefaults())
        XCTAssertEqual(l10n("action.record"), "تسجيل")
        XCTAssertEqual(l10n.layoutDirection, .rightToLeft)

        l10n.setLanguage(.english, defaults: isolatedDefaults())
        XCTAssertEqual(l10n("action.record"), "Record")
        XCTAssertEqual(l10n.layoutDirection, .leftToRight)
    }

    func testLanguageChoiceIsRemembered() {
        let defaults = isolatedDefaults()
        L10n(language: .arabic, defaults: defaults).setLanguage(.english, defaults: defaults)
        XCTAssertEqual(L10n(defaults: defaults).language, .english)
    }

    func testFormattedArgumentsAreFilledInOrder() {
        let l10n = L10n(language: .english, defaults: isolatedDefaults())
        XCTAssertEqual(l10n("maqam.ajnas", "Rast", "Hijaz"), "Lower jins Rast, upper jins Hijaz")
        // In Arabic, Foundation wraps each argument in directional isolates
        // (U+2068 … U+2069) so a Latin word or number inside an RTL sentence
        // keeps its place. That is wanted; compare the visible text.
        let arabic = L10n(language: .arabic, defaults: isolatedDefaults())
        let filled = arabic("maqam.ajnas", "راست", "حجاز")
            .replacingOccurrences(of: "\u{2068}", with: "")
            .replacingOccurrences(of: "\u{2069}", with: "")
        XCTAssertEqual(filled, "الجنس الأسفل راست، والجنس الأعلى حجاز")
    }

    func testNumbersAndDurationsAreSpokenInTheInterfaceLanguage() {
        let arabic = L10n(language: .arabic, defaults: isolatedDefaults())
        let english = L10n(language: .english, defaults: isolatedDefaults())
        XCTAssertEqual(english.number(3), "3")
        XCTAssertNotEqual(arabic.number(3), "", "Arabic number formatting must produce text")
        XCTAssertTrue(english.spokenDuration(187).contains("3 minutes"), english.spokenDuration(187))
        XCTAssertNotEqual(arabic.spokenDuration(187), english.spokenDuration(187))
        XCTAssertEqual(english.clock(187), "3:07")
        XCTAssertEqual(english.decibels(-200), "silence")
        XCTAssertEqual(english.decibels(-6.02), "-6.0 dB")
    }

    /// Every error a user can meet has a real title and message in both
    /// languages — VoiceOver users hear these, so a raw key is a failure.
    func testEveryErrorHasSpokenTextInBothLanguages() {
        let errors: [AppError] = [
            .unsupportedFormat(fileExtension: "ogg"), .corruptedAudio, .emptyAudio, .fileAccessDenied,
            .microphonePermissionDenied, .noInputDevice, .insufficientStorage(requiredMegabytes: 300),
            .audioEngineFailed(detail: "d"), .recordingFailed(detail: "d"), .playbackFailed(detail: "d"),
            .projectCorrupted(name: "n"), .projectSaveFailed(detail: "d"), .projectNotFound, .coreFailure(code: 2),
            .maqamInvalid(name: "m"), .libraryUnreadable, .libraryTooNew, .exportFailed(detail: "f"),
            .exportSettings(problem: Int32(MQ_EXPORT_MP3_SAMPLE_RATE.rawValue)), .exportSettings(problem: 3),
        ]
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: isolatedDefaults())
            for error in errors {
                XCTAssertNotEqual(l10n(error.titleKey), error.titleKey, "\(language) \(error)")
                let message = error.message(l10n)
                XCTAssertFalse(message.hasPrefix("error."), "\(language) \(error)")
                XCTAssertFalse(message.contains("%@"), "\(language) \(error)")
            }
        }
        XCTAssertTrue(AppError.microphonePermissionDenied.offersSettings)
        XCTAssertFalse(AppError.corruptedAudio.offersSettings)
    }

    func testEveryBuiltinJinsHasANameInBothLanguages() {
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: isolatedDefaults())
            for maqam in MaqamCatalog.builtins {
                for jins in [maqam.lowerJins, maqam.upperJins] {
                    XCTAssertNotEqual(MaqamText.jinsName(jins, l10n: l10n), jins, "\(language) \(jins)")
                }
            }
        }
    }
}
