package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FN-INVOICE-03 / FN-NEW-T2 — 商品税收分类编码 OpenAPI。
 *
 * <p>GET /openapi/v1/tax-classification
 * ?keyword=蔬菜&taxpayer_type=GENERAL&page=1&size=20
 *
 * <p>FN-NEW-T2 切到原始码表 {@code fxy_financial_tax_classification_code}（4273 行国税总局规范全字段
 * V20260530_003 seed）。返回字段含合并 19 位完整码 + 货物名称 + 简称 + 说明，旧应用表
 * {@code fxy_financial_tax_classification}（V20260528_001 INSERT IGNORE 4005 行）通过 LEFT JOIN
 * 兜衍生字段（默认税率 / 纳税人类型 / 备注必填）；INNER JOIN 命中率 4005/4205=95.2%，剩余 200 条
 * 新表独有条目 LEFT JOIN 衍生字段为 null，由调用方按需处理。
 *
 * <p>keyword 对 goods_name / short_name / merged_code 做 LIKE 三路匹配；
 * taxpayer_type 过滤 old.applicable_taxpayer_type = ? OR = 'BOTH'（仅旧表 4005 条上有此字段）。
 * 编码字段为非必填：调用方不传 keyword 可分页浏览全 4273 行。
 *
 * <p>FN-NEW-T2-FIX（workflow review 修复）：API 加 {@code page} 分页参数 + 真 {@code total = COUNT(*)}；
 * 旧的 {@code limit} 参数兼容保留（视作 size，只取 page 1）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/tax-classification")
@SaIgnore
public class OpenApiTaxClassificationController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public JsonResult search(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String taxpayerType,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             @RequestParam(required = false) Integer limit) {
        // 兼容旧调用方：limit 等同于 size，不传 page 时取 page=1
        int effectiveSize = pickSize(size, limit);
        int effectivePage = page == null || page < 1 ? 1 : page;
        int offset = (effectivePage - 1) * effectiveSize;

