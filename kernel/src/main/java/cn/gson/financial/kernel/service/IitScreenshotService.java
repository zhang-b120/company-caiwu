package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.entity.OcTask;
import cn.gson.financial.kernel.model.entity.TaxFilingStatus;
import cn.gson.financial.kernel.model.repo.OcTaskRepository;
import cn.gson.financial.kernel.model.repo.TaxFilingStatusRepository;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 个税截图看板服务。
 *
 * <p>截图来源：<b>{@code oc_task}（OpenClaw 任务队列）</b>。个税申报任务
 * （task_type = iit_declare）回调时，{@code updateOcTask} 在主事务把整个回调体写入
 * {@code result_json}，其中含顶层 {@code screenshot_urls} 数组。相比 task_iit_item
 * （best-effort 派生、可能缺失），oc_task 是主流程权威记录，申报跑过就一定有。</p>
 *
 * <p>同一账期可有多条带截图的 iit_declare 任务（salary/business + 重试，无论申报成功/失败，
 * 只要抓到截图就纳入——与任务工作台口径一致）；按 iit_type 只取 finished_at 最新一条，
 * 避免展示历史重试的旧截图。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IitScreenshotService extends AbsService {

    private static final String CACHE_SUBDIR = "iit-screenshot-cache";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Value("${customize-config.base-path:D:\\financialExcel}")
    private String basePath;

    /** OC 截图本地存储目录（与 OcCallbackController/UploadController 同一份配置）。 */
    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String ocScreenshotRoot;

    private final TaxFilingStatusRepository taxFilingStatusRepository;
    private final OcTaskRepository ocTaskRepository;

    /** 分页列表，每行 = 账套+账期的截图汇总 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResults<Map<String, Object>> listPage(Long currentUserId,
                                                      Map<String, Object> filters,
                                                      Page page) {
        Map<String, Object> params = new HashMap<>();
        if (filters != null) params.putAll(filters);
        params.put("currentUserId", currentUserId);
        // 空字符串转 null，让 Sqltoy #[...] 自动跳过
        params.replaceAll((k, v) -> (v instanceof String s && s.isBlank()) ? null : v);

        org.sagacity.sqltoy.model.Page<Map<String, Object>> sqltoyPage =
                new org.sagacity.sqltoy.model.Page(page.getPageSize(), page.getPage());
        org.sagacity.sqltoy.model.Page<Map<String, Object>> result =
                (org.sagacity.sqltoy.model.Page) lazyDao.findPageBySql(
                        sqltoyPage, "iit_screenshot_list", params, Map.class);

        List<Map<String, Object>> rows = result.getRows();
        rows.forEach(this::unpackScreenshots);
        return new PageResults<>(rows, page, result.getRecordCount());
    }

    /**
     * 把 SQL 的 ocTasksJson（{ocTaskId,iitType,finishedAt,urls} 列表）展开为
     * screenshots：[{ocTaskId, idx, iitType}]，每个 URL 一项。
     * “同账套同 iit_type 取 finished_at 最新一条” 已在 SQL 内用 ROW_NUMBER 完成，
     * 这里只负责展开 URL。取图时前端用 ocTaskId + idx。
     */
    private void unpackScreenshots(Map<String, Object> row) {
        Object raw = row.remove("ocTasksJson");
        List<Map<String, Object>> shots = new ArrayList<>();
        if (raw != null && !raw.toString().isBlank()) {
            try {
                List<Map<String, Object>> tasks = JSON_MAPPER.readValue(
                        raw.toString(), new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> t : tasks) {
                    if (t == null || t.get("ocTaskId") == null) continue;
                    long ocTaskId = Long.parseLong(String.valueOf(t.get("ocTaskId")));
                    String iitType = strOf(t.get("iitType"));
                    List<String> urls = toUrlList(t.get("urls"));
                    for (int i = 0; i < urls.size(); i++) {
                        String u = urls.get(i);
                        if (u == null || u.isBlank()) continue;
                        Map<String, Object> shot = new LinkedHashMap<>();
                        shot.put("ocTaskId", ocTaskId);
                        shot.put("idx", i);
                        shot.put("iitType", iitType);
                        shots.add(shot);
                    }
                }
            } catch (Exception e) {
                log.warn("IIT 截图列表解析失败: {}", e.getMessage());
            }
        }
        row.put("screenshots", shots);
    }

    private static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** urls 可能已是 List（SQL JSON_EXTRACT 嵌套数组），也可能是 JSON 字符串；都转 List&lt;String&gt;。 */
    private List<String> toUrlList(Object urlsRaw) {
        if (urlsRaw == null) return List.of();
        if (urlsRaw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) urlsRaw) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        String s = urlsRaw.toString();
        if (StrUtil.isBlank(s)) return List.of();
        try {
            return JSON_MAPPER.readValue(s, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 代理输出压缩图（thumb/medium）或原图（original），带磁盘缓存与 ETag。
     * 截图由 oc_task.id + idx 定位（result_json.screenshot_urls 的第 idx 个）。
     */
    public void serveImage(Long ocTaskId, int idx, String variant,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {
        OcTask oc = ocTaskRepository.findById(ocTaskId).orElse(null);
        String url = screenshotUrlAt(oc, idx);
        if (url == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "截图不存在");
            return;
        }

        // 原图：透传源（不压缩），用于下载备查
        if ("original".equalsIgnoreCase(variant)) {
            serveOriginal(url, ocTaskId, idx, response);
            return;
        }

        boolean isThumb = "thumb".equalsIgnoreCase(variant);
        String variantKey = isThumb ? "thumb" : "medium";
        int longEdge   = isThumb ? 320  : 1920;
        double quality = isThumb ? 0.75 : 0.80;

        // 缓存键纳入来源签名（url + finishedAt）：来源一变就换文件名，避免重申报后命中旧缓存。
        String srcSig = url + "|" + (oc.getFinishedAt() == null ? "" : oc.getFinishedAt());
        String sig = Integer.toHexString(srcSig.hashCode());

        File cacheDir = resolveCacheDir();
        String prefix = ocTaskId + "_" + idx + "_" + variantKey + "_";
        String cacheFileName = prefix + sig + ".jpg";
        File cacheFile = new File(cacheDir, cacheFileName);

        if (!cacheFile.exists()) {
            // 清理同 ocTask+idx+variant 的旧签名缓存
            File[] stale = cacheDir.listFiles((dir, name) ->
                    name.startsWith(prefix) && !name.equals(cacheFileName));
            if (stale != null) {
                for (File f : stale) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            buildCache(url, longEdge, quality, cacheFile);
        }

        if (!cacheFile.exists()) {
            // 压缩失败（格式不支持 / 写盘失败等）：回退直接内联输出原图，保证至少能显示
            log.warn("缩略图生成失败，回退原图内联输出: ocTaskId={} idx={} variant={}", ocTaskId, idx, variant);
            streamInline(url, response);
            return;
        }

        String etag = "\"" + cacheFile.length() + "-" + cacheFile.lastModified() + "\"";
        if (etag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        response.setContentType("image/jpeg");
        response.setHeader("Cache-Control", "max-age=86400, private");
        response.setHeader("ETag", etag);
        response.setContentLengthLong(cacheFile.length());
        try (InputStream in = new FileInputStream(cacheFile);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** 取 oc_task.result_json 里 screenshot_urls 第 idx 个 URL；越界/空/解析失败返回 null。 */
    private String screenshotUrlAt(OcTask oc, int idx) {
        if (oc == null || idx < 0 || StrUtil.isBlank(oc.getResultJson())) return null;
        try {
            Map<String, Object> result = JSON_MAPPER.readValue(
                    oc.getResultJson(), new TypeReference<Map<String, Object>>() {});
            List<String> urls = toUrlList(result.get("screenshot_urls"));
            if (idx >= urls.size()) return null;
            String u = urls.get(idx);
            return StrUtil.isBlank(u) ? null : u;
        } catch (Exception e) {
            log.warn("解析 oc_task.result_json 失败 id={} err={}", oc.getId(), e.getMessage());
            return null;
        }
    }

    /** 选一个可写的缓存目录：优先与 OC 截图同盘（必可写），其次 base-path，最后系统临时目录。 */
    private File resolveCacheDir() {
        List<File> candidates = new ArrayList<>();
        File ocParent = new File(ocScreenshotRoot).getParentFile();
        if (ocParent != null) candidates.add(new File(ocParent, CACHE_SUBDIR));
        candidates.add(new File(basePath, CACHE_SUBDIR));
        candidates.add(new File(System.getProperty("java.io.tmpdir"), CACHE_SUBDIR));
        for (File d : candidates) {
            if (d.isDirectory() || d.mkdirs()) return d;
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 压缩失败时的兜底：直接内联输出原图（保证能显示，只是未压缩）。 */
    private void streamInline(String url, HttpServletResponse response) throws IOException {
        String ext = guessExt(url);
        try (InputStream in = openSourceStream(url)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "截图不可用");
                return;
            }
            response.setContentType("png".equals(ext) ? "image/png" : "image/jpeg");
            response.setHeader("Cache-Control", "max-age=3600, private");
            try (OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            log.warn("IIT 原图内联输出失败 url={} err={}", url, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "截图读取失败");
            }
        }
    }

    /** 生成压缩图写入 cacheFile：源可为 http(s) 或本地文件 */
    private void buildCache(String screenshotUrl, int longEdge, double quality, File cacheFile) {
        try {
            InputStream srcStream = openSourceStream(screenshotUrl);
            if (srcStream == null) return;
            try (srcStream) {
                Thumbnails.of(srcStream)
                        .size(longEdge, longEdge)
                        .keepAspectRatio(true)
                        .outputFormat("jpg")
                        .outputQuality(quality)
                        .toFile(cacheFile);
            }
        } catch (Throwable e) {
            // 用 Throwable 兜住 ImageIO 某些格式抛的 Error；完整记录原因便于定位（最后一个参数打印堆栈）
            log.warn("IIT 截图压缩失败 url={} : {}", screenshotUrl, e.toString(), e);
        }
    }

    /** 原图透传（下载备查），不做压缩；复用 openSourceStream 的白名单/网络逻辑。 */
    private void serveOriginal(String url, Long ocTaskId, int idx, HttpServletResponse response) throws IOException {
        String ext = guessExt(url);
        try (InputStream in = openSourceStream(url)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "原图不可用");
                return;
            }
            response.setContentType("png".equals(ext) ? "image/png" : "image/jpeg");
            response.setHeader("Cache-Control", "max-age=86400, private");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"iit_" + ocTaskId + "_" + idx + "." + ext + "\"");
            try (OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            log.warn("IIT 原图读取失败 url={} err={}", url, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "原图读取失败");
            }
        }
    }

    /** 从 url 猜扩展名（去掉 query 串），仅认 png/jpg/jpeg/webp，默认 jpg。 */
    private static String guessExt(String url) {
        String path = url.split("\\?", 2)[0];
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase();
            if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("webp")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return "jpg";
    }

    /**
     * 打开截图源输入流。
     * <p>OC 截图（URL 形如 {@code .../oc-screenshots/xxx.png}）本质是本机文件
     * （{oc.screenshot-root}/xxx），UploadController 才对外用 HTTP 提供。这里**优先按本地文件读取**，
     * 避免服务端反向 HTTP 拉自身（/oc-screenshots 需登录态、且公网回环易失败）导致取图报错。</p>
     */
    private InputStream openSourceStream(String screenshotUrl) throws IOException {
        // 1) OC 截图：从 URL 截出 /oc-screenshots/ 后的子路径，直接读本地盘
        int marker = screenshotUrl.indexOf("/oc-screenshots/");
        if (marker >= 0) {
            String sub = screenshotUrl.substring(marker + "/oc-screenshots/".length());
            int q = sub.indexOf('?');
            if (q >= 0) sub = sub.substring(0, q);
            File root = new File(ocScreenshotRoot).getCanonicalFile();
            File f = new File(root, sub).getCanonicalFile();
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                log.warn("OC 截图路径越界，拒绝: {}", screenshotUrl);
                return null;
            }
            if (f.exists() && f.isFile()) {
                return new FileInputStream(f);
            }
            log.warn("OC 截图本地文件不存在，回退 HTTP: {} -> {}", screenshotUrl, f);
            // 落空则继续往下走 HTTP 兜底
        }

        // 2) 其它 http(s)：走网络兜底
        if (screenshotUrl.startsWith("http://") || screenshotUrl.startsWith("https://")) {
            HttpURLConnection conn = (HttpURLConnection) new URL(screenshotUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            return conn.getInputStream();
        }

        // 3) 相对/绝对文件路径：按 base-path 白名单解析，防路径穿越
        File root = new File(basePath).getCanonicalFile();
        File f = new File(screenshotUrl).isAbsolute()
                ? new File(screenshotUrl).getCanonicalFile()
                : new File(root, screenshotUrl).getCanonicalFile();
        if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) {
            log.warn("IIT 截图路径越界，拒绝: {}", screenshotUrl);
            return null;
        }
        if (!f.exists()) {
            log.warn("IIT 截图文件不存在: {}", f);
            return null;
        }
        return new FileInputStream(f);
    }

    /** 设置/清除发送状态（upsert tax_filing_status 三列） */
    @Transactional
    public void setSendStatus(Long accountSetId, String taxPeriod, boolean sent, Long userId) {
        TaxFilingStatus status = taxFilingStatusRepository
                .findByAccountSetIdAndTaxPeriod(accountSetId, taxPeriod)
                .orElseGet(() -> {
                    TaxFilingStatus s = new TaxFilingStatus();
                    s.setAccountSetId(accountSetId);
                    s.setTaxPeriod(taxPeriod);
                    return s;
                });
        status.setIitScreenshotSent(sent);
        status.setIitScreenshotSentAt(sent ? LocalDateTime.now() : null);
        status.setIitScreenshotSentBy(sent ? userId : null);
        taxFilingStatusRepository.save(status);
    }
}
