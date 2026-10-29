import re, sys, json, collections
sys.path.insert(0, '.')
from summ import S
import os
R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))) + '/'
def rd(p): return open(R+p, encoding='utf-8').read().split('\n')
PS, TS, AR, RM, TP = (rd('spec/PRODUCT_SPEC.md'), rd('spec/THEME_SYSTEM.md'), rd('spec/ARCHITECTURE.md'),
                      rd('spec/IMPLEMENTATION_ROADMAP.md'), rd('spec/TEST_PLAN.md'))
REQ = r'(?:N?FR)-[A-Z0-9]+'
def cells(line):
    c = [x.strip() for x in line.strip().strip('|').split('|')]
    return c
def expand(text, bare=False):
    """expand requirement IDs incl. ranges (…), slash (/NN), and bare ', -NN' continuations"""
    out = []
    pat = re.compile(r'(%s)-(\d+)|…(?:\s*(%s)-)?(\d+)|/(\d+)|(?<![\w\-])-(\d+)' % (REQ, REQ))
    pre = None; last = None
    for m in pat.finditer(text):
        if m.group(1):
            pre, last = m.group(1), int(m.group(2)); out.append(f"{pre}-{last:02d}")
        elif m.group(4) and pre and last is not None and m.group(0).startswith('…'):
            n = int(m.group(4))
            for k in range(last+1, n+1): out.append(f"{pre}-{k:02d}")
            last = n
        elif m.group(5) and pre and last is not None:
            last = int(m.group(5)); out.append(f"{pre}-{last:02d}")
        elif m.group(6) and bare and pre:
            last = int(m.group(6)); out.append(f"{pre}-{last:02d}")
    return out
# ---------------- requirements
reqs = collections.OrderedDict()
def add(rid, **kw):
    assert rid not in reqs, rid
    reqs[rid] = kw
sec = None
for i, l in enumerate(PS, 1):
    m = re.match(r'### (4\.\d+) ', l)
    if m: sec = m.group(1)
    if l.startswith('## 5.'): sec = '5'
    if l.startswith('## 6.'): sec = None
    if not re.match(r'\| (N?FR)-[A-Z0-9]+-\d+ ', l): continue
    c = cells(l); rid = c[0]
    if sec == '5':
        assert len(c) == 4, l
        add(rid, stmt=c[1], pri=c[2], rel='—', evtxt='—', label='Proposed (blanket statement, PRODUCT_SPEC §5 intro)', acc=c[3], accloc=f'PRODUCT_SPEC §5', src='PRODUCT_SPEC', line=i)
    else:
        assert len(c) == 6, (len(c), l[:80])
        ev, _, lab = c[4].partition(' · ')
        if not _: ev, lab = '—', c[4]   # "Proposed" only
        add(rid, stmt=c[1], pri=c[2], rel=c[3], evtxt=ev, label=lab, acc=c[5], accloc=f'PRODUCT_SPEC §{sec}', src='PRODUCT_SPEC', line=i)
sec = None
for i, l in enumerate(TS, 1):
    if l.startswith('### 15.1'): sec = '15.1'
    if l.startswith('### 15.2'): sec = '15.2'
    if l.startswith('## 16'): sec = None
    if not sec or not re.match(r'\| (N?FR)-THEME-\d+ ', l): continue
    c = cells(l); assert len(c) == 4, l[:60]
    add(c[0], stmt=c[1], pri='—', rel='—', evtxt=c[3], label=c[3], acc=c[2], accloc=f'THEME_SYSTEM §{sec}', src='THEME_SYSTEM', line=i)
AREAS = []
def area(r): return re.match(r'(N?FR)-([A-Z0-9]+)-', r).groups()
# ---------------- evidence registry
EVR = collections.defaultdict(set)   # id -> sources
def reg(src, text, rowonly=True):
    for m in re.finditer(r'^\|\s*((?:APK|DOC|PUB|SEC|OBS)-\d+)\s*\|', text, re.M):
        EVR[m.group(1)].add(src)
