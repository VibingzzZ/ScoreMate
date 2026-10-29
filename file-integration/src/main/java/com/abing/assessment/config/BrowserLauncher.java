package com.abing.assessment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * 启动完成后自动打开浏览器。
 *
 * <p>这一步不是"锦上添花"，是形态成立的必要条件：本程序没有窗口、没有托盘图标，
 * 如果不打开浏览器，用户双击 exe 之后的观感就是"点了没反应"（已实测发生）。
 *
 * <p>端口必须从运行时拿，不能写死——端口是探测出来的，而且可能因为被占用而递增。
 */
@Component
public class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    /** 测试或无头环境可以用 assessment.open-browser=false 关掉 */
    @Value("${assessment.open-browser:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void openOnReady(ApplicationReadyEvent event) {
        if (!enabled) {
            log.info("assessment.open-browser=false，跳过打开浏览器");
            return;
        }
        String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port");
        if (port == null || port.isBlank()) {
            port = System.getProperty("assessment.port");
        }
        if (port == null || port.isBlank()) {
            log.warn("拿不到实际端口，跳过打开浏览器");
            return;
        }
        try {
            open(Integer.parseInt(port));
        } catch (NumberFormatException e) {
            log.warn("端口值异常（{}），跳过打开浏览器", port);
        }
    }

    /** 打开 http://127.0.0.1:{port}/，优先用系统默认浏览器，失败则走系统命令兜底 */
    public static void open(int port) {
        String url = "http://127.0.0.1:" + port + "/";
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                log.info("已打开浏览器：{}", url);
                return;
            }
        } catch (Exception e) {
            log.warn("Desktop.browse 不可用（{}），尝试系统命令", e.toString());
        }
        fallbackOpen(url);
    }

    private static void fallbackOpen(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
            log.info("已通过系统命令打开：{}", url);
        } catch (Exception e) {
            log.warn("自动打开浏览器失败，请手动访问 {}", url);
        }
    }
}
