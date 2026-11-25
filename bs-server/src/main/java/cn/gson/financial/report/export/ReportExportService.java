package cn.gson.financial.report.export;

import cn.gson.financial.config.ReportExportAsyncConfig;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.ReportService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * FN-08 报表异步导出 Service。
 *
 * <p>核心职责：
 * <ol>
 *   <li>{@link #submit(SubmitRequest)} — 同步 enqueue（去重 + 写 Redis QUEUED + 调 async）</li>
 *   <li>{@link #runAsync(String)} — {@code @Async("reportExportExecutor")} 跑 ReportService → 落文件 → 更新 Redis</li>
 *   <li>{@link #find(String)} / {@link #findFile(String)} — 查 / 下载</li>
 * </ol>
 *
 * <p>Redis key 约定（全局 prefix {@code financial_v3_} 由 spring.cache.redis.key-prefix 决定，本类不重复加）：
 * <ul>
 *   <li>{@code report:export:{jobId}} — JSON 整体存 Job 元数据，24h TTL</li>
 *   <li>{@code report:export:dedup:{accountSetsId}:{type}:{year}:{month}:{format}} — 去重锁，5 分钟 TTL，value=jobId</li>
 *   <li>{@code report:export:cache:{accountSetsId}:{type}:{year}:{month}} — 结果文件路径缓存，24h TTL，
 *       同样 (accountSetsId, type, year, month) 24h 内复用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    /** Redis key 前缀 — 业务前缀，全局 {@code financial_v3_} 由 Spring 自动加 */
    public static final String KEY_PREFIX = "report:export:";
    public static final String DEDUP_PREFIX = KEY_PREFIX + "dedup:";
    public static final String CACHE_PREFIX = KEY_PREFIX + "cache:";

    /** Job 状态 24h TTL */
    public static final Duration JOB_TTL = Duration.ofHours(24);
    /** 去重锁 5 分钟 */
    public static final Duration DEDUP_TTL = Duration.ofMinutes(5);
    /** 结果文件缓存 24h（与 ops 自动清理对齐） */
    public static final Duration RESULT_CACHE_TTL = Duration.ofHours(24);

    /** 报表类型 → ReportService 模板键映射（与 OpenApiReportController 一致） */
    public static final Map<String, String> TYPE_TO_TEMPLATE_KEY = Map.of(
            "BALANCE_SHEET", "zcfz",
            "PROFIT_LOSS", "lr",
            "CASH_FLOW", "xjll"
    );

    public static final Set<String> ALLOWED_FORMATS = Set.of("JSON", "XLSX", "PDF");

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ─── 依赖注入 ───
    private final StringRedisTemplate redisTemplate;
    private final ReportService reportService;
    private final CurrencyService currencyService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    /**
     * 自注入 — 通过 Spring 代理调 {@link #runAsync(String)}，让 {@code @Async} 切面真正生效。
     * 直接 {@code this.runAsync()} 不走代理 → 退化成同步执行。
     * 用 {@code @Lazy} 打破循环依赖。测试里允许为 null（{@link #submit} 检测到 null 就同步跑，等价 unit test）。
     */
    @Autowired
    @Lazy
    private ReportExportService self;

    /** spring.cache.redis.key-prefix — Spring 不会自动把它注到 StringRedisTemplate，要手动加 */
    @Value("${spring.cache.redis.key-prefix:}")
    private String globalKeyPrefix;

    /** customize-config.base-path 下的 report-exports 子目录 */
    @Value("${customize-config.base-path:D:\\financialExcel}")
    private String basePath;

    /** report-exports 子目录名 */
    public static final String SUBDIR = "report-exports";

    private ObjectMapper objectMapper;

    @PostConstruct
    void initObjectMapper() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ───────────────────────────────────────────────────────────────────
    // public api
    // ───────────────────────────────────────────────────────────────────

    /** 提交参数 — controller 接住之后封一个 record / DTO 传进来 */
    public static class SubmitRequest {
        public Long accountSetsId;
        public String type;
        public Integer year;
        public Integer month;
        /** JSON / XLSX / PDF，缺省 JSON */
        public String format;
    }

    /**
     * 提交导出任务（同步部分）：去重 → 写 Redis QUEUED → 异步触发。
     * 立刻返回（不等异步完成）。
     *
     * @return 新建或复用的 ReportExportJob（status=QUEUED 或 RUNNING/QUEUED 已存在的）
     */
    public ReportExportJob submit(SubmitRequest req) {
        validate(req);

        String type = req.type.trim().toUpperCase();
        String format = (req.format == null || req.format.isBlank()) ? "JSON" : req.format.trim().toUpperCase();

        // 去重：5 分钟内同 (accountSetsId, type, year, month, format) 有 QUEUED/RUNNING → 复用
        String dedupKey = dedupKey(req.accountSetsId, type, req.year, req.month, format);
        String existingJobId = redisTemplate.opsForValue().get(absKey(dedupKey));
        if (existingJobId != null) {
            ReportExportJob existing = find(existingJobId);
            if (existing != null
                    && ("QUEUED".equals(existing.getStatus()) || "RUNNING".equals(existing.getStatus()))) {
                log.info("[FN-08] dedup hit: jobId={} (acc={}, type={}, {}/{}, fmt={})",
                        existingJobId, req.accountSetsId, type, req.year, req.month, format);
                return existing;
            }
        }

        // 新 job
        ReportExportJob job = new ReportExportJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setStatus("QUEUED");
        job.setProgress(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setAccountSetsId(req.accountSetsId);
        job.setType(type);
        job.setYear(req.year);
        job.setMonth(req.month);
        job.setFormat(format);
        job.setPeriod(String.format("%04d-%02d", req.year, req.month));

        save(job);
        // 占去重位（value 是 jobId 便于将来排查）
        redisTemplate.opsForValue().set(absKey(dedupKey), job.getJobId(),
                DEDUP_TTL.toSeconds(), TimeUnit.SECONDS);

        // 异步触发：走 self 代理才能让 @Async 切面生效。
        // 测试场景（self 未注入）退化为同步直跑，方便断言。
        if (self != null) {
            self.runAsync(job.getJobId());
        } else {
            runAsync(job.getJobId());
        }

        log.info("[FN-08] submit jobId={} (acc={}, type={}, {}/{}, fmt={})",
                job.getJobId(), req.accountSetsId, type, req.year, req.month, format);

        return job;
    }

    /** 查任务 — controller 用 */
    public ReportExportJob find(String jobId) {
        if (jobId == null || jobId.isBlank()) return null;
        String json = redisTemplate.opsForValue().get(absKey(KEY_PREFIX + jobId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, ReportExportJob.class);
        } catch (JsonProcessingException e) {
            log.error("[FN-08] job json corrupt jobId={}", jobId, e);
            return null;
        }
    }

    /** 下载结果文件 — 仅 SUCCESS 才返回 file */
    public File findFile(String jobId) {
        ReportExportJob job = find(jobId);
        if (job == null) return null;
        if (!"SUCCESS".equals(job.getStatus())) return null;
        if (job.getResultPath() == null) return null;
        File f = new File(job.getResultPath());
        return f.exists() ? f : null;
    }

    // ───────────────────────────────────────────────────────────────────
    // async worker
    // ───────────────────────────────────────────────────────────────────

    /**
     * 异步 worker — 跑 ReportService → 写文件 → 更新 Redis。
     *
     * <p>分阶段更新 progress：
     * <pre>
     *   10%  load AccountSets + Currency
     *   30%  items()      取报表项
     *   60%  reportData() 算余额套模板
     *   90%  序列化文件
     *   100% SUCCESS
     * </pre>
     */
    @Async(ReportExportAsyncConfig.EXECUTOR_NAME)
    public void runAsync(String jobId) {
        ReportExportJob job = find(jobId);
        if (job == null) {
            log.warn("[FN-08] runAsync: job not found jobId={}", jobId);
            return;
        }
        try {
            job.setStatus("RUNNING");
            updateProgress(job, 1, "started");

            // ───── 1. 结果缓存命中（同 acc/type/year/month 24h 内有现成文件）─────
            String cacheKey = resultCacheKey(job.getAccountSetsId(), job.getType(), job.getYear(),
                    job.getMonth(), job.getFormat());
            String cachedPath = redisTemplate.opsForValue().get(absKey(cacheKey));
            if (cachedPath != null) {
                File cached = new File(cachedPath);
                if (cached.exists() && cached.length() > 0) {
                    log.info("[FN-08] result cache hit jobId={} cached={}", jobId, cachedPath);
                    job.setResultPath(cachedPath);
                    job.setResultSize(cached.length());
                    finishSuccess(job);
                    return;
                }
            }

            // ───── 2. 取 AccountSets + 本币 ─────
            QAccountSets q = QAccountSets.accountSets;
            AccountSets accountSets = bqf.selectFrom(q).where(q.id.eq(job.getAccountSetsId())).fetchFirst();
            if (accountSets == null) {
                throw new IllegalStateException("账套不存在：" + job.getAccountSetsId());
            }
            CurrencyDto local = currencyService.fetchLocal(job.getAccountSetsId());
            if (local == null) {
                throw new IllegalStateException("账套未配置本币：" + job.getAccountSetsId());
            }
            updateProgress(job, 10, "loaded account sets");

            String templateKey = TYPE_TO_TEMPLATE_KEY.get(job.getType());
            if (templateKey == null) {
                throw new IllegalStateException("不支持的报表类型：" + job.getType());
            }

            // ───── 3. items() 取报表项（模板结构） ─────
            List<ReportItemsDto> items = reportService.items(templateKey, job.getAccountSetsId(), false);
            updateProgress(job, 30, "loaded items");

            // ───── 4. reportData() 算数（最慢的一步） ─────
            LocalDate voucherDate = YearMonth.of(job.getYear(), job.getMonth()).atDay(1);
            List<ReportItemsDto> data = reportService.reportData(
                    items, accountSets, templateKey, local.getId(), voucherDate, "");
            updateProgress(job, 60, "computed data");

            // ───── 5. 序列化文件 ─────
            byte[] bytes = serialize(data, job);
            File outFile = writeFile(job, bytes);
            job.setResultPath(outFile.getAbsolutePath());
            job.setResultSize(outFile.length());
            updateProgress(job, 90, "written file");

            // 写结果缓存（同 acc/type/year/month/format 24h 内复用）
            redisTemplate.opsForValue().set(absKey(cacheKey), outFile.getAbsolutePath(),
                    RESULT_CACHE_TTL.toSeconds(), TimeUnit.SECONDS);

            finishSuccess(job);
        } catch (Throwable t) {
            log.error("[FN-08] runAsync failed jobId={}", jobId, t);
            job.setStatus("FAILED");
            job.setProgress(100);
            job.setCompletedAt(LocalDateTime.now());
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            // 截断防止过长
            job.setErrorMsg(msg.length() > 500 ? msg.substring(0, 500) : msg);
            save(job);
        }
    }

    private void finishSuccess(ReportExportJob job) {
        job.setStatus("SUCCESS");
        job.setProgress(100);
        job.setCompletedAt(LocalDateTime.now());
        save(job);
    }

    // ───────────────────────────────────────────────────────────────────
    // helpers (package-private 便于单测)
    // ───────────────────────────────────────────────────────────────────

    void save(ReportExportJob job) {
        try {
            String json = objectMapper.writeValueAsString(job);
            redisTemplate.opsForValue().set(absKey(KEY_PREFIX + job.getJobId()), json,
                    JOB_TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("序列化 ReportExportJob 失败 jobId=" + job.getJobId(), e));
        }
    }

    void updateProgress(ReportExportJob job, int progress, String stage) {
        job.setProgress(progress);
        save(job);
        if (log.isDebugEnabled()) {
            log.debug("[FN-08] jobId={} progress={} stage={}", job.getJobId(), progress, stage);
        }
    }

    /** Spring StringRedisTemplate 不会自动加 spring.cache.redis.key-prefix，自己拼 */
    String absKey(String relative) {
        return globalKeyPrefix + relative;
    }

    static String dedupKey(Long accountSetsId, String type, Integer year, Integer month, String format) {
        return DEDUP_PREFIX + accountSetsId + ":" + type + ":" + year + ":" + month + ":" + format;
    }

    static String resultCacheKey(Long accountSetsId, String type, Integer year, Integer month, String format) {
        return CACHE_PREFIX + accountSetsId + ":" + type + ":" + year + ":" + month + ":" + format;
    }

    static void validate(SubmitRequest req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        if (req.accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (req.type == null || req.type.isBlank()) throw new IllegalArgumentException("type 不能为空");
        String type = req.type.trim().toUpperCase();
        if (!TYPE_TO_TEMPLATE_KEY.containsKey(type)) {
            throw new IllegalArgumentException("type 非法：" + req.type
                    + "（合法值：BALANCE_SHEET / PROFIT_LOSS / CASH_FLOW）");
        }
        if (req.year == null || req.month == null) {
            throw new IllegalArgumentException("year/month 不能为空");
        }
        if (req.month < 1 || req.month > 12) {
            throw new IllegalArgumentException("month 非法：" + req.month);
        }
        if (req.format != null && !req.format.isBlank()) {
            String fmt = req.format.trim().toUpperCase();
            if (!ALLOWED_FORMATS.contains(fmt)) {
                throw new IllegalArgumentException("format 非法：" + req.format
                        + "（合法值：" + ALLOWED_FORMATS + "）");
            }
        }
    }

    /**
     * 把 reportData 序列化到字节数组。
     * <ul>
     *   <li>JSON — 直接 Jackson 序列化 items 列表 + 元数据</li>
     *   <li>XLSX — 简化版：用 fastjson 序列化成 JSON 包成 .xlsx 占位（避免引入 jxls 全套模板）</li>
     *   <li>PDF  — 同上占位（占位是为了不在导出路径上引入 PDF 字体依赖）</li>
     * </ul>
     * 注：XLSX/PDF 当前是占位实现，body 是 JSON 内容只是后缀不同 —— 业务需要真实 .xlsx
     * 时复用 ReportService 内的 JxlsUtils.exportExcel("template/report.xlsx", ...) 即可，
     * 本次为了不耦合 ReportService 的归档实现先用占位。
     */
    byte[] serialize(List<ReportItemsDto> data, ReportExportJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getJobId());
        payload.put("accountSetsId", job.getAccountSetsId());
        payload.put("type", job.getType());
        payload.put("templateKey", TYPE_TO_TEMPLATE_KEY.get(job.getType()));
        payload.put("year", job.getYear());
        payload.put("month", job.getMonth());
        payload.put("period", job.getPeriod());
        payload.put("format", job.getFormat());
        payload.put("generatedAt", LocalDateTime.now().toString());

        List<Map<String, Object>> rows = new ArrayList<>();
        if (data != null) {
            for (ReportItemsDto it : data) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", it.getId());
                m.put("title", it.getTitle());
                m.put("level", it.getLevel());
                m.put("pos", it.getPos());
                m.put("rowNum", it.getRowNum());
                m.put("parentId", it.getParentId());
                m.put("values", it.getValues());
                rows.add(m);
            }
        }
        payload.put("items", rows);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(bos, payload);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 落盘：{basePath}/report-exports/{accountSetsId}/{yyyy-MM}/{jobId}.{ext} */
    File writeFile(ReportExportJob job, byte[] bytes) throws IOException {
        String monthDir = YearMonth.of(job.getYear(), job.getMonth()).format(MONTH_FMT);
        File dir = new File(new File(new File(basePath, SUBDIR), String.valueOf(job.getAccountSetsId())), monthDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建导出目录：" + dir.getAbsolutePath());
        }
        String ext = switch (job.getFormat()) {
            case "XLSX" -> "xlsx";
            case "PDF" -> "pdf";
            default -> "json";
        };
        File f = new File(dir, job.getJobId() + "." + ext);
        Files.write(Path.of(f.toURI()), bytes);
        return f;
    }

    // ───────────────────────────────────────────────────────────────────
    // utility — 测试用
    // ───────────────────────────────────────────────────────────────────

    /** 测试用：返回 globalKeyPrefix 注入值，便于断言 Redis key 拼接 */
    public String getGlobalKeyPrefix() {
        return globalKeyPrefix;
    }

    /** 测试用：覆盖 globalKeyPrefix（@Value 注入不进 mock，所以暴露 setter） */
    public void setGlobalKeyPrefix(String p) {
        this.globalKeyPrefix = p == null ? "" : p;
    }

    /** 测试用：覆盖 basePath */
    public void setBasePath(String p) {
        this.basePath = p;
    }

    /** 测试用：返回 ObjectMapper（如果 @PostConstruct 没跑过则懒构造） */
    public ObjectMapper getObjectMapper() {
        if (objectMapper == null) initObjectMapper();
        return objectMapper;
    }

    /** 测试用：替换 self 代理（mock 一个 spy 进来可以拦截 runAsync 调用） */
    public void setSelf(ReportExportService self) {
        this.self = self;
    }

    /** 测试用：替换 BlazeJPAQueryFactory */
    public void setBqf(BlazeJPAQueryFactory bqf) {
        this.bqf = bqf;
    }
}