raw = lambda p: open(R+p, encoding='utf-8').read()
reg('APK_ANALYSIS', raw('research/APK_ANALYSIS.md'))
for m_ in re.finditer(r'APK-\d+', raw('research/APK_ANALYSIS.md')): EVR[m_.group(0)].add('APK_ANALYSIS (mention/heading)')
pb = raw('research/PUBLIC_BEHAVIOR.md'); reg('PUBLIC_BEHAVIOR §Evidence', pb[pb.index('\n## Evidence'):])
ap = raw('spec/ANDROID_PLATFORM.md'); reg('ANDROID_PLATFORM §References', ap[ap.index('\n## References'):])
ev = raw('research/EVIDENCE.md')
idx = set(re.findall(r'^\|\s*((?:APK|DOC|PUB|SEC|OBS)-\d+)\s*\|', ev, re.M))
for a, b in [(19, 34)]:
    for k in range(a, b+1): idx.add(f'APK-{k:03d}'); EVR[f'APK-{k:03d}'].add('EVIDENCE (range)')
for i in idx: EVR[i].add('EVIDENCE')
for a,b,p in [(1,72,'DOC'),(1,76,'PUB'),(1,5,'SEC')]:
    pass
for i in range(1, 73): idx.add(f'DOC-{i:03d}')   # EVIDENCE.md states DOC-001…072 registered in ANDROID_PLATFORM
for i in range(1, 77): idx.add(f'PUB-{i:03d}')
for i in range(1, 11): idx.add(f'SEC-{i:03d}')
decs = set(re.findall(r'^\| (D-\d+) ', raw('research/DECISIONS.md'), re.M))
unr = set(re.findall(r'^\| ?(U-\d+)', raw('research/UNRESOLVED.md'), re.M))
EVTOK = re.compile(r'\b((?:APK|DOC|PUB|SEC|OBS)-\d+)(?:…\s*(?:(?:APK|DOC|PUB|SEC|OBS)-)?(\d+))?')
def evids(text):
    out = []
    for m in EVTOK.finditer(text):
        a = m.group(1); out.append(a)
        if m.group(2):
            p, n = a.split('-'); 
            for k in range(int(n)+1, int(m.group(2))+1): out.append(f'{p}-{k:03d}')
    return out
def others(text):
    return sorted(set(re.findall(r'\b([DU]-\d+|P-\d+)\b', text)))
for r, d in reqs.items():
    d['ev'] = list(dict.fromkeys(evids(d['evtxt'])))
    d['oth'] = others(d['evtxt'])
    d['evnote'] = ''
    if d['evtxt'] == '—' and d['src'] == 'PRODUCT_SPEC' and d['accloc'].endswith('§5'):
        d['ev'] = list(dict.fromkeys(evids(d['stmt'] + ' ' + d['acc'])))
        d['oth'] = others(d['stmt'] + ' ' + d['acc'])
        if d['ev'] or d['oth']: d['evnote'] = ' (cited in statement)'
    d['summary'] = S[r]; assert len(S[r].split()) <= 12, (r, S[r])
assert set(S) == set(reqs), (set(S) ^ set(reqs))
dang = {}
for r, d in reqs.items():
    for e in d['ev']:
        miss = [] 
        if e not in EVR or not (EVR[e] - {'EVIDENCE'}): miss.append('no definition row in APK_ANALYSIS/PUBLIC_BEHAVIOR/ANDROID_PLATFORM')
        if e not in idx: miss.append('not covered by EVIDENCE.md index')
        if miss: dang.setdefault(e, []).append((r, miss))
dang_other = []
for r, d in reqs.items():
    for o in d['oth']:
        if o.startswith('D-') and o not in decs: dang_other.append((r, o))
        if o.startswith('U-') and o not in unr: dang_other.append((r, o))
# ---------------- architecture
arch = collections.defaultdict(list); asec = ''
arch_all = set()
for l in AR:
    m = re.match(r'#{2,3} (\d+(?:\.\d+)?)\.? ', l)
    if m: asec = m.group(1)
    for rid in expand(l):
        arch_all.add(rid)
        if asec and asec not in arch[rid]: arch[rid].append(asec)
AREAMOD = {
 'HOME': ':feature:home, :app', 'LIST': ':feature:home, :core:ui, :platform:apps, :core:data',
 'SEARCH': ':core:search, :feature:search, :platform:system', 'APPS': ':platform:apps, :platform:icons, :core:data, :feature:home',
 'FAV': ':feature:home, :core:data', 'GEST': ':feature:home, :platform:system', 'NOTIF': ':platform:notifications',
 'WIDGET': ':platform:widgets, :feature:widgets', 'SET': ':feature:settings, :core:data', 'BACKUP': ':backup, :core:data',
 'ONBOARD': ':feature:onboarding, :platform:system', 'LIFE': ':core:data, :platform:apps, :app', 'A11Y': ':core:ui, :feature:home',
}
NFRMOD = {'PERF': ':benchmark, :core:data, :core:ui', 'A11Y': ':core:ui, :feature:*', 'PRIV': ':app (manifest), build-logic, :platform:notifications',
          'REL': ':core:data, :core:common, :app', 'COMPAT': ':app, build-logic', 'I18N': ':core:ui, :core:search, :platform:system', 'THEME': ':core:theme, :core:json, :core:data, :feature:theme'}
