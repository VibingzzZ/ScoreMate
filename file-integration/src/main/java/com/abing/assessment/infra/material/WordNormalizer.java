package com.abing.assessment.infra.material;

import com.abing.assessment.common.BusinessException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Word(.doc / .docx) → 图片列表。
 *
 * <p>S3 落地：Word 不能像图片那样直接喂视觉模型，所以先借 LibreOffice 把文档转成 PDF，
 * 再复用 {@link PdfNormalizer} 渲染成每一页一张 PNG。这样就和 PDF 走完全相同的下游，
 * 不用为 Word 单独写识别分支。
 *
 * <p>降级：如果用户电脑没装 LibreOffice，转不了，就直接返回友好的 415 提示「请另存为 PDF」，
 * 而不是抛一个看不懂的 500 堆栈。
 */
@Component
public class WordNormalizer implements MaterialNormalizer {

    /** LibreOffice 首次启动要加载 JRE，给足超时 */
    private static final long CONVERT_TIMEOUT_SECONDS = 90;

    private final PdfNormalizer pdfNormalizer;

    public WordNormalizer(PdfNormalizer pdfNormalizer) {
        this.pdfNormalizer = pdfNormalizer;
    }

    @Override
    public boolean supports(String ext, String mime) {
        if (mime != null && (mime.equals("application/msword")
                || mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || mime.equals("application/vnd.ms-word"))) {
            return true;
        }
        return ext != null && (ext.equalsIgnoreCase("doc") || ext.equalsIgnoreCase("docx"));
    }

    @Override
    public List<byte[]> toImages(InputStream in, String originalName) throws IOException {
        Path soffice = detectSoffice().orElseThrow(() -> new BusinessException(
                "当前电脑未安装 LibreOffice，无法识别 Word。请把 Word 另存为 PDF 后再上传", 415));

        // 落临时目录：输入用真实扩展名（LibreOffice 靠扩展名选导入过滤器），输出 PDF 由它自己命名
        Path workDir = Files.createTempDirectory("assess-word-");
        Path src = workDir.resolve("source." + extOf(originalName));
        Files.copy(in, src, StandardCopyOption.REPLACE_EXISTING);

        // 独立的 LibreOffice profile，避免和本机已打开的 LibreOffice 抢同一个 profile 锁
        Path loProfile = workDir.resolve("lo-profile");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(soffice.toString());
            cmd.add("--headless");
            cmd.add("--invisible");
            cmd.add("-env:UserInstallation=" + loProfile.toUri());
            cmd.add("--convert-to");
            cmd.add("pdf");
            cmd.add("--outdir");
            cmd.add(workDir.toString());
            cmd.add(src.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 把 LibreOffice 在 headless 下往 stdout 打的一大堆日志吃掉
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            boolean finished = p.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new BusinessException("Word 转 PDF 超时，请稍后再试或直接上传 PDF", 500);
            }
            if (p.exitValue() != 0) {
                throw new BusinessException("Word 转 PDF 失败（LibreOffice 退出码 " + p.exitValue() + "）", 500);
            }

            // LibreOffice 输出：source.docx -> source.pdf
            Path pdf = workDir.resolve("source.pdf");
            if (!Files.exists(pdf)) {
                pdf = Files.list(workDir)
                        .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("Word 转 PDF 未生成文件", 500));
            }

            try (InputStream pdfIn = Files.newInputStream(pdf)) {
                return pdfNormalizer.toImages(pdfIn, "source.pdf");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Word 转 PDF 被中断", 500);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static String extOf(String name) {
        if (name == null) {
            return "docx";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "docx";
        }
        String e = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ("doc".equals(e) || "docx".equals(e)) ? e : "docx";
    }

    /** 探测本机 LibreOffice 可执行文件，找不到返回空 */
    private static Optional<Path> detectSoffice() {
        List<String> binNames = isWindows()
                ? Arrays.asList("soffice.exe", "soffice")
                : Collections.singletonList("soffice");

        // 1) PATH 里找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                for (String bin : binNames) {
                    Path p = Paths.get(dir, bin);
                    if (Files.isRegularFile(p)) {
                        return Optional.of(p);
                    }
                }
            }
        }

        // 2) 常见安装目录（跨平台兜底）
        List<Path> candidates = new ArrayList<>();
        addIfSet(candidates, "ProgramFiles", "LibreOffice/program");
        addIfSet(candidates, "ProgramFiles(x86)", "LibreOffice/program");
        addIfSet(candidates, "ProgramW6432", "LibreOffice/program");
        candidates.add(Paths.get("/Applications/LibreOffice.app/Contents/MacOS"));
        candidates.add(Paths.get("/usr/bin"));
        candidates.add(Paths.get("/opt/libreoffice/program"));
        candidates.add(Paths.get("/snap/libreoffice/current/program"));

        for (Path dir : candidates) {
            for (String bin : binNames) {
                Path p = dir.resolve(bin);
                if (Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    private static void addIfSet(List<Path> out, String envKey, String sub) {
        String v = System.getenv(envKey);
        if (v != null && !v.isBlank()) {
            out.add(Paths.get(v, sub));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteRecursively(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                if (Files.isDirectory(child)) {
                    deleteRecursively(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        } catch (IOException ignored) { }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) { }
    }
}
