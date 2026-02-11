# GlyphHacker 状态机（简洁版）

这份文档只写两件事：

1) 每个状态是什么。
2) 状态怎么跳转（直接写清条件，不分散）。

---

## 固定阈值（默认）

- `commandOpenMaxLuma = 1.0`
- `glyphDisplayMinLuma = 10.0`
- `goColorDeltaThreshold = 18.0`
- `countdownVisibleThreshold = 5.0`
- `progressVisibleThreshold = 20.0`
- `edgeActivationThreshold = 26.0`
- `minimumLineBrightness = 70.0`
- `minimumMatchScore = 0.68`
- `stableFrameCount = 1`

亮度范围近似 `0..255`。

---

## 亮度判定（先定义清楚）

- `commandOpenDetected`
  - `firstBoxLuma < 1.0`
  - 且 `countdownLuma < 1.0`
  - 且 `progressLuma < 1.0`

- `glyphDisplayDetected`
  - `firstBoxLuma > 10.0`

- `readyIndicatorsVisible`
  - `countdownLuma >= 5.0`
  - 且 `progressLuma >= 20.0`

- `waitGoEligible`
  - `commandOpenSeen == true`
  - 且 `glyphDisplaySeen == true`
  - 且 `readyIndicatorsVisible == true`

说明：

- `commandOpenSeen`：任意一帧出现 `commandOpenDetected` 后就锁存为 `true`，直到整轮结束重置。
- `glyphDisplaySeen`：在 `commandOpenSeen=true` 后，任意一帧出现 `glyphDisplayDetected` 即锁存为 `true`，直到整轮重置。

---

## phase 状态定义

- `IDLE`（悬浮窗显示 `闲`）
- `COMMAND_OPEN`（`令`）
- `GLYPH_DISPLAY`（`识`）
- `WAIT_GO`（`备`）
- `AUTO_DRAW`（`绘`）
- `SCORING`（`终`，当前流程基本不常驻）

---

## phase 跳转条件（直接版）

### `IDLE -> COMMAND_OPEN`

当前帧满足：

- `firstBoxLuma < 1.0`
- 且 `countdownLuma < 1.0`
- 且 `progressLuma < 1.0`

即 `commandOpenDetected=true`。

---

### `COMMAND_OPEN -> GLYPH_DISPLAY`

满足任意一个就进 `GLYPH_DISPLAY`：

- `glyphDisplaySeen == true`（即曾出现过 `firstBoxLuma > 10.0`）
- 或 `activeEdges` 非空
- 或 `sequence` 非空
- 或 `glyphDisplayLatched == true`

---

### `GLYPH_DISPLAY -> WAIT_GO`

满足以下全部：

- `commandOpenSeen == true`
- `glyphDisplaySeen == true`
- `countdownLuma >= 5.0`
- `progressLuma >= 20.0`

即 `waitGoEligible=true`。

---

### `WAIT_GO -> AUTO_DRAW`

满足以下全部：

- `waitGoEligible == true`
- `firstBoxLuma >= 18.0`
- `sequence` 非空

触发后：

- `drawRequested = true`
- `drawTriggered = true`
- `phase = AUTO_DRAW`

如果 `firstBoxLuma >= 18.0` 但 `sequence` 为空，不会绘制，继续留在 `WAIT_GO`。

---

### `WAIT_GO -> GLYPH_DISPLAY`（回退）

如果之前在 `WAIT_GO`，但后续 `waitGoEligible` 失效（倒计时或进度条条件掉了），回退到 `GLYPH_DISPLAY`。

---

### `AUTO_DRAW -> IDLE`

绘制触发后进入静默检测：

- 每帧若 `activeEdges` 为空，`quietFramesAfterDraw += 1`
- 若出现边则清零
- 当 `quietFramesAfterDraw > 20` 时，`resetForNextRound()`，回到 `IDLE`

---

## 序列写入规则（影响第二位数字）

只在以下条件下更新序列：

- `!drawTriggered`
- 且 `commandOpenSeen == true`
- 且 `glyphDisplaySeen == true`

写入条件：

- 普通写入：同一 glyph 连续命中 `stableFrameCount` 帧（默认 1）
- 或快速写入：glyph 变化且（有空白过渡或当前序列为空）

去重：

- 与序列最后一个 glyph 相同，不重复 append。

---

## 悬浮窗显示规则

### 左侧符号

- `✓`：`captureRunning && recognitionEnabled`
- `✗`：其他情况

### 文字

- 如果 `recognitionEnabled=false`：固定 `停0`
- 否则：`<阶段字><数字>`
  - 阶段字：`闲 | 令 | 识 | 备 | 绘 | 终`
  - 数字：
    - 非 `AUTO_DRAW`：`sequence.size`（0-9）
    - `AUTO_DRAW`：`drawRemainingCount`（0-9）

---

## 控制层状态（主页面/悬浮窗点击）

- `显示`
  - 直接显示悬浮窗

- `隐藏`
  - 关闭悬浮窗（`overlayVisible=false`）

- `开始识别`
  - 每次都先申请录屏权限
  - 用户授权后启动识别（`recognitionEnabled=true`）

- `停止识别`
  - 不销毁录屏会话，只把 `recognitionEnabled=false`
  - 悬浮窗显示 `停0`，左侧 `✗`

- 悬浮窗左侧 `✗` 被点击时
  - 直接拉起录屏权限申请
  - 用户授权后开始识别

- 悬浮窗左侧 `✓/✗` 被长按时
  - 中止当前录屏服务会话
  - 重新申请录屏权限
  - 用户授权后重启录屏服务并恢复识别

- 悬浮窗右侧 `X` 被长按时
  - 立即重置识别会话
  - 运行中时状态恢复到 `闲0`

- 录屏 `MediaProjection.onStop`（系统中断）
  - `recognitionEnabled=false`
  - 停止采集服务
  - 悬浮窗落到 `停0` + `✗`
