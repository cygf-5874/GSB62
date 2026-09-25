排期上这周轮到 timerwheel：Java 17 的定时器库，只用 JDK 标准库，构建与自检走 scripts/*.sh，
不许引入 Maven/Gradle，也不许有第三方依赖。
README「对外契约」一节列了 11 条；src/ 下是完整实现，test/ 里 15 个用例当前全绿
—— 但它们只覆盖单层时间轮 + 固定时钟的路径。

现状：只有单层实现，跨层任务会丢或者提前触发；每推进一个 tick 都要扫整张表。

任务：把契约一节的全部 11 条补齐。

验收（check/ 是固定验收程序，别改）：
- bash scripts/check.sh 退出码 0，11 个场景全过（basic 3 + order 2 + cancel 2 + cost 2 +
  overflow 1 + concurrency 1）；
- bash scripts/test.sh 全绿。

约束：
1. 不改 check/、不改 test/ 里既有用例的断言；`Clock` 接口与已公开方法签名不变（可新增方法）。
2. 只用 JDK；构建只用 javac/java。
3. 时间只能从注入的 Clock 读，实现里不得出现 System.currentTimeMillis / System.nanoTime。
4. 成本判据看 Stats 里的扫描节点计数，不看墙钟；并发场景在独立 JVM 里跑并带 5 秒看门狗。
