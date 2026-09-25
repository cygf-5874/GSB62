# timerwheel

一个**分层定时轮**小库（Java 17，只用 JDK 标准库，无第三方依赖）。

`TimerWheel` 按注入的 `Clock` 推进：到期的任务在推进时触发。时间不读墙钟，
一律从构造时注入的 `Clock` 取，因此触发时刻完全确定、与机器速度无关。

```java
Clock clock = Clock.fixed(0L);                     // 或任何自定义实现
TimerWheel wheel = new TimerWheel(clock);

Tick t = wheel.schedule(1_500L, () -> System.out.println("hi"));   // 1.5 秒后触发
wheel.advance();                                   // 把时钟推进到「现在」，触发到期任务
wheel.cancel(t);                                   // 取消；已取消 / 已触发都返回 false
Stats s = wheel.stats();                           // scheduled / fired / cancelled / liveHandles / scannedNodes
```

## 目录

```
src/timerwheel/Clock.java         时间源接口（可注入）
src/timerwheel/Tick.java          排期句柄
src/timerwheel/Stats.java         计数快照
src/timerwheel/TimerWheel.java    定时轮实现
test/timerwheel/TimerWheelTest.java  既有用例（15 个，手写 runner，无第三方框架）
scripts/build.sh                  编译 src/ 与 test/ 到 out/
scripts/test.sh                   跑既有用例
scripts/check.sh                  固定验收入口（勿改）
check/Checker.java                固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh      # 编译到 out/
bash scripts/test.sh       # 既有用例
bash scripts/check.sh      # 固定验收；支持 -list 与 --only <组名>
bash scripts/check.sh -list              # 列出全部验收场景
bash scripts/check.sh --only cost        # 只跑一组
```

## 对外契约

下面 11 条是 `timerwheel` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**：其中若干条现在的实现并没有满足。

1. **排期**。`schedule(long delayMillis, Runnable task)` 排一个 `delayMillis` 毫秒后触发的任务，
   返回一个 `Tick` 句柄。`delayMillis` 为负抛 `IllegalArgumentException`，`task` 为 `null` 抛
   `NullPointerException`。`Tick.id()` 在同一时间轮内单调递增（与入队顺序一致），
   `Tick.deadlineMillis()` 等于排期时 `Clock.nowMillis()` 加上 `delayMillis`。
2. **按注入时钟推进**。`advance()` 把时间轮推进到注入 `Clock` 的当前时间，
   处理所有到期任务，并返回本次调用触发了几条。任务在**第一个满足
   `tick * 1000 >= deadlineMillis` 的 tick 被处理时**触发：**绝不早于
   `deadlineMillis`**，最迟不晚于它之后 1000ms。`advance()` 之外不做任何后台推进。
3. **计数快照**。`stats()` 返回 `Stats`：`scheduled` / `fired` / `cancelled` 是三个累计计数，
   `liveHandles` 是当前仍挂着（未触发、未取消）的句柄数，`scannedNodes` 是推进过程中
   被检查过的节点总数。`Stats` 的字段不可变，每次 `stats()` 都是一份独立快照。
4. **同一 tick 的顺序**。同一到期时刻的多个任务，按它们的**入队顺序**依次触发。
5. **分层推进**。时间轮至少分**秒 / 分 / 时三层**，合起来覆盖足够长的量程：
   一个跨越层级的任务（例如几十秒后排入、在几分钟后到期）必须**恰好触发一次**，
   既不许丢，也不许在 deadline 之前触发。
6. **稳定序**。第 4 条的入队顺序在**分层 / 跨层**下同样成立：同一到期时刻的任务，
   不论它是直接落在低层，还是从高层降级下来，都按入队顺序触发；
   触发顺序**不得依赖 `HashMap` 之类的迭代顺序**——同样的入队顺序必须给出同样的触发顺序。
7. **取消的返回值语义**。`cancel(Tick)` 只对「还挂着」的句柄返回 `true`；
   对**已取消**的句柄返回 `false`，对**已触发**的句柄返回 `false`，对 `null` 返回 `false`。
   以上任何情况都**不抛异常**。被取消的任务不再触发。
8. **句柄可回收**。取消或触发之后，句柄与它在队列里的节点都必须可回收：
   当所有排期都已触发 / 取消时，`Stats.liveHandles()` 必须归零。
   此外，`schedule` / `cancel` / `stats()` 允许被多线程并发调用（`advance()` 由单一线程驱动），
   并发期间不许丢任务或重复触发，且计数守恒：`scheduled == fired + cancelled + liveHandles`。
9. **摊销成本**。推进的成本必须与「本次到期的任务数」成正比，而不是与「轮盘上的总任务数」
   成正比：推进 1000ms 时被检查过的节点数（`Stats.scannedNodes()` 的增量）必须
   `≤ c × 到期任务数`（`c` 是一个小的常数）。**不许每推进一个 tick 就扫全表**：
   没有任务到期时，推进若干 tick 也应几乎不检查任何节点。判据只看 `scannedNodes()`，不看墙钟。
10. **超出最大层级的延迟**。延迟超过分层量程上限的任务，必须提升到顶层并在**正确的时刻**
    触发：不许丢弃，也不许提前。它和普通任务一样遵守第 2 条的触发时刻语义。
11. **可注入时钟**。所有「现在」都从构造时注入的 `Clock` 读取；实现里**不得出现**
    `System.currentTimeMillis` 或 `System.nanoTime`（固定件会扫描源码）。

## 验收

`check/` 是固定验收程序，**不要修改它**。它按六组场景检查上面的契约：

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `basic` | `fire-exactly-once` | 1、2 |
| `basic` | `not-before-deadline` | 2 |
| `basic` | `stats-counters` | 3、7 |
| `order` | `same-deadline-fifo` | 4 |
| `order` | `fifo-across-tiers` | 5、6 |
| `cancel` | `return-values` | 7 |
| `cancel` | `reclaim-handles` | 7、8 |
| `cost` | `amortized-per-tick` | 9 |
| `cost` | `idle-advance-scans-nothing` | 9 |
| `overflow` | `beyond-max-span` | 5、10 |
| `concurrency` | `threaded-schedule-and-clock` | 8、11 |

共 11 个场景。每个场景独立判定，失败不会遮蔽其余场景；判据全部确定性，时间由可注入的
`Clock` 控制，成本由 `Stats.scannedNodes()` 计数（不看墙钟、不看机器速度）。
`concurrency` 场景另起一个独立的 JVM 子进程，带 5 秒看门狗（超时会打印线程栈并以退出码 `3`
结束）。
