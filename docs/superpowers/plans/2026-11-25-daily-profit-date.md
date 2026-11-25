# Daily Profit Date Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop displaying historical profit as today's profit.
**Architecture:** Reuse MarketMath.daily, attach quote date and time in the Android presentation layer, and aggregate only today's results.
**Tech Stack:** Kotlin, Android Views, JUnit, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-25-daily-profit-date-design.md

## Global Constraints

- Keep existing refresh frequency and holding-value calculations.
- No additional dependencies; retain Android 8.0 support.

## Review Focus

- Zero-change estimates must remain today's estimates.
- Mixed dates must not be summed into today's profit.
- Weekends/older NAVs must show explicit dates.
- Missing quotes must not imply zero profit for a nonzero position.
- Today's published NAV must supersede estimates.

### Task 1: Correct profit presentation and aggregation

**Files:** MainActivity.kt, new DailyProfitPresentation.kt, new DailyProfitUiTest.kt, README.md.
**Interfaces:** Consume MarketMath.daily(Holding, FundQuote?, String); produce dated display data shared by the overview, list and ledger.

- [x] Add real UI regression cases for stale NAV, zero-change estimate, mixed dates, partial/missing data and published NAV priority.
- [x] Run `scripts/build-local.sh :app:testDebugUnitTest --tests '*DailyProfitUiTest'`; confirm expected failures against current UI.
- [x] Implement dated presentation, time labels and today's-only aggregation; document refresh and profit rules.
- [x] Run `scripts/build-local.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; expect success.
- [ ] Review the complete diff, commit the fix and push the branch to origin as authorized.

## Verification record

- Original behavior: 16/16 date regression checks failed as expected on Android 9/15.
- Independent review identified missing quote provenance when daily change is unavailable; reproduced with two failing checks and corrected.
- Final validation: 66 core tests + 46 Android unit tests passed, zero failures/errors/skips; lintDebug and assembleDebug passed.
- Width checks include neighboring amount and holding-profit text. No provider/network behavior or physical device rendering was changed or verified.
