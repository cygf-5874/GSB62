package timerwheel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个定时轮。
 *
 * <p>时间不读墙钟，一律从构造时注入的 {@link Clock} 取。
 * 对外语义以 README 的「对外契约」一节为准；这里是按当前代码走出来的行为。
 */
public final class TimerWheel {

    /** 轮盘的槽位数。 */
    private static final int SLOTS = 64;

    /** 一个槽位代表的时长（毫秒）。 */
    private static final long TICK_MILLIS = 1000L;

    private final Clock clock;

    /** 所有还挂着的排期，按入队顺序。 */
    private final List<Tick> pending = new ArrayList<>();

    /** 句柄 id -> 排期，用于 cancel。 */
    private final Map<Long, Tick> byId = new HashMap<>();

    /** 已经推进到的 tick 号。 */
    private long currentTick;

    private long nextId;
    private long scheduled;
    private long fired;
    private long cancelled;
    private long liveHandles;
    private long scannedNodes;

    public TimerWheel(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.currentTick = Math.floorDiv(clock.nowMillis(), TICK_MILLIS) - 1L;
    }

    /** 注入时钟当前的时间。 */
    public long nowMillis() {
        return clock.nowMillis();
    }

    /**
     * 排一个 {@code delayMillis} 毫秒后触发的任务。
     *
     * @throws NullPointerException      {@code task} 为 null
     * @throws IllegalArgumentException  {@code delayMillis} 为负
     */
    public Tick schedule(long delayMillis, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis 必须非负：" + delayMillis);
        }
        long deadline = clock.nowMillis() + delayMillis;
        Tick tick = new Tick(nextId++, deadline, task);
        pending.add(tick);
        byId.put(tick.id(), tick);
        scheduled++;
        liveHandles++;
        return tick;
    }

    /** 取消一个还没触发的排期，返回是否取消成功。 */
    public boolean cancel(Tick tick) {
        if (tick == null) {
            return false;
        }
        if (byId.containsKey(tick.id())) {
            Tick node = byId.remove(tick.id());
            pending.remove(node);
            node.cancelled = true;
            cancelled++;
            liveHandles--;
        }
        return true;
    }

    /** 把时钟推进到注入 {@link Clock} 的当前时间，返回本次触发了几条。 */
    public int advance() {
        long target = Math.floorDiv(clock.nowMillis(), TICK_MILLIS);
        int count = 0;
        while (currentTick < target) {
            currentTick++;
            count += sweep(currentTick);
        }
        return count;
    }

    /** 处理一个 tick 上的到期排期。 */
    private int sweep(long tick) {
        int count = 0;
        List<Tick> keep = new ArrayList<>(pending.size());
        for (Tick tick0 : pending) {
            scannedNodes++;
            if (slotOf(tick0.deadlineMillis()) == Math.floorMod(tick, SLOTS)) {
                byId.remove(tick0.id());
                tick0.fired = true;
                fired++;
                count++;
                tick0.task().run();
            } else {
                keep.add(tick0);
            }
        }
        pending.clear();
        pending.addAll(keep);
        return count;
    }

    /** 某个绝对到期时刻落在哪个槽。 */
    private static long slotOf(long deadlineMillis) {
        return Math.floorMod(Math.floorDiv(deadlineMillis, TICK_MILLIS), SLOTS);
    }

    /** 计数快照。 */
    public Stats stats() {
        return new Stats(scheduled, fired, cancelled, liveHandles, scannedNodes);
    }
}
