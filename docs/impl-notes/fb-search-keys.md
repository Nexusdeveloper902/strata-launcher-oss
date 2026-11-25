# Neighbouring-key slips in search (`fb-search-keys`)

Owner feedback FB-015 ("way more often than not you press the wrong key… 'pg' when searching for phone instead of
'ph'") and review finding R-002 (fuzzy scratch arrays sized to the whole query; surrogate pairs counted as two edits),
both in `docs/feedback/BACKLOG.md`. Everything is **Proposed** in the spec: FR-SEARCH-02 and D-016 are amended,
ARCHITECTURE §8 gains a tier row, tests are T-SEARCH-28…31 plus a slip set in T-PERF-05.

## Spec changes

| Where | What |
|---|---|
| PRODUCT_SPEC FR-SEARCH-02 | Amendment (FB-015): slip tier, its limits and gate; R-002 code points and long-query skip. |
| ARCHITECTURE §8 | New row "(FB-015) slip", base 250; T6 row notes R-002. |
| DECISIONS D-016 | Amendment and rationale (measured noise, recall, perf; weighted distance rejected). |
| TEST_PLAN | T-SEARCH-28…31; T-SEARCH-22 now uses "Nate Mode"; T-PERF-05 slip set. |
| REQUIREMENTS_TRACEABILITY | Regenerated (290 test cases). |

## Design

- **`KeyboardLayout`** (`core/search`): letter rows plus row shifts (0, ¼, ¾ key). Two keys are neighbours when side by
  side in a row, or in adjacent rows with centres less than one key apart, so each key touches two above and two below.
  Built once per layout into 26 bit masks; a check is one array read and a bit test. Only folded a–z have neighbours.
  **Proposed.**
- **Seam for other layouts:** `SearchIndex.build(…, keyboard = KeyboardLayout.QWERTY)`. `QWERTZ`, `AZERTY` and `NONE`
  are defined and tested but not wired. A later setting, or a lookup of the active IME subtype, can pass another layout
  without touching the ranking. **Proposed.** Whether the platform reports the Samsung keyboard's layout reliably is
  **Unknown**.
- **Tier `ADJACENT` (250)**, between `INITIALS` (300) and `SUBSTRING` (200). The query must equal the start of the
  label or of a later word (camel-case splits included) except for substitutions, each by a neighbouring key. There
  are no insertions, deletions or other substitutions. A match at the label start gets +10, like fuzzy.
- **Second pass** (`SearchIndex.forgiving`): over the entries the first pass did not match, under the existing gate
  (fewer than 5 label matches, a letter in the query). It tries a slip first, then fuzzy (T6) if no slip matched.
- **Precomputed at build:** each label and camel form keeps its code points and code-point word starts, and the index
  keeps the longest form length. Per keystroke the query is converted to code points once. The pass makes no other
  allocation except the fuzzy scratch, which is now bounded by the longest label.
- **R-002:** the OSA distance runs over code-point arrays, so an emoji left out is one edit. The slip and fuzzy passes
  are skipped when the query is longer than the longest label form plus the edit budget. The scratch is sized
  `min(query, longest label) + edits + 1`. **Verified** by T-SEARCH-31.

## Ranking rules and why

| Rule | Reason |
|---|---|
| Exact, prefix, word prefix and initials stay above slips | They are deliberate. A label that really starts with "pg" ("Pgn Reader") or has initials "pg" ("Pocket Guide") ranks above Phone. The brief preferred Phone above real "pg" apps, but it also required exact, prefix and word-start matches to stay first; the hard rule wins. In practice few labels start with such a pair, so Phone is first for "pg" on the test list. |
| Slips above substring, package and fuzzy | A wrong neighbouring key at a word start is stronger evidence than letters somewhere inside a word. Fuzzy edits are not tied to the keyboard. |
| From 2 characters; 1 slip up to 4, 2 for 5–9, 3 from 10 | The owner's case is 2 characters. At least about two thirds of the letters must be right. |
| One letter never slips | One key says nothing about which key was meant. "j" would add 8 of the 41 test labels. The brief allowed one slip for 1–2 characters; for one character, none is the safer choice. |
| Word starts only, never package names | "Graphite Sketch" has "ph" inside a word, and "pg" must not light it up. Package segments are not typed from the keyboard. |
| Same gate as fuzzy (< 5 label matches) | Slips never join a full first pass ("ca" stays clean). |
| Substitutions only, not a weighted edit distance | With a neighbour counted as half an edit, two slips in four letters ("bite" → "note") would pass under T6's one-edit budget. |

Examples on the invented 41-label list in `KeyboardAdjacencyTest`:

- "pg", "oh" and "pgo" find Phone and Photos.
- "pj" finds Phone, Photos and Pixelgram (j is next to i).
- "og" (two slips) and "pm" (m is not next to h) find nothing.
- "nw" finds Brightside News (slip on a later word), ranked above Fernway Travel (substring).
- "nores" finds Notes (slip), ranked above Norges Atlas (fuzzy).
- "cslendsr" finds Calendar, and "xalculatpe" finds Calculator (3 slips at 10 characters).

## Measurements (JVM, this container, 500 apps; three runs each, `--rerun`)

| Set | Before (median / p95 ms) | After (median / p95 ms) |
|---|---|---|
| T-PERF-05 base ("a", "ma", "map", "mpas", "テ") | 0.045–0.061 / 0.18–0.25 | 0.039–0.093 / 0.15–0.35 |
| T-PERF-05 typo set | 0.110–0.149 / 0.14–0.22 | 0.110–0.114 / 0.15–0.18 |
| Slip set ("pg", "oh", "pgo", "phpne", "cslendsr", "mapz") | 0.063–0.078 / 0.13–0.14 | 0.077–0.081 / 0.14–0.17 |
| Index build (500, cached translit) | 6.5–8.0 | 6.5–7.4 |

**Verified** that the change stays within noise on this host. The earlier figure (0.18 / 0.67 ms) came from another
host. Noise and recall over all 702 one- and two-letter queries (**Verified**, printed by T-SEARCH-30): 239 queries get
slips, adding 1.6 apps on average and 7 at worst ("fa"). 336 of the 338 one-slip variants of each label's first two
letters find their app; the other 2 are held back by the 5-match gate.

## Inferred / Unknown

- **Inferred:** the owner's keyboard is QWERTY with the usual stagger. The neighbour map is geometric, not measured on
  the Samsung keyboard.
- **Unknown:** whether a slip for the first letter ("oh") is as common as one for the second ("pg"). Both are allowed.
- **Unknown:** cost on the A56. The JVM numbers are far below the 8 / 16 ms budget.

## A56 check list

1. Type "pg": Phone is the first result, and Photos is also listed.
2. Type "oh" and "pgo": Phone is listed. Type "og": Phone is not listed.
3. Type a real 2-letter prefix (e.g. "ca"): the results are unchanged, with no unrelated slip results.
4. Type one letter that starts no label: no results except the store row (if a store is installed).
5. Type "phpne": Phone is listed. Type a normal typo of 4+ letters (e.g. "calender"): it is still found.
6. Search feels as fast as before: there is no lag while typing quickly.
