package timerwheel;

/**
 * 一次排期的句柄。{@link TimerWheel#schedule(long, Runnable)} 返回它，
 * {@link TimerWheel#cancel(Tick)} 用它取消。
 *
 * <p>句柄只描述「排了什么」：{@link #id()} 是排期编号（同一时间轮内单调递增，
 * 与入队顺序一致），{@link #deadlineMillis()} 是绝对到期时刻，
 * {@link #task()} 是要跑的任务。
 */
public final class Tick {

    private final long id;
    private final long deadlineMillis;
    private final Runnable task;

    /** 生命周期标记，由 {@link TimerWheel} 维护。 */
    boolean fired;
    boolean cancelled;

    Tick(long id, long deadlineMillis, Runnable task) {
        this.id = id;
        this.deadlineMillis = deadlineMillis;
        this.task = task;
    }

    /** 排期编号，同一时间轮内单调递增。 */
    public long id() {
        return id;
    }

    /** 绝对到期时刻（注入时钟的时间轴，单位毫秒）。 */
    public long deadlineMillis() {
        return deadlineMillis;
    }

    /** 到期时要跑的任务。 */
    public Runnable task() {
        return task;
    }

    /** 是否已经触发过。 */
    public boolean isFired() {
        return fired;
    }

    /** 是否已经被取消。 */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "Tick{id=" + id + ", deadlineMillis=" + deadlineMillis + "}";
    }
}
