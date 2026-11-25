package cn.gson.financial.ai;

import cn.gson.financial.ai.TaxCodeMatcher.CodeCandidate;
import cn.gson.financial.ai.TaxCodeMatcher.MatchResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.enums.TaxpayerType;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FN-AI-T2（185）— AI 开票建议引擎主体：组装提示词 → 调 {@link QwenClient} → 解析 → 校验纠偏 → 产出
 * <b>对齐 CollectReq 的建议草案</b>（output schema 冻结）。开票链路零改。
 *
 * <p>关键护栏：
 * <ul>
 *   <li><b>税号只提取不编造</b>：模型给的 taxNo 若未在用户文本中逐字出现，强制置 null + source≠from_prompt。</li>
 *   <li><b>编码两阶段</b>：交 {@link TaxCodeMatcher}（LLM 给关键词 → 后端查真实末级码）。</li>
 *   <li><b>税率纠偏</b>：税率须落在纳税人允许集；taxRateStated=false（文本未给定）→ 强制 warnings + 不高置信。</li>
 *   <li><b>降级不阻断</b>：Qwen key 空/超时/非 JSON → {@code degraded:true}，前端回退手填。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceSuggestService {

    private final QwenClient qwenClient;
    private final TaxCodeMatcher taxCodeMatcher;
    private final PdfExtractor pdfExtractor;

    static final String SYSTEM_PROMPT_PATH = "ai/prompts/invoice-suggest.system.txt";
    static final String USER_PROMPT_PATH = "ai/prompts/invoice-suggest.user.txt";

    private volatile String systemPromptCache;
    private volatile String userTemplateCache;

    // 置信度分层阈值（≥0.80 high / 0.50–0.80 medium / <0.50 low）
    static final double HIGH = 0.80;
    static final double MEDIUM = 0.50;

    // FN-AI-T3（187）文件上限（拍板）：图片 ≤5 张、单图 ≤10MB、单 PDF ≤20MB；vision 总图片上限兜底。
    static final int MAX_IMAGES = 5;
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    static final long MAX_PDF_BYTES = 20L * 1024 * 1024;
    static final int MAX_VISION_IMAGES = 8;

    // FN-AI-T4（188）批量上限（拍板）：一次 ≤50 张，超出截断 + warnings。
    static final int MAX_BATCH = 50;
    static final String BATCH_SYSTEM_PROMPT_PATH = "ai/prompts/invoice-suggest-batch.system.txt";
    static final String BATCH_USER_PROMPT_PATH = "ai/prompts/invoice-suggest-batch.user.txt";
    private volatile String batchSystemPromptCache;
    private volatile String batchUserTemplateCache;

    /**
     * 主入口。
     *
     * @param as  卖方账套（控制器按 accountSetsId 取，已确保非空）
     * @param req 前端入参（userPrompt/productText/invoiceTypePref/carrierPref/images/pdfs）
     * @return {suggestion:{...}, degraded:boolean}
     */
    public Map<String, Object> suggest(AccountSets as, SuggestReq req) {
        AiSellerContext seller = AiSellerContext.fromAccountSets(as);

        List<String> fileWarnings = new ArrayList<>();
        // 先处理 PDF：抽文本（并入文本路径）/ 扫描件渲染图（走 vision）
        StringBuilder pdfText = new StringBuilder();
        List<String> visionImages = new ArrayList<>();
        collectPdfs(req, pdfText, visionImages, fileWarnings);
        // 输入图片（base64）——校验数量/大小后并入 vision 图片集
        collectInputImages(req, visionImages, fileWarnings);

        // 税号校验文本 = 用户文字 + PDF 抽出的文本（图片里的内容无法当文本校验，靠 visualInput 放行）
        String verificationText = combineText(req) + "\n" + pdfText;
        boolean visualInput = !visionImages.isEmpty();

        JSONObject llm;
        try {
            String system = loadSystemPrompt();
            String userText = renderUser(seller, req);
            if (pdfText.length() > 0) {
                userText = userText + "\n【附加文本（来自上传 PDF）】\n" + pdfText;
            }
            if (visualInput) {
                // 多模态：图片/扫描件走 vision 模型
                llm = qwenClient.chatJsonVision(system, userText, capVisionImages(visionImages));
            } else {
                llm = qwenClient.chatJson(system, userText);
            }
        } catch (QwenException e) {
            // 降级：不阻断开票，前端回退手填
            log.warn("[FN-AI] Qwen 降级 accountSetsId={} visual={}：{}", seller.accountSetsId, visualInput, e.getMessage());
            return degraded(seller, e.getMessage());
        } catch (Exception e) {
            log.error("[FN-AI] AI 建议异常 accountSetsId={}（降级）", seller.accountSetsId, e);
            return degraded(seller, "AI 建议失败：" + e.getMessage());
        }

        Map<String, Object> out = build(seller, req, verificationText, llm, visualInput);
        // 文件级提醒并入 suggestion.warnings
        if (!fileWarnings.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> suggestion = (Map<String, Object>) out.get("suggestion");
            if (suggestion != null) {
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) suggestion.get("warnings");
                warnings.addAll(fileWarnings);
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // FN-AI-T4（188）批量 / 一对多：一个卖方 → 多张票
    // ════════════════════════════════════════════════════════════════

    /**
     * 批量入口：解析输入里的<b>多张发票</b>（多购方 / 多条开票要求）→ {@code suggestions[]}，
     * 每张逐字复用单张 {@link #buildSuggestion}（编码两阶段 / 税率默认 / 票种识别 / 税号规则 / 含税口径）。
     * 降级 / 文件上限 / vision 超时 / 不落盘 全沿用 187。
     *
     * @return {suggestions:[...], count, degraded, warnings, accountSets}
     */
    public Map<String, Object> suggestBatch(AccountSets as, BatchReq req) {
        AiSellerContext seller = AiSellerContext.fromAccountSets(as);

        List<String> fileWarnings = new ArrayList<>();
        StringBuilder pdfText = new StringBuilder();
        List<String> visionImages = new ArrayList<>();
        collectPdfs(req, pdfText, visionImages, fileWarnings);
        collectInputImages(req, visionImages, fileWarnings);

        String verificationText = combineText(req) + "\n" + pdfText;
        boolean visualInput = !visionImages.isEmpty();

        JSONArray invoices;
        try {
            String system = loadBatchSystemPrompt();
            String userText = renderBatchUser(seller, req);
            if (pdfText.length() > 0) {
                userText = userText + "\n【附加文本（来自上传 PDF）】\n" + pdfText;
            }
            JSONObject llm = visualInput
                    ? qwenClient.chatJsonVision(system, userText, capVisionImages(visionImages))
                    : qwenClient.chatJson(system, userText);
            invoices = llm.getJSONArray("invoices");
            if (invoices == null) invoices = new JSONArray();
        } catch (QwenException e) {
            log.warn("[FN-AI-T4] Qwen 批量降级 accountSetsId={} visual={}：{}", seller.accountSetsId, visualInput, e.getMessage());
            return degradedBatch(seller, e.getMessage());
        } catch (Exception e) {
            log.error("[FN-AI-T4] AI 批量建议异常 accountSetsId={}（降级）", seller.accountSetsId, e);
            return degradedBatch(seller, "AI 批量建议失败：" + e.getMessage());
        }

        List<String> warnings = new ArrayList<>(fileWarnings);
        JSONArray commonItems = normalizeCommonItems(req.getCommonItems());

        int totalRecognized = invoices.size();
        if (totalRecognized > MAX_BATCH) {
            warnings.add("识别到 " + totalRecognized + " 张，超过批量上限 " + MAX_BATCH
                    + "，仅取前 " + MAX_BATCH + " 张，其余请分批处理");
        }
        int n = Math.min(totalRecognized, MAX_BATCH);

        List<Map<String, Object>> suggestions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject inv = invoices.getJSONObject(i);
            // commonItems 给定 → 统一商品套用到每张（金额按各张补全）；否则用模型逐张明细
            JSONArray itemsOverride = (commonItems != null && !commonItems.isEmpty())
                    ? mergeCommonItems(commonItems, inv) : null;
            suggestions.add(buildSuggestion(seller, req, verificationText, inv, itemsOverride, visualInput));
        }
        if (suggestions.isEmpty()) {
            warnings.add("未识别到任何发票，请检查输入（清单/文件）或改用单张开票");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestions", suggestions);
        out.put("count", suggestions.size());
        out.put("degraded", false);
        out.put("warnings", warnings);
        out.put("accountSets", accountSetsBlock(seller));
        return out;
    }

    private Map<String, Object> degradedBatch(AiSellerContext seller, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestions", new ArrayList<>());
        out.put("count", 0);
        out.put("degraded", true);
        out.put("message", message);
        out.put("warnings", new ArrayList<>());
        out.put("accountSets", accountSetsBlock(seller));
        return out;
    }

    private static Map<String, Object> accountSetsBlock(AiSellerContext seller) {
        Map<String, Object> accountSets = new LinkedHashMap<>();
        accountSets.put("companyName", seller.companyName);
        accountSets.put("taxpayerType", seller.taxpayerType.name());
        return accountSets;
    }

    /** commonItems（前端统一商品/税率）→ LLM-item 形状 JSONArray（code 19 位 → 当 codeProvided）。 */
    static JSONArray normalizeCommonItems(List<Map<String, Object>> common) {
        if (common == null || common.isEmpty()) return null;
        JSONArray arr = new JSONArray();
        for (Map<String, Object> m : common) {
            if (m == null) continue;
            JSONObject it = new JSONObject();
            it.put("name", m.get("name"));
            // 已解析的 19 位 code → 走 codeProvided 优先采用；否则透传 codeProvided/codeKeyword
            Object code = m.get("code");
            Object codeProvided = m.get("codeProvided");
            Object codeKeyword = m.get("codeKeyword");
            if (codeProvided != null) it.put("codeProvided", codeProvided);
            else if (code != null) it.put("codeProvided", code);
            if (codeKeyword != null) it.put("codeKeyword", codeKeyword);
            it.put("amount", m.get("amount"));
            it.put("taxRate", m.get("taxRate"));
            // 显式 stated；未给则：给了 taxRate 视为明确，否则按纳税人默认
            it.put("taxRateStated", m.containsKey("taxRateStated")
                    ? m.get("taxRateStated") : (m.get("taxRate") != null));
            it.put("spec", m.get("spec"));
            it.put("unit", m.get("unit"));
            it.put("qty", m.get("qty"));
            it.put("price", m.get("price"));
            arr.add(it);
        }
        return arr;
    }

    /**
     * 统一商品套到一张发票：commonItems 当模板（商品/编码/税率），缺 amount 时按该张金额补全
     * （蚊香片场景：商品/税率统一，金额逐张变）。
     */
    static JSONArray mergeCommonItems(JSONArray commonItems, JSONObject invoice) {
        List<BigDecimal> invAmounts = invoiceAmounts(invoice);
        JSONArray result = new JSONArray();
        for (int i = 0; i < commonItems.size(); i++) {
            JSONObject t = new JSONObject(new LinkedHashMap<>(commonItems.getJSONObject(i)));
            if (t.get("amount") == null) {
                if (i < invAmounts.size()) t.put("amount", invAmounts.get(i));
                else if (!invAmounts.isEmpty()) t.put("amount", invAmounts.get(0));
            }
            result.add(t);
        }
        return result;
    }

    /** 取一张发票里的金额序列（优先 items[].amount，否则顶层 amount）。 */
    private static List<BigDecimal> invoiceAmounts(JSONObject invoice) {
        List<BigDecimal> out = new ArrayList<>();
        JSONArray its = invoice.getJSONArray("items");
        if (its != null) {
            for (int i = 0; i < its.size(); i++) {
                BigDecimal a = its.getJSONObject(i).getBigDecimal("amount");
                if (a != null) out.add(a);
            }
        }
        if (out.isEmpty()) {
            BigDecimal top = invoice.getBigDecimal("amount");
            if (top != null) out.add(top);
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // FN-AI-T3（187）文件输入处理（PDF 抽文本/渲染、图片校验）——内存处理，不落盘
    // ════════════════════════════════════════════════════════════════

    private void collectPdfs(SuggestReq req, StringBuilder pdfText, List<String> visionImages, List<String> warnings) {
        if (req.getPdfs() == null) return;
        int n = 0;
        for (String pdfB64 : req.getPdfs()) {
            if (pdfB64 == null || pdfB64.isBlank()) continue;
            n++;
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(stripDataPrefix(pdfB64).replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                warnings.add("第 " + n + " 个 PDF base64 非法，已跳过");
                continue;
            }
            if (bytes.length > MAX_PDF_BYTES) {
                warnings.add("第 " + n + " 个 PDF 超过 20MB，已跳过");
                continue;
            }
            try {
                PdfExtractor.PdfContent c = pdfExtractor.extract(bytes);
                if (c.hasText()) {
                    pdfText.append(c.text()).append('\n');
                } else if (c.hasImages()) {
                    visionImages.addAll(c.pageImagesBase64());
                    if (c.scanTruncated()) {
                        warnings.add("第 " + n + " 个 PDF 为扫描件共 " + c.totalPages() + " 页，仅识别前 " + c.renderedPages() + " 页，其余未含，请核对/手工补全");
                    }
                }
            } catch (PdfExtractor.PdfExtractException e) {
                log.warn("[FN-AI-T3] PDF 解析失败（跳过）：{}", e.getMessage());
                warnings.add("第 " + n + " 个 PDF 解析失败，已跳过：" + e.getMessage());
            }
        }
    }

    private void collectInputImages(SuggestReq req, List<String> visionImages, List<String> warnings) {
        if (req.getImages() == null) return;
        int kept = 0;
        int seen = 0;
        for (String img : req.getImages()) {
            if (img == null || img.isBlank()) continue;
            seen++;
            if (kept >= MAX_IMAGES) {
                warnings.add("图片超过 " + MAX_IMAGES + " 张，仅取前 " + MAX_IMAGES + " 张");
                break;
            }
            if (estimatedBytes(img) > MAX_IMAGE_BYTES) {
                warnings.add("第 " + seen + " 张图片超过 10MB，已跳过");
                continue;
            }
            visionImages.add(img.trim());
            kept++;
        }
    }

    /** vision 总图片兜底上限（输入图 + 扫描件页），超出截断并不影响主流程。 */
    private static List<String> capVisionImages(List<String> imgs) {
        if (imgs.size() <= MAX_VISION_IMAGES) return imgs;
        return new ArrayList<>(imgs.subList(0, MAX_VISION_IMAGES));
    }

    /** 估算 base64 解码后字节数（不真解码，省内存）：约 len*3/4，去掉 data: 前缀与 padding 误差可忽略。 */
    static long estimatedBytes(String base64OrDataUrl) {
        String b = stripDataPrefix(base64OrDataUrl).replaceAll("\\s", "");
        return (long) (b.length() * 3L / 4);
    }

    /** 去掉 {@code data:...;base64,} 前缀，返回纯 base64。 */
    static String stripDataPrefix(String s) {
        if (s == null) return "";
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma >= 0) {
            return s.substring(comma + 1);
        }
        return s;
    }

    // ════════════════════════════════════════════════════════════════
    // 组装输出（冻结 schema）
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> build(AiSellerContext seller, SuggestReq req, String combinedText, JSONObject llm,
                                      boolean visualInput) {
        Map<String, Object> suggestion = buildSuggestion(seller, req, combinedText, llm, null, visualInput);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestion", suggestion);
        out.put("degraded", false);
        return out;
    }

    /**
     * 组装<b>单张 suggestion</b>（items/header/票种区/assumptions/warnings/fieldStatus/accountSets）。
     * <p>单张端点（{@link #suggest}）与批量端点（{@link #suggestBatch}，FN-AI-T4/188）<b>逐字共用</b>本方法
     * —— 编码两阶段解析 / 税率默认 / 票种识别 / 税号规则（含图片放行）/ 含税口径完全一致。
     *
     * @param itemsOverride 非空 → 用它当明细（批量 {@code commonItems} 统一商品套用到每张）；null → 用 {@code llm.items}
     */
    Map<String, Object> buildSuggestion(AiSellerContext seller, SuggestReq req, String combinedText,
                                        JSONObject llm, JSONArray itemsOverride, boolean visualInput) {
        List<String> assumptions = new ArrayList<>(strList(llm.getJSONArray("assumptions")));
        List<String> warnings = new ArrayList<>(strList(llm.getJSONArray("warnings")));
        Map<String, String> fieldStatus = new LinkedHashMap<>();

        // ── 明细 ──（批量统一商品 itemsOverride 优先；否则用模型逐张明细）
        JSONArray llmItems = itemsOverride != null ? itemsOverride : llm.getJSONArray("items");
        List<Map<String, Object>> items = new ArrayList<>();
        int idx = 0;
        if (llmItems != null) {
            for (int i = 0; i < llmItems.size(); i++) {
                JSONObject it = llmItems.getJSONObject(i);
                items.add(buildItem(it, seller.taxpayerType, idx, warnings, assumptions, fieldStatus));
                idx++;
            }
        }
        if (items.isEmpty()) {
            warnings.add("未识别到任何商品明细，请人工补充");
        }

        // ── 抬头（税号只提取不编造 + 242 购方≠销方兜底）──
        Map<String, Object> header = buildHeader(llm.getJSONObject("header"), seller, combinedText, visualInput, warnings, fieldStatus);

        // ── 票种区（含票种识别：专/普 + invoiceTypeStated + 专票要素校验）──
        String taxInclusion = normTaxInclusion(llm.getString("taxInclusion"));
        String invoiceType = resolveInvoiceType(req.getInvoiceTypePref(), llm.getString("invoiceType"), seller.taxpayerType);
        boolean invoiceTypeStated = invoiceTypeStated(req.getInvoiceTypePref());
        String invoiceCarrier = resolveCarrier(req.getCarrierPref(), llm.getString("invoiceCarrier"));
        if (!invoiceTypeStated) {
            warnings.add("票种为按倾向/纳税人推断（" + ("SPECIAL".equals(invoiceType) ? "专票" : "普票") + "），请核对");
        }
        // 专票要素：购方名称/税号 + 地址/电话/开户行/账号需齐全（缺要素=不能开/不能抵扣）
        if ("SPECIAL".equals(invoiceType)) {
            List<String> miss = new ArrayList<>();
            if (header.get("taxNo") == null) miss.add("税号");
            if (header.get("companyName") == null) miss.add("名称");
            if (header.get("address") == null) miss.add("地址");
            if (header.get("phone") == null) miss.add("电话");
            if (header.get("bank") == null) miss.add("开户行");
            if (header.get("bankAccount") == null) miss.add("账号");
            if (!miss.isEmpty()) {
                warnings.add("专用发票需购方要素齐全，当前缺：" + String.join("、", miss) + "，请补全或改普票");
            }
        }
        fieldStatus.put("taxInclusion", "high");
        fieldStatus.put("invoiceType", invoiceTypeStated ? "high" : "medium");
        fieldStatus.put("invoiceCarrier", "high");

        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("items", items);
        suggestion.put("header", header);
        suggestion.put("taxInclusion", taxInclusion);
        suggestion.put("invoiceType", invoiceType);
        suggestion.put("invoiceTypeStated", invoiceTypeStated);
        suggestion.put("invoiceCarrier", invoiceCarrier);
        suggestion.put("specialBusiness", null);      // 次要字段，默认 null（人工按需补）
        suggestion.put("differentialTax", null);
        suggestion.put("assumptions", assumptions);
        suggestion.put("warnings", warnings);
        suggestion.put("fieldStatus", fieldStatus);

        Map<String, Object> accountSets = new LinkedHashMap<>();
        accountSets.put("companyName", seller.companyName);
        accountSets.put("taxpayerType", seller.taxpayerType.name());
        suggestion.put("accountSets", accountSets);

        return suggestion;
    }

    /** 单条明细 → output item（含编码两阶段解析 + 税率纠偏）。 */
    private Map<String, Object> buildItem(JSONObject it, TaxpayerType taxpayerType, int idx,
                                          List<String> warnings, List<String> assumptions,
                                          Map<String, String> fieldStatus) {
        String name = trimToNull(it.getString("name"));
        if (name == null) name = "";

        // 编码两阶段解析
        MatchResult mr = taxCodeMatcher.match(it.getString("codeKeyword"), it.getString("codeProvided"));
        if (mr.note() != null && !mr.note().isBlank()) {
            if (mr.matchedMid() || !mr.hit()) warnings.add(name + "：" + mr.note());
            else assumptions.add(name + "：" + mr.note());
        }
        if (!mr.hit()) {
            warnings.add(name + "：税收分类编码未匹配，请人工选（codeCandidates 可选）");
        }

        // 税率默认 / 纠偏（FN-AI-T7/191：未给或未明确 → 按纳税人类型默认 1%/6%，绝不静默落 0）
        BigDecimal llmRate = it.getBigDecimal("taxRate");
        boolean stated = Boolean.TRUE.equals(it.getBoolean("taxRateStated"));
        Set<BigDecimal> allowed = allowedRates(taxpayerType);
        boolean isZero = llmRate != null && llmRate.compareTo(BigDecimal.ZERO) == 0;

        boolean defaulted = false;
        BigDecimal rate;
        if (llmRate == null || (isZero && !stated)) {
            // LLM 未给税率，或给了 0 但未声明免税 → 按纳税人类型默认（GENERAL 6% / 小规模·个体 1%）。
            // 注意：allowedRates 含 0（免税），不能靠 containsRate 兜底——0 在允许集会被直接接受，故必须在此显式默认。
            rate = defaultRate(taxpayerType);
            defaulted = true;
            stated = false;     // 默认值不算"文本明确"，触发人工核对
        } else {
            rate = llmRate;     // 含"明确免税 0%（taxRateStated=true）"原样保留
        }

        boolean adjusted = false;
        if (!defaulted && !containsRate(allowed, rate)) {
            // LLM 给了非 0 且不在纳税人允许集 → 优先用编码默认税率纠偏，否则强提醒（口径不变）
            BigDecimal codeRate = candidateRate(mr);
            if (codeRate != null && containsRate(allowed, codeRate)) {
                rate = codeRate;
                adjusted = true;
                warnings.add(name + "：税率 " + pct(llmRate) + " 与纳税人类型不符，已按编码默认税率调整为 " + pct(codeRate) + "，请核对");
            } else {
                warnings.add(name + "：税率 " + pct(llmRate) + " 与纳税人类型「" + taxpayerType.name() + "」允许集不符，请人工核对");
            }
        }
        if (!stated && !adjusted) {
            warnings.add(name + "：税率 " + pct(rate) + " 为按纳税人类型默认（文本未给定），请核对");
        }

        // 置信度：编码置信 ∩ 税率是否文本明确 ∩ 是否纠偏
        double itemConf = mr.confidence();
        if (!stated) itemConf = Math.min(itemConf, MEDIUM + 0.05);   // 推断税率不作高置信
        if (adjusted) itemConf = Math.min(itemConf, MEDIUM + 0.05);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);                                       // 纯品名（不带 * *）
        out.put("code", mr.code());                                  // 真实末级编码 | null（不变）
        // 票面 *简称* = 末级码的上一级（父节点）官方简称/名（202）；无命中 → null。不取叶子自己名/项目名/关键词。
        out.put("codeName", mr.hit() ? taxCodeMatcher.invoiceShortName(mr.code()) : null);
        out.put("codeConfidence", round2(mr.confidence()));
        out.put("codeCandidates", candidatesToList(mr.candidates()));
        out.put("spec", trimToNull(it.getString("spec")));
        out.put("unit", trimToNull(it.getString("unit")));
        out.put("qty", it.getBigDecimal("qty"));
        out.put("price", it.getBigDecimal("price"));
        out.put("amount", llmAmount(it));
        out.put("taxRate", rate);
        out.put("taxRateStated", stated);
        out.put("taxRateAdjusted", adjusted);
        out.put("confidence", round2(itemConf));
        out.put("reason", trimToNull(it.getString("reason")));

        fieldStatus.put("items[" + idx + "].code", level(mr.confidence()));
        fieldStatus.put("items[" + idx + "].taxRate", stated && !adjusted ? "high" : "medium");
        return out;
    }

    /**
     * 抬头：税号只提取不编造。
     * <ul>
     *   <li><b>文本路径</b>（visualInput=false）：taxNo 若未在文本逐字出现 → 强制 null + source≠from_prompt（防臆造，185 红线）。</li>
     *   <li><b>多模态路径</b>（visualInput=true，FN-AI-T3）：taxNo 由上传图片/扫描件读出、本就不在文本中，
     *       <b>保留</b>该 taxNo、source=from_prompt（来自上传文件），但置中等置信 + 提醒人工核对（图片无法做文本逐字校验）。</li>
     * </ul>
     */
    private Map<String, Object> buildHeader(JSONObject h, AiSellerContext seller, String combinedText, boolean visualInput,
                                            List<String> warnings, Map<String, String> fieldStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (h == null) h = new JSONObject();

        String companyName = trimToNull(h.getString("companyName"));
        String taxNo = trimToNull(h.getString("taxNo"));

        // 242：购方疑似被模型把【卖方上下文】整块抄成销方（购方=销方）→ 置 null + 警告。
        // 与 visualInput 无关、先于 taxNoFromImage 图片信任分支（图片路径这次就是漏的口）。
        boolean nameIsSeller = companyName != null && eqSeller(companyName, seller.companyName);
        boolean taxIsSeller = taxNo != null && eqSeller(taxNo, seller.creditCode);
        if (nameIsSeller || taxIsSeller) {
            log.info("[242] 购方疑似误识别为销方（name/tax 命中销方）→ 置 null 待人工填 accountSetsId={}", seller.accountSetsId);
            companyName = null;
            taxNo = null;
            warnings.add("购买方疑似被误识别为销售方自己，已清空，请人工填写购买方名称/税号");
        }

        boolean taxNoInText = taxNo != null && textContains(combinedText, taxNo);
        // 多模态：taxNo 来自图片，无法文本校验 → 视为"来自上传文件"，不当臆造丢弃
        boolean taxNoFromImage = taxNo != null && !taxNoInText && visualInput;
        boolean taxNoFabricated = taxNo != null && !taxNoInText && !visualInput;
        if (taxNoFabricated) {
            log.info("[FN-AI-T2] 模型给的 taxNo 未在文本出现，强制置 null（不编造）");
            taxNo = null;
        }

        String source;
        double headerConf;
        boolean taxNoTrusted;     // 决定 fieldStatus / 高亮
        if (taxNoInText) {
            source = "from_prompt";
            headerConf = 0.85;
            taxNoTrusted = true;
        } else if (taxNoFromImage) {
            source = "from_prompt";     // 来自上传文件（图片/扫描件）
            headerConf = 0.7;
            taxNoTrusted = false;       // 仍要人工核对（图片识别非逐字校验）
        } else if (companyName != null) {
            source = "inferred";
            headerConf = 0.5;
            taxNoTrusted = false;
        } else {
            source = "empty";
            headerConf = 0.2;
            taxNoTrusted = false;
        }

        if (taxNo == null) {
            warnings.add(taxNoFabricated
                    ? "购买方税号无法从文本提取（模型臆造已丢弃），请人工填写"
                    : "购买方税号缺失，请人工填写");
        } else if (taxNoFromImage) {
            warnings.add("购买方税号来自上传图片/PDF（非文本校验），请人工核对");
        }
        if (companyName == null) {
            warnings.add("购买方公司名缺失，请人工填写");
        }

        out.put("companyName", companyName);
        out.put("taxNo", taxNo);
        out.put("phone", trimToNull(h.getString("phone")));
        out.put("email", trimToNull(h.getString("email")));
        out.put("address", trimToNull(h.getString("address")));
        out.put("bank", trimToNull(h.getString("bank")));
        out.put("bankAccount", trimToNull(h.getString("bankAccount")));
        out.put("confidence", round2(headerConf));
        out.put("source", source);

        fieldStatus.put("companyName", companyName != null ? "high" : "low");
        fieldStatus.put("taxNo", taxNoTrusted ? "high" : (taxNo != null ? "medium" : "low"));
        return out;
    }

    private Map<String, Object> degraded(AiSellerContext seller, String message) {
        Map<String, Object> accountSets = new LinkedHashMap<>();
        accountSets.put("companyName", seller.companyName);
        accountSets.put("taxpayerType", seller.taxpayerType.name());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestion", null);
        out.put("degraded", true);
        out.put("message", message);
        out.put("accountSets", accountSets);
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // 票种 / 税率 / 文本 helpers
    // ════════════════════════════════════════════════════════════════

    static String resolveInvoiceType(String pref, String llmType, TaxpayerType taxpayerType) {
        String p = pref == null ? "AUTO" : pref.trim().toUpperCase();
        if ("GENERAL".equals(p) || "SPECIAL".equals(p)) return p;
        // AUTO：小规模/个体默认普票；一般纳税人按模型诉求（默认普票）
        if (taxpayerType == TaxpayerType.GENERAL) {
            return "SPECIAL".equalsIgnoreCase(llmType) ? "SPECIAL" : "GENERAL";
        }
        return "GENERAL";
    }

    /** 票种是否文本/用户明确给定：pref 显式 GENERAL/SPECIAL → true；AUTO → false（推断，需人工核对）。 */
    static boolean invoiceTypeStated(String pref) {
        String p = pref == null ? "AUTO" : pref.trim().toUpperCase();
        return "GENERAL".equals(p) || "SPECIAL".equals(p);
    }

    static String resolveCarrier(String pref, String llmCarrier) {
        String p = pref == null ? "AUTO" : pref.trim();
        if ("01".equals(p) || "02".equals(p)) return p;
        return "02".equals(llmCarrier) ? "02" : "01";   // AUTO → 模型值，默认电子
    }

    static String normTaxInclusion(String v) {
        return "1".equals(v) ? "1" : "2";   // 默认含税
    }

    /** 纳税人允许税率集（小数）。一般 13/9/6（+3/1 征收率）；小规模/个体 1/3。0 表示免税。 */
    static Set<BigDecimal> allowedRates(TaxpayerType t) {
        return switch (t) {
            case GENERAL -> Set.of(bd("0.13"), bd("0.09"), bd("0.06"), bd("0.03"), bd("0.01"), bd("0"));
            case SMALL_SCALE, INDIVIDUAL -> Set.of(bd("0.03"), bd("0.01"), bd("0"));
        };
    }

    /**
     * FN-AI-T7（191）税率未给定时的纳税人默认值：一般纳税人 6%，小规模/个体 1%。
     * 仅作"文本没写税率"的兜底（taxRateStated=false），<b>绝不静默落 0</b>——0 是免税、需显式声明。
     */
    static BigDecimal defaultRate(TaxpayerType t) {
        return t == TaxpayerType.GENERAL ? bd("0.06") : bd("0.01");
    }

    private static boolean containsRate(Set<BigDecimal> set, BigDecimal r) {
        for (BigDecimal x : set) {
            if (x.compareTo(r) == 0) return true;
        }
        return false;
    }

    private static BigDecimal candidateRate(MatchResult mr) {
        if (mr.candidates() == null) return null;
        for (CodeCandidate c : mr.candidates()) {
            if (c.taxRate() != null) return c.taxRate();
        }
        return null;
    }

    // 221：实例方法（非 static）——要调 taxCodeMatcher.invoiceShortName 给候选补简称；
    // 调用点在 buildItem 实例链路内（candidatesToList(mr.candidates())），不受影响。
    private List<Map<String, Object>> candidatesToList(List<CodeCandidate> cs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cs == null) return out;
        for (CodeCandidate c : cs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("name", c.name());                               // 末级自己名（前端区分用）
            // 221：票面简称=末级码父名，与匹配命中 codeName 同方法（invoiceShortName），口径一致
            m.put("shortName", taxCodeMatcher.invoiceShortName(c.code()));
            m.put("taxRate", c.taxRate());
            m.put("applicableTaxpayerType", c.applicableTaxpayerType() == null ? "BOTH" : c.applicableTaxpayerType());
            m.put("isLeaf", c.isLeaf());   // 211：候选恒为末级（叶子）
            out.add(m);
        }
        return out;
    }

    static String combineText(SuggestReq req) {
        StringBuilder sb = new StringBuilder();
        if (req.getUserPrompt() != null) sb.append(req.getUserPrompt());
        if (req.getProductText() != null) sb.append('\n').append(req.getProductText());
        return sb.toString();
    }

    /** 文本是否含目标串（去空白归一，容忍模型/文本里的空格差异）。 */
    static boolean textContains(String text, String needle) {
        if (text == null || needle == null) return false;
        String t = text.replaceAll("\\s", "");
        String n = needle.replaceAll("\\s", "");
        return !n.isEmpty() && t.contains(n);
    }

    /**
     * 242：购方字段是否等同于销方（疑似把【卖方上下文】抄成购方）。
     * 归一后比较：去全/半角空白 + 全角 ASCII→半角 + 大写（税号大小写无关、名称空格无关）。
     */
    static boolean eqSeller(String headerVal, String sellerVal) {
        String a = normForCompare(headerVal);
        String b = normForCompare(sellerVal);
        return !a.isEmpty() && a.equals(b);
    }

    /** 归一：去空白（含全角空格）、全角 ASCII→半角、转大写。 */
    static String normForCompare(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '　' || Character.isWhitespace(c)) continue;     // 去空白（含全角空格 U+3000）
            if (c >= '！' && c <= '～') c -= 0xFEE0;             // 全角 ASCII → 半角
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static BigDecimal llmAmount(JSONObject it) {
        BigDecimal a = it.getBigDecimal("amount");
        return a == null ? BigDecimal.ZERO : a;
    }

    static String level(double conf) {
        if (conf >= HIGH) return "high";
        if (conf >= MEDIUM) return "medium";
        return "low";
    }

    private static String pct(BigDecimal rate) {
        return rate.multiply(bd("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static List<String> strList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getString(i);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    // ════════════════════════════════════════════════════════════════
    // 提示词资源（外部文件，改文案不重编译）
    // ════════════════════════════════════════════════════════════════

    String loadSystemPrompt() {
        if (systemPromptCache == null) systemPromptCache = readResource(SYSTEM_PROMPT_PATH);
        return systemPromptCache;
    }

    String renderUser(AiSellerContext seller, SuggestReq req) {
        if (userTemplateCache == null) userTemplateCache = readResource(USER_PROMPT_PATH);
        String invoiceTypePref = req.getInvoiceTypePref() == null ? "AUTO" : req.getInvoiceTypePref();
        String carrierPref = req.getCarrierPref() == null ? "01" : req.getCarrierPref();
        return userTemplateCache
                .replace("{{sellerContext}}", seller.toPromptBlock())
                .replace("{{invoiceTypePref}}", invoiceTypePref)
                .replace("{{carrierPref}}", carrierPref)
                .replace("{{userText}}", combineText(req));
    }

    String loadBatchSystemPrompt() {
        if (batchSystemPromptCache == null) batchSystemPromptCache = readResource(BATCH_SYSTEM_PROMPT_PATH);
        return batchSystemPromptCache;
    }

    String renderBatchUser(AiSellerContext seller, BatchReq req) {
        if (batchUserTemplateCache == null) batchUserTemplateCache = readResource(BATCH_USER_PROMPT_PATH);
        String invoiceTypePref = req.getInvoiceTypePref() == null ? "AUTO" : req.getInvoiceTypePref();
        String carrierPref = req.getCarrierPref() == null ? "01" : req.getCarrierPref();
        return batchUserTemplateCache
                .replace("{{sellerContext}}", seller.toPromptBlock())
                .replace("{{invoiceTypePref}}", invoiceTypePref)
                .replace("{{carrierPref}}", carrierPref)
                .replace("{{commonItemsBlock}}", commonItemsBlock(req.getCommonItems()))
                .replace("{{userText}}", combineText(req));
    }

    /** 统一商品/税率提示块（给模型：每张套用同样商品，只变金额）；无则空串。 */
    private static String commonItemsBlock(List<Map<String, Object>> common) {
        if (common == null || common.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n【统一商品/税率（套用到每张，只变各自金额）】\n");
        for (Map<String, Object> m : common) {
            if (m == null) continue;
            sb.append("- 商品：").append(nz(m.get("name")));
            if (m.get("taxRate") != null) sb.append("；税率：").append(m.get("taxRate"));
            if (m.get("codeKeyword") != null) sb.append("；类别：").append(m.get("codeKeyword"));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载提示词资源失败：" + path, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 入参 DTO（input schema 冻结）
    // ════════════════════════════════════════════════════════════════

    @lombok.Data
    public static class SuggestReq {
        /** 必填；卖方上下文据此自动取（雪花 id 字符串） */
        private String accountSetsId;
        private String userPrompt;
        private String productText;
        /** GENERAL | SPECIAL | AUTO（默认 AUTO） */
        private String invoiceTypePref;
        /** 01 电子(默认) | 02 纸质 | AUTO */
        private String carrierPref;
        /**
         * FN-AI-T3（187）图片 base64 数组（可带 {@code data:image/...;base64,} 前缀）。
         * 有图片 → 走 vision 模型；≤5 张、单图 ≤10MB（超限跳过/截断并 warnings）。
         */
        private List<String> images;
        /**
         * FN-AI-T3（187）PDF base64 数组。后端抽文本（→文本路径）或扫描件渲染前 3 页（→vision）。
         * 单 PDF ≤20MB（超限跳过并 warnings）。文件仅用于解析、不落盘。
         */
        private List<String> pdfs;
    }

    /**
     * FN-AI-T4（188）批量入参 = 单张入参 + 可选 {@code commonItems}（统一商品/税率，套用到每张）。
     * commonItems 元素：{@code {name, code?(19位)/codeKeyword?/codeProvided?, taxRate?, taxRateStated?,
     * amount?, spec?, unit?, qty?, price?}}；amount 缺省时按各张金额补全（蚊香片一对多）。
     */
    @lombok.Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class BatchReq extends SuggestReq {
        private List<Map<String, Object>> commonItems;
    }
}
