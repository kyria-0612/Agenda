# Agenda
一个用来管理个人任务和小事的app

## 源代码

- [android/](android/README.md)：Kotlin / Jetpack Compose 原生 Android 工程，包含 app、domain、data 三个模块、测试及构建脚本。
- [prototype/](prototype/README.md)：HTML、CSS、JavaScript 交互原型和视觉方案。
- [documents/](documents/)：项目开发与技术设计说明。
- [design-tokens.json](design-tokens.json)、[DESIGN.md](DESIGN.md)：设计参数及视觉规范。

Android 构建环境与步骤见 [Android README](android/README.md)。网页原型可在仓库根目录运行 `python -m http.server 8765`，然后访问 `http://localhost:8765/prototype/`。

仓库仅保存项目源码、必要资源和开发说明。个人报告、PPT 等课程交付文件、安装包、构建缓存及本机配置不纳入版本控制，相关排除规则见 [.gitignore](.gitignore)。
