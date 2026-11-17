# Agenda Android

原生 Kotlin / Compose 工程，Android 12（API 31）起。0.5.0 已作为用户认可的视觉与交互基线保存；0.6.0 接入 Room 和 DataStore，0.6.1 补齐独立周计划添加、完成与切周查看。首次启动为空，对象、任务、日程、说明、材料元数据及周计划持久保存，材料使用应用私有副本。

## 构建

本机 SDK：`D:/allAboutAppCache/Android/sdk`。`local.properties` 属于机器配置，不提交。换机后通过 Android Studio 选择 SDK 或创建此文件。

在本目录运行：

```powershell
.\build.ps1
```

脚本使用 Studio 自带 JDK，运行规则测试、调试 APK 构建和 Android Lint。其他机器用 `-JdkPath` 指向兼容 JDK。本机项目父目录含单引号，Java 的参数文件会错误解析 Gradle worker 类路径；脚本选取空闲 Z/Y/X/W 盘符临时映射本目录，构建完成后释放，不移动文件、不更改全局环境变量。普通无特殊字符目录可直接使用 Wrapper。

APK：`app/build/outputs/apk/debug/app-debug.apk`。统一交付副本位于项目根目录 `artifacts/`。0.6.0 包名 `com.agenda.local`，应用名「Agenda」；历史 `com.agenda.motiondemo`「Agenda · 动效样机」与新应用分开安装，保留交互对照。当前均为调试签名，不是正式发行包。

锁定版本：Gradle 8.13、AGP 8.13.2、Kotlin / Compose Compiler 2.2.21、Compose BOM 2025.12.00、Activity Compose 1.12.1、Coroutines 1.10.2；compile / target SDK 36。0.6.0 增加 Room 2.8.4、DataStore 1.1.7、KSP 2.2.21-2.0.4、Lifecycle 2.9.4，保留已通过的 UI 依赖组合。首版数据库 schema 位于 `data/schemas/`，版本变更须显式迁移，不启用破坏性重建兜底。

本机首次下载 KSP 编译器遇到 Java TLS 失败。`tools/fetch_ksp_compiler.py` 和 `tools/fetch_build_artifacts.py` 可按需从官方 Maven Central 下载并验证官方 SHA256；可选的项目本地 Maven 缓存仅允许设置中列出的编译模块，其他机器没有该目录时继续使用官方远程仓库。缓存目录不提交，不修改全局 Gradle 或 Studio 配置。

## 模块与内容

当前 0.11.0 经过需求对照补齐任务自身说明、已有材料关联和相关删除撤销；修正表单恢复、对象搜索定位、画布视角保持、日程候选与行动空态。数据库显式迁移到 v6，备份 v3 兼容读取 v1/v2。Android 12 及 API 36.1 各 68 项数据设备测试通过，32 项界面检查通过；已覆盖升级手机并核对原有 11 类业务与配置。完整记录见项目根目录 documents/文档对照与发布检查-0.11.0.md。以下为历史版本记录；正式签名按用户选择留待持续真机体验稳定后处理。

当前 0.10.0 补齐应用内每日提醒、任务板双列空间位置持久保存、自定义日程背景及文字、独立休闲活动管理。数据库显式迁移至 v5，备份格式 v2 包含背景图片及新增记录，并兼容读取 v1。详情和验证边界见项目根目录 documents/日常功能补齐-0.10.0.md；下列旧版本说明作为历史记录保留。

0.9.0 完成当前数据完整备份与恢复批次。行动页左上角进入，通过 SAF 导出 ZIP 或选择导入；先校验，确认后自动保存最近三份恢复前完整快照并整体切换数据集。`LocalDataGate` 统一冻结写入，`WorkspaceManager` 以 AtomicFile 指针发布完整的新数据库/附件/设置；进程中断启动验证或回退。数据库仍为 v4，逻辑备份格式为 v1。布局/背景等待 D 批次接入并扩展兼容导出。`tools/backup_review.py` 验证系统文件选择器与整套原生流程，证据在 `review/backup-0.9.0/`，数据与故障测试在 `BackupPersistenceTest.kt`；仅模拟器合成数据。

0.8.0 完成删除与短暂撤销批次：六类内容删除前预览、对象/任务关联处理、每操作独立持久恢复集、到期清理附件及重启恢复。默认 8 秒，按系统辅助功能建议延长。Schema v4 仅新增 `agenda_delete_undo`，v1–v4 迁移链保留；没有破坏性重建。`tools/deletion_review.py` 只操作已有合成数据的模拟器，证据在 `review/deletion-0.8.0/`。完整备份恢复仍是下一批范围。

0.7.0 为「编辑与整理」开发批次：补齐任务、对象、说明、日程和周计划编辑，任务可移动归属；保存沿用原 ID 与完成状态。Schema 仍为 v3。`tools/editing_review.py` 在已有合成数据的模拟器上验证界面和重启，证据在 `review/editing-0.7.0/`。交付范围、剩余批次见 `documents/正式版补全执行计划.md`（相对项目根目录），本批不是正式版全部完成。

0.6.2 新增应用内图片查看（双指缩放、双击放大、拖动、复位），及材料显示标题/文字说明编辑与搜索。导入后打开材料信息表单；标题留空回退原文件名。Room schema 2 → 3 显式新增元数据列，图片在 IO 线程解码为最长边最多 2048px 的预览，原始副本保持不变；GIF 等动图当前使用静态预览，不提供逐帧播放。其他文件仍交给系统查看工具。设备测试覆盖 v1 → v3、v2 → v3，原生界面复查脚本为 `tools/material_image_review.py`（仅模拟器）。

