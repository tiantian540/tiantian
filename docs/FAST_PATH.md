# 本地快路径（Local Fast Path）

阶段 2 的边界文件。实现见 `core-agent/src/main/kotlin/dev/xiaoman/agent/LocalPicker.kt`，
接入点是 `OperatorLoop.run()` 里 `constraint != null` 的分支。

## 1. 只做什么

只服务 **FlowRunner 里目标已经写死的约束步**：

```yaml
- tap: 确认呼叫
- type: { target: 目的地, text: 公司 }
```

即 `StepConstraint("tap" | "type", target, text)`。自由任务、`ai` 步骤、`assert` 步骤一律
不看快路径。

## 2. 硬边界（写死，不接受“顺手优化”）

1. **只选节点，不生成坐标。** 命中的结果只能是 `Action.Tap(nodeId = ...)` /
   `Action.TypeText(nodeId = ..., text = ...)`，`point` 永远为 null。坐标由 `hands/` 从
   真实节点计算，快路径不得自己算位置。
2. **必须经 core-sentinel 审核。** 命中后走的执行路径与模型通道完全一致：
   `sentinel.guard` → `sentinel.authorize` → `awaitApprovalScreen` → `device.perform`。
   本文件与 `LocalPicker` 都不 import、不调用 `core-sentinel`，也不改变其规则。
3. **任何不确定或失败立即回退模型通道。** 具体是：
   - 没有匹配、多于一个匹配、精确命中不唯一 → 返回 null，本次迭代直接走模型；
   - `type` 步骤没有 `text` → 返回 null；
   - 快路径动作被 Sentinel 拦截（`Executed.Blocked`）→ 把拦截原因记进 history/feedback，
     同一屏立即交给模型重新决策；
   - `perform` 返回失败 → 同上，交给模型；
   - 快路径自身抛异常视为“没命中”，绝不让异常穿出去。
   回退不是“换一条执行通道”，而是原本就存在的模型通道。
4. **默认关闭。** `AgentConfig.localFastPath` 默认 `false`，需要在构造 `AgentConfig` 时显式打开
   （当前没有 UI 开关，也不在设置页暴露）。

## 3. 选择规则

对 `screen.nodes` 中满足以下条件的节点：

- `password == false`（密码框永不入选，见第 5 节）；
- `bounds` 非空（`right > left && bottom > top`）；
- `type` 步骤额外要求 `editable == true`。

把 `text` 与 `contentDescription` 去掉首尾空白、转小写、去掉所有空白字符后比较：

1. **精确匹配**：归一化后完全相等。恰好 1 个 → 命中，`reason=exact`；多于 1 个 → `null`。
2. **子串匹配**（仅当精确匹配为 0 个）：节点文本包含目标，或节点文本（长度 ≥ 2）被目标包含。
   恰好 1 个 → 命中，`reason=substring`；多于 1 个 → `null`。

**没有“最佳候选”这种妥协**：同分、多命中、只有一个字符的模糊重合，一律 `null`。

## 4. 指标

沿用阶段 0 的 logcat 埋点（tag `Xiaoman`），`op` 行增加 `select` 与 `reason`：

```
op step=2 observe=612ms select=local reason=exact nodes=37 pkg=com.sdu.didi.psnger secure=false
op step=3 observe=640ms select=model reason=no_unique_match model=1830ms nodes=35 pkg=... secure=false
op step=1 observe=588ms select=model reason=disabled model=1710ms ...
```

`reason` 取值：`exact` / `substring`（本地命中）、`no_unique_match`（开了开关但没命中）、
`disabled`（开关关闭）、`no_constraint`（不是约束步）、`local_blocked` / `local_failed`
（本地命中但执行被拦或失败，已回退模型）。不新增协议、不加新日志文件。

## 5. 为什么密码框永不入选

`NodeCompactor` 对 `password` 节点会清空 `text`，但 `description` 仍可能保留“密码”之类的提示，
且 `type` 步骤一旦命中就会直接输入。快路径没有“人工复核这一步选得对不对”的环节，所以密码框
在候选集里直接排除，宁可回退模型。

## 6. 已知限制

- 本地命中只保证“唯一”，不保证“正确”；`perform` 的 ok 只代表手势/输入已注入。
- 依赖控件自身暴露的 `text` / `contentDescription`。只有图标、没有文本的按钮无法本地命中。
- 与模型通道一样，同一屏重复执行后由既有 stuck 检测兜底。
- 不依赖阶段 1 的 `observeNodesUnsafeForSecurity` 或 `changedDuringCapture`：本路径只用普通
  `observe()` 的结果。

## 7. 关闭与回退

- 运行期：把构造 `AgentConfig` 处的 `localFastPath` 改回 `false`（默认值）。
- 代码级：删除/注释 `OperatorLoop` 里的 `if (config.localFastPath && constraint != null) { ... }`
  块即可，模型通道未改动，删掉后行为等价于阶段 1。
- 不需要改动 `core-sentinel/`、`hands/`，也不需要数据迁移。

## 8. 明确不做（后续阶段）

- 记忆格式改造、learn 晋升门禁、ExperienceGuard；
- 新增执行通道或坐标推算；
- 把 `observeNodesUnsafeForSecurity` 用进决策。
