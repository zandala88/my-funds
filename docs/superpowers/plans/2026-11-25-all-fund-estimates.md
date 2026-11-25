# All Fund Estimates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement dated, explainable forecasts for all public-fund categories with honest missing-data states.
**Architecture:** Core models/parsers/engine; injected Android transport with bounded parallel metadata fetch and batched security quotes; persisted provenance and UI integration.
**Tech Stack:** Kotlin, Android Views/SQLite, JUnit/Robolectric; no new dependencies.
**Spec:** docs/superpowers/specs/2026-11-25-all-fund-estimates-design.md

## Global Constraints

Keep Android 8.0+, current refresh preference, formal NAV separate from model prices, and existing portfolio data intact. Do not normalize disclosed holdings to 100%. Preserve timezone-specific valuation dates and basis dates. No paid/login integration.

## Review Focus

- Currency/date mismatch cannot create today's QDII profit.
- Money-fund per-10k income must never become market price.
- Missing child/stock returns cannot be silently zero.
- Partial model results cannot enter complete overview totals.
- Cancellation/cache failures cannot discard official NAV updates.

### Task 1: Core estimates and persistence

Files: new core FundEstimation.kt, EstimationParser.kt and tests; MarketModels.kt, MarketMath.kt, MarketCache.kt.
Interfaces: FundProfile, SecurityQuote, FundEstimate; EstimationEngine.estimate(profile, quotes, fundQuotes, profiles, day); EstimationParser profile/positions/allocation/securities; FundQuote forecast and missing reason.

- [x] Write failing tests for every category and all Review Focus cases; run :core:test and observe missing functionality.
- [x] Implement bounded parsing, type classification, compositional daily-return math, date/currency validation, cycles/partial coverage and money income separation.
- [x] Verify core tests, including backward-compatible quote cache round-trip and model priority.

### Task 2: Live acquisition and Android presentation

Files: new Android EstimationClient.kt; MarketClient.kt, MainActivity.kt, DailyProfitPresentation.kt; new acquisition tests and UI regressions; README.md.
Interfaces: injectable text transport and cache; enrich formal quotes without replacing formal NAV; append source/date/coverage/missing reason to presentation.

- [x] Write failing acquisition tests with realistic payloads for batching, cache expiry, partial failures and cancellation; UI tests for models, prediction labels and incomplete results.
- [x] Implement daily metadata cache, bounded concurrent profiles, batch quotes, FOF dependency discovery and fallback sources; integrate two-phase refresh.
- [x] Implement model provenance and summary exclusions, update documentation and version.
- [x] Verify :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease and live representative source smoke checks.

### Task 3: Review and delivery

- [x] Independent review against design; reproduce important findings with failing tests and fix in one pass.
- [ ] Repeat relevant full validation, commit and push to origin/main as already authorized.
- [ ] Verify release signing against the attached device, adb install -r and launch; report actual coverage and limitations.

## Final verification

- `scripts/build-local.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` passed: 116 core tests + 73 Android unit tests, zero failures/errors/skips.
- Real public-source smoke checks exercised 11 representative funds across the implemented categories and confirmed model survival through the actual quote cache codec. Several funds correctly returned partial coverage instead of full-fund claims.
- All independent-review findings were reproduced in regressions and corrected. Added defenses for primary-NAV outages, ETF/REIT no-NAV cache state, stale/foreign provider intervals, and currency labels surviving metadata failures.
- Release APK version is 0.8.0 / versionCode 8. ADB installation awaits reconnection of the user's device; the device list was empty at delivery preparation.
