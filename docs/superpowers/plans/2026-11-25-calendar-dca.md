# Income Calendar and DCA Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an honest income calendar and reliable weekly/monthly local DCA scheduling.
**Architecture:** Core time series/calendar/schedule engines and schema-2 persistence; injected public data acquisition; dedicated Android views and latest-state merging.
**Tech Stack:** Kotlin, Android native Views/SQLite, JUnit/Robolectric; existing dependencies.
**Spec:** docs/superpowers/specs/2026-11-25-calendar-dca-design.md

## Global Constraints

Android 8+, local-only bookkeeping, no real fund orders. Confirmed prices only for new DCA entries. Never invent missing historic shares or sum currencies. Preserve schema-1 imports and existing records. No unknown-year trading-day guesses.

## Review Focus

- Late NAVs cannot insert trades behind later user operations or overwrite edits.
- New purchases/DCA do not earn an entire prior day rise.
- A shifted or retried schedule occurrence is posted at most once.
- Partial/estimated days never masquerade as full confirmed monthly totals.
- Failed/malformed calendar or historical API responses cannot erase financial history.

### Task 1: Core calendar, schedule and persistence

Files: new TradingCalendar.kt, IncomeCalendar.kt, DcaSchedule.kt and tests; Portfolio.kt, BackupCodec.kt, MarketMath.kt.
Interfaces: DailyNav/NavHistory; IncomeEntry/IncomeDaySummary; DcaSchedule/Execution; DcaEngine.advance(portfolio, day, prices, calendar); TradingCalendar status/shift; backward-compatible Portfolio fields.

- [x] Write and run failing tests covering spec and Review Focus, then implement strict parsing and core math/state transitions.
- [x] Add schema-1→2 migration and round-trip/bounds tests; retain old trades and snapshots.
- [x] Run full core tests.

### Task 2: Acquisition and native UI

Files: new CalendarDataClient.kt, IncomeCalendarViews.kt, DcaScheduleViews.kt; MarketClient.kt, MainActivity.kt, DcaPlanViews.kt and Android tests.

- [x] Write failing transport tests for historical range/pagination, unit interpretation, calendar failures/cache and stale callbacks; implement acquisition using existing transport.
- [x] Write UI tests for date/currency selection, confirmed-vs-estimate summaries and weekly/monthly/pause/history behavior.
- [x] Integrate current/historical snapshots and pending execution retry; preserve latest user edits and accurate refresh states.
- [x] Update version/README and verify :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease.

### Task 3: Review and delivery

- [x] Independent review; reproduce important findings RED→GREEN and finish full verification.
- [ ] Commit/push to origin/main; build signed distribution artifacts.
- [x] ADB same-signature install -r and launch if device connected; report any missing physical connection.

## Verification evidence

2026-11-25: `scripts/build-local.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` passed: 152 core + 84 Android tests, zero failures. Independent review and follow-up found no remaining blockers after regression fixes for precise NAV differences, legacy cursors, verified closure retries and baseline/ledger invalidation. Signed 0.9.0 delivery follows.

ADB install `-r` succeeded on OnePlus 13; package reports versionCode 9 / versionName 0.9.0 and MainActivity cold launch returned Status ok. Certificate SHA-256 matches the prior release: `a97685059168df60836b332357f6fa425daca102ec2b869419294defa0d0ea56`.
