package timerwheel;

import java.util.ArrayList;
import java.util.List;

/**
 * timerwheel 既有用例。自带极简 runner（不引入任何第三方测试框架）。
 *
 * <p>覆盖范围只包括**单层时间轮 + 固定时钟的路径**：基本排期与触发、
 * 同一 tick 的触发顺序、以及几个计数。跨层推进、取消的返回值语义、
 * 句柄回收、摊销扫描成本、超长延迟与并发不在本文件的覆盖范围内。
 */
public final class TimerWheelTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        run("firesOnceAfterDelay", TimerWheelTest::firesOnceAfterDelay);
        run("advanceReturnsFiredCount", TimerWheelTest::advanceReturnsFiredCount);
        run("advanceReturnsZeroWhenNothingDue", TimerWheelTest::advanceReturnsZeroWhenNothingDue);
        run("notFiredBeforeDeadline", TimerWheelTest::notFiredBeforeDeadline);
        run("firesAtExactDeadlineTick", TimerWheelTest::firesAtExactDeadlineTick);
        run("sameDeadlineKeepsInsertionOrder", TimerWheelTest::sameDeadlineKeepsInsertionOrder);
        run("differentDelaysFireInDeadlineOrder", TimerWheelTest::differentDelaysFireInDeadlineOrder);
        run("fireOrderStableAcrossRuns", TimerWheelTest::fireOrderStableAcrossRuns);
        run("statsScheduledCountsScheduleCalls", TimerWheelTest::statsScheduledCountsScheduleCalls);
        run("statsFiredCountsTriggers", TimerWheelTest::statsFiredCountsTriggers);
        run("statsCancelledCountsCancelCalls", TimerWheelTest::statsCancelledCountsCancelCalls);
        run("cancelPreventsFiring", TimerWheelTest::cancelPreventsFiring);
        run("cancelNullReturnsFalse", TimerWheelTest::cancelNullReturnsFalse);
        run("negativeDelayRejected", TimerWheelTest::negativeDelayRejected);
        run("nullTaskRejected", TimerWheelTest::nullTaskRejected);

        System.out.println("通过 " + passed + "/" + (passed + failed));
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- runner

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + "  " + t);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void expectNpe(Runnable body, String message) {
        try {
            body.run();
        } catch (NullPointerException e) {
            return;
        }
        throw new AssertionError(message + " 期望=NullPointerException 实际=没有抛出");
    }

    private static void expectBadArgument(Runnable body, String message) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError(message + " 期望=IllegalArgumentException 实际=没有抛出");
    }

    // ------------------------------------------------------------ 测试时钟

    /** 手写的可推进假时钟：用例自己控制「现在」。 */
    private static final class FakeClock implements Clock {
        private long now;

        FakeClock(long now) {
            this.now = now;
        }

        void advanceMillis(long delta) {
            now += delta;
        }

        void set(long millis) {
            now = millis;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }

    // ----------------------------------------------------------- 既有用例

    private static void firesOnceAfterDelay() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> log = new ArrayList<>();
        wheel.schedule(1000L, () -> log.add("a"));

        clock.set(1000L);
        eq(1, wheel.advance(), "到 1000ms 时应触发 1 条");
        eq(List.of("a"), log, "任务应触发一次");

        clock.set(9000L);
        eq(0, wheel.advance(), "之后再推进不应重复触发");
        eq(List.of("a"), log, "任务仍应只触发一次");
    }

    private static void advanceReturnsFiredCount() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        wheel.schedule(2000L, () -> { });
        wheel.schedule(2000L, () -> { });
        clock.set(2000L);
        eq(2, wheel.advance(), "同一个 tick 上的两条都应触发");
    }

    private static void advanceReturnsZeroWhenNothingDue() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> log = new ArrayList<>();
        wheel.schedule(2000L, () -> log.add("a"));
        clock.set(1000L);
        eq(0, wheel.advance(), "未到 deadline 时不应触发");
        eq(List.of(), log, "日志应为空");
    }

    private static void notFiredBeforeDeadline() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> log = new ArrayList<>();
        wheel.schedule(3000L, () -> log.add("x"));
        clock.set(2000L);
        wheel.advance();
        eq(List.of(), log, "距 deadline 还有 1 秒时不应触发");
        clock.set(3000L);
        wheel.advance();
        eq(List.of("x"), log, "到 deadline 时应触发");
    }

    private static void firesAtExactDeadlineTick() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        wheel.schedule(5000L, () -> { });
        clock.set(4999L);
        eq(0, wheel.advance(), "4999ms 时不应触发");
        clock.set(5000L);
        eq(1, wheel.advance(), "5000ms 时应恰好触发");
    }

    private static void sameDeadlineKeepsInsertionOrder() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<Integer> order = new ArrayList<>();
        wheel.schedule(2000L, () -> order.add(0));
        wheel.schedule(2000L, () -> order.add(1));
        wheel.schedule(2000L, () -> order.add(2));
        clock.set(2000L);
        wheel.advance();
        eq(List.of(0, 1, 2), order, "同一到期时刻应按入队顺序触发");
    }

    private static void differentDelaysFireInDeadlineOrder() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> order = new ArrayList<>();
        wheel.schedule(3000L, () -> order.add("a"));
        wheel.schedule(1000L, () -> order.add("b"));
        wheel.schedule(2000L, () -> order.add("c"));
        clock.set(3000L);
        wheel.advance();
        eq(List.of("b", "c", "a"), order, "应按到期时间先后触发");
    }

    private static void fireOrderStableAcrossRuns() {
        eq(runOnceForOrder(), runOnceForOrder(), "同样入队顺序应给出同样的触发顺序");
    }

    private static List<Integer> runOnceForOrder() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = i;
            wheel.schedule(1000L + (i % 3) * 1000L, () -> order.add(id));
        }
        clock.set(3000L);
        wheel.advance();
        return order;
    }

    private static void statsScheduledCountsScheduleCalls() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        Stats before = wheel.stats();
        eq(0L, before.scheduled, "初始 scheduled 应为 0");
        eq(0L, before.fired, "初始 fired 应为 0");
        wheel.schedule(1000L, () -> { });
        wheel.schedule(2000L, () -> { });
        wheel.schedule(3000L, () -> { });
        Stats after = wheel.stats();
        eq(3L, after.scheduled, "排 3 条后 scheduled 应为 3");
        eq(0L, after.fired, "还没推进时 fired 应为 0");
    }

    private static void statsFiredCountsTriggers() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        wheel.schedule(1000L, () -> { });
        wheel.schedule(2000L, () -> { });
        wheel.schedule(3000L, () -> { });
        clock.set(1000L);
        wheel.advance();
        eq(1L, wheel.stats().fired, "推进 1 秒后 fired 应为 1");
        clock.set(3000L);
        wheel.advance();
        eq(3L, wheel.stats().fired, "推进 3 秒后 fired 应为 3");
    }

    private static void statsCancelledCountsCancelCalls() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        Tick a = wheel.schedule(1000L, () -> { });
        wheel.schedule(2000L, () -> { });
        check(wheel.cancel(a), "取消一个还没触发的排期应返回 true");
        Stats s = wheel.stats();
        eq(1L, s.cancelled, "成功取消一次后 cancelled 应为 1");
        eq(2L, s.scheduled, "scheduled 不受取消影响");
    }

    private static void cancelPreventsFiring() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        List<String> log = new ArrayList<>();
        Tick a = wheel.schedule(1000L, () -> log.add("a"));
        check(wheel.cancel(a), "取消应成功");
        clock.set(10_000L);
        eq(0, wheel.advance(), "被取消的任务不应触发");
        eq(List.of(), log, "日志应为空");
    }

    private static void cancelNullReturnsFalse() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        check(!wheel.cancel(null), "取消 null 句柄应返回 false 而不是抛异常");
    }

    private static void negativeDelayRejected() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        expectBadArgument(() -> wheel.schedule(-1L, () -> { }), "负的 delayMillis");
    }

    private static void nullTaskRejected() {
        FakeClock clock = new FakeClock(0L);
        TimerWheel wheel = new TimerWheel(clock);
        expectNpe(() -> wheel.schedule(1000L, null), "null task");
    }

    private TimerWheelTest() {
    }
}
