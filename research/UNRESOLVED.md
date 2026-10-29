# Unresolved Questions and Blockers

| ID | Type | Question / blocker | Impact | Needed to resolve | State |
|---|---|---|---|---|---|
| U-001 | Legal blocker | Is decompiling method bodies permitted for this purpose in the user's jurisdiction and under the app's terms? | Internal defaults, theme file structure, free-tier limits remain Unknown. Spec does not depend on them. | Legal review; user's jurisdiction; license terms. | open — paused |
| U-002 | Environment blocker | No authorized device/emulator to run the original app. | All timing, animation, layout measurements Unknown. | User-provided screen recordings or a lawful test device. | open |
| U-003 | Legal | The original app's current Terms of Service / EULA were not reviewed. | Contract may restrict analysis already done. | Retrieve and review terms. | open |
| U-004 | Legal/process | Multi-agent separation is not a legally sufficient clean room. | Release risk. | Personnel separation for build phase + counsel sign-off. | open |
| U-005 | Provenance | User did not state where the APK was downloaded; no reference hash to compare. | Artifact authenticity unverified. | User states source; compare with an official channel. | open |
| U-006 | Product | Final independent product name and repo rename. | Branding. | Owner decision (D-002). | open |
