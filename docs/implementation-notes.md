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


## 0.2.0 决策

- 按用户明确的新要求调整原生 UI、移除独立行情，并按其“支付宝”答复实施本机 OCR；没有新增服务器或云识别流程。
- 选择内置 Tesseract 中文模型，避免依赖 Google 服务或运行统计联网；代价是 APK 增大，提供 4.8MiB ARM64 包及约14MiB通用包。
- 没有实际截图时只实现保守的标签解析和人工核对，不承诺覆盖全部支付宝排版；不确定份额、成本或日期必须补齐。
- 审阅中的分组恢复由 Minor 提升为实际导入目标行为问题，一并修复；文档同步完成。没有留下其他审阅报告项。
- 桌面生成样例和核心测试不能代替安卓真机 OCR、视觉和生命周期验证；此限制在交付记录中保留。