def component(r):
    k, a = area(r)
    mod = (NFRMOD[a] if k == 'NFR' or a == 'THEME' and False else None)
    if r.startswith('NFR'): mod = NFRMOD[a]
    elif a == 'THEME': mod = ':core:theme, :core:json, :core:data, :feature:theme'
    else: mod = AREAMOD[a]
    s = mod + ' (area)'
    if arch.get(r): s += '; cited ARCH ' + ', '.join('§' + x for x in arch[r])
    else: s += '; not cited in ARCH'
    return s
# ---------------- tests
cov = collections.defaultdict(list); tests = {}; test_refs = []
tsec = ''
for l in TP:
    m = re.match(r'## (\d+)\.', l)
    if m: tsec = m.group(1)
    if tsec == '22': continue
    if re.match(r'\| T-[A-Z0-9]+-\d+ ', l):
        c = cells(l); tid = c[0]; tests[tid] = c
        ids = expand(c[-1]) if c[-1] else []
        for rid in ids:
            cov[rid].append(tid)
        test_refs.append((tid, ids, c[-1]))
m22 = collections.OrderedDict()
in22 = False
for l in TP:
    if l.startswith('## 22'): in22 = True
    if in22 and l.startswith('| ') and not l.startswith('| Requirement') and not l.startswith('|---'):
        c = cells(l); rid = re.match(r'(\S+)', c[0]).group(1)
        # tests may be "T-BACKUP-04 … T-BACKUP-10"
        t = c[1]
        lst = []
        parts = re.split(r',\s*', t)
        for p in parts:
            mm = re.match(r'(T-[A-Z0-9]+)-(\d+)\s*…\s*(?:T-[A-Z0-9]+-)?(\d+)', p.strip())
            if mm:
                for k in range(int(mm.group(2)), int(mm.group(3))+1): lst.append(f'{mm.group(1)}-{k:02d}')
            else:
                lst += re.findall(r'T-[A-Z0-9]+-\d+', p)
        m22[rid] = (lst, c[0])
t22_all = set(x for v in m22.values() for x in v[0])
# unknown test ids in §22
bad_t22 = sorted(t22_all - set(tests))
# test refs to nonexistent reqs
nonex = []
for tid, ids, raw_c in test_refs:
    for rid in ids:
        if rid not in reqs: nonex.append((tid, rid, 'Covers column'))
for rid in m22:
    if rid not in reqs: nonex.append(('§22', rid, 'traceability row'))
# also truncated/“FR-THEME-*” style wildcard refs in Covers
wild = [(tid, raw_c) for tid, ids, raw_c in test_refs if re.search(r'(N?FR)-[A-Z0-9]+-\*|(N?FR)-[A-Z0-9]+(?!-\d)\b(?<!-)', raw_c) and re.search(r'-\*', raw_c)]
# ---------------- roadmap
ms = {}; cur = None; buf = {}
for i, l in enumerate(RM):
    m = re.match(r'### (M\d+|v1\.x)', l)
    if m: cur = m.group(1); buf[cur] = ''
    if cur and (l.startswith('**Requirements:**') or (buf.get(cur+'_on'))):
        if l.startswith('**Requirements:**'): buf[cur+'_on'] = True
        if l.startswith('**DoD') or l.strip() == '': buf[cur+'_on'] = False
        else: buf[cur] += ' ' + l
mstones = collections.defaultdict(list)
for k in [f'M{i}' for i in range(10)]:
    for rid in expand(buf.get(k, ''), bare=True):
        if k not in mstones[rid]: mstones[rid].append(k)
# v1.x table
for l in RM:
    if l.startswith('| ') and 'v1.x' not in l[:6]:
        pass
inv = False
for l in RM:
    if l.startswith('### v1.x'): inv = True
    elif l.startswith('## 3'): inv = False
    if inv and l.startswith('| ') and not l.startswith('| Item') and not l.startswith('|---'):
        c = cells(l)
        for rid in expand(c[1]):
            if 'v1.x' not in mstones[rid]: mstones[rid].append('v1.x')
