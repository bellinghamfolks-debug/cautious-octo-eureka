# 2.6.0 — a switch that can be reached, and a bundle that answers

Two changes, and they are the same change viewed from either end. The first makes it possible to
capture the pixels behind a failure at the moment the failure happens. The second makes the bundle
say what it knows instead of repeating what it noticed.

## 1. The control that could not be reached

Screen capture mirrors the display. eSight's shared view has to stay in front for anything to be
captured at all. So opening VisionBridge to reach the "save a screen image on a reading failure"
switch replaced that view, and by the time the switch was flipped the page that would not read was
no longer being captured. The one control needed mid-session was the one control unreachable
mid-session.

### What was built

Two surfaces, same action, same spoken confirmation:

- **Android's accessibility button** (`EvidenceCaptureShortcutService`). The floating shortcut in the
  navigation bar, or the volume-key shortcut, whichever the user has assigned. Reachable from inside
  any app. A TalkBack user already knows this surface.
- **A notification action** on the existing foreground-service notification. No setup at all, and
  the shade is reachable from inside the shared view too.

The decision itself — new state, wording, whether to mark the moment — is `EvidenceShortcut`, which
is pure and tested. That matters more than it looks: the announcement *is* the user interface here.
A sighted user pressing a switch sees where it landed; the person this is built for hears one
sentence and has nothing else to check it against. So the sentence names the new state first, quotes
the frame count when switching off, and says so explicitly when nothing was captured rather than
leaving a silence to be interpreted.

### What the service is allowed to do

No `accessibilityEventTypes` at all — the attribute has no "none" flag, and leaving it out is how a
service subscribes to nothing: the mask defaults to zero. With `canRetrieveWindowContent="false"`
alongside it, the service is delivered no events and can read no window content. This is not
politeness — asking someone who depends on TalkBack to
enable a *second* accessibility service is a real cost, and it is only reasonable if the second one
demonstrably cannot see anything. It receives a button press and nothing else.

### One behaviour changed on purpose

Switching capture off used to delete every frame already captured. That was defensible while the
switch lived on a settings screen, where turning it off meant "I do not want images in my bundle".
It is wrong for a shortcut, because the workflow the shortcut exists for is *on → reproduce → off →
export*, and deleting on the third step throws away everything the first two were for.

So switching off now stops capture and keeps what was caught. What keeps that safe is unchanged:

- nothing is captured unless the user switched it on;
- the count appears in the manifest, in the archive's file name, and in the README inside it, so a
  bundle with images cannot be mistaken for one without;
- the ceiling is still 40 frames and 24 MB, enforced rather than promised;
- deleting is now an explicit action ("حذف اللقطات المحفوظة") that reports how many it removed.

## 2. Diagnostics that answer instead of accumulating

### Every discarded region is now counted

`DbPostProcessor` had four `continue` statements. Between them they decided whether a text region
became a box, and they counted nothing. That is why one bundle could not answer whether a large,
clear English word was *never detected* or *detected and thrown away* — two defects that need
opposite repairs and that looked identical from outside.

`DbPostProcessor.Census` now reports, per frame: how many regions cleared the binary threshold, how
many were accepted, and how many were rejected under each of the four reasons
(`below_minimum_side`, `taller_than_wide_limit`, `mean_probability_below_threshold`,
`empty_after_scaling`). The largest rejected region is described by size, position and score, as
fractions of the frame so the numbers survive not knowing what resolution the frame was detected at.
Accepted line heights are reported as a p10/median/p90 spread, which is the quantity the resolution
controller solves on — a frame where every accepted line is nine pixels tall explains a poor read
without anyone opening an image.

### The network is described at the moment of binding

Five field sessions all ran with cellular forced. None ran without. "Cellular freezes the app" was
therefore a correlation with no control group, and no number of further sessions in the same
configuration would settle it. What settles it is what the bound network could actually do at the
instant it was bound, recorded next to what the default network could do at the same instant:
transports, validated, captive portal, suspended, metered, link bandwidth, signal strength.

