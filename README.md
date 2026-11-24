# My Funds · 基金自选

个人使用的轻量原生安卓基金工具。持仓、分组、操作记录和设置保存在手机；联网只查询行情及基金资料，换机使用文件备份。

## 已确定的方向

- Kotlin + Android 原生 View/Canvas + SQLite，Android 8.0 及以上。
- 不需要账号、服务器、域名、付费数据源或推送服务。
- 导出单个 JSON 文件，在新设备导入恢复；导入前校验，保留本机恢复点。
- 基金搜索、自选、持仓收益、交易记录、分组、走势图和基金资料。
- 盘中估值取决于数据源是否返回有效数据；缺失时明确标记，不能当成零涨跌。

设计与实施计划见 [设计](docs/superpowers/specs/2026-11-24-funds-native-android-design.md) 和 [计划](docs/superpowers/plans/2026-11-24-native-android.md)。

开发中。安装包、构建步骤和验证结果将在实际完成后补充。