rm_unknown = sorted(r for r in mstones if r not in reqs)
# ---------------- output
def t_cell(r):
    a = m22.get(r, ([], ''))[0]; b = cov.get(r, [])
    allt = list(dict.fromkeys(a + [x for x in b if x not in a]))
    outs = []
    for t in allt:
        mark = ''
        if t in a and t not in b: mark = '~'   # §22 only
        if t in b and t not in a: mark = '^'   # Covers only
        outs.append(t + mark)
    return outs
rows = {}
for r, d in reqs.items():
    d['tests'] = t_cell(r)
    d['ms'] = mstones.get(r, [])
ORDER = ['HOME','LIST','SEARCH','APPS','FAV','GEST','NOTIF','WIDGET','SET','BACKUP','ONBOARD','LIFE','A11Y']
groups = collections.OrderedDict()
for a in ORDER: groups[f'FR-{a} (PRODUCT_SPEC §4)'] = [r for r in reqs if r.startswith(f'FR-{a}-')]
groups['FR-THEME (THEME_SYSTEM §15.1)'] = [r for r in reqs if r.startswith('FR-THEME-')]
for a in ['PERF','A11Y','PRIV','REL','COMPAT','I18N']: groups[f'NFR-{a} (PRODUCT_SPEC §5)'] = [r for r in reqs if r.startswith(f'NFR-{a}-')]
groups['NFR-THEME (THEME_SYSTEM §15.2)'] = [r for r in reqs if r.startswith('NFR-THEME-')]
assert sum(len(v) for v in groups.values()) == len(reqs)
def esc(s): return s.replace('|', '\\|')
def accell(d):
    a = d['acc']
    if re.search(r'defined when scheduled|^\s*$|^—$', a): return d['accloc'] + ' — NONE (placeholder: “%s”)' % a.strip('() ')
    return d['accloc']
def mscell(d):
    m = d['ms']
    rel = d['rel']
    s = ', '.join(m) if m else 'NONE'
    if rel != '—': s += f' [spec Rel: {rel}]'
    return s
def evcell(d):
    e = d['ev'] + d['oth']
    return (', '.join(e) + d['evnote']) if e else '—'
out = []
W = out.append
W('# Strata Launcher — Requirements Traceability Matrix')
W('')
W('Task T-06b · Owner: agent:arch · 2026-10-29 · Status: review · Generated mechanically from the spec files (script kept outside the repo).')
W('')
W('Sources: `spec/PRODUCT_SPEC.md` §4–§5, `spec/THEME_SYSTEM.md` §15, `spec/ARCHITECTURE.md`, `spec/TEST_PLAN.md` (§22 plus the *Covers* column of every test table), `spec/IMPLEMENTATION_ROADMAP.md`, and the evidence registries `research/EVIDENCE.md`, `research/APK_ANALYSIS.md`, `research/PUBLIC_BEHAVIOR.md` §Evidence, `spec/ANDROID_PLATFORM.md` §References.')
W('')
W('## How to read this matrix')
W('- **Pri** is copied as written. THEME_SYSTEM §15 sets priority at section level (all THEME requirements are **P0 for v1**), so THEME rows show `—`, meaning P0. PRODUCT_SPEC §5 NFR rows carry a priority but no per-row evidence or label; the §5 intro says all targets are Proposed.')
W('- **Summary** is a paraphrase (≤ 12 words). **Label** and **Evidence** are as written in the source row (evidence IDs in a range such as `DOC-019…022` are expanded in the check but shown as written after expansion). Non-evidence references in the evidence cell (`D-*`, `U-*`, `P-*` principles) are kept in the Evidence column.')
W('- **Component**: ARCHITECTURE.md has no requirement→module table. Modules are therefore derived from the area (marked `(area)`) using the ARCHITECTURE §2 module descriptions; `cited ARCH §n` lists the architecture sections that mention the ID explicitly (ranges such as `FR-SEARCH-02…07` expanded).')
W('- **Acceptance**: file and section holding the acceptance criteria for the row.')
W('- **Tests**: union of TEST_PLAN §22 and the *Covers* column of the test tables. `~` = listed in §22 only (the test’s Covers column does not name the requirement). `^` = named in a Covers column but missing from §22. No suffix = both agree.')
W('- **Milestone**: from the roadmap “Requirements:” lines (and the v1.x table). `NONE` = no roadmap milestone lists the ID. `[spec Rel: …]` is the release column of PRODUCT_SPEC and is shown for comparison only.')
W('- The `FR-THEME-*` pointer row in PRODUCT_SPEC §4.9 is not a requirement and is excluded.')
W('')
for g, ids in groups.items():
    W(f'## {g}')
    W('')
    W('| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |')
    W('|---|---|---|---|---|---|---|---|---|')
    for r in ids:
        d = reqs[r]
        W('| ' + ' | '.join(esc(x) for x in [r, d['pri'], d['summary'], d['label'], evcell(d), component(r), accell(d), ', '.join(d['tests']) or 'NONE', mscell(d)]) + ' |')
    W('')
