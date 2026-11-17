# Agenda 视觉方案对比

从项目根目录启动 `python -m http.server 8765`，打开 http://localhost:8765/prototype/visual-directions/ 。

- index.html：自适应交互对比页，手机可切换三套方案。
- overview.html：1366px 宽并排总览，适合桌面或截图。
- phone.html?style=mist|paper|night：390px 手机检查页。
- design-tokens.json：三个候选的配色、字体、图标、圆角与动效说明。
- assets/icons.svg：可复用 SVG 符号；图标为本轮自绘 Agenda Line。
- assets/fonts/：本地字体子集、来源与许可证。

演示数据不持久保存，不影响正式原型。设计说明见 documents/视觉风格方案.md。
