package cn.gson.financial.task.workbench.export;

import cn.gson.financial.kernel.model.entity.TaskIitItem;
import cn.gson.financial.kernel.model.entity.TaskPeriod;
import cn.gson.financial.kernel.model.repo.TaskIitItemRepository;
import cn.gson.financial.kernel.model.repo.TaskPeriodRepository;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 个税申报情况导出（异步）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>全局同一时刻仅允许 1 个任务（{@link #currentTaskId}）</li>
 *   <li>单独的单线程池执行实际导出（避免占用 Spring 默认池）</li>
 *   <li>任务状态保存在内存 map，TTL 1 小时后自动清理本地文件</li>
 *   <li>xlsx 中每行截图按缩略图水平排列嵌入；原图全部打包到 zip 的 {@code screenshots/} 目录</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IitExportService {

    private final TaskWorkbenchService taskWorkbenchService;
    private final TaskPeriodRepository taskPeriodRepository;
    private final TaskIitItemRepository taskIitItemRepository;

    /** 截图本地存储根目录（与 OcCallbackController 同一配置项） */
    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String screenshotRoot;

    /** 导出生成物临时目录 */
    @Value("${oc.export-root:E:\\qykj\\file\\iit-export}")
    private String exportRoot;

    /** 任务表 */
    private final Map<String, IitExportTaskStatus> tasks = new ConcurrentHashMap<>();
    /** 全局当前正在运行的任务 ID（实现"只允许1个任务"） */
    private final AtomicReference<String> currentTaskId = new AtomicReference<>();
    /** 单线程异步执行池 */
    private ExecutorService executor;

    /** 任务存活时间 */
    private static final Duration TASK_TTL = Duration.ofHours(1);
    /** 缩略图宽 px */
    private static final int THUMB_WIDTH_PX = 220;
    /** 缩略图高 px */
    private static final int THUMB_HEIGHT_PX = 140;
    /** 截图列宽度容纳的张数（超过仍可嵌入，只是显示溢出当前列） */
    private static final int SCREENSHOT_COL_CAPACITY = 8;
    /** 1 px → EMU */
    private static final int EMU_PER_PX = 9525;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @PostConstruct
    public void init() {
        FileUtil.mkdir(exportRoot);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "iit-export-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("IitExportService 初始化：exportRoot={}, screenshotRoot={}", exportRoot, screenshotRoot);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────
    // 公共 API
    // ─────────────────────────────────────────────────────────

    /**
     * 提交导出任务。
     * @return taskId；若已有任务在跑则抛 {@link IllegalStateException}
     */
    public String submitExport(IitExportRequest req, Long userId, String userName) {
        String existing = currentTaskId.get();
        if (existing != null) {
            IitExportTaskStatus old = tasks.get(existing);
            if (old != null && IitExportTaskStatus.RUNNING.equals(old.getStatus())) {
                throw new IllegalStateException("已有导出任务进行中（提交人："
                        + (old.getUserName() == null ? "未知" : old.getUserName())
                        + "），请稍后再试");
            }
            currentTaskId.compareAndSet(existing, null);
        }

        String taskId = IdUtil.fastSimpleUUID();
        IitExportTaskStatus s = new IitExportTaskStatus();
        s.setTaskId(taskId);
        s.setUserId(userId);
        s.setUserName(userName);
        s.setStage("排队中");
        tasks.put(taskId, s);

        if (!currentTaskId.compareAndSet(null, taskId)) {
            tasks.remove(taskId);
            throw new IllegalStateException("已有导出任务进行中，请稍后再试");
        }

        executor.submit(() -> {
            try {
                doExport(taskId, req);
            } catch (Throwable t) {
                log.error("[iit-export] 任务异常 taskId={}", taskId, t);
                IitExportTaskStatus st = tasks.get(taskId);
                if (st != null) {
                    st.setStatus(IitExportTaskStatus.FAILED);
                    st.setStage("失败");
                    st.setErrorMsg(t.getMessage());
                    st.setFinishedAt(LocalDateTime.now());
                    st.touch();
                }
            } finally {
                currentTaskId.compareAndSet(taskId, null);
            }
        });
        return taskId;
    }

    public IitExportTaskStatus getStatus(String taskId) {
        return tasks.get(taskId);
    }

    public File getFile(String taskId) {
        IitExportTaskStatus s = tasks.get(taskId);
        if (s == null || !IitExportTaskStatus.DONE.equals(s.getStatus()) || s.getFilePath() == null) {
            return null;
        }
        File f = new File(s.getFilePath());
        return f.exists() ? f : null;
    }

    /** 每 10 分钟清理超过 TTL 的任务及其文件 */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minus(TASK_TTL);
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, IitExportTaskStatus> e : tasks.entrySet()) {
            IitExportTaskStatus s = e.getValue();
            LocalDateTime ref = s.getFinishedAt() != null ? s.getFinishedAt() : s.getCreatedAt();
            if (ref.isBefore(cutoff)) dead.add(e.getKey());
        }
        for (String id : dead) {
            IitExportTaskStatus s = tasks.remove(id);
            if (s != null && s.getFilePath() != null) {
                FileUtil.del(s.getFilePath());
                log.info("[iit-export] 清理过期任务 taskId={} file={}", id, s.getFilePath());
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 内部：实际导出流程
    // ─────────────────────────────────────────────────────────

    private void doExport(String taskId, IitExportRequest req) throws Exception {
        IitExportTaskStatus st = tasks.get(taskId);
        st.setStage("查询账套");
        st.touch();

        // 1) 解析账套范围
        List<Map<String, Object>> rows = resolveAccountSetRows(req);
        if (rows.isEmpty()) {
            throw new IllegalStateException("没有符合条件的账套");
        }
        st.setTotal(rows.size());
        st.touch();

        // 2) 批量查个税明细：accountSetId → period → iitItem
        st.setStage("查询个税申报记录");
        st.touch();
        List<Long> accountSetIds = rows.stream()
                .map(r -> toLong(r.get("accountSetId")))
                .filter(Objects::nonNull)
                .toList();

        Map<Long, TaskPeriod> tpByAsId = new HashMap<>();
        for (TaskPeriod tp : taskPeriodRepository.findByPeriodWithEverDoneInit(req.getPeriod(), accountSetIds)
                .stream().map(arr -> (TaskPeriod) arr[0]).toList()) {
            tpByAsId.put(tp.getAccountSetId(), tp);
        }
        List<Long> tpIds = tpByAsId.values().stream().map(TaskPeriod::getId).toList();
        Map<Long, List<TaskIitItem>> iitByTpId = tpIds.isEmpty()
                ? Collections.emptyMap()
                : taskIitItemRepository.findByTaskPeriodIdIn(tpIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(TaskIitItem::getTaskPeriodId));

        // 3) 准备工作目录
        String stamp = TS_FMT.format(LocalDateTime.now());
        File workDir = new File(exportRoot, "task-" + taskId);
        File shotsDir = new File(workDir, "screenshots");
        FileUtil.mkdir(shotsDir);
        String safePeriod = req.getPeriod().replace("-", "");
        String baseName = "个税申报导出_" + req.getPeriod() + "_" + stamp;
        File xlsxFile = new File(workDir, "个税申报情况_" + safePeriod + ".xlsx");
        File zipFile = new File(exportRoot, baseName + ".zip");

        // 4) 生成 Excel + 拷贝原图
        st.setStage("生成 Excel 与处理截图");
        st.touch();
        writeExcelAndCopyScreenshots(req, rows, tpByAsId, iitByTpId, xlsxFile, shotsDir, st);

        // 5) 打包 ZIP
        st.setStage("打包 ZIP");
        st.touch();
        zipDirectory(workDir, zipFile);

        // 6) 清理临时工作目录（保留最终 zip）
        FileUtil.del(workDir);

        // 7) 标记完成
        st.setStatus(IitExportTaskStatus.DONE);
        st.setStage("完成");
        st.setFileName(zipFile.getName());
        st.setFilePath(zipFile.getAbsolutePath());
        st.setFileSize(zipFile.length());
        st.setFinishedAt(LocalDateTime.now());
        st.touch();
        log.info("[iit-export] 完成 taskId={} file={} size={}KB rows={} screenshots={}",
                taskId, zipFile.getAbsolutePath(), zipFile.length() / 1024,
                st.getRowsWritten(), st.getScreenshotsEmbedded());
    }

    /** 根据导出范围拿到账套行（含 companyName/creditCode/province/currentAccountDate 等） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveAccountSetRows(IitExportRequest req) {
        String range = req.getExportRange() == null ? "filter" : req.getExportRange();

        // 用 getTasks 拿到一致的筛选/排序/字段
        // selected/all 走单页超大 pageSize；filter 透传所有筛选条件
        int bigPage = 100_000;
        List<Long> userIds = req.getFilterUserIds();
        if ("all".equals(range)) {
            // 全部账期：忽略所有过滤参数（仅 period 必须）
            Map<String, Object> result = taskWorkbenchService.getTasks(
                    req.getPeriod(), null, null, null, null, null, null, 1, bigPage,
                    null, null, null);
            return (List<Map<String, Object>>) result.get("tasks");
        }

        // filter 模式
        Map<String, Object> result = taskWorkbenchService.getTasks(
                req.getPeriod(),
                StrUtil.blankToDefault(req.getStatus(), "all"),
                req.getStepField(),
                req.getVat(),
                req.getAccountPeriod(),
                req.getProvince(),
                req.getKeyword(),
                1, bigPage,
                userIds,
                req.getDeclareFilter(),
                req.getIitFilter());
        List<Map<String, Object>> all = (List<Map<String, Object>>) result.get("tasks");
        if ("selected".equals(range)) {
            Set<Long> picked = new HashSet<>();
            if (CollUtil.isNotEmpty(req.getSelectedAccountSetIds())) {
                picked.addAll(req.getSelectedAccountSetIds());
            }
            return all.stream()
                    .filter(r -> picked.contains(toLong(r.get("accountSetId"))))
                    .toList();
        }
        return all;
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private void zipDirectory(File dir, File outZip) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outZip);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos, java.nio.charset.StandardCharsets.UTF_8)) {
            Path root = dir.toPath();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String entryName = root.relativize(p).toString().replace(File.separatorChar, '/');
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Excel 生成（含截图嵌入）
    // ─────────────────────────────────────────────────────────

    private void writeExcelAndCopyScreenshots(IitExportRequest req,
                                              List<Map<String, Object>> rows,
                                              Map<Long, TaskPeriod> tpByAsId,
                                              Map<Long, List<TaskIitItem>> iitByTpId,
                                              File xlsxFile,
                                              File shotsRoot,
                                              IitExportTaskStatus st) throws IOException {
        Set<String> typeFilter = CollUtil.isEmpty(req.getIitTypes())
                ? null : new HashSet<>(req.getIitTypes());
        boolean onlySuccess = req.isOnlySuccess();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("个税申报情况");
            XSSFDrawing drawing = sheet.createDrawingPatriarch();

            // 列宽：A 客户=28, B 税号=24, C 账期=12, D 类型=14, E 状态=10, F 退税=18, G 结果=50, H 错误=40, I 截图=动态
            int[] widths = { 28, 24, 12, 14, 10, 18, 50, 40, 0 };
            for (int i = 0; i < widths.length - 1; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            int screenshotCol = widths.length - 1;
            int picColWidthPx = SCREENSHOT_COL_CAPACITY * (THUMB_WIDTH_PX + 6) + 10;
            sheet.setColumnWidth(screenshotCol, pxToColumnWidth(picColWidthPx));

            // 表头
            CellStyle headerStyle = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            headerStyle.setFont(hf);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            String[] headers = {
                    "客户名称", "税号", "账期", "个税类型", "申报状态",
                    "应补/退税额", "申报结果", "错误信息", "截图（缩略图，原图见 screenshots/ 目录）"
            };
            Row hr = sheet.createRow(0);
            hr.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);

            CellStyle bodyStyle = wb.createCellStyle();
            bodyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            bodyStyle.setWrapText(true);

            int rowIdx = 1;
            int processedAs = 0;
            for (Map<String, Object> row : rows) {
                processedAs++;
                Long asId = toLong(row.get("accountSetId"));
                String companyName = strOf(row.get("companyName"));
                String creditCode = strOf(row.get("creditCode"));
                TaskPeriod tp = tpByAsId.get(asId);

                List<TaskIitItem> items = tp == null
                        ? Collections.emptyList()
                        : iitByTpId.getOrDefault(tp.getId(), Collections.emptyList());

                // 没有任何个税记录的账套：导出空占位行（让用户看到"这家没报")，除非 onlySuccess
                if (items.isEmpty()) {
                    if (!onlySuccess) {
                        writeRow(sheet, bodyStyle, rowIdx++, companyName, creditCode, req.getPeriod(),
                                "—", "未申报", "", "", "");
                    }
                } else {
                    for (TaskIitItem it : items) {
                        if (typeFilter != null && !typeFilter.contains(it.getIitType())) continue;
                        String status = it.getDeclareStatus();
                        if (onlySuccess && !"done".equals(status)) continue;

                        IitDisplay d = extractDisplay(it);
                        int curRow = rowIdx++;
                        writeRow(sheet, bodyStyle, curRow,
                                companyName, creditCode, req.getPeriod(),
                                iitTypeLabel(it.getIitType()),
                                statusLabel(status),
                                d.taxRefund, d.declarationResult, d.errorMsg);
                        st.setRowsWritten(st.getRowsWritten() + 1);

                        // 嵌入截图
                        List<String> urls = parseScreenshotUrls(it.getScreenshotUrls());
                        if (!urls.isEmpty()) {
                            sheet.getRow(curRow).setHeightInPoints(THUMB_HEIGHT_PX * 72f / 96f);
                            int embedded = embedScreenshotsAndCopy(
                                    wb, sheet, drawing, curRow, screenshotCol, urls,
                                    shotsRoot, companyName + "_" + req.getPeriod() + "_" + it.getIitType());
                            st.setScreenshotsEmbedded(st.getScreenshotsEmbedded() + embedded);
                        }
                    }
                }
                st.setProgress(processedAs);
                if (processedAs % 20 == 0) st.touch();
            }
            st.touch();

            try (FileOutputStream out = new FileOutputStream(xlsxFile)) {
                wb.write(out);
            }
        }
    }

    private void writeRow(Sheet sheet, CellStyle style, int rowIdx,
                          String company, String code, String period,
                          String iitType, String status,
                          String taxRefund, String declarationResult, String errorMsg) {
        Row row = sheet.createRow(rowIdx);
        String[] vals = { company, code, period, iitType, status, taxRefund, declarationResult, errorMsg };
        for (int i = 0; i < vals.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(vals[i] == null ? "" : vals[i]);
            c.setCellStyle(style);
        }
        // 第 9 列留给图片，先建空 cell 防止图片定位异常
        Cell pic = row.createCell(vals.length);
        pic.setCellStyle(style);
    }

    /**
     * 把单条申报记录的所有截图下载/拷贝到 shotsRoot/subDirName/ 下，并按缩略图水平排列嵌入到 (row, col) 单元格。
     * @return 实际嵌入的截图数
     */
    private int embedScreenshotsAndCopy(XSSFWorkbook wb, XSSFSheet sheet, XSSFDrawing drawing,
                                        int row, int col, List<String> urls,
                                        File shotsRoot, String subDirName) {
        File subDir = new File(shotsRoot, sanitizeFileName(subDirName));
        FileUtil.mkdir(subDir);

        int embedded = 0;
        int idx = 0;
        for (String url : urls) {
            idx++;
            File src = resolveScreenshotFile(url);
            if (src == null || !src.exists()) {
                log.warn("[iit-export] 截图源文件不存在: {}", url);
                continue;
            }
            // 拷贝原图到 screenshots/{subDir}/{idx_原名}
            String origName = src.getName();
            String safeName = String.format("%02d_%s", idx, origName);
            File dest = new File(subDir, safeName);
            try {
                FileUtil.copy(src, dest, true);
            } catch (Exception e) {
                log.warn("[iit-export] 拷贝原图失败 src={} dest={}: {}", src, dest, e.getMessage());
            }

            // 生成缩略图字节
            byte[] thumb;
            try {
                thumb = makeThumbnailBytes(src);
            } catch (Exception e) {
                log.warn("[iit-export] 缩略图生成失败 src={}: {}", src, e.getMessage());
                continue;
            }
            if (thumb == null || thumb.length == 0) continue;

            int picIdx = wb.addPicture(thumb, Workbook.PICTURE_TYPE_PNG);
            int n = embedded; // 已嵌入索引，从 0 开始
            int slot = THUMB_WIDTH_PX + 6;
            XSSFClientAnchor anchor = new XSSFClientAnchor(
                    (n * slot) * EMU_PER_PX,     // dx1
                    4 * EMU_PER_PX,              // dy1
                    (n * slot + THUMB_WIDTH_PX) * EMU_PER_PX, // dx2
                    (4 + THUMB_HEIGHT_PX) * EMU_PER_PX,       // dy2
                    col, row,
                    col, row);
            anchor.setAnchorType(ClientAnchor.AnchorType.DONT_MOVE_AND_RESIZE);
            drawing.createPicture(anchor, picIdx);
            embedded++;
        }
        return embedded;
    }

    /** 把本地图片缩成 {@link #THUMB_WIDTH_PX}px 宽的 PNG 字节流（保持比例） */
    private byte[] makeThumbnailBytes(File src) throws IOException {
        BufferedImage img = ImageIO.read(src);
        if (img == null) return null;
        int targetW = THUMB_WIDTH_PX;
        int targetH = Math.max(1, Math.round(img.getHeight() * (float) targetW / img.getWidth()));
        BufferedImage thumb = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024)) {
            ImageIO.write(thumb, "PNG", bos);
            return bos.toByteArray();
        }
    }

    /**
     * URL 可能形如：
     *  - "/oc-screenshots/xxx.png"
     *  - "http://server/oc-screenshots/xxx.png"
     *  - 完整外部 URL
     * 解析出本地文件。仅支持本地存在的截图；纯外网 URL 暂不下载（避免任意网络）。
     */
    private File resolveScreenshotFile(String url) {
        if (StrUtil.isBlank(url)) return null;
        String marker = "/oc-screenshots/";
        int idx = url.indexOf(marker);
        if (idx >= 0) {
            String name = url.substring(idx + marker.length());
            File f = new File(screenshotRoot, name);
            if (f.exists()) return f;
        }
        // 兜底：当成相对路径
        File f = new File(screenshotRoot, url);
        return f.exists() ? f : null;
    }

    private List<String> parseScreenshotUrls(String json) {
        if (StrUtil.isBlank(json)) return Collections.emptyList();
        try {
            JSONArray arr = JSON.parseArray(json);
            List<String> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                Object v = arr.get(i);
                if (v != null) {
                    String s = v.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[iit-export] 解析 screenshotUrls 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private IitDisplay extractDisplay(TaskIitItem it) {
        IitDisplay d = new IitDisplay();
        if (StrUtil.isNotBlank(it.getDeclareResult())) {
            try {
                Map<String, Object> json = JSON.parseObject(it.getDeclareResult());
                if (json != null) {
                    if (json.get("declaration_result") != null) {
                        d.declarationResult = json.get("declaration_result").toString().trim()
                                .replaceAll("\\r\\n|\\r|\\n", " ").replaceAll("\\s+", " ");
                    }
                    if (json.get("error_msg") != null) {
                        d.errorMsg = json.get("error_msg").toString().trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        if (StrUtil.isBlank(d.errorMsg) && StrUtil.isNotBlank(it.getErrorMsg())) {
            d.errorMsg = it.getErrorMsg().trim();
        }
        if (StrUtil.isNotBlank(d.declarationResult)) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("应补[/／]退税额[=：:]\\s*-?[\\d,]+\\.?\\d*")
                    .matcher(d.declarationResult);
            if (m.find()) d.taxRefund = m.group();
        }
        return d;
    }

    private static String iitTypeLabel(String t) {
        if (t == null) return "";
        return switch (t) {
            case "salary" -> "综合所得（工资薪金）";
            case "business" -> "经营所得";
            default -> t;
        };
    }

    private static String statusLabel(String s) {
        if (s == null) return "";
        return switch (s) {
            case "done" -> "申报成功";
            case "error" -> "申报失败";
            case "pending" -> "待申报";
            case "running" -> "申报中";
            case "skipped" -> "本期跳过";
            default -> s;
        };
    }

    private static String strOf(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Windows 路径不允许的字符替换为下划线 */
    private static String sanitizeFileName(String name) {
        if (name == null) return "_";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 像素 → POI 列宽单位（1/256 字符宽）。
     * 经验公式：列宽_px ≈ (width / 256) * 7 + 5
     * 反推：width = (px - 5) / 7 * 256
     */
    private static int pxToColumnWidth(int px) {
        return (int) ((px - 5) / 7.0 * 256);
    }

    private static class IitDisplay {
        String taxRefund = "";
        String declarationResult = "";
        String errorMsg = "";
    }
}