        long totalMatched = countClassifications(keyword, taxpayerType);
        List<Map<String, Object>> rows = searchClassifications(keyword, taxpayerType, effectiveSize, offset);
        List<Map<String, Object>> records = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) records.add(toDto(row));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", records);                    // xiaowei 期望的字段名
        data.put("total", (int) totalMatched);        // 真 COUNT(*) 全量匹配数；不再用 records.size()
        data.put("page", effectivePage);
        data.put("size", effectiveSize);
        data.put("limit", effectiveSize);             // 兼容旧字段
        data.put("records", records);                 // 兼容旧调用方
        return JsonResult.successful(data);
    }

    private static int pickSize(Integer size, Integer limit) {
        Integer raw = size != null ? size : limit;
        if (raw == null || raw < 1) return 20;
        return Math.min(raw, 200);  // 上限 200 保护后端
    }

    // ─── helpers (public for spy-based tests) ────────────────────────

    /**
     * FN-NEW-T2 / FN-NEW-T2-FIX：搜索源切到 {@code fxy_financial_tax_classification_code} 原始码表，
     * LEFT JOIN 旧应用表拿衍生字段。
     * <p>返回 Map 字段保持稳定（{@code code/name/short_name/description/category/default_tax_rate/
     * applicable_taxpayer_type/remark_required/parent_code}），让上游 toDto 兼容旧别名 + 加新字段。
     */
    public List<Map<String, Object>> searchClassifications(String keyword, String taxpayerType, int size, int offset) {
        // 220：补 parent_name（父节点货物名）+ has_children（是否末级），照搬 tree 接口 findDirectChildren 两段——
        // 让搜索命中能分清是否真末级（非末级别当可选末级）。WHERE/分页/shortName 等其它字段语义不变，仅新增列。
        StringBuilder sql = new StringBuilder(
                "SELECT c.merged_code AS code, c.goods_name AS name, " +
                "c.short_name AS short_name, c.description AS description, " +
                "old.category AS category, old.default_tax_rate AS default_tax_rate, " +
                "old.applicable_taxpayer_type AS applicable_taxpayer_type, " +
                "old.remark_required AS remark_required, old.parent_code AS parent_code, " +
                "p.goods_name AS parent_name, " +
                "EXISTS (SELECT 1 FROM fxy_financial_tax_classification_code c2 " +
                "        WHERE c2.parent_code = c.merged_code) AS has_children " +
                "FROM fxy_financial_tax_classification_code c " +
                "LEFT JOIN fxy_financial_tax_classification_code p ON p.merged_code = c.parent_code " +
                "LEFT JOIN fxy_financial_tax_classification old ON old.code = c.merged_code " +
                "WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, keyword, taxpayerType);
        sql.append(" ORDER BY c.serial_no LIMIT ? OFFSET ?");
        params.add(size); params.add(offset);
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /**
     * FN-NEW-T2-FIX：返回与 search 同 WHERE 条件下的全量匹配行数（供 total 字段用）。
     */
    public long countClassifications(String keyword, String taxpayerType) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM fxy_financial_tax_classification_code c " +
                "LEFT JOIN fxy_financial_tax_classification old ON old.code = c.merged_code " +
                "WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, keyword, taxpayerType);
        Long n = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return n == null ? 0L : n;
    }

    /**
     * 抽出 WHERE 拼接 —— 让 search / count 共享同样过滤条件，total 才与本页一致。
     */
    private static void appendWhere(StringBuilder sql, List<Object> params,
                                    String keyword, String taxpayerType) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (c.goods_name LIKE ? OR c.short_name LIKE ? OR c.merged_code LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (taxpayerType != null && !taxpayerType.isBlank()) {
            // 旧表才有 applicable_taxpayer_type，不在旧表的条目（200 条新表独有）会被这条过滤掉。
            // 调用方明确要求按"一般 / 小规模"过滤时，只匹配有明确语义的条目。
            sql.append(" AND (old.applicable_taxpayer_type = ? OR old.applicable_taxpayer_type = 'BOTH')");
            params.add(taxpayerType.trim().toUpperCase());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // FN-TREE-T2 树形端点（依赖 V20260530_005 加的 parent_code / level 两列）
    //
    //   GET /openapi/v1/tax-classification/tree
    //     不传 parent       → 返第 1 级（篇）6 个根节点
    //     ?parent=<19位码>  → 返该节点的直接子代
    //     ?full=1           → 返嵌套整树（4273 行，V20260604_002 覆盖回填后），带 Cache-Control + ETag
    //
    // 节点字段：{ code, name, shortName, level, parentCode, hasChildren, taxRate, children? }
    // hasChildren 由 EXISTS(parent_code = 本 code) 一次性算出，走 idx_parent
    // ════════════════════════════════════════════════════════════════

    /** full=1 整树的 ETag —— 静态数据，迁移升级时改这里（V20260604_002 整表覆盖回填：以电税树为准 4273 节点） */
    static final String TREE_FULL_ETAG = "\"tax-tree-V20260604.002\"";

    @GetMapping("/tree")
    public ResponseEntity<JsonResult> tree(
            @RequestParam(required = false) String parent,
            @RequestParam(required = false, defaultValue = "0") String full,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        boolean isFull = "1".equals(full) || "true".equalsIgnoreCase(full);

        // 整树模式：ETag 命中 → 304 Not Modified
        if (isFull && TREE_FULL_ETAG.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(TREE_FULL_ETAG)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .build();
        }

        List<Map<String, Object>> nodes;
        if (isFull) {
            // FN-TREE-PERF：整树改为「一条 SQL 拉全表 4273 行 + 内存按 parent_code 拼树」，
            // 替代原 BFS「每个有子节点的节点单发一条 SQL」的 N+1（几百~上千次往返 → >60s 超时）。
            // 输出结构 / 字段 / 排序（merged_code）/ ETag 完全不变，调用方无感知。
            nodes = getFullTreeCached();
        } else {
            List<Map<String, Object>> rawRows = findDirectChildren(parent);
            nodes = new ArrayList<>(rawRows.size());
            for (Map<String, Object> row : rawRows) nodes.add(toTreeNode(row));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parent", parent);
        data.put("full", isFull);
        data.put("count", nodes.size());
        data.put("nodes", nodes);
        JsonResult body = JsonResult.successful(data);

        if (isFull) {
            return ResponseEntity.ok()
                    .eTag(TREE_FULL_ETAG)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 按 parent_code 拿直接子代。parent=null/空 → 返 level=1 的根。
     * hasChildren 用 EXISTS(parent_code = c.merged_code) 走 idx_parent。
     */
    public List<Map<String, Object>> findDirectChildren(String parent) {
        boolean isRoot = parent == null || parent.isBlank();
        // 202：补 parent_name（父节点货物名）——票面 *简称* 取上一级名，供前端手选同 AI 口径。
        String sql = "SELECT c.merged_code AS code, c.goods_name AS name, c.short_name, " +
                "c.level, c.parent_code AS parent_code, p.goods_name AS parent_name, " +
                "old.default_tax_rate AS default_tax_rate, " +
                "EXISTS (SELECT 1 FROM fxy_financial_tax_classification_code c2 " +
                "        WHERE c2.parent_code = c.merged_code) AS has_children " +
                "FROM fxy_financial_tax_classification_code c " +
                "LEFT JOIN fxy_financial_tax_classification_code p ON p.merged_code = c.parent_code " +
                "LEFT JOIN fxy_financial_tax_classification old ON old.code = c.merged_code " +
                (isRoot ? "WHERE c.level = 1 " : "WHERE c.parent_code = ? ") +
                "ORDER BY c.merged_code";
        if (isRoot) {
            return jdbcTemplate.queryForList(sql);
        }
        return jdbcTemplate.queryForList(sql, parent.trim());
    }

    /**
     * FN-TREE-PERF：一次性拉全表（4273 行）并在内存里拼成嵌套整树，返回 level=1 的根节点列表。
     * <ul>
     *   <li>单条 SQL（无逐行 {@code EXISTS}）：取 code/name/short_name/level/parent_code/default_tax_rate，
     *       按 {@code merged_code} 排序 —— 与分层查询同序，保证输出顺序不变。</li>
     *   <li>{@code hasChildren} 在内存里算：某节点被别的节点挂为子代即为 true，无需 EXISTS 子查询。</li>
     *   <li>根 = {@code level==1}；非根按 {@code parent_code} 挂到父节点；父不在集合内的孤儿丢弃并计数告警
     *       （与原 BFS 仅从根可达的语义一致）。</li>
     * </ul>
     */
    /**
     * FN-TREE-T2：整树进程内缓存。整树是静态字典（4273 行，Flyway seed），首次构建后缓存住，
     * 后续 full=1 请求直接返回、不再拼树/查库（~0ms）。字典只随迁移变更——而迁移伴随进程重启，
     * {@code volatile} 字段随之归零、自然失效；若将来加运行时字典导入，导入处调
     * {@link #invalidateTreeCache()} 主动刷新即可。
     */
    private volatile List<Map<String, Object>> fullTreeCache;

    private List<Map<String, Object>> getFullTreeCached() {
        List<Map<String, Object>> c = fullTreeCache;
        if (c != null) return c;
        synchronized (this) {
            if (fullTreeCache == null) fullTreeCache = buildFullTree();
            return fullTreeCache;
        }
    }

    /** 字典导入/刷新后调用，失效整树缓存（预留给后续运行时导入钩子）。 */
    public void invalidateTreeCache() {
        fullTreeCache = null;
    }

    /**
     * FN-TREE-PERF：一条 SQL 拉全表（4273 行），供 {@link #buildFullTree()} 内存拼树用。
     * <p>public 仅为 spy-based 单测可替身（与 {@link #findDirectChildren} / {@link #searchClassifications} 同约定）。
     */
    public List<Map<String, Object>> findAllTreeRows() {
        // 202：补 parent_name（父节点货物名）——票面 *简称* 取上一级名。
        String sql = "SELECT c.merged_code AS code, c.goods_name AS name, c.short_name, " +
                "c.level, c.parent_code AS parent_code, p.goods_name AS parent_name, " +
                "old.default_tax_rate AS default_tax_rate " +
                "FROM fxy_financial_tax_classification_code c " +
                "LEFT JOIN fxy_financial_tax_classification_code p ON p.merged_code = c.parent_code " +
                "LEFT JOIN fxy_financial_tax_classification old ON old.code = c.merged_code " +
                "ORDER BY c.merged_code";
        return jdbcTemplate.queryForList(sql);
    }

    private List<Map<String, Object>> buildFullTree() {
        List<Map<String, Object>> rows = findAllTreeRows();

        // 先建 code -> node 索引（保持查询顺序）
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = toTreeNode(row);
            node.put("hasChildren", false);   // 内存重算：先置 false，挂到子代时翻 true
            byCode.put(String.valueOf(row.get("code")), node);
        }

        // 再按 parent_code 挂接：根=level 1；其余挂到父；孤儿丢弃计数
        List<Map<String, Object>> roots = new ArrayList<>();
        int orphans = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = byCode.get(String.valueOf(row.get("code")));
            Integer level = row.get("level") == null ? null : ((Number) row.get("level")).intValue();
            Object parentCodeObj = row.get("parent_code");
            String parentCode = parentCodeObj == null ? null : parentCodeObj.toString();

            if (level != null && level == 1) {
                roots.add(node);
                continue;
            }
            Map<String, Object> parent = parentCode == null ? null : byCode.get(parentCode);
            if (parent == null) { orphans++; continue; }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> kids = (List<Map<String, Object>>) parent.get("children");
            if (kids == null) {
                kids = new ArrayList<>();
                parent.put("children", kids);
                parent.put("hasChildren", true);
            }
            kids.add(node);
        }
        if (orphans > 0) {
            log.warn("[tax-classification/tree] full tree built with {} orphan node(s) dropped (parent_code 不在码表内)", orphans);
        }
        return roots;
    }

    /** 单个节点 → tree DTO */
    private static Map<String, Object> toTreeNode(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", row.get("code"));
        m.put("name", row.get("name"));
        // 202：shortName = 票面 *简称* = 上一级（父节点）名；顶层无父 → 退回自身 short_name/name。
        // 与 AI 路径（item.codeName=TaxCodeMatcher.invoiceShortName）同口径，手选/AI 票面简称一致。
        m.put("shortName", firstNonBlankStr(row.get("parent_name"), row.get("short_name"), row.get("name")));
        m.put("level", row.get("level") == null ? null : ((Number) row.get("level")).intValue());
        m.put("parentCode", row.get("parent_code"));
        m.put("hasChildren", toBool(row.get("has_children")));
        m.put("taxRate", row.get("default_tax_rate"));  // 旧表 LEFT JOIN，稀疏
        return m;
    }

    private static Map<String, Object> toDto(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object code = row.get("code");           // 已 alias 为 merged_code 的 19 位完整码
        // FN-NEW-T2 新字段：合并编码 + 名称 + 简称 + 说明（开票表单下拉用）
        m.put("code", code);
        m.put("mergedCode", code);
        m.put("name", row.get("name"));
        m.put("shortName", row.get("short_name"));
        m.put("description", row.get("description"));
        // xiaowei 期望的字段名（别名，兼容旧调用方）
        m.put("goodsCode", code);
        m.put("goodsName", row.get("name"));
        m.put("taxRate", row.get("default_tax_rate"));
        // invoiceType：一般纳税人可开专用发票，其余默认普通发票
        String taxpayerType = row.get("applicable_taxpayer_type") != null
                ? row.get("applicable_taxpayer_type").toString() : "";
        m.put("invoiceType", "GENERAL".equals(taxpayerType) ? "DIGITAL_SPECIAL" : "DIGITAL_NORMAL");
        // 旧应用表衍生字段（LEFT JOIN 来的，不在旧表时为 null）
        m.put("category", row.get("category"));
        m.put("defaultTaxRate", row.get("default_tax_rate"));
        m.put("applicableTaxpayerType", taxpayerType);
        m.put("remarkRequired", toBool(row.get("remark_required")));
        m.put("parentCode", row.get("parent_code"));
        // 220：是否末级（hasChildren=true→非末级，不可直接开票）+ 父节点名（票面简称/同口径 tree）。
        // 旧调用方（xiaowei）不读这两个新键，无影响；search SQL 无 has_children/parent_name 时（理论不会）回退 false/null。
        m.put("hasChildren", toBool(row.get("has_children")));
        m.put("parentName", row.get("parent_name"));
        return m;
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(v.toString());
    }

    /** 取首个非空白字符串（202：shortName 优先父节点名，无父退回自身）。 */
    private static String firstNonBlankStr(Object... vals) {
        for (Object v : vals) {
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }
}
