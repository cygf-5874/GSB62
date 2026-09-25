package timerwheel;

/**
 * 时间源。时间轮不读墙钟：所有「现在」都从构造时注入的实现里取。
 *
 * <p>这样可以给「什么时候到期」写确定性的用例，而不依赖机器速度。
 */
public interface Clock {

    /** 当前时间，单位毫秒。 */
    long nowMillis();

    /** 一个永远停在 {@code millis} 的固定时钟。 */
    static Clock fixed(long millis) {
        return () -> millis;
    }
}
