import Foundation
import UIKit

/// Speaks status changes through VoiceOver.
///
/// Progress is announced at whole quarters only, and the same sentence is not
/// repeated, so a long import does not flood the user with numbers.
@MainActor
final class Announcer {
    private var lastMessage = ""
    private var lastQuarterByTask: [String: Int] = [:]

    func announce(_ message: String, important: Bool = false) {
        guard !message.isEmpty, message != lastMessage else { return }
        lastMessage = message
        if #available(iOS 17.0, *) {
            let priority: UIAccessibilityPriority = important ? .high : .default
            let attributed = NSAttributedString(string: message,
                                                attributes: [.accessibilitySpeechAnnouncementPriority: priority])
            UIAccessibility.post(notification: .announcement, argument: attributed)
        } else {
            UIAccessibility.post(notification: .announcement, argument: message)
        }
    }

    /// Announces `message(percent)` when progress crosses 25%, 50% and 75%.
    func progress(task: String, fraction: Double, message: (Double) -> String) {
        let quarter = Int((min(max(fraction, 0), 1) * 4).rounded(.down))
        guard quarter > (lastQuarterByTask[task] ?? 0), quarter < 4 else { return }
        lastQuarterByTask[task] = quarter
        announce(message(Double(quarter) / 4))
    }

    func reset(task: String) {
        lastQuarterByTask[task] = nil
    }
}
