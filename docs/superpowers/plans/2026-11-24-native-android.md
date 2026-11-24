# My Funds Native Android Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the requested checkout. Steps use checkbox syntax for tracking.

**Goal:** Deliver a lightweight native Android fund tracker with local holdings, live data requests and portable file backups.

**Architecture:** A pure Kotlin core owns financial arithmetic, validation, backup serialization and defensive response parsing. Android platform Views, SQLite and system document intents provide the app shell; background executors fetch HTTPS data and post results only while the activity is alive.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, compile/target API 35, minimum API 26, platform Views/Canvas/SQLite, JUnit 4.13.2. org.json is provided by Android and included only in JVM tests.

**Spec:** docs/superpowers/specs/2026-11-24-funds-native-android-design.md

## Global Constraints

- Work under /Users/admin/project/my-funds on codex/native-android; do not push without an explicit request.
- Native Android only; no server, account, paid data, analytics, cloud backups, or background polling.
- Holdings, trades, groups and settings persist locally; external requests contain only market query parameters.
- Backups use UTF-8 JSON with format and schemaVersion; validate before atomic replacement and preserve an undo snapshot.
- Decimal arithmetic uses BigDecimal; fund codes remain six-character strings.
- Missing/stale valuation must be distinguishable from genuine zero change.
- The 10MB release APK limit is an optimization target to measure, not an assumed result.

## Review Focus

- Corrupt, oversize or future-version backup must leave all existing data intact.
- Partial provider responses and HTTP-200 business errors must preserve cached valid data.
- Negative quantities, excessive sell orders and fees must never corrupt the portfolio.
- Lifecycle transitions must stop polling and prevent asynchronous results from touching a destroyed activity.
- Import/export must retain leading zero codes, decimal precision, group/order/settings and ledger identity.

---

### Task 1: Financial domain and portable backups

**Files:** core/build.gradle.kts; core/src/main/kotlin/com/myfunds/core/{Portfolio,BackupCodec}.kt; core/src/test/kotlin/com/myfunds/core/PortfolioTest.kt; root Gradle wrapper and configuration.

**Interfaces:** Produces Portfolio, Holding, Group, Trade, AppSettings; PortfolioOperations.buy/sell/calibrate; PortfolioMath.summary; BackupCodec.encode/decode(String): Portfolio.

- [x] Add build configuration and wrapper; put portable build tools in a user cache outside the repository.
- [x] Write tests for exact buy/sell fee arithmetic, oversell/negative rejection, full backup roundtrip, leading-zero codes, invalid references/duplicates/future versions and bad decimals. Run ./gradlew :core:test; expect missing production symbols.
- [x] Implement domain validation, decimal math and schema-versioned backup codec. Use nullable unknown values rather than synthetic zeroes.
- [x] Run ./gradlew :core:test; expect all tests pass. Commit source and evidence.

### Task 2: Market parsing and direct HTTPS data

**Files:** core/src/main/kotlin/com/myfunds/core/MarketModels.kt; core/src/test/kotlin/com/myfunds/core/MarketParserTest.kt; app/src/main/java/com/myfunds/android/MarketClient.kt.

**Interfaces:** Consumes Holding.code; produces FundQuote/SearchResult/ChartPoint/IndexQuote, MarketParser and Android MarketClient. FundQuote carries published date, nullable estimated values and estimate time separately.

- [x] Write fixtures testing missing estimate fields, null Datas, provider business errors, malformed/null fields, historical series and six-digit search codes. Observe :core:test fail.
- [x] Implement defensive parsers and HTTPS client with timeout, bounded response size, encoded params and no personal portfolio payload. Include on-demand overview/manager/disclosed holdings/market data endpoints from the reference repo.
- [x] Run :core:test; expect pass. Probe representative live endpoints once and record successes and unavailable datasets honestly. Commit.

### Task 3: Native local application and migration files

**Files:** app/build.gradle.kts; app/src/main/AndroidManifest.xml; app/src/main/java/com/myfunds/android/{LocalStore,MainActivity,LineChartView,Ui}.kt; app/src/main/res/values and drawable resources.

**Interfaces:** Consumes core portfolio models/codecs and MarketClient. LocalStore.load/save/replaceWithBackup/restorePrevious preserves one complete portfolio transactionally; cached quotes are stored separately. MainActivity owns document intents and the visible refresh lifecycle.

- [x] Write Android instrumentation tests for persistent restart, transactional invalid-import preservation, repeat import, cached-quote reload and restore-previous; these must not require live network. First run compilation expecting missing LocalStore.
- [x] Implement SQLite storage with transactions and persisted import restore point; all fallible import validation precedes mutation.
- [x] Implement native Chinese UI: portfolio and groups/sorting, search/add/edit/buy/sell/calibrate, overview and records, detail chart/metadata/manager/disclosed holdings, indices and market datasets, settings, cache cleanup and document export/import/undo. No production sample holdings.
- [x] Enforce lifecycle-aware optional 60-second foreground refresh; pause/cancel on stop; retain cache on failures; display precise data dates and estimate markers. Disable OS backup/transfer extraction and request INTERNET only.
- [x] Run :core:test :app:assembleDebug :app:lintDebug and instrumentation if an emulator is available. Confirm document/file operations show success only after streams close. Commit.

### Task 4: Release, review and reproducible delivery

**Files:** README.md; scripts/build-local.sh; docs/verification.md; local-only signing key and release artifacts excluded from Git.

**Interfaces:** Produces reproducible developer instructions and a signed APK plus SHA-256 and measured bytes. Signing material remains outside source control.

- [x] Build signed optimized release with a persistent local key; verify signature and manifest permissions with SDK tools.
- [x] Run :core:test :app:lintDebug :app:assembleRelease; run available emulator/instrumentation checks and clearly record any unrun hardware checks.
- [x] Review whole branch using requesting-code-review; fix important findings with regression tests, then rerun required checks.
- [x] Document installation, backup/import recovery, data availability, offline behavior, APK size and signing-key retention. Commit completed work; keep current branch and do not push automatically.
