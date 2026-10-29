# Unresolved Questions and Blockers

| ID | Type | Question / blocker | Impact | Needed to resolve | State |
|---|---|---|---|---|---|
| U-001 | Legal blocker | Is decompiling method bodies permitted for this purpose in the user's jurisdiction and under the app's terms? | Internal defaults, theme file structure, free-tier limits remain Unknown. Spec does not depend on them. | Legal review; user's jurisdiction; license terms. | open — paused |
| U-002 | Environment blocker | No authorized device/emulator to run the original app. | All timing, animation, layout measurements Unknown. | User-provided screen recordings or a lawful test device. | open |
| U-003 | Legal | The original app's current Terms of Service / EULA were not reviewed. | Contract may restrict analysis already done. | Retrieve and review terms. | open |
| U-004 | Legal/process | Multi-agent separation is not a legally sufficient clean room. | Release risk. | Personnel separation for build phase + counsel sign-off. | open |
| U-005 | Provenance | User did not state where the APK was downloaded; no reference hash to compare. | Artifact authenticity unverified. | User states source; compare with an official channel. | open |
| U-006 | Product | Final independent product name and repo rename. | Branding. | Owner decision (D-002). | open |
| U-007 | Research gap | How does the original's search match queries (prefix/substring/fuzzy/transliteration/ranking)? | Our matching is Proposed, not mirrored. | Runtime observation. | open — spec proceeds with own design |
| U-008 | Research gap | Exact free-tier theme retention limit (APK-025, PUB-043). | None for our product (all free). | Runtime observation. | open — low priority |
| U-009 | Contradiction | In-app font size: PUB-038 says none; APK-022 shows scale steps. | Likely PUB-038 stale. Our spec includes font scale regardless. | Runtime observation. | open — resolved for our spec |
| U-010 | Research gap | Original's screen-reader behavior undocumented. | Our a11y reqs are Proposed from platform guidance. | TalkBack test on device. | open |
| U-011 | Research gap | Per-app icon changes free or paid in original (PUB-018 vs SEC-002). | None (ours free). | Observation. | open — low priority |
| U-012 | Contradiction | Widget backup: PUB-047 (newer) says widgets backed up; APK-031 strings say widgets may fail to restore. | Consistent reading: widgets restored when the provider still exists, else reported. Our spec adopts that. | — | resolved (interpretation) |
| U-013 | Contradiction | Search gesture: SEC-002 says swipe down; PUB-014 says swipe down = notification shade, swipe up = search; APK-028 swipe-up. | Majority + APK → swipe up = search. | — | resolved |