# ---------- validation
nFR = sum(1 for r in reqs if r.startswith('FR')); nNFR = len(reqs) - nFR
prod = [r for r, d in reqs.items() if d['src'] == 'PRODUCT_SPEC']
th = [r for r, d in reqs.items() if d['src'] == 'THEME_SYSTEM']
W('## Validation report')
W('')
W('### Counts')
W(f'- Requirements: **{len(reqs)}** ({nFR} FR, {nNFR} NFR). PRODUCT_SPEC: {len(prod)} ({sum(1 for r in prod if r.startswith("FR"))} FR, {sum(1 for r in prod if r.startswith("NFR"))} NFR). THEME_SYSTEM: {len(th)} ({sum(1 for r in th if r.startswith("FR"))} FR, {sum(1 for r in th if r.startswith("NFR"))} NFR).')
pc = collections.Counter(d['pri'] for d in reqs.values())
W('- Priority as written: ' + ', '.join(f'{k}: {v}' for k, v in sorted(pc.items())) + '.')
W(f'- Requirements with at least one test: {sum(1 for d in reqs.values() if d["tests"])}; without: {sum(1 for d in reqs.values() if not d["tests"])}.')
W(f'- Requirements with a roadmap milestone: {sum(1 for d in reqs.values() if d["ms"])}; without: {sum(1 for d in reqs.values() if not d["ms"])}.')
W(f'- Distinct evidence IDs cited (after range expansion): {len(set(e for d in reqs.values() for e in d["ev"]))}; test cases found: {len(tests)}; §22 rows: {len(m22)}.')
W('')
def haspri(d, ps): return any(re.search(r'\b%s\b' % p, d['pri']) for p in ps)
W('### P0/P1 requirements lacking tests')
lack = [r for r, d in reqs.items() if d['pri'] != '—' and haspri(d, ['P0','P1']) and not d['tests']]
W('- ' + (', '.join(lack) if lack else 'None.'))
lack_th = [r for r, d in reqs.items() if d['pri'] == '—' and not d['tests']]
W(f'- THEME_SYSTEM requirements (priority not stated) without tests: ' + (', '.join(lack_th) if lack_th else 'None.'))
# P0/P1 with only §22-only or covers-only mismatch
mm = [r for r, d in reqs.items() if d['tests'] and all(t.endswith('~') for t in d['tests'])]
W('- Requirements whose tests come **only** from §22 (no test’s Covers column names the requirement): ' + (', '.join(mm) if mm else 'None.'))
mm2 = [r for r, d in reqs.items() if d['tests'] and all(t.endswith('^') for t in d['tests'])]
W('- Requirements whose tests come **only** from Covers columns (absent from §22): ' + (', '.join(mm2) if mm2 else 'None.'))
mixed = sorted(set((r, t) for r, d in reqs.items() for t in d['tests'] if t[-1] in '~^'))
W('- §22-only pairs (~): ' + (', '.join(f'{r}/{t[:-1]}' for r,t in mixed if t[-1]=='~') or 'none') + '. Covers-only pairs (^): ' + (', '.join(f'{r}/{t[:-1]}' for r,t in mixed if t[-1]=='^') or 'none') + '.')
W(f'- §22/Covers disagreements in total: {sum(1 for r,t in mixed if t[-1]=="~")} §22-only pairs, {sum(1 for r,t in mixed if t[-1]=="^")} Covers-only pairs (suffixes in the matrix).')
W('- P2 / unscheduled rows without tests (expected per TEST_PLAN §22): ' + ', '.join(r for r, d in reqs.items() if d['pri'].strip().startswith('P2') and not d['tests']) + '.')
sec22 = set(re.findall(r'(N?FR-[A-Z0-9]+-\d+)', ' '.join(TP[TP.index('Not covered (P2, scheduled v1.x/later): FR-HOME-06, FR-HOME-07, FR-SEARCH-08, FR-APPS-12, FR-FAV-04,'):]))) if any(l.startswith('Not covered') for l in TP) else set()
W('')
W('### Requirements lacking acceptance criteria')
na = [r for r, d in reqs.items() if re.search(r'defined when scheduled|^\s*$|^—$', d['acc'])]
W('- ' + (', '.join(f'{r} (“{reqs[r]["acc"]}”)' for r in na) if na else 'None.'))
W('')
W('### Dangling evidence IDs')
if dang:
    for e, lst in sorted(dang.items()):
        W(f'- {e}: cited by ' + ', '.join(r for r, _ in lst) + ' — ' + '; '.join(sorted(set(m for _, ms_ in lst for m in ms_))))
