package com.abing.assessment;

import com.abing.assessment.config.BrowserLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AssessmentApplication {

    private static final Logger log = LoggerFactory.getLogger(AssessmentApplication.class);

    /** 端口探测区间：范围小、好记，日志里一眼能认出来是自己的 */
    private static final int PORT_FROM = 18080;
    private static final int PORT_TO = 18120;

    /** 持有到进程退出，由操作系统自动释放，不要手动 close */
    private static FileChannel lockChannel;
    private static FileLock instanceLock;

    public static void main(String[] args) {
        // ── 1. 单实例检查 ────────────────────────────────────────────────
        // 用户会连点 exe（实测连点 8 次起了 8 个实例，端口一路涨到 18085）。
        // 双击的语义应该是"打开界面"，不是"再启动一个服务"，所以先看有没有在跑的。
        if (!tryAcquireInstanceLock()) {
            log.info("已有实例在运行，尝试直接打开它的界面…");
            Integer running = waitForRunningInstance(5_000);
            if (running != null) {
                log.info("已有实例：http://127.0.0.1:{}/", running);
                if (isOpenBrowserEnabled()) {
                    BrowserLauncher.open(running);
                }
            } else {
                log.warn("检测到已有实例但暂时连不上。请等几秒再双击，或手动访问 http://127.0.0.1:{}", PORT_FROM);
            }
            return;
        }

        // ── 2. 自己选端口 ────────────────────────────────────────────────
        // 不用 server.port=0：Tomcat 拿到随机端口后要关掉探测 socket 再重新 bind，
        // 中间那一瞬可能被别的进程抢走。本机实测报 "Port 50545 was already in use"，
        // 而 50545 其实是别的程序占的，这种错误对用户毫无意义。
        int port = findAvailablePort(PORT_FROM, PORT_TO);
        System.setProperty("server.port", String.valueOf(port));
        System.setProperty("assessment.port", String.valueOf(port));

        SpringApplication.run(AssessmentApplication.class, args);
    }

    /**
     * 尝试独占实例锁。拿不到说明已有实例（启动中或已运行）。
     * <p>锁文件放在 LOCALAPPDATA 而不是程序目录：Program Files 下不可写。
     */
    private static boolean tryAcquireInstanceLock() {
        try {
            Path lockPath = lockFilePath();
            Files.createDirectories(lockPath.getParent());
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            return instanceLock != null;
        } catch (Exception e) {
            // 锁不可用（权限/文件被占）时不能把用户挡在外面，退化为允许启动
            log.warn("单实例锁不可用（{}），继续启动", e.toString());
            return true;
        }
    }

    /** 测试/无头环境可用 -Dassessment.open-browser=false 关闭 */
    private static boolean isOpenBrowserEnabled() {
        String v = System.getProperty("assessment.open-browser");
        return v == null || Boolean.parseBoolean(v);
    }

    private static Path lockFilePath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData == null || localAppData.isBlank())
                ? Paths.get(System.getProperty("user.home"), ".assessment")
                : Paths.get(localAppData, "Assessment");
        return base.resolve("app.lock");
    }

    /** 等已有实例就绪（它可能还在启动中），最多等 timeoutMs */
    private static Integer waitForRunningInstance(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int port = PORT_FROM; port <= PORT_TO; port++) {
                if (isOurApp(port)) {
                    return port;
                }
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 判断某个端口上跑的是不是我们自己。
     * <p>只认 /api/status 里的 app 标识，避免把"别的程序恰好占了这个端口"误判成自己的实例。
     */
    private static boolean isOurApp(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/status")
                    .toURL().openConnection();
            conn.setConnectTimeout(300);
            conn.setReadTimeout(600);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                return false;
            }
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return body.contains("\"assessment\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 在 [from, to] 里找一个当前能绑在回环地址上的端口。
     * <p>只绑 127.0.0.1：既满足"别人访问不到"，也避免和局域网程序抢端口。
     */
    private static int findAvailablePort(int from, int to) {
        for (int port = from; port <= to; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException ignored) {
                // 这个端口被占用，试下一个
            }
        }
        throw new IllegalStateException("端口 " + from + "-" + to + " 全部被占用，请关闭占用程序后重试");
    }
}
