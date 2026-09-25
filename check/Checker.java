import timerwheel.Clock;
import timerwheel.Stats;
import timerwheel.Tick;
import timerwheel.TimerWheel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * timerwheel 的固定验收程序（勿改）。
 *
 * <p>11 个场景覆盖 README「对外契约」的 11 条：basic 3 / order 2 / cancel 2 /
 * cost 2 / overflow 1 / concurrency 1。并发场景在独立 JVM 子进程里跑，带 5 秒看门狗；
 * 其余场景失败不早退，一次把问题都暴露出来。
 *
 * <p>用法：java -cp out Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String group;
        final String name;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    /** 手写的可推进假时钟：时间语义全部走它，不碰墙钟。 */
    private static final class FakeClock implements Clock {
        private long now;

        FakeClock(long now) {
            this.now = now;
        }

        void set(long millis) {
            this.now = millis;
        }

        void advanceMillis(long delta) {
            this.now += delta;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--concurrency-child".equals(args[0])) {
            System.exit(runConcurrencyChild());
        }

        List<String> only = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) only.add(g);
                }
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        Path root = Paths.get("").toAbsolutePath().normalize();
        List<Scenario> all = scenarios(root);

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        int total = 0;
        int pass = 0;
        for (Scenario s : all) {
            if (!only.isEmpty() && !only.contains(s.group)) continue;
            total++;
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                pass++;
                System.out.println("PASS " + s.name);
            } else {
                System.out.println("FAIL " + s.name + "  " + err);
            }
        }
        if (total == 0) {
            System.out.println("没有匹配的场景（--only " + only + "）");
            System.exit(2);
        }
        System.out.println("结果：通过 " + pass + "/" + total);
        if (pass != total) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios(Path root) {
        List<Scenario> out = new ArrayList<>();

        // ---------------- basic ----------------

        out.add(new Scenario("basic/fire-exactly-once", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> log = new ArrayList<>();
            wheel.schedule(1000L, () -> log.add("a"));
            wheel.schedule(2000L, () -> log.add("b"));
            wheel.schedule(3000L, () -> log.add("c"));

            clock.set(1000L);
            int n1 = wheel.advance();
            if (n1 != 1) {
                return "期望=推进到 1000ms 时 advance() 触发 1 条 实际=" + n1;
            }
            if (!log.equals(List.of("a"))) {
                return "期望=1000ms 时已触发 [a] 实际=" + log;
            }
            clock.set(2000L);
            wheel.advance();
            if (!log.equals(List.of("a", "b"))) {
                return "期望=2000ms 时已触发 [a, b] 实际=" + log;
            }
            clock.set(3000L);
            int n3 = wheel.advance();
            if (n3 != 1 || !log.equals(List.of("a", "b", "c"))) {
                return "期望=3000ms 时触发 1 条且日志 [a, b, c] 实际=返回 " + n3 + " 日志 " + log;
            }
            clock.set(60_000L);
            int n4 = wheel.advance();
            if (n4 != 0 || log.size() != 3) {
                return "期望=之后再推进不重复触发（返回 0，日志仍 3 条）实际=返回 " + n4 + " 日志 " + log;
            }
            return null;
        }));

        out.add(new Scenario("basic/not-before-deadline", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> log = new ArrayList<>();
            wheel.schedule(3000L, () -> log.add("x"));

            clock.set(2000L);
            int n = wheel.advance();
            if (n != 0 || !log.isEmpty()) {
                return "期望=deadline=3000ms 之前不触发 实际=返回 " + n + " 日志 " + log;
            }
            clock.set(2999L);
            wheel.advance();
            if (!log.isEmpty()) {
                return "期望=2999ms 时不触发（不许早触发）实际=" + log;
            }
            clock.set(3000L);
            n = wheel.advance();
            if (n != 1 || !log.equals(List.of("x"))) {
                return "期望=3000ms 时恰好触发 1 次 实际=返回 " + n + " 日志 " + log;
            }
            clock.set(30_000L);
            wheel.advance();
            if (log.size() != 1) {
                return "期望=之后不再触发 实际=日志 " + log;
            }
            return null;
        }));

        out.add(new Scenario("basic/stats-counters", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> log = new ArrayList<>();
            Stats s0 = wheel.stats();
            if (s0.scheduled != 0L || s0.fired != 0L || s0.cancelled != 0L) {
                return "期望=初始 Counters 全 0 实际=" + s0;
            }
            wheel.schedule(1000L, () -> log.add("a"));
            Tick b = wheel.schedule(2000L, () -> log.add("b"));
            Stats s1 = wheel.stats();
            if (s1.scheduled != 2L || s1.fired != 0L) {
                return "期望=排 2 条后 scheduled=2 fired=0 实际=" + s1;
            }
            clock.set(1000L);
            wheel.advance();
            Stats s2 = wheel.stats();
            if (s2.scheduled != 2L || s2.fired != 1L) {
                return "期望=触发第 1 条后 scheduled=2 fired=1 实际=" + s2;
            }
            if (!wheel.cancel(b)) {
                return "期望=取消一个还没触发的排期返回 true 实际=false";
            }
            Stats s3 = wheel.stats();
            if (s3.cancelled != 1L) {
                return "期望=成功取消一次后 cancelled=1 实际=" + s3;
            }
            clock.set(20_000L);
            wheel.advance();
            if (!log.equals(List.of("a"))) {
                return "期望=被取消的 b 不触发（日志 [a]）实际=" + log;
            }
            return null;
        }));

        // ---------------- order ----------------

        out.add(new Scenario("order/same-deadline-fifo", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> order = new ArrayList<>();
            wheel.schedule(2000L, () -> order.add("t0"));
            wheel.schedule(2000L, () -> order.add("t1"));
            wheel.schedule(2000L, () -> order.add("t2"));
            wheel.schedule(1000L, () -> order.add("early"));
            clock.set(2000L);
            wheel.advance();
            if (!order.equals(List.of("early", "t0", "t1", "t2"))) {
                return "期望=按到期时间，同到期时刻按入队顺序 [early, t0, t1, t2] 实际=" + order;
            }
            List<String> again = runOrderProbe();
            if (!again.equals(runOrderProbe())) {
                return "期望=同样的入队顺序必须给出同样的触发顺序 实际=" + again;
            }
            return null;
        }));

        out.add(new Scenario("order/fifo-across-tiers", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> order = new ArrayList<>();

            wheel.schedule(70_000L, () -> order.add("a"));      // 最早入队，deadline=70000
            clock.set(64_000L);
            wheel.advance();
            if (!order.isEmpty()) {
                return "期望=跨层排期在 deadline 之前不触发 实际=" + order;
            }

            wheel.schedule(6_000L, () -> order.add("b"));       // 后入队，deadline=70000
            wheel.schedule(5_000L, () -> order.add("d"));       // 最后入队，deadline=69000

            clock.set(69_000L);
            wheel.advance();
            if (!order.equals(List.of("d"))) {
                return "期望=69000ms 时只有 d 触发 实际=" + order;
            }
            clock.set(70_000L);
            wheel.advance();
            if (!order.equals(List.of("d", "a", "b"))) {
                return "期望=同一到期时刻 70000ms 按入队顺序 [a, b]（整体 [d, a, b]）实际=" + order;
            }
            clock.set(200_000L);
            wheel.advance();
            if (!order.equals(List.of("d", "a", "b"))) {
                return "期望=不再重复触发 实际=" + order;
            }
            return null;
        }));

        // ---------------- cancel ----------------

        out.add(new Scenario("cancel/return-values", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            List<String> log = new ArrayList<>();

            Tick a = wheel.schedule(5000L, () -> log.add("a"));
            if (!wheel.cancel(a)) {
                return "期望=取消一个还没触发的句柄返回 true 实际=false";
            }
            if (wheel.cancel(a)) {
                return "期望=重复取消同一个句柄返回 false 实际=true";
            }
            clock.set(6000L);
            wheel.advance();
            if (log.contains("a")) {
                return "期望=被取消的任务不触发 实际=日志 " + log;
            }
            if (wheel.cancel(a)) {
                return "期望=取消已触发的句柄返回 false 实际=true";
            }

            Tick b = wheel.schedule(1000L, () -> log.add("b"));
            clock.set(7000L);
            wheel.advance();
            if (!log.equals(List.of("b"))) {
                return "期望=b 正常触发 实际=" + log;
            }
            if (wheel.cancel(b)) {
                return "期望=取消已触发的句柄返回 false 实际=true";
            }
            if (wheel.cancel(null)) {
                return "期望=cancel(null) 返回 false 实际=true";
            }
            return null;
        }));

        out.add(new Scenario("cancel/reclaim-handles", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            Tick[] ticks = new Tick[10];
            for (int i = 0; i < ticks.length; i++) {
                ticks[i] = wheel.schedule((i + 1) * 1000L, () -> { });
            }
            for (int i = 0; i < 5; i++) {
                if (!wheel.cancel(ticks[i])) {
                    return "期望=取消第 " + i + " 个句柄返回 true 实际=false";
                }
            }
            Stats s1 = wheel.stats();
            if (s1.liveHandles != 5L) {
                return "期望=10 排期取消 5 个后 liveHandles=5 实际=" + s1.liveHandles;
            }
            clock.set(20_000L);
            wheel.advance();
            Stats s2 = wheel.stats();
            if (s2.fired != 5L) {
                return "期望=剩下 5 个全部触发 实际=fired=" + s2.fired;
            }
            if (s2.liveHandles != 0L) {
                return "期望=取消 / 触发之后句柄全部可回收（liveHandles=0）实际=" + s2.liveHandles;
            }
            if (s2.scheduled != s2.fired + s2.cancelled + s2.liveHandles) {
                return "期望=计数守恒 scheduled == fired + cancelled + liveHandles 实际=" + s2;
            }
            return null;
        }));

        // ---------------- cost ----------------

        out.add(new Scenario("cost/amortized-per-tick", () -> {
            FakeClock clock = new FakeClock(1_000_000L);
            TimerWheel wheel = new TimerWheel(clock);
            int background = 2000;
            for (int i = 0; i < background; i++) {
                wheel.schedule(1_500_000L, () -> { });      // 远未到期
            }
            int due = 50;
            int[] fired = new int[1];
            for (int i = 0; i < due; i++) {
                wheel.schedule(0L, () -> fired[0]++);        // 本 tick 内到期
            }
            long before = wheel.stats().scannedNodes;
            clock.set(1_001_000L);
            int n = wheel.advance();
            long scanned = wheel.stats().scannedNodes - before;
            if (n != due) {
                return "期望=本 tick 触发 " + due + " 条 实际=" + n;
            }
            long limit = 5L * due;
            if (scanned > limit) {
                return "期望=推进 1000ms 的扫描节点数 ≤ 5 × 到期数（" + due + "）= " + limit
                        + "（不许每 tick 全表扫描）实际=" + scanned;
            }
            return null;
        }));

        out.add(new Scenario("cost/idle-advance-scans-nothing", () -> {
            FakeClock clock = new FakeClock(1_000_000L);
            TimerWheel wheel = new TimerWheel(clock);
            for (int i = 0; i < 3000; i++) {
                wheel.schedule(1_500_000L, () -> { });      // 远未到期
            }
            long before = wheel.stats().scannedNodes;
            clock.set(1_010_000L);
            int n = wheel.advance();
            long scanned = wheel.stats().scannedNodes - before;
            if (n != 0) {
                return "期望=没有到期任务时 advance() 返回 0 实际=" + n;
            }
            if (scanned > 32L) {
                return "期望=没有到期任务时推进 10 个 tick 的扫描节点数 ≤ 32 实际=" + scanned
                        + "（不应触碰那 3000 条未到期的排期）";
            }
            return null;
        }));

        // ---------------- overflow ----------------

        out.add(new Scenario("overflow/beyond-max-span", () -> {
            FakeClock clock = new FakeClock(0L);
            TimerWheel wheel = new TimerWheel(clock);
            long delay = 300_000_000L;                      // 远超最高层的量程
            int[] fired = new int[1];
            wheel.schedule(delay, () -> fired[0]++);

            clock.set(delay - 1000L);
            wheel.advance();
            if (fired[0] != 0) {
                return "期望=超出最大层级的延迟在 deadline 之前不触发 实际=已触发 " + fired[0] + " 次";
            }
            clock.set(delay);
            int n = wheel.advance();
            if (fired[0] != 1 || n != 1) {
                return "期望=到 deadline 时恰好触发 1 次 实际=触发 " + fired[0] + " 次，advance() 返回 " + n;
            }
            clock.set(delay + 10_000_000L);
            wheel.advance();
            if (fired[0] != 1) {
                return "期望=之后不再重复触发 实际=触发 " + fired[0] + " 次";
            }
            return null;
        }));

        // ---------------- concurrency（放最后：超时走 exit 3） ----------------

        out.add(new Scenario("concurrency/threaded-schedule-and-clock", () -> {
            String scan = scanWallClock(root);
            if (scan != null) return scan;
            return runConcurrencyChildProcess();
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    /** 供 order 场景做「同样入队顺序 → 同样触发顺序」的复跑。 */
    private static List<String> runOrderProbe() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String name = "p" + i;
            wheel.schedule(1000L + (i % 3) * 1000L, () -> order.add(name));
        }
        clock.set(3000L);
        wheel.advance();
        return order;
    }

    /** 扫描 src/timerwheel/*.java，确认实现没有绕过注入 Clock 去读墙钟。 */
    private static String scanWallClock(Path root) throws Exception {
        Path dir = root.resolve("src/timerwheel");
        if (!Files.isDirectory(dir)) {
            return "期望=src/timerwheel 目录存在 实际=缺失";
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        List<String> bad = new ArrayList<>();
        for (Path p : files) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.contains("System.currentTimeMillis") || text.contains("System.nanoTime")) {
                bad.add(p.getFileName().toString());
            }
        }
        if (!bad.isEmpty()) {
            return "期望=时间只能来自注入的 Clock，实现里不出现 System.currentTimeMillis / System.nanoTime"
                    + " 实际=命中 " + bad;
        }
        return null;
    }

    /** 在独立 JVM 子进程里跑并发场景，超时 5 秒。 */
    private static String runConcurrencyChildProcess() throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--concurrency-child");
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        // 子进程内有 5 秒看门狗；这里留一个更长的兜底，防止子进程连看门狗都跑不到。
        if (!timedJoin(reader, 15L, TimeUnit.SECONDS)) {
            System.out.println("FAIL concurrency/threaded-schedule-and-clock  "
                    + "期望=并发子进程在 5 秒内结束 实际=15 秒仍未结束；打印本进程线程栈：");
            dumpStacks("  ");
            proc.destroyForcibly();
            System.exit(3);
        }

        int code = proc.exitValue();
        String childOut;
        synchronized (sink) {
            childOut = sink.toString().trim().replace('\n', ' ');
        }
        if (code == 3) {
            System.out.println("FAIL concurrency/threaded-schedule-and-clock  "
                    + "期望=并发子进程退出码 0 实际=退出码 3（5 秒看门狗超时）；子进程输出：");
            System.out.println(childOut.replace("; ", ";\n"));
            System.exit(3);
        }
        if (code != 0) {
            return "期望=并发子进程退出码 0 实际=退出码 " + code + "；子进程输出：" + childOut;
        }
        return null;
    }

    /** 打印当前 JVM 全部线程的栈。 */
    private static void dumpStacks(String indent) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            System.out.println(indent + "[" + entry.getKey().getName() + "]");
            for (StackTraceElement st : entry.getValue()) {
                System.out.println(indent + "  at " + st);
            }
        }
    }

    /** 带超时的 join（TimeUnit.SECONDS.timedJoin 的等价物）。 */
    private static boolean timedJoin(Thread t, long timeout, TimeUnit unit) {
        try {
            t.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    // ------------------------------------------------- 并发子进程（独立 JVM）

    private static int runConcurrencyChild() {
        final int threads = 8;
        final int perThread = 25;
        final int total = threads * perThread;
        final FakeClock clock = new FakeClock(0L);
        final TimerWheel wheel = new TimerWheel(clock);

        final long[] deadline = new long[total];
        final boolean[] cancelled = new boolean[total];
        final boolean[] fired = new boolean[total];
        final long[] firedAt = new long[total];
        final int[] early = new int[1];
        final int[] duplicate = new int[1];
        final Throwable[] errors = new Throwable[threads];
        final CountDownLatch start = new CountDownLatch(1);

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        final int idx = id * perThread + i;
                        long delay = delayFor(i);
                        deadline[idx] = delay;
                        Tick handle = wheel.schedule(delay, () -> {
                            if (fired[idx]) {
                                duplicate[0]++;
                            }
                            fired[idx] = true;
                            long at = clock.nowMillis();
                            firedAt[idx] = at;
                            if (at < deadline[idx]) {
                                early[0]++;
                            }
                        });
                        if (i % 7 == 3) {
                            if (wheel.cancel(handle)) {
                                cancelled[idx] = true;
                            }
                        }
                    }
                } catch (Throwable e) {
                    errors[id] = e;
                }
            }, "timerwheel-scheduler-" + t);
            workers[t].start();
        }

        start.countDown();
        for (Thread w : workers) {
            if (!timedJoin(w, 5L, TimeUnit.SECONDS)) {
                System.out.println("[child] 看门狗超时：调度线程 " + w.getName() + " 未在 5 秒内结束");
                dumpStacks("[child] ");
                return 3;
            }
        }
        for (int t = 0; t < threads; t++) {
            if (errors[t] != null) {
                System.out.println("[child] 调度线程 " + t + " 抛出异常：" + errors[t]);
                return 7;
            }
        }

        long maxDeadline = 0L;
        for (int idx = 0; idx < total; idx++) {
            if (!cancelled[idx] && deadline[idx] > maxDeadline) {
                maxDeadline = deadline[idx];
            }
        }
        for (long t = 0L; t <= maxDeadline + 1000L; t += 1000L) {
            clock.set(t);
            wheel.advance();
        }

        int cancelledCount = 0;
        int firedCount = 0;
        for (int idx = 0; idx < total; idx++) {
            if (cancelled[idx]) {
                cancelledCount++;
            } else if (fired[idx]) {
                firedCount++;
            }
        }
        Stats s = wheel.stats();
        System.out.println("[child] 8 线程 × " + perThread + " 条排期（其中取消 " + cancelledCount
                + " 条）：" + s);
        System.out.println("[child] 未取消的 " + (total - cancelledCount) + " 条中已触发 " + firedCount
                + " 条；早触发 " + early[0] + " 次；重复触发 " + duplicate[0] + " 次");

        if (early[0] != 0) {
            return 5;
        }
        if (duplicate[0] != 0) {
            return 6;
        }
        if (firedCount != total - cancelledCount) {
            return 4;
        }
        if (s.scheduled != total || s.fired != total - cancelledCount
                || s.cancelled != cancelledCount || s.liveHandles != 0L) {
            return 9;
        }
        return 0;
    }

    /** 并发场景里每个索引对应的延迟：混合单层与跨层。 */
    private static long delayFor(int i) {
        switch (i % 5) {
            case 0:
                return 1_000L + i * 100L;
            case 1:
                return 30_000L + i;
            case 2:
                return 70_000L + i * 1_000L;
            case 3:
                return 130_000L + i * 100L;
            default:
                return 2_000L + i;
        }
    }

    private Checker() {
    }
}
