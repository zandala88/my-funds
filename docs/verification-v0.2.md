# 0.2.0 验证记录

日期：2026-11-24。用户要求：更柔和的 UI、删除独立行情模块、支付宝截图 OCR。

## 实际执行

```sh
scripts/build-local.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease
```

- 核心测试 41 项，失败 0、错误 0。
- Android 生产代码及 7 项 instrumentation 检查已编译；Lint 错误 0。
- 正式 APK 签名验证通过，证书与 0.1.0 一致；包名不变，versionCode=2、versionName=0.2.0，最低 Android 8.0。
- 从 APK 清单核验只申请 INTERNET 权限；OCR 页面不导出。
- ARM64 原生库全部 ELF LOAD 段按 16KiB 对齐；JNI 类名在 R8 后保留。
- 内置模型 SHA-256 核验通过：a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730。

## 安装包

- `my-funds-v0.2.0-arm64.apk`：5030493 字节，4.80 MiB。
  SHA-256：`798c82977c0f3dd3a5aa12d7c51180f0accd4304a90c62aca4725411027e6a23`。
- `my-funds-v0.2.0-universal.apk`：14661131 字节，13.98 MiB。
  SHA-256：`ad80a7b733bed2eacde0d65edaf81bf040d8dd364c7115247bef694fb410d3d3`。

ARM64 适合支持 arm64-v8a 的手机；其他架构使用通用包或按 ABI 构建。两者均可离线识别，不依赖 Google 服务。

## OCR 引擎样例与审阅回归

使用人工生成的中文图片（非真实账户）和内置 chi_sim 模型，在桌面 Tesseract 5.5.4 上完成实际识别；安卓内置引擎是 Tesseract 5.5.1，未在设备运行。该样例揭示了千位逗号被识别为小数点的情况，已将实际输出纳入回归：歧义数值必须留空，不能截断成看似有效的份额。

测试覆盖列表金额不当份额、总额及六位整数不当基金代码、可用份额不覆盖总份额、相邻标签不能借用数字、A/C 与截断名称、明确日期保留及未知日期待确认、草稿恢复、重复/已有基金不累计、非法字段不提交。

独立代码审阅指出的解析、日期、恢复点与草稿生命周期问题已修复。重复无变化导入在主界面和数据库层均跳过写入，保留已有恢复点；导入目标分组随状态恢复。

## 验证边界

当前没有连接的安卓设备或模拟器，也未获得用户真实支付宝截图。7 项 instrumentation 包含 SQLite、恢复点、大文件及实际 JNI 识别样例，已编译但尚未运行。手机视觉、系统图片选择器、旋转/系统回收、低内存设备取消延迟、真实支付宝排版与识别率仍需真机验证。

OCR 过程中只保留有界文字草稿，图片不进入数据库或文件备份；图片本身和运行中的识别任务不在 Activity 重建时恢复。当前支持单张截图最多 50 个核对条目。

## 源码与许可证

- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0。
- [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) 4.1.0 简体中文模型。
- 相关开源许可与通知随 APK 打包，可在设置查看。
