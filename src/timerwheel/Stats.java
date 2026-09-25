package timerwheel;

/** {@link TimerWheel#stats()} 返回的计数快照。字段不可变。 */
public final class Stats {

    /** 累计的 {@code schedule(...)} 调用次数。 */
    public final long scheduled;

    /** 累计的触发次数。 */
    public final long fired;

    /** 累计的成功取消次数。 */
    public final long cancelled;

    /** 当前仍挂在轮盘上、既没触发也没被取消的句柄数。 */
    public final long liveHandles;

    /** 累计被检查过的节点数（摊销成本的度量，与墙钟无关）。 */
    public final long scannedNodes;

    public Stats(long scheduled, long fired, long cancelled, long liveHandles, long scannedNodes) {
        this.scheduled = scheduled;
        this.fired = fired;
        this.cancelled = cancelled;
        this.liveHandles = liveHandles;
        this.scannedNodes = scannedNodes;
    }

    @Override
    public String toString() {
        return "Stats{scheduled=" + scheduled + ", fired=" + fired + ", cancelled=" + cancelled
                + ", liveHandles=" + liveHandles + ", scannedNodes=" + scannedNodes + "}";
    }
}
