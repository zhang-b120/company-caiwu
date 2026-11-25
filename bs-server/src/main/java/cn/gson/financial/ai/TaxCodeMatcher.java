package cn.gson.financial.ai;

import cn.gson.financial.ai.TaxCodeLookup.TaxCodeRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FN-AI-T2（185）— 税收分类编码两阶段解析（LLM 只给关键词，后端查真实末级码）。
 *
 * <p>算法（照 demo {@code fapiao_ai_preview.py} 已验证逻辑落地，查真 DB 码表）：
 * <ol>
 *   <li><b>codeProvided</b>：取数字、短码<b>右补零到 19 位</b>（3079900→3079900000000000000），命中码表即优先采用；
 *       同时给同级候选供切换。</li>
 *   <li><b>codeKeyword</b>：按 goods_name/short_name 模糊搜，打分（名称精确+6 / 包含+3 / 简称+1 / 末级+2）选 top1。</li>
 *   <li><b>命中中类自动下钻末级</b>：top1 非末级 → 逐层下钻，每层优先"其他/未列明"兜底子项，得真实可开票末级码。</li>
 *   <li>返回 {@code code/codeName/confidence + candidates}（同类候选，操作员可切换）。</li>
 * </ol>
 *
 * <p>置信度：codeProvided 命中或名称精确命中 → high(0.85)；一般命中 → medium(0.6)；
 * 无命中 / 仍停在中类 → low(0.3)，待人工选（绝不硬填）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxCodeMatcher {

    public static final double CONF_HIGH = 0.85;
    public static final double CONF_MEDIUM = 0.6;
    public static final double CONF_LOW = 0.3;

    private static final int MAX_CANDIDATES = 5;   // 214：去噪，候选上限下调
    private static final int DESCEND_GUARD = 12;

    private final TaxCodeLookup lookup;

    /**
     * 单条编码候选（对齐 output schema 的 codeCandidates）。
     * <p>211：{@code isLeaf}=该候选是否末级（叶子）。本匹配器只产出末级候选，故恒为 true；字段透出供前端校验/显示。
     */
    public record CodeCandidate(String code, String name, BigDecimal taxRate, String applicableTaxpayerType, boolean isLeaf) {
        static CodeCandidate of(TaxCodeRow r) {
            return new CodeCandidate(r.code(), r.name(), r.defaultTaxRate(), r.applicableTaxpayerType(), r.leaf());
        }
    }

    /**
     * 解析结果。
     *
     * @param code        选定的开票末级编码（无命中=null）
     * @param codeName    选定编码的货物名称
     * @param confidence  置信度 0-1
     * @param candidates  同类候选（含选定项，操作员可切换）
     * @param note        过程说明（下钻/未命中等，可并入 assumptions/warnings）
     * @param matchedMid  命中后仍停在中类（开票需末级）→ true，触发低置信+人工
     */
    public record MatchResult(String code, String codeName, double confidence,
                              List<CodeCandidate> candidates, String note, boolean matchedMid) {
        boolean hit() {
            return code != null;
        }
    }

    public MatchResult match(String codeKeyword, String codeProvided) {
        // ── 阶段 1：codeProvided 优先（短码补零 19 位）──
        String provided19 = normalizeProvided(codeProvided);
        if (provided19 != null) {
            Optional<TaxCodeRow> row = lookup.findByCode(provided19);
            if (row.isPresent()) {
                return resolveDirect(row.get());
            }
            log.info("[FN-AI-T2] codeProvided={} 补零后 {} 码表未命中，回退 keyword", codeProvided, provided19);
        }

        // ── keyword 本身就是 19 位编码 ──
        String kw = codeKeyword == null ? "" : codeKeyword.trim();
        if (kw.matches("\\d{19}")) {
            Optional<TaxCodeRow> row = lookup.findByCode(kw);
            if (row.isPresent()) {
                return resolveDirect(row.get());
            }
        }

        // ── 阶段 2：keyword 模糊搜 + 打分 ──
        if (kw.isEmpty()) {
            return new MatchResult(null, null, CONF_LOW, List.of(), "无 codeKeyword，无法匹配", false);
        }
        List<Scored> scored = searchScored(kw);
        if (scored.isEmpty()) {
            return new MatchResult(null, null, CONF_LOW, List.of(),
                    "关键词「" + kw + "」码表未匹配，请人工选编码", false);
        }
        Scored top = scored.get(0);
        // 211：命中可能是中类（父级），收敛到末级；下钻不到末级则置空 code（绝不返回父级码）。
        TaxCodeRow leaf = toLeaf(top.row);
        if (leaf == null) {
            // 命中类目但无法定位单一末级 → code=null，给搜索命中里的末级候选 + 人工选
            return new MatchResult(null, null, CONF_LOW, buildLeafCandidates(null, scored),
                    "命中类目「" + top.row.name() + "」但无法定位末级编码，请从候选/树人工选", true);
        }
        String note = null;
        if (!leaf.code().equals(top.row.code())) {
            note = "命中中类「" + top.row.name() + "」→ 下钻末级 " + leaf.code() + " " + leaf.name();
        }
        double conf = top.score >= 6 ? CONF_HIGH : CONF_MEDIUM;   // 名称精确命中→高，否则中
        return new MatchResult(leaf.code(), leaf.name(), conf, buildLeafCandidates(leaf, scored), note, false);
    }

    /**
     * 211：文本直给编码（codeProvided / 19 位 keyword）收敛为末级。
     * 直码是末级 → 直接采用；是中类 → 下钻末级；下钻不到末级 → 置空 code、人工选（绝不返回父级码）。
     */
    private MatchResult resolveDirect(TaxCodeRow r) {
        TaxCodeRow leaf = toLeaf(r);
        if (leaf == null) {
            return new MatchResult(null, null, CONF_LOW, List.of(),
                    "文本给定编码 " + r.code() + " 为类目（非末级）且无下级末级，请人工选末级", true);
        }
        String note;
        if (leaf.code().equals(r.code())) {
            note = "采用文本直接给定编码 " + r.code() + " " + r.name();
        } else {
            note = "文本给定编码 " + r.code() + "（类目）→ 下钻末级 " + leaf.code() + " " + leaf.name();
        }
        return new MatchResult(leaf.code(), leaf.name(), CONF_HIGH, buildLeafCandidates(leaf, null), note, false);
    }

    /** 211：把行收敛到末级——已是叶子→自身；中类→下钻；下钻不到叶子→null。 */
    private TaxCodeRow toLeaf(TaxCodeRow r) {
        if (r == null) return null;
        if (r.leaf()) return r;
        TaxCodeRow leaf = descendToLeaf(r);
        return (leaf != null && leaf.leaf()) ? leaf : null;
    }

    // ─── helpers ───────────────────────────────────────────────

    /**
     * FN-AI（202）票面 {@code *简称*} = 末级码的<b>上一级（父节点）</b>官方简称/名（编码仍取末级，故意不同级）。
     * <p>例：蚊香片末级 {@code 1070299990000000000}（叶子「其他未列明化学制品」）→ 父
     * {@code 1070299000000000000}「其他化学制品」→ 票面 {@code *其他化学制品*蚊香片}。
     * <p>取父节点 {@code short_name}（官方简称）优先，无则父 {@code name}；<b>无父（顶层）退回本节点名</b>，
     * 绝不取叶子自己名 / 项目名 / 关键词。
     */
    public String invoiceShortName(String code) {
        if (code == null || code.isBlank()) return null;
        Optional<TaxCodeRow> self = lookup.findByCode(code);
        if (self.isEmpty()) return null;
        TaxCodeRow r = self.get();
        String parentCode = r.parentCode();
        if (parentCode == null || parentCode.isBlank()) {
            return firstNonBlank(r.shortName(), r.name());   // 顶层无父 → 退回自身
        }
        Optional<TaxCodeRow> parent = lookup.findByCode(parentCode);
        if (parent.isEmpty()) return firstNonBlank(r.shortName(), r.name());
        TaxCodeRow p = parent.get();
        return firstNonBlank(p.shortName(), p.name());
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 短码右补零到 19 位；非数字剥离；非法/超长 → null。 */
    static String normalizeProvided(String codeProvided) {
        if (codeProvided == null) return null;
        String digits = codeProvided.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        if (digits.length() > 19) return null;
        if (digits.length() < 19) {
            StringBuilder sb = new StringBuilder(digits);
            while (sb.length() < 19) sb.append('0');
            digits = sb.toString();
        }
        return digits;
    }

    private record Scored(TaxCodeRow row, int score) {
    }

    private List<Scored> searchScored(String keyword) {
        List<TaxCodeRow> rows = lookup.searchByKeyword(keyword, 40);
        List<Scored> out = new ArrayList<>();
        for (TaxCodeRow r : rows) {
            int score = 0;
            String name = r.name() == null ? "" : r.name();
            String shortName = r.shortName() == null ? "" : r.shortName();
            if (keyword.equals(name)) score += 6;
            else if (name.contains(keyword) || keyword.contains(name)) score += 3;
            if (shortName.contains(keyword)) score += 1;
            if (score == 0) continue;
            if (r.leaf()) score += 2;
            out.add(new Scored(r, score));
        }
        out.sort(Comparator.comparingInt(Scored::score).reversed());
        return out;
    }

    /** 逐层下钻到末级；每层优先"其他/未列明"兜底子项，其余按 code 升序取首。 */
    private TaxCodeRow descendToLeaf(TaxCodeRow start) {
        TaxCodeRow cur = start;
        int guard = 0;
        while (cur != null && !cur.leaf() && guard < DESCEND_GUARD) {
            guard++;
            List<TaxCodeRow> kids = lookup.findChildren(cur.code());
            if (kids.isEmpty()) break;
            TaxCodeRow next = null;
            // 优先"其他/未列明"
            for (TaxCodeRow k : sortByCode(kids)) {
                String n = k.name() == null ? "" : k.name();
                if (n.startsWith("其他") || n.contains("未列明")) {
                    next = k;
                    break;
                }
            }
            if (next == null) next = sortByCode(kids).get(0);
            cur = next;
        }
        return cur;
    }

    private static List<TaxCodeRow> sortByCode(List<TaxCodeRow> rows) {
        List<TaxCodeRow> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(TaxCodeRow::code));
        return copy;
    }

    /**
     * 214：构建<b>全末级</b>候选（去噪）——只给<b>相关</b>末级，不再枚举整类无关兄弟。
     * <ol>
     *   <li>选定末级 {@code chosen} 置首；</li>
     *   <li>搜索命中里的<b>相关末级</b>（keyword 命中、按 score 序、去重）。非末级一律剔除。</li>
     * </ol>
     * <b>不再枚举「同类末级兄弟」</b>（原会把蚊香片所属类目下 火柴/光洁制品/室内散香 等无关末级全塞进来，很乱）；
     * 同类切换走税编树。上限 {@link #MAX_CANDIDATES}。
     * <p>{@code chosen} 明确且无相关命中 → 候选仅 1 项；{@code chosen} 为 null（仅命中类目）→ 只收搜索命中里的相关末级。
     */
    private List<CodeCandidate> buildLeafCandidates(TaxCodeRow chosen, List<Scored> scored) {
        Map<String, CodeCandidate> byCode = new LinkedHashMap<>();
        if (chosen != null && chosen.leaf()) {
            byCode.put(chosen.code(), CodeCandidate.of(chosen));   // 选定末级置首
        }
        // 搜索命中里的相关末级（按 score 序、去重；非末级剔除）—— 不枚举同类兄弟
        if (scored != null) {
            for (Scored sc : scored) {
                if (byCode.size() >= MAX_CANDIDATES) break;
                if (sc.row.leaf()) byCode.putIfAbsent(sc.row.code(), CodeCandidate.of(sc.row));
            }
        }
        return new ArrayList<>(byCode.values());
    }
}