else:
    W('- None: every APK/DOC/PUB/SEC/OBS ID cited resolves to a definition row (APK-019…034 covered by the EVIDENCE.md range and APK_ANALYSIS §5 rows).')
if dang_other: W('- Dangling D-/U- references: ' + ', '.join(f'{r}→{o}' for r, o in dang_other))
else: W('- D-*/U-* references in evidence cells all resolve in DECISIONS.md / UNRESOLVED.md.')
apk_gap = [f'APK-{k:03d}' for k in range(19, 35) if 'APK_ANALYSIS' not in EVR.get(f'APK-{k:03d}', set())]
W('- APK-019…034 rows in APK_ANALYSIS: ' + ('all present.' if not apk_gap else 'missing rows for ' + ', '.join(apk_gap) + ' (covered only by the EVIDENCE.md range).'))
W('')
W('### Test IDs referencing non-existent requirements')
W('- ' + ('; '.join(f'{t} → {r} ({w})' for t, r, w in nonex) if nonex else 'None (all IDs in Covers columns and §22 rows exist in PRODUCT_SPEC/THEME_SYSTEM).'))
W('- §22 test IDs that are not defined in any test table: ' + (', '.join(bad_t22) if bad_t22 else 'None.'))
W('- Tests with an empty Covers cell: ' + (', '.join(t for t, c in tests.items() if not c[-1].strip()) or 'None.'))
W('- Test cases referenced by no requirement (neither §22 nor any Covers match a requirement): ' + (', '.join(sorted(t for t in tests if t not in t22_all and not any(t in v for v in cov.values()))) or 'None.'))
W('')
W('### Requirements missing a milestone')
nm = [r for r, d in reqs.items() if not d['ms']]
W('- ' + (', '.join(nm) if nm else 'None.'))
later = [r for r in nm if reqs[r]['rel'] in ('later',)]
gen = [r for r in nm if r not in later]
W('- Of these, ' + ', '.join(later) + ' are P2 with release “later” (deliberately unscheduled). Genuine gap: ' + ((', '.join(gen) + ' (named by no roadmap Requirements line' + ('; its test T-THEME-52 does appear in the M9 DoD' if 'NFR-THEME-12' in nm and 'T-THEME-52' in '\n'.join(RM) else '') + ').') if gen else 'none.'))
W('- Roadmap requirement IDs that do not exist in the specs: ' + (', '.join(rm_unknown) if rm_unknown else 'None.'))
W('')
W('### Other observations')
arch_unknown = sorted(x for x in arch_all if x not in reqs)
W('- IDs cited in ARCHITECTURE.md that do not exist as requirements: ' + (', '.join(arch_unknown) if arch_unknown else 'None.'))
nocite = [r for r in reqs if not arch.get(r)]
W(f'- Requirements never cited in ARCHITECTURE.md (component is area-derived only): {len(nocite)} of {len(reqs)}.')
W('- THEME_SYSTEM §15 has no priority and no release column: all ' + str(len(th)) + ' THEME rows show `—` for Pri. PRODUCT_SPEC §4.9 declares the theme area P0 / v1 as a block; this is not repeated per row.')
W('- PRODUCT_SPEC §5 NFR rows have no per-row evidence or label; they inherit “Proposed” from the §5 intro. Several NFRs cite platform constraints informally (for example DOC-052, SEC-006).')
open(R+'spec/REQUIREMENTS_TRACEABILITY.md', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(len(reqs), nFR, nNFR, len(lack), len(nm), len(dang), len(nonex), len(na), rm_unknown, arch_unknown, wild)
