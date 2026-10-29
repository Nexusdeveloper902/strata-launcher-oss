# Research Method and Analysis Boundaries

## 1. Sources and provenance classes
| Class | Example | Default max confidence |
|---|---|---|
| APK metadata | package name, version, SDK, signature | Verified (for the artifact) |
| Manifest declarations | activities, intent filters, permissions | Verified that it is *declared*; behavior only Inferred |
| Resource tables | user-facing string keys, locales, font licenses | Verified that the resource exists; behavior Inferred |
| Class-name inventory | bundled libraries, package names | Inferred (names are not proof of use) |
| Black-box observation | running the app on a device | Verified — **not performed** (see §4) |
| Official Android docs | developer.android.com | Verified for platform facts |
| Public product docs | vendor help pages, store listing | Corroborated when it matches APK evidence |
| Secondary source | reviews, Reddit | Inferred unless corroborated |
| Proposal | our own design | Proposed |

## 2. Legal framing (not legal advice)
The user asserted reverse engineering "may be lawful". We neither accept nor reject that:
it depends on facts not available to us (user's jurisdiction, how the APK was obtained,
license terms accepted, purpose). The relevant frameworks we considered:

- **EU Software Directive 2009/24/EC.** Art. 5(3) lets a lawful user observe, study or
  test a program's functioning *while loading, displaying or running* it. Art. 6
  permits decompilation only when indispensable for **interoperability** of an
  independently created program, limited to the necessary parts, and the information
  may not be used to create a substantially similar program in expression. Building a
  competing launcher is not, by itself, an interoperability purpose.
- **US.** Copyright protects expression, not functionality (17 U.S.C. §102(b));
  intermediate copying for reverse engineering has been found fair use in specific
  cases (*Sega v. Accolade*, *Sony v. Connectix*), but contract terms can restrict it
  and DMCA §1201 prohibits circumventing technological protection measures, with a
  narrow interoperability exception (§1201(f)).
- **Contract.** Google Play Terms of Service and the app's own terms may prohibit
  reverse engineering; the app's current terms were **not reviewed** (U-003).
- **Trademark / trade dress.** Product name, logo, distinctive visual identity and
  marketing copy must not be reused regardless of how behavior is learned.

## 3. Activity matrix and what was actually done
| Activity | Risk | Done? |
|---|---|---|
| Hash, size, package metadata | Low | **Yes** |
| Read manifest declarations | Low (public, parsed by every installer) | **Yes** |
| Read resource table (string keys, locales, font licence notices) | Low–medium | **Yes**, summarised only; values paraphrased, not copied |
| List class/package names to identify libraries | Low–medium | **Yes**, names only |
| Decompile method bodies (DEX → Java/Kotlin) | **Medium–high** (Art. 6 purpose test; contract) | **No** — paused pending legal review (U-001) |
| Runtime observation on an authorized install | Low if lawfully installed | **No** — no device/emulator in environment (U-002) |
| Access protected resources, paid features, backends | High | **No — prohibited** |
| Circumvent billing / licence / obfuscation | High | **No — prohibited** |
| Reuse extracted code, assets, strings, fonts | High | **No — prohibited** |

## 4. Environment
- Isolated, ephemeral Linux cloud container; APK copied to a session scratch directory
  outside the repository; **never installed or executed**.
- Tooling: `sha256sum`, `unzip -l`, Python `androguard` 4.1.4 (static parser) run with
  isolated interpreter flags. Network was used only to install the parser and, by
  research agents, to read public documentation. No traffic to the vendor's services.
- The APK and raw extraction output are **not committed**.

## 5. Clean-room separation
- Multi-agent separation here is **not** a legally sufficient clean room. All agents
  share one orchestrator that saw the APK resource tables.
- Recommended for the build phase: implementers receive only `spec/`; a reviewer who has
  not seen the APK audits `spec/` for copied expression; legal counsel signs off before
  any release (U-004).
- Functional facts (e.g. "an alphabet index lets the user jump to a letter") may be
  independently implemented. Expression (exact copy, layouts, artwork, file formats'
  private structure, branding) may not be reused.

## 6. Confidence labels
Verified · Corroborated · Inferred · Proposed · Unknown — defined in `AGENTS.md`.