Capability-level only. No carrier name, no subscriber identity, no addresses — none of which would
help a diagnosis, all of which would be a cost to carry in a file the user sends to someone.

### Repetition is counted, not written

One bundle carried 2,109 automatic findings. All of them said "a stage was slow" or "a frame was
dropped". Together they said what one of them said, and they buried the events that mattered.

`FindingBudget` writes the first eight of a kind — twenty-five for critical ones — and after that
only an instance at least 25% worse than the worst already written. Everything else is counted, and
the counts are emitted once at session end with the worst value each kind reached. Nothing is
dropped silently; the repetition is summarised instead of transcribed.

A finding with no measurement can never beat a record, so after its burst it goes quiet. That is
deliberate: an unmeasured observation repeated a thousand times adds nothing after the eighth.

### Four new verdict rules

| Code | Fires when | Severity |
| --- | --- | --- |
| `DETECTOR_REJECTED_LARGE_REGIONS` | Frames end with zero accepted boxes while regions above threshold existed, and the largest discarded one covers ≥2% of the frame | MAJOR |
| `BOUND_NETWORK_NEVER_VALIDATED` | Most cellular bindings landed on a network the system never validated, with the default network's state alongside | MAJOR |
| `READING_RESOLUTION_NEVER_SETTLED` | The adaptive controller spent most of the session bracketing because it never found text to measure | MINOR |
| `NO_FRAME_KEPT_TO_SETTLE_IT` | Something genuinely failed, no frame was kept, and a frame would have settled it — with the instructions for the shortcut | MINOR |

The last one is guidance rather than a defect, so it is gated: it appears only when a FATAL or MAJOR
finding exists *and* no evidence frame was captured. Advice with no defect behind it is exactly the
noise the rest of this release removes.

## What was verified, and what was not

**The whole unit suite: 240 tests, all passing.** That includes 8 for the shortcut's decision and
its announcement, 10 for the finding budget, 9 for the detection census, and 11 new verdict-rule
cases — each of which checks both that the rule fires on the timeline it was derived from *and* that
it stays silent on a healthy one, because a rule that fires on everything is worse than no rule.

These were run outside Gradle. The build environment for this change could not reach
`dl.google.com`, so neither the Android SDK nor any AndroidX artifact was available and
`assembleDebug` could not run. Compiling against `org.robolectric:android-all:15-…` from Maven
Central gives a real Android 15 framework to type-check against, which covers every file that does
not import AndroidX:

- **Compiled against the real framework**: `DiagnosticHub`, `DiagnosticRecorder`, `EvidenceStore`,
  `FindingBudget`, `SessionVerdict`, `CellularNetworkManager`, `PaddleOcrEngine`, `DbPostProcessor`,
  the vision and speech packages, and — the one whose Android API usage was least obvious —
  `EvidenceCaptureShortcutService`, including `AccessibilityButtonController`, its callback
  overrides, and the service lifecycle methods.
- **Not compiled anywhere yet**: `MediaProjectionService`, `MainActivity`, `MainViewModel`,
  `SettingsScreen`, and the real `VisionBridgeApp` — all of which import AndroidX or the generated
  `R`. Their new code was read line by line and the symbols it reaches for were verified to exist
  (`Settings.ACTION_ACCESSIBILITY_SETTINGS`, `Toast.makeText`, `AppSettings` being a data class, the
  two new string resources), but reading is not compiling and should not be reported as such.

The gap in that harness showed itself immediately: it type-checks Kotlin and cannot link resources,
so it passed a `res/xml` file whose `accessibilityEventTypes="typeNone"` aapt rejects outright —
`typeNone` is an `AccessibilityServiceInfo` constant, not a resource flag, and subscribing to
nothing is done by omitting the attribute. Only the real build caught it. Anything under `res/` is
outside what can be checked here.

**Not verified on a device.** No adb, no emulator. The service's registration with the button
controller at runtime, the notification action's round trip through `PendingIntent`, and the spoken
confirmation reaching the user over a live capture are all unexercised. They are the first things to
check on hardware.
