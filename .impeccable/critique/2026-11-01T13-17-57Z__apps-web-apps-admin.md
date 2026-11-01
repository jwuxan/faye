---
target: Admin UI spacing
total_score: 28
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
target_identity: "file:/Users/jwu/workspace/coupang/faye/apps/web/apps/admin"
timestamp: 2026-11-01T13-17-57Z
slug: apps-web-apps-admin
---
# Spacing critique: Faye Admin UI (apps/web/apps/admin)
Method: dual-agent (A: 6fde4706, B: 6afb5277)

## Scores
1 Status 3 | 2 Real world 3 | 3 Control 3 | 4 Consistency 2 | 5 Error prevention 3 | 6 Recognition 2 | 7 Efficiency 4 | 8 Aesthetic 2 | 9 Recovery 3 | 10 Help 3 | Total 28/40 Good

## Specificity
Flow workspace is product-specific; shell, directory, settings and picker are stock shadcn. Detector: CLI 0 findings (regex-only); URL scan overused-font x4 (false positive), nested-cards x3+1 advisory (Choose panel real). Spacing on 2px half-steps (6px, 10px, 42px ml-10.5), token test enforces values not roles.

## Priority issues
- [P1] Flow workspace left edges misalign: PageHeader px-3 (shell.tsx:327), tab strip px-2 (workspace.tsx:169), TabToolbar px-4, inspector header pr-1.5 pl-3 (workspace.tsx:305), inspector sections px-3 vs PanelSection px-4. Fix: px-4 everywhere; tab strip pl-3.5. /impeccable layout
- [P1] Three page layouts: workspace full-bleed, directory px-6 py-6 gap-5 (directory.tsx:71), settings max-w-4xl (settings.tsx:62); duplicate title (breadcrumb + h2); row heights h-12 vs h-10. /impeccable layout
- [P1] At 1280 editor pane ~412px < @md, icon-only tabs (workspace.tsx:407); w-88 floating inspector covers graph nodes. Fix: @sm breakpoint or minSize; dock inspector under ~640px canvas. /impeccable adapt
- [P2] Pane header heights: h-11 PageHeader vs h-10 bars; Versions list header bare p no rule (versions.tsx:139); detail header ~52px (versions.tsx:197); Preview drops TabToolbar (workspace.tsx:191). /impeccable layout
- [P2] Label patterns and control heights per page: h-7/88px label, h-8/16rem column, h-9 stacked gap-7; General has 3 row styles; switch rows break rhythm. /impeccable polish

## Persona red flags
Alex: icon-only tabs, inspector covers graph, 57 ungrouped path cards, truncated app names at 1024.
Jordan: two 40px bars before content, Preview button below fold, "Sign in" name clash.
Engineer: /docs drops tenant, inline --sidebar-width 15rem (shell.tsx:118), no override cue.

## Minor
Picker single gap-6 (page.tsx:16); time-limit fieldset box-in-box 11px offset (settings.tsx:565); description textarea unbounded; control widths arbitrary; Choose panel nested card ml-10.5; Paths bordered cards -> divide-y; graph pill h-8 vs inspector header h-10; Customization Preview/Brand baseline mismatch; docs nav cramped.

## Questions
Directory pages as list/detail panes? Should the inspector ever hide the graph? Enforce --pane-inset and --bar-h roles in the token test?
