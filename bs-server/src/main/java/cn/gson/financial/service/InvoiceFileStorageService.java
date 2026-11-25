package cn.gson.financial.service;

import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * FN-DLV-T1（182）— 发票文件中心存储（local 后端存盘 + 可访问 URL）。
 *
 * <p>套 OC 截图同一套范式：存到 {@code invoice-files.root}（{@code E:\qykj\file\invoice-files}，与
 * {@code oc.screenshot-root} 同在 {@code E:\qykj\file\} 下），URL 经同一台 8888/api 文件服务下回。
 *
 * <p>存储布局：{@code <root>\<orderNo>\<kind>.<ext>}；URL：
 * {@code ${base-url}/invoice-files/<orderNo>/<kind>.<ext>}。
 *
 * <p>本类是 <b>OSS 配置切换点</b>——未来切对象存储只改本类，控制器/DTO 不动。
 *
 * <p>安全：orderNo/kind 强校验 + 落盘/下载双 canonical 校验，杜绝 {@code ../} 路径穿越任意文件读写。
 */
@Slf4j
@Service
public class InvoiceFileStorageService {

    /** 发票文件存储根目录（Windows 路径；🟥 不准用 /data 等 Linux 路径、不准放 C 盘/jar 临时目录）。 */
    @Value("${invoice-files.root:E:\\qykj\\file\\invoice-files}")
    String root;

    /** 发票文件 URL 公网基础地址（同 {@code oc.screenshot-base-url} 那台 8888/api）。 */
    @Value("${invoice-files.base-url:http://150.139.141.142:8888/api}")
    String baseUrl;

    /**
     * 275：每票 evidence 唯一命名（不覆盖）会累积——每个 orderNo 目录仅保留最近 K 张，
     * store 后删多余旧 {@code evidence-*.png} 防堆盘（{@code ≤0} 视为默认 5）。
     */
    @Value("${invoice-files.evidence-keep:5}")
    int evidenceKeep;

    /** 允许的文件类别（与 BT 上传 kind 对齐）。 */
    static final Set<String> ALLOWED_KINDS = Set.of("pdf", "ofd", "xml", "qrcode", "evidence");

    /** orderNo 安全字符：仅字母数字下划线连字符、1-64 位——杜绝路径穿越（{@code ../}、绝对路径、盘符）。 */
    private static final Pattern SAFE_ORDER_NO = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * 275：存证截图文件名匹配——同时命中老固定名 {@code evidence.png} 与新唯一名
     * {@code evidence-<epochMillis>-<rand>.png}，用于取最新 / 留 K 张清理。
     */
    private static final Pattern EVIDENCE_FILE = Pattern.compile("^evidence(-[A-Za-z0-9]+)*\\.png$");

    /** 存盘结果。 */
    public static class StoredFile {
        public final String url;
        public final String orderNo;
        public final String kind;
        public final String fileName;

        StoredFile(String url, String orderNo, String kind, String fileName) {
            this.url = url;
            this.orderNo = orderNo;
            this.kind = kind;
            this.fileName = fileName;
        }
    }

    /**
     * 存一个发票文件到 {@code <root>\<orderNo>\<kind>.<ext>} 并返回可访问 URL。
     *
     * @throws IllegalArgumentException orderNo/kind/file 非法（控制器映射 400）
     * @throws IOException              落盘失败（控制器映射 500）
     */
    public StoredFile store(String orderNo, String kind, MultipartFile file) throws IOException {
        String safeOrderNo = requireSafeOrderNo(orderNo);
        String safeKind = requireKind(kind);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        String ext = resolveExt(file.getOriginalFilename(), safeKind);
        // 275：仅 evidence 唯一命名 evidence-<epochMillis>-<rand>.png（一票多次失败/成功各留一张、不覆盖、
        // URL 每次不同→天然去缓存）。pdf/ofd/xml/qrcode 仍 <kind>.<ext> 固定名+覆盖（一票一终态，零回归）。
        String fileName = "evidence".equals(safeKind)
                ? "evidence-" + System.currentTimeMillis() + "-" + randomToken() + "." + ext
                : safeKind + "." + ext;

        File dir = new File(root, safeOrderNo);
        File target = new File(dir, fileName);
        ensureWithinRoot(target);                    // 落盘前 canonical 兜底
        Files.createDirectories(dir.toPath());
        file.transferTo(target);                     // evidence 走唯一名不覆盖；其它 kind 同名重传即替换

        if ("evidence".equals(safeKind)) {
            pruneOldEvidence(dir);                   // 275 红线4：每票仅留最近 K 张，删多余旧图防堆盘
        }

        String url = buildUrl(safeOrderNo, fileName);
        log.info("[FN-DLV-T1] 发票文件已存盘 orderNo={} kind={} file={} size={} url={}",
                safeOrderNo, safeKind, fileName, file.getSize(), url);
        return new StoredFile(url, safeOrderNo, safeKind, fileName);
    }

    /**
     * 268/275：该订单存证截图 URL——返目录内**最新**的 {@code evidence*.png}，不存在则 {@code null}（不返死链）。
     *
     * <p>275 起 evidence 唯一命名（{@code evidence-<ts>-<rand>.png}），此处 glob 同时兼容老 {@code evidence.png}
     * 与新唯一名、按 mtime 倒序取最新——270 回写列空（best-effort 失败/老票）时仍能 stat 兜底到最新那张。
     *
     * <p>纯只读：仅列目录 + 拼 URL，不落盘/不改任何状态。orderNo 非法或路径越界一律返 {@code null}（不抛）。
     */
    public String evidenceUrlIfPresent(String orderNo) {
        String safe;
        try {
            safe = requireSafeOrderNo(orderNo);
        } catch (IllegalArgumentException e) {
            return null;
        }
        File dir = new File(root, safe);
        String latest = latestEvidenceFileName(dir);
        if (latest == null) {
            return null;
        }
        File f = new File(dir, latest);
        try {
            ensureWithinRoot(f);                     // canonical 兜底：越界即视为不存在
        } catch (IllegalArgumentException e) {
            return null;
        }
        return f.isFile() ? buildUrl(safe, latest) : null;
    }

