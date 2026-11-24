# 实现决策与审阅记录

以下是实施时作出的决定与验证边界。

```text
Ruling: user instruction to develop here accepts the concrete design and authorizes inline work — avoids another approval loop — user can still steer scope.
Ruling: use the existing checkout with codex/native-android rather than another worktree — user explicitly requested development in this directory — no isolation from unrelated changes in the same checkout.
Ruling: bootstrap RED/GREEN using Gradle's bundled Kotlin 1.9.23 compiler while project Kotlin 2.1.0 downloads — same source also passed the configured Gradle build — no production language-version deviation.
Ruling: use a bounded InputStream reader rather than readNBytes — Android 8 compatibility — no loss of functionality.
Final: Ruling: no attached Android device/emulator — compiled five instrumentation checks and retained explicit runtime-verification limitation — SQLite/device UI behavior still needs on-phone confirmation.
Final: Ruling: unavailable intraday valuation remains unknown rather than buying data or fabricating a value — honors no-paid-data scope — some real-time valuation views can remain empty.
Final: minor (deferred): none; reviewer minor findings were regraded by functional impact and addressed in the same fix pass.
```

独立审阅指出的大文件读取、期初份额、倒序操作、导入旋转竞态、残缺缓存、异常备份字段、乱序响应和资料缓存问题，均在同一次修复中处理。核心回归已通过；涉及手机平台的路径仍保留 5 项 instrumentation 测试，待实际设备运行。
