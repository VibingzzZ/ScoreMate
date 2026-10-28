package com.abing.assessment.api;

import com.abing.assessment.common.Result;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 页面生命周期：用户关掉页面后，进程自动退出。
 *
 * <p>本工具是「双击 exe → 弹一个本地页面」的形态，页面没了就没必要留着服务进程
 * （白占端口、留个后台进程，用户会以为没关掉）。
 *
 * <p>但"没心跳就杀"是不可行的，三个场景会误杀，所以这里分三态判定：
 * <ol>
 *   <li><b>正常关页</b>：pagehide 时前端用 sendBeacon 打 /api/bye，
 *       宽限期（默认 8 秒）内没有新心跳 → 退出</li>
 *   <li><b>刷新页面</b>：刷新也会触发 pagehide，但新页面几百毫秒内就 /api/beat，
 *       心跳晚于 bye → 取消退出，刷新不会误杀</li>
 *   <li><b>切到后台</b>：浏览器会把后台标签的定时器限流到约 1 次/分钟，
 *       光靠心跳超时会误杀。所以前端在 visibilitychange 时主动上报 visible=false，
 *       隐藏状态不计超时（只是浏览器崩了才会被 {@code visible-timeout} 兜底）</li>
 * </ol>
 *
 * <p>多标签页：任何一个标签还在心跳，都会把 lastBeatAt 刷新到 bye 之后，从而取消退出。
 *
 * <p>从未有过心跳（0）时永不退出——这样开发期用 curl 调接口、
 * 或 {@code assessment.open-browser=false} 的无头启动不会被误杀。
 */
@RestController
public class PageLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(PageLifecycleController.class);

    private final boolean exitOnPageClose;
    private final long closeGraceMillis;
    private final long visibleTimeoutMillis;

    /** 最近一次心跳；0 = 从来没有页面连上来 */
    private final AtomicLong lastBeatAt = new AtomicLong(0);

    /** 收到 pagehide 的时刻；0 = 当前没有待处理的关闭信号 */
    private final AtomicLong byeAt = new AtomicLong(0);

    /** 页面当前是否可见（隐藏时不计心跳超时，原因见类注释第 3 条） */
    private volatile boolean pageVisible = true;

    private ScheduledExecutorService watcher;

    public PageLifecycleController(
            @Value("${assessment.exit-on-page-close:true}") boolean exitOnPageClose,
            @Value("${assessment.page-close-grace-seconds:8}") long graceSeconds,
            @Value("${assessment.visible-timeout-seconds:600}") long visibleTimeoutSeconds) {
        this.exitOnPageClose = exitOnPageClose;
        this.closeGraceMillis = graceSeconds * 1000;
        this.visibleTimeoutMillis = visibleTimeoutSeconds * 1000;
    }

    @PostConstruct
    void startWatcher() {
        if (!exitOnPageClose) {
            log.info("页面关闭自动退出：未启用（assessment.exit-on-page-close=false）");
            return;
        }
        // 用守护线程轮询，不引 @EnableScheduling：少一个全局开关，也不影响测试并发
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "page-lifecycle-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(this::check, 2, 2, TimeUnit.SECONDS);
        log.info("页面关闭自动退出：已启用（关闭页面后 {} 秒无心跳则退出）", closeGraceMillis / 1000);
    }

    @PreDestroy
    void stopWatcher() {
        if (watcher != null) {
            watcher.shutdownNow();
        }
    }

    /**
     * 心跳：页面开着就定期调，打开时、切前台/后台时各调一次。
     *
     * @param visible 页面是否可见（false = 切到后台，此时不计心跳超时）
     */
    @PostMapping("/api/beat")
    public Result<Void> beat(@RequestParam(defaultValue = "true") boolean visible) {
        lastBeatAt.set(System.currentTimeMillis());
        pageVisible = visible;
        return Result.success();
    }

    /**
     * 关闭信号：页面关闭/刷新前用 {@code navigator.sendBeacon} 发过来。
     * 只记录时刻，真正退出要等宽限期结束（给刷新和多标签留余地）。
     */
    @PostMapping("/api/bye")
    public Result<Void> bye() {
        byeAt.set(System.currentTimeMillis());
        return Result.success();
    }

    private void check() {
        try {
            long now = System.currentTimeMillis();
            long lastBeat = lastBeatAt.get();

            // 还没有页面连过：不退出（curl / 无头调试场景）
            if (lastBeat == 0) {
                return;
            }

            // ① 收到过关闭信号，且之后没有新心跳（刷新会立刻带来新心跳）
            long bye = byeAt.get();
            if (bye > 0 && lastBeat < bye && now - bye > closeGraceMillis) {
                exit("页面已关闭");
                return;
            }

            // ② 兜底：可见状态下长时间没心跳（浏览器崩溃/被强杀，没来得及发 bye）
            if (pageVisible && now - lastBeat > visibleTimeoutMillis) {
                exit("页面失联（" + visibleTimeoutMillis / 1000 + " 秒无心跳）");
            }
        } catch (Throwable t) {
            // 守护线程不能因为一次异常就悄悄死掉，否则"自动退出"会静默失效
            log.warn("页面生命周期检查异常", t);
        }
    }

    private void exit(String reason) {
        log.info("{}，程序退出", reason);
        System.exit(0);
    }
}