    /** 列出 {@code <orderNo>/} 下所有 {@code evidence*.png}（老固定名+新唯一名），按 mtime 倒序取最新文件名；无则 {@code null}。 */
    private static String latestEvidenceFileName(File dir) {
        File[] files = dir.listFiles((d, name) -> EVIDENCE_FILE.matcher(name).matches());
        if (files == null || files.length == 0) {
            return null;
        }
        File latest = null;
        for (File f : files) {
            if (f.isFile() && (latest == null || isNewer(f, latest))) {
                latest = f;
            }
        }
        return latest == null ? null : latest.getName();
    }

    /** mtime 更晚为新；mtime 相同（同毫秒/低精度 FS）时文件名字典序更大为新（唯一名内含 epochMillis，仍稳定）。 */
    private static boolean isNewer(File a, File b) {
        long ma = a.lastModified(), mb = b.lastModified();
        return ma != mb ? ma > mb : a.getName().compareTo(b.getName()) > 0;
    }

    /** 拼可访问 URL：{@code ${base-url}/invoice-files/<orderNo>/<fileName>}。 */
    public String buildUrl(String orderNo, String fileName) {
        String base = StrUtil.removeSuffix(StrUtil.nullToEmpty(baseUrl), "/");
        return base + "/invoice-files/" + orderNo + "/" + fileName;
    }

    /**
     * 下载：把 URL 子路径（{@code /invoice-files/} 之后部分）解析为 {@code root} 下文件。
     * <p>canonical 校验：解析后路径必须仍在 {@code root} 内，否则视为路径穿越，返回 {@code null}（控制器 403）。
     */
    public File resolveForDownload(String subPath) {
        if (StrUtil.isBlank(subPath)) return null;
        File file = new File(root, subPath);
        try {
            String canonical = file.getCanonicalPath();
            String allowedRoot = new File(root).getCanonicalPath();
            if (!canonical.startsWith(allowedRoot)) {
                log.warn("[FN-DLV-T1] 发票文件下载路径越界拦截 subPath={}", subPath);
                return null;
            }
        } catch (IOException e) {
            log.warn("[FN-DLV-T1] 发票文件下载路径解析失败 subPath={}", subPath);
            return null;
        }
        return file;
    }

    /** 落盘 canonical 兜底：目标必须落在 root 下，否则拒绝（防 orderNo/文件名意外越界）。 */
    private void ensureWithinRoot(File target) {
        try {
            String canonical = target.getCanonicalPath();
            String allowedRoot = new File(root).getCanonicalPath();
            if (!canonical.startsWith(allowedRoot)) {
                throw new IllegalArgumentException("目标路径越界");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径解析失败", e);
        }
    }

    private static String requireSafeOrderNo(String orderNo) {
        String v = StrUtil.trim(orderNo);
        if (StrUtil.isBlank(v) || !SAFE_ORDER_NO.matcher(v).matches()) {
            throw new IllegalArgumentException("orderNo 非法（仅允许字母/数字/下划线/连字符，长度 1-64）");
        }
        return v;
    }

    private static String requireKind(String kind) {
        String v = StrUtil.isBlank(kind) ? null : StrUtil.trim(kind).toLowerCase();
        if (v == null || !ALLOWED_KINDS.contains(v)) {
            throw new IllegalArgumentException("kind 非法（允许 pdf/ofd/xml/qrcode/evidence）");
        }
        return v;
    }

    /**
     * 275 红线4：每票 evidence 仅保留最近 K 张（按 mtime 倒序），删多余旧 {@code evidence-*.png} 防堆盘。
     * best-effort：删失败/异常只 warn，不影响本次上传（文件已落盘）。{@code K≤0} 时回退默认 5。
     */
    private void pruneOldEvidence(File dir) {
        int keep = evidenceKeep > 0 ? evidenceKeep : 5;
        File[] files = dir.listFiles((d, name) -> EVIDENCE_FILE.matcher(name).matches());
        if (files == null || files.length <= keep) {
            return;
        }
        Arrays.sort(files, (a, b) -> isNewer(a, b) ? -1 : (isNewer(b, a) ? 1 : 0));   // mtime 倒序：新→旧
        for (int i = keep; i < files.length; i++) {
            try {
                if (!files[i].delete()) {
                    log.warn("[FN-EVIDENCE-UNIQUE] 旧存证清理删除失败 file={}", files[i].getName());
                }
            } catch (Exception e) {
                log.warn("[FN-EVIDENCE-UNIQUE] 旧存证清理异常 file={}：{}", files[i].getName(), e.getMessage());
            }
        }
    }

    /** 唯一名随机后缀：4 位数字，兜同毫秒并发撞名（红线5）。 */
    private static String randomToken() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 后缀取自原文件名；缺失/非法时兜底：qrcode/evidence→png，其余=kind 本身。 */
    private static String resolveExt(String originalFilename, String kind) {
        String suffix = FileNameUtil.getSuffix(originalFilename);
        if (StrUtil.isNotBlank(suffix) && suffix.matches("[A-Za-z0-9]{1,8}")) {
            return suffix.toLowerCase();
        }
        return ("qrcode".equals(kind) || "evidence".equals(kind)) ? "png" : kind;
    }
}
