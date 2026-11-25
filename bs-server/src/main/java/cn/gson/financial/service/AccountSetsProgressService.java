package cn.gson.financial.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FN-NEW-PROGRESS — 账套 6 步进度批量聚合。
 *
 * <p>给 BT 工作台一次性渲染 6000 户的"数据 / 做账 / 增值税 / 附加 / 个税 / 归档"6 步状态。
 *
 * <p>性能策略：
 * <ol>
 *   <li>主 SQL 先按 active account_sets 分页（LIMIT/OFFSET）拿 size 条基本字段</li>
 *   <li>同条 SQL 内嵌 9 个标量子查询：voucher_count + 3 个 declare_task 状态 + 4 个资料类 has-* + checkout 状态</li>
 *   <li>Java 端再组装 status 枚举 + overallProgress + warnings</li>
 * </ol>
 * 单页 50 户 ≤ 50 * 9 = 450 subquery 触发，依赖 voucher.voucher_date / declare_task.account_sets_id+tax_type+period
 * 等索引秒级返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSetsProgressService {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 100;

    // 步骤 done 加 16.67，in_progress 加 8（按工单约定）
    static final double PROGRESS_DONE = 16.67;
    static final double PROGRESS_IN_PROGRESS = 8.0;

    private final JdbcTemplate jdbcTemplate;

    /** 不限登录账号的全量查询（内部对账 StageStatusSyncService 用）。openapi /progress 必须走带 ownerUserId 的重载。 */
    public Result query(String period, String keyword, boolean pendingOnly, int page, int size) {
        return query(period, keyword, pendingOnly, page, size, null);
    }

    /**
     * FN-PROGRESS-SCOPE-BY-OWNER：ownerUserId 非空 → 仅该登录账号名下账套（user_account_sets 归属）；
     * 为 null → 不按账号过滤（全量，仅供内部对账）。openapi 控制器对 null 拦截返空、绝不暴露全量。
     */
    public Result query(String period, String keyword, boolean pendingOnly, int page, int size, Long ownerUserId) {
        if (period == null || period.isBlank()) throw new IllegalArgumentException("period 不能为空");
        YearMonth ym;
        try { ym = YearMonth.parse(period); }
        catch (Exception e) { throw new IllegalArgumentException("period 格式非法（应为 yyyy-MM）：" + period); }
        if (page < 1) page = 1;
        if (size < 1) size = DEFAULT_SIZE;
        if (size > MAX_SIZE) size = MAX_SIZE;

        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();
        int yearVal = ym.getYear();
        int monthVal = ym.getMonthValue();

        // ─── 总数（active 账套 + 按登录账号归属 + 可选 keyword） ───
        // FN-PROGRESS-SCOPE-BY-OWNER：COUNT 与主查询同口径加 user_account_sets 归属过滤（否则 total 对不上）
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String kwLike = kw == null ? null : "%" + kw + "%";
        String ownerIn = ownerUserId == null ? ""
                : " AND a.id IN (SELECT account_sets_id FROM fxy_financial_user_account_sets WHERE user_id = ?) ";
        String countSql = "SELECT COUNT(1) FROM fxy_financial_account_sets a WHERE a.deactivated = 0" + ownerIn
                + (kwLike == null ? "" : " AND a.company_name LIKE ? ");
        List<Object> countArgs = new ArrayList<>();
        if (ownerUserId != null) countArgs.add(ownerUserId);
        if (kwLike != null) countArgs.add(kwLike);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, countArgs.toArray());
        long totalBeforeFilter = total == null ? 0L : total;

        // ─── 主查询：单 SQL + 9 子查询 ───
        // 性能注意：subquery 走 (account_sets_id, *_date) 索引，无 N+1（一条 SQL 完成）
        String sql = """
                SELECT
                    a.id                AS account_sets_id,
                    a.company_name      AS company_name,
                    a.credit_code       AS credit_code,
                    a.province          AS province,
                    a.industry_name     AS industry_name,
                    a.vat_type          AS vat_type,
                    a.individual_business AS individual_business,
                    a.invoice_collect_mode AS invoice_collect_mode,  -- FN-INVOICE-COLLECT-MODE-CONFIG：发票采集模式 QUARTERLY/MONTHLY
                    a.current_account_date AS current_account_date,  -- FN-PROGRESS-ADD-CURRENTPERIOD：账套当前账期（归一 yyyy-MM）
                    (SELECT COUNT(1) FROM fxy_financial_voucher v
                       WHERE v.account_sets_id = a.id
                         AND v.voucher_date BETWEEN ? AND ?) AS voucher_count,
                    (SELECT c.status FROM fxy_financial_checkout c
                       WHERE c.account_sets_id = a.id
                         AND c.check_year = ? AND c.check_month = ?
                       LIMIT 1) AS checkout_status,
                    (SELECT d.id FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) = 'VAT'
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS vat_task_id,
                    (SELECT d.status FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) = 'VAT'
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS vat_task_status,
                    (SELECT d.id FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) = 'SURCHARGE'
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS surcharge_task_id,
                    (SELECT d.status FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) = 'SURCHARGE'
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS surcharge_task_status,
                    (SELECT d.id FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) IN ('IIT', 'PIT')
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS iit_task_id,
                    (SELECT d.status FROM fxy_financial_declare_task d
                       WHERE d.account_sets_id = a.id
                         AND UPPER(d.tax_type) IN ('IIT', 'PIT')
                         AND d.period_year = ? AND d.period_month = ?
                       ORDER BY d.id DESC LIMIT 1) AS iit_task_status,
                    -- FN-FIX-PROGRESS-BANK-FILE-COUNT: 改查 file_info 表（按文件数计），
                    -- 因 XW 未解析时 bank_statement_detail 为 0 → BT chip 误显示 0 误导会计
                    (SELECT COUNT(1) FROM fxy_financial_file_info f
                       WHERE f.account_sets_id = a.id
                         AND f.type = 'bankStatement'
                         AND (f.is_delete IS NULL OR f.is_delete = 0)
                         AND f.account_set_period = ?) AS bank_count,
                    (SELECT COUNT(1) FROM fxy_financial_salary s
                       WHERE s.account_sets_id = a.id
                         AND s.year = ? AND s.month = ?) AS salary_count,
                    -- FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：BT 面板发票数量按「做账归属期」统计，
                    -- 用 COALESCE(assignment_date, trade_date)（assignmentDate 优先 + tradeDate 兜底），与 FN 内部口径一致，
                    -- 纳入被指定到本期但开票在别月的发票（绑参不变，仍 periodStart/periodEnd 各一对）。
                    (SELECT COUNT(1) FROM fxy_financial_invoice i
                       WHERE i.account_sets_id = a.id AND i.type = 0 AND i.state = 0
                         AND COALESCE(i.assignment_date, i.trade_date) BETWEEN ? AND ?) AS out_invoice_count,
                    (SELECT COUNT(1) FROM fxy_financial_invoice i
                       WHERE i.account_sets_id = a.id AND i.type = 1 AND i.state = 0
                         AND COALESCE(i.assignment_date, i.trade_date) BETWEEN ? AND ?) AS in_invoice_count,
                    -- STAGE-STATUS-PERSIST: LEFT JOIN task_period 取 step* 持久值（单一真相源），
                    -- 与现有派生值并排返回做对账兜底（保留派生不删）。JOIN 走 uk(account_set_id, period) 索引。
                    tp.id                    AS tp_id,
                    tp.step_verify           AS tp_step_verify,
                    tp.step_init             AS tp_step_init,
                    tp.step_data             AS tp_step_data,
                    tp.step_bookkeep         AS tp_step_bookkeep,
                    tp.step_carry            AS tp_step_carry,
                    tp.step_tax_national     AS tp_step_tax_national,
                    tp.step_tax_iit          AS tp_step_tax_iit,
                    tp.step_payment          AS tp_step_payment,
                    tp.step_tax_annual       AS tp_step_tax_annual,
                    tp.step_archive          AS tp_step_archive,
                    tp.pre_declare_confirmed AS tp_pre_declare_confirmed,
                    -- FN-FLOW-PROGRESS-READ: LEFT JOIN flow_period 取 flow 新模型聚合状态（BT 工作台优先读 flowPeriod）。
                    -- 与 task_period 并存、零耦合（flow 流程的真相源），JOIN 走 uk(account_set_id, period)。
                    fp.id                    AS fp_id,
                    fp.st_login              AS fp_st_login,
                    fp.st_verify             AS fp_st_verify,
                    fp.st_init               AS fp_st_init,
                    fp.st_collect            AS fp_st_collect,
                    fp.st_book               AS fp_st_book,
                    fp.st_carry              AS fp_st_carry,
                    fp.st_predeclare         AS fp_st_predeclare,
                    fp.st_declare_vat        AS fp_st_declare_vat,
                    fp.st_declare_iit        AS fp_st_declare_iit,
                    fp.st_payment            AS fp_st_payment,
                    fp.st_archive            AS fp_st_archive,
                    fp.predeclare_confirmed  AS fp_predeclare_confirmed
                FROM fxy_financial_account_sets a
                LEFT JOIN fxy_financial_task_period tp
                       ON tp.account_set_id = a.id AND tp.period = ?
                LEFT JOIN fxy_financial_flow_period fp
                       ON fp.account_set_id = a.id AND fp.period = ?
                WHERE a.deactivated = 0
                """ + ownerIn + (kwLike == null ? "" : " AND a.company_name LIKE ? ") + """
                ORDER BY a.id ASC
                LIMIT ? OFFSET ?
                """;

        List<Object> args = new ArrayList<>();
        // voucher_count
        args.add(periodStart); args.add(periodEnd);
        // checkout (year, month)
        args.add(yearVal); args.add(monthVal);
        // 3 个 declare_task * 2（id + status）= 6 个 (year, month)
        for (int i = 0; i < 6; i++) { args.add(yearVal); args.add(monthVal); }
        // bank：file_info.account_set_period 是当月 1 号 LocalDate，单参绑定
        args.add(periodStart);
        // salary (year, month)
        args.add(yearVal); args.add(monthVal);
        // 销项 / 进项 invoice
        args.add(periodStart); args.add(periodEnd);
        args.add(periodStart); args.add(periodEnd);
        // STAGE-STATUS-PERSIST：LEFT JOIN task_period 的 tp.period（VARCHAR 'yyyy-MM'）
        args.add(period);
        // FN-FLOW-PROGRESS-READ：LEFT JOIN flow_period 的 fp.period（VARCHAR 'yyyy-MM'），紧随 tp.period
        args.add(period);
        // FN-PROGRESS-SCOPE-BY-OWNER：WHERE 的 user_account_sets 归属过滤绑参（在 deactivated 之后、kwLike 之前），仅当限定账号
        if (ownerUserId != null) args.add(ownerUserId);
        if (kwLike != null) args.add(kwLike);
        args.add(size);
        args.add((page - 1) * size);

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, args.toArray());
        } catch (Exception e) {
            log.error("[FN-NEW-PROGRESS] query failed period={} keyword={}", period, keyword, e);
            throw new RuntimeException("查询进度失败：" + e.getMessage(), e);
        }

        List<Map<String, Object>> records = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> rec = assembleRecord(row, period);
            if (pendingOnly) {
                Integer prog = (Integer) rec.get("overallProgress");
                if (prog != null && prog >= 100) continue;
            }
            records.add(rec);
        }

        Result r = new Result();
        r.total = totalBeforeFilter;  // total 是 active 账套数；pendingOnly 不改 total，仅过滤当前页
        r.page = page;
        r.size = size;
        r.records = records;
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    // 组装单条记录
    // ═══════════════════════════════════════════════════════════════

    /**
     * FN-PROGRESS-SCOPE-BY-OWNER：按手机号(fxy_financial_user.mobile，唯一)解析登录用户 id。
     * 无匹配 / 多匹配（理论不会，mobile 唯一）→ null（调用方据此保守返空+提示，不默认全量）。
     */
    public Long resolveUserIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM fxy_financial_user WHERE mobile = ?", Long.class, phone.trim());
        return ids.size() == 1 ? ids.get(0) : null;
    }

    Map<String, Object> assembleRecord(Map<String, Object> row, String period) {
        Map<String, Object> rec = new LinkedHashMap<>();
        Long accId = asLong(row.get("account_sets_id"));
        rec.put("accountSetsId", accId);
        rec.put("companyName", row.get("company_name"));
        rec.put("creditCode", row.get("credit_code"));
        rec.put("province", row.get("province"));
        rec.put("industryName", row.get("industry_name"));
        Object vatTypeRaw = row.get("vat_type");
        rec.put("vatType", vatTypeRaw);
        boolean indivBiz = "1".equals(String.valueOf(row.get("individual_business")))
                || Boolean.TRUE.equals(row.get("individual_business"));
        rec.put("individualBusiness", indivBiz);
        rec.put("taxpayerType", deriveTaxpayerType(vatTypeRaw, indivBiz));
        rec.put("period", period);
        // FN-PROGRESS-ADD-CURRENTPERIOD：账套当前账期（current_account_date 归一 yyyy-MM），与选中期 period 并列，供工作台门控批量
        rec.put("currentPeriod", toYearMonth(row.get("current_account_date")));
        // FN-INVOICE-COLLECT-MODE-CONFIG：发票采集模式（QUARTERLY/MONTHLY；null 视为 QUARTERLY 默认）供 BT 展示/切换
        Object collectMode = row.get("invoice_collect_mode");
        rec.put("invoiceCollectMode", collectMode != null ? collectMode.toString() : "QUARTERLY");

        // ─── 各步骤 ───
        long voucherCount = asLong(row.get("voucher_count"));
        Integer checkoutStatus = asInt(row.get("checkout_status"));
        boolean closed = checkoutStatus != null && checkoutStatus == 2;

        Map<String, Object> steps = new LinkedHashMap<>();

        // data
        long bankCount = asLong(row.get("bank_count"));
        long salaryCount = asLong(row.get("salary_count"));
        long outInvoice = asLong(row.get("out_invoice_count"));
        long inInvoice = asLong(row.get("in_invoice_count"));
        int missingCount = 0;
        List<String> warnings = new ArrayList<>();
        if (bankCount == 0)   { missingCount++; warnings.add("缺银行流水"); }
        if (salaryCount == 0) { missingCount++; warnings.add("缺工资表"); }
        if (outInvoice == 0)  { missingCount++; warnings.add("缺销项发票"); }
        if (inInvoice == 0)   { missingCount++; warnings.add("缺进项发票"); }
        steps.put("data", stepMap("data", missingCount == 0 ? "ready" : "missing",
                "missingCount", missingCount));

        // bookkeep
        String bookkeepStatus;
        if (voucherCount == 0) bookkeepStatus = "todo";
        else if (closed)       bookkeepStatus = "done";
        else                   bookkeepStatus = "in_progress";
        steps.put("bookkeep", stepMap("bookkeep", bookkeepStatus,
                "voucherCount", voucherCount));

        // vat / surcharge / iit — 同模式
        steps.put("vat",       declareStep(row, "vat"));
        steps.put("surcharge", declareStep(row, "surcharge"));
        steps.put("iit",       declareStep(row, "iit"));

        // archive
        steps.put("archive", stepMap("archive", closed ? "done" : "open",
                "checkedOut", closed));

        rec.put("steps", steps);

        // ─── overallProgress ───
        int progress = 0;
        for (Object stepObj : steps.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) stepObj;
            String st = String.valueOf(s.get("status"));
            if ("done".equals(st) || "ready".equals(st)) progress += (int) Math.round(PROGRESS_DONE);
            else if ("in_progress".equals(st)) progress += (int) Math.round(PROGRESS_IN_PROGRESS);
        }
        if (progress > 100) progress = 100;
        rec.put("overallProgress", progress);

        rec.put("warnings", warnings);

        // ─── STAGE-STATUS-PERSIST：task_period 持久化 step*（单一真相源），与上方派生 steps 并排返回 ───
        // BT 读取约定：persisted 优先、为 null（历史无 task_period 行 / 未回写）回退派生 steps.*.status。
        // 派生值保留不删（§2.3/§2.5），用于对账：persisted=done 但业务数据为空 → 漂移可被发现。
        boolean hasTaskPeriod = row.get("tp_id") != null;
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("hasTaskPeriod", hasTaskPeriod);
        persisted.put("verify", row.get("tp_step_verify"));
        persisted.put("init", row.get("tp_step_init"));
        persisted.put("data", row.get("tp_step_data"));
        persisted.put("bookkeep", row.get("tp_step_bookkeep"));
        persisted.put("carry", row.get("tp_step_carry"));
        persisted.put("taxNational", row.get("tp_step_tax_national"));
        persisted.put("taxIit", row.get("tp_step_tax_iit"));
        persisted.put("payment", row.get("tp_step_payment"));
        persisted.put("taxAnnual", row.get("tp_step_tax_annual"));
        persisted.put("archive", row.get("tp_step_archive"));
        boolean preDecl = "1".equals(String.valueOf(row.get("tp_pre_declare_confirmed")))
                || Boolean.TRUE.equals(row.get("tp_pre_declare_confirmed"));
        persisted.put("preDeclareConfirmed", hasTaskPeriod && preDecl);
        rec.put("stepsPersisted", persisted);

        // ─── FN-FLOW-PROGRESS-READ：flow 新模型聚合状态（BT 工作台 6 列优先读 flowPeriod，见 fn-tools:565）───
        // 无 flow_period 行（未建行/未执行）→ flowPeriod=null，BT 回退派生 steps / stepsPersisted。
        if (row.get("fp_id") != null) {
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("id", asLong(row.get("fp_id")));
            flow.put("stLogin", row.get("fp_st_login"));
            flow.put("stVerify", row.get("fp_st_verify"));
            flow.put("stInit", row.get("fp_st_init"));
            flow.put("stCollect", row.get("fp_st_collect"));
            flow.put("stBook", row.get("fp_st_book"));
            flow.put("stCarry", row.get("fp_st_carry"));
            flow.put("stPredeclare", row.get("fp_st_predeclare"));
            flow.put("stDeclareVat", row.get("fp_st_declare_vat"));
            flow.put("stDeclareIit", row.get("fp_st_declare_iit"));
            flow.put("stPayment", row.get("fp_st_payment"));
            flow.put("stArchive", row.get("fp_st_archive"));
            boolean fpPreDecl = "1".equals(String.valueOf(row.get("fp_predeclare_confirmed")))
                    || Boolean.TRUE.equals(row.get("fp_predeclare_confirmed"));
            flow.put("predeclareConfirmed", fpPreDecl);
            rec.put("flowPeriod", flow);
        } else {
            rec.put("flowPeriod", null);
        }

        // BT 数量 chip 所需：每类原始数据的条数（0 = 未上传，BT 显示灰色 N/A）
        Map<String, Object> dataStats = new LinkedHashMap<>();
        dataStats.put("bankFlow", (int) bankCount);
        dataStats.put("salesInvoice", (int) outInvoice);
        dataStats.put("purchaseInvoice", (int) inInvoice);
        dataStats.put("salary", (int) salaryCount);
        rec.put("dataStats", dataStats);

        return rec;
    }

    // ═══════════════════════════════════════════════════════════════
    // STAGE-STATUS-PERSIST §8：派生 → task_period.step_* 列映射（存量回填 + 后台对账「单一派生口径」）
    //   关键：复用 assembleRecord 已算出的派生 steps，不另写一套口径。
    //   pre_declare_confirmed 无派生来源（由专用端点维护），不在此映射。
    // ═══════════════════════════════════════════════════════════════

    /** persisted 键（stepsPersisted）↔ 实体字段名 ↔ 派生映射，供回填/对账共用。 */
    public static final Map<String, String> FIELD_TO_PERSISTED_KEY = Map.of(
            "stepData", "data",
            "stepBookkeep", "bookkeep",
            "stepCarry", "carry",
            "stepTaxNational", "taxNational",
            "stepTaxIit", "taxIit",
            "stepArchive", "archive"
    );

    /**
     * 把 {@link #assembleRecord} 的派生 {@code steps} 映射成 task_period 的 6 个 step_* 列值（实体字段名→状态）。
     * <p>口径：采集 data(ready→done) / 做账 bookkeep(done|in_progress→running|todo→pending) /
     * 结转 carry(=已关账 closed→done) / 报税国税 = vat+surcharge 合并 / 报税个税 iit / 归档 archive(=closed)。
     * <p>说明：现有派生未区分"结转"与"归档"（progress 的 archive 步即 {@code closed?done:open}），故二者回填同源；
     * 上线后由显式写分流（关账→carry，BT 报税后→archive）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> deriveStepColumns(Map<String, Object> record) {
        Map<String, Object> steps = record.get("steps") instanceof Map
                ? (Map<String, Object>) record.get("steps") : Collections.emptyMap();
        boolean closed = "done".equals(stepStatus(steps, "archive"));   // 派生 archive = closed?done:open
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("stepData", "ready".equals(stepStatus(steps, "data")) ? "done" : "pending");
        cols.put("stepBookkeep", mapBookkeep(stepStatus(steps, "bookkeep")));
        cols.put("stepCarry", closed ? "done" : "pending");
        cols.put("stepTaxNational", combineDeclare(stepStatus(steps, "vat"), stepStatus(steps, "surcharge")));
        cols.put("stepTaxIit", mapDeclare(stepStatus(steps, "iit")));
        cols.put("stepArchive", closed ? "done" : "pending");
        return cols;
    }

    private static String stepStatus(Map<String, Object> steps, String key) {
        Object s = steps.get(key);
        return (s instanceof Map) ? String.valueOf(((Map<?, ?>) s).get("status")) : null;
    }

    private static String mapBookkeep(String s) {
        return switch (s == null ? "" : s) {
            case "done" -> "done";
            case "in_progress" -> "running";
            default -> "pending";            // todo
        };
    }

    private static String mapDeclare(String s) {
        return switch (s == null ? "" : s) {
            case "done" -> "done";
            case "failed" -> "error";
            case "in_progress" -> "running";
            default -> "pending";            // todo
        };
    }

    /** 国税 = 增值税 + 附加：任一失败→error；都完成→done；任一进行/完成→running；否则 pending。 */
    private static String combineDeclare(String vat, String surcharge) {
        String a = mapDeclare(vat), b = mapDeclare(surcharge);
        if ("error".equals(a) || "error".equals(b)) return "error";
        if ("done".equals(a) && "done".equals(b)) return "done";
        if ("running".equals(a) || "running".equals(b) || "done".equals(a) || "done".equals(b)) return "running";
        return "pending";
    }

    /** vat / surcharge / iit 三步走同一逻辑 */
    static Map<String, Object> declareStep(Map<String, Object> row, String stepKey) {
        Object id = row.get(stepKey + "_task_id");
        String taskStatus = String.valueOf(row.get(stepKey + "_task_status"));
        String status;
        if (id == null) {
            status = "todo";
            taskStatus = null;
        } else {
            status = switch (taskStatus) {
                case "SUCCESS" -> "done";
                case "FAILED" -> "failed";
                case "PENDING", "PREPARING", "EXECUTING", "MANUAL" -> "in_progress";
                default -> "todo";
            };
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("declareTaskId", id);
        m.put("declareStatus", taskStatus);
        return m;
    }

    /** 把 vatType + individualBusiness 派生为 SMALL_SCALE/GENERAL/INDIVIDUAL */
    static String deriveTaxpayerType(Object vatTypeRaw, boolean individualBiz) {
        if (individualBiz) return "INDIVIDUAL";
        if (vatTypeRaw == null) return "SMALL_SCALE";
        // vatTypeRaw 来自 db varchar — 兼容 SMALL_SCALE/COMMONLY/0/1
        String v = vatTypeRaw.toString().trim().toUpperCase();
        if ("COMMONLY".equals(v) || "GENERAL".equals(v) || "1".equals(v)) return "GENERAL";
        return "SMALL_SCALE";
    }

    static Map<String, Object> stepMap(String stepKey, String status, String extraK, Object extraV) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put(extraK, extraV);
        return m;
    }

    static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }

    static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    /** FN-PROGRESS-ADD-CURRENTPERIOD：日期值归一为 yyyy-MM（java.sql.Date / LocalDate / 字符串 toString 均为 yyyy-MM-dd 前缀）。 */
    static String toYearMonth(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() >= 7 ? s.substring(0, 7) : s;
    }

    public static class Result {
        public long total;
        public int page;
        public int size;
        public List<Map<String, Object>> records;
    }
}