- `app`：三页原生界面、ViewModel、轻量线性图标、字体混排、卡片与画布手势、玻璃浮层和系统入口。
- `domain`：共享模型、仓储接口、尽快做筛选、画布阻尼计算；无 Android 依赖。
- `data`：Room 持久仓储、DataStore 设置、材料复制与恢复清理；历史内存仓储保留为测试 fixture，不在真实数据入口初始化演示内容。

事项默认进入关系图，左上角切换双列任务板；任务板点击展开、正常滑动滚动、长按后移动。行动页提供原四卡及下方「尽快做」，可完成任务。日程支持悬挂展开与完整日期吸附，引用项可完成。搜索、任务详情、新任务及未保存关闭保护提供玻璃浮层示例。

## 验证与证据

规则测试：`domain/src/test` 和 `data/src/test`，覆盖日期窗口、跨年、并列顺序、完成补位、阻尼与引用同步。可用本目录的 `tools/device_review.py` 在指定的模拟器安装并操作样机，输出 `review/` 截图、界面树和报告：

```powershell
python -X utf8 tools/device_review.py --serial emulator-5554 --adb D:/allAboutAppCache/Android/sdk/platform-tools/adb.exe
```

自动脚本限制为 `emulator-` 设备，不会自动向真机安装。截图来自 Android 模拟器，不是网页。阶段记录及实际通过项目见 [阶段计划](../documents/Android动效样机阶段计划.md)。

0.2.0 的针对性回归使用 `tools/feedback_review.py`，截图和报告位于 `review/feedback-2026-10-27/`。该脚本默认也限制为模拟器，只有显式 `--allow-physical` 才允许选定真机；抓图前确认 Agenda 为前台，不抓取其他应用内容。修复对应关系、设备事件与最终验证结果见 [本轮修复记录](../documents/真机反馈修复-2026-10-27.md)。

0.3.0 使用 `tools/material_motion_review.py`，记录原生卡片实际尺寸，检查中间帧、宽高同时变化、弹性超调及连续点击收回；录屏与玻璃截图位于 `review/material-motion-0.3.0/`。本轮实测 27 个不同尺寸，详情见 [玻璃与任务板动效修正](../documents/玻璃与任务板动效修正-0.3.0.md)。

0.4.0 使用 `tools/refinement_review.py`，覆盖关系图内容居中、整卡点击、克制的连续展开、月历日期、说明创建、系统选择器导入和私有文件副本一致性、悬挂日期聚焦。任务板和行动共用 `ExpandablePair`，三处展开共用 `AgendaMotion`。截图和报告在 `review/refinement-0.4.0/`；实施见 [0.4.0 修正记录](../documents/交互与材质修正-0.4.0.md)，阶段验收和下一阶段见 [收口与后续开发计划](../documents/交互样机收口与后续开发计划.md)。

0.5.0 使用 `tools/notes_glass_smoothness_review.py` 验证标题/正文说明、最终玻璃材质首帧、连续三轮日期收起。截图、首帧与原生尺寸/帧日志位于 `review/notes-glass-smoothness-0.5.0/`，具体结果和限制见 [0.5.0 优化记录](../documents/说明与首帧及收起优化-0.5.0.md)。

0.6.0 使用 `tools/local_data_review.py` 验证创建对象/任务/说明/材料、引用与临时日程、判重、完成同步及强制停止重启；`--fresh` 只允许在模拟器清理这个测试应用的数据。真实数据库与文件设备测试位于 `data/src/androidTest`，单独安装数据模块的测试 APK 后运行 `com.agenda.data.test/androidx.test.runner.AndroidJUnitRunner`。首版 schema 和所有原生证据分别保存在 `data/schemas/` 与 `review/local-data-0.6.0/`。

## 已知边界

- 任务板当前长按移动在双列槽位之间落位，未实现正式空间坐标持久化和复杂碰撞求解。
- 关系图保留规则布局并按实际内容边界概览；大规模避让、双指手感及高刷新率性能需真机继续验证。
- 0.3.0 玻璃改为后台生成的实际背景模糊快照，框外仅轻微降低明度；面板打开期间使用静态快照，动态背景实时模糊与真机性能仍需后续校准。
- 0.6.1 增加周计划独立添加、勾选、切周查看及持久化，schema 1 → 2 使用显式新增表迁移；不写入普通任务或日程。`tools/weekly_plan_review.py` 仅在已有 0.6.0 数据的模拟器验证覆盖升级及界面隔离，证据在 `review/weekly-plan-0.6.1/`。0.7.0 已补齐编辑与移动归属，0.8.0 补齐删除撤销，0.9.0 补齐当前数据备份恢复；背景、布局等剩余范围按正式版补全计划实施。
- 尽快做按今天起七个自然日（今天至第六天）、排除逾期；2026-10-29 用户已确认该边界。
- 手机字体缩放、横竖屏恢复、无障碍和 Android 12 兼容性需要继续覆盖；不将已能构建视为这些项目全部验收。

0.1.0–0.5.0 为内存演示阶段，历史修复记录保留；0.6.0 真实数据版与历史样机分开安装，首次启动不导入演示记录。

## 字体

四种字体沿用已确认规范。`tools/prepare_fonts.py` 将本地 WOFF 转为 Android TTF，正文默认固定 400，另生成 Space Grotesk 600 用于加粗 Agenda 品牌；中文子集补齐样机代码与演示内容，其他字符由系统回退。源字体来自 Google Fonts 官方仓库，OFL 授权及来源随包放在 `assets/font-licenses/`。扩展中文子集时可将官方完整同款字体放入 `.font-sources/` 后重跑脚本，不需要改变字体选择。
