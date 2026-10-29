# Agent Rules and Handoff Convention

## Hard rules (all agents)
1. **Clean room.** Never copy decompiled code, proprietary strings, artwork, icons,
   fonts, wallpapers, theme files or brand names from the original app into `spec/`
   or into any implementation. Describe behavior in your own words.
2. **No circumvention.** Never attempt to unlock paid features, bypass licensing,
   billing, authentication or obfuscation, contact the original vendor's backends,
   or extract credentials/keys.
3. **Evidence first.** Every material claim carries a label —
   `Verified`, `Corroborated`, `Inferred`, `Proposed`, `Unknown` — and an evidence
   ID from `research/EVIDENCE.md` (or a `Proposed` design rationale).
4. **Never invent behavior.** If it was not observed or documented, it is `Unknown`
   or `Proposed`, not a fact.
5. **No secrets.** Do not commit credentials, tokens, personal data, or the APK itself.
6. **Implementers** read `spec/` only. They must not receive the APK, extraction output
   or `research/APK_ANALYSIS.md` raw notes (see `research/RESEARCH_METHOD.md` §5).

## Task assignment
Each task is recorded in `research/STATUS.md` as:

```
| T-NN | owner | owned output file | question | state |
```
- One task = one narrow question = **one owned output file**. Only the owner edits it;
  others propose changes via `research/handoffs/T-NN.md` or `research/UNRESOLVED.md`.
- States: `todo`, `active`, `blocked(<reason>)`, `review`, `done`.

## Handoff format (≤ 15 lines, in `research/handoffs/T-NN.md`)
```
Task: T-NN  Owner: <agent>  Output: <file>
Answer: <2–4 sentence answer to the task question>
New evidence IDs: <list>
New requirement IDs: <list or none>
Open questions → UNRESOLVED.md: <U-IDs>
Confidence: <label>  Limitations: <one line>
```

## ID scheme (stable; never renumber, only deprecate)
| Prefix | Meaning | Registry |
|---|---|---|
| `APK-nnn` | Finding from the APK artifact | `research/EVIDENCE.md` |
| `DOC-nnn` | Official Android documentation | `research/EVIDENCE.md` |
| `PUB-nnn` | Public product docs / store listing | `research/EVIDENCE.md` |
| `SEC-nnn` | Secondary source (review, forum) | `research/EVIDENCE.md` |
| `OBS-nnn` | Direct black-box observation | `research/EVIDENCE.md` |
| `D-nnn` | Decision | `research/DECISIONS.md` |
| `U-nnn` | Unresolved question / blocker | `research/UNRESOLVED.md` |
| `FR-<AREA>-nn` / `NFR-<AREA>-nn` | Requirement | `spec/REQUIREMENTS_TRACEABILITY.md` |
| `T-<AREA>-nn` | Test case | `spec/TEST_PLAN.md` |
