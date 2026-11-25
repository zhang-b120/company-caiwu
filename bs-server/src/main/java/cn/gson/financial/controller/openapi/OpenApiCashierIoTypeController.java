package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.dto.CashierIoTypeVoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.CashierIoTypeService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * FN-NEW-CASHIER-IO-TYPE-LIST
 *
 * <p>GET /openapi/v1/cashier-io-type/list?accountSetsId=
 *
 * <p>返回账套全部收支类别清单，供 XW 建立「ioTypeCode ↔ 名字」映射，
 * 以便调 cashier-journal/batch 时传正确的 ioTypeCode（账套真实编码，非名字）。
 *
 * <p>绑定了凭证模板（CashierIoTypeVoucherTemplate）的收支类别会附带对方科目
 * （subjectId / subjectCode / subjectName），便于 XW 做「收支类别 ↔ 对方科目」
 * 一致性校验，减少 batch 写错 subjectCode 的概率。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/cashier-io-type")
@SaIgnore
public class OpenApiCashierIoTypeController {

    private final CashierIoTypeService cashierIoTypeService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QSubject qSubject = QSubject.subject;

    /**
     * GET /openapi/v1/cashier-io-type/list?accountSetsId=
     *
     * <p>返回字段：
     * <pre>
     * {
     *   "id": 12,
     *   "code": "3",          ← cashier-journal/batch 的 ioTypeCode 传这里的值
     *   "name": "工资支出",
     *   "type": "支",          ← 收 / 支（与 direction 对应：支=debit，收=credit）
     *   "matchWords": ["工资","薪资"],  ← 摘要自动匹配关键词，可为 null
     *   "templates": [         ← 绑定的凭证模板（无模板则为空列表）
     *     {
     *       "templateId": 55,
     *       "journalType": "银行存款",
     *       "direction": "贷",
     *       "subjectId": 1001,
     *       "subjectCode": "2211",
     *       "subjectName": "应付职工薪酬"
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/list")
    public JsonResult list(@RequestParam Long accountSetsId) {
        List<CashierIoTypeDto> dtos = cashierIoTypeService.list(accountSetsId, null);

        Map<Long, String[]> subjectInfoMap = loadSubjectInfo(dtos);
        List<Map<String, Object>> rows = buildRows(dtos, subjectInfoMap);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("total", rows.size());
        return JsonResult.successful(data);
    }

    // ─── helpers (package-private for spy-based tests) ───────────────────────

    /** 批量加载 templates 里涉及的所有科目 code + fullName，避免 N+1 */
    Map<Long, String[]> loadSubjectInfo(List<CashierIoTypeDto> dtos) {
        Set<Long> ids = new HashSet<>();
        for (CashierIoTypeDto dto : dtos) {
            if (dto.getTemplates() != null) {
                for (CashierIoTypeVoucherTemplateDto t : dto.getTemplates()) {
                    Long sid = parseSubjectId(t.getSubjectId());
                    if (sid != null) ids.add(sid);
                }
            }
        }
        Map<Long, String[]> map = new HashMap<>();
        if (!ids.isEmpty()) {
            bqf.selectFrom(qSubject).where(qSubject.id.in(ids)).fetch()
               .forEach(s -> map.put(s.getId(), new String[]{s.getCode(), s.getFullName()}));
        }
        return map;
    }

    List<Map<String, Object>> buildRows(List<CashierIoTypeDto> dtos, Map<Long, String[]> subjectInfoMap) {
        List<Map<String, Object>> rows = new ArrayList<>(dtos.size());
        for (CashierIoTypeDto dto : dtos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", dto.getId());
            row.put("code", dto.getCode());
            row.put("name", dto.getName());
            row.put("type", dto.getType() != null ? dto.getType().name() : null);
            row.put("matchWords", dto.getMatchWords());

            List<Map<String, Object>> tpls = new ArrayList<>();
            if (dto.getTemplates() != null) {
                for (CashierIoTypeVoucherTemplateDto t : dto.getTemplates()) {
                    Long sid = parseSubjectId(t.getSubjectId());
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("templateId", t.getId());
                    tm.put("journalType", t.getJournalType() != null ? t.getJournalType().name() : null);
                    tm.put("direction", t.getDirection() != null ? t.getDirection().name() : null);
                    tm.put("subjectId", sid);
                    String[] info = sid != null ? subjectInfoMap.get(sid) : null;
                    tm.put("subjectCode", info != null ? info[0] : null);
                    tm.put("subjectName", info != null ? info[1] : null);
                    tpls.add(tm);
                }
            }
            row.put("templates", tpls);
            rows.add(row);
        }
        return rows;
    }

    private static Long parseSubjectId(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException ignore) { return null; }
    }
}
