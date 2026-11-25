package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.TaxDeclare;
import cn.gson.financial.kernel.model.repo.TaxDeclareRepository;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 应申报税费种信息：按账套整批覆盖保存（先删后插，无数据时写占位记录）
 */
@Service
@RequiredArgsConstructor
public class TaxDeclareService {

    private final TaxDeclareRepository taxDeclareRepository;

    /**
     * 外部程序同步某个账套的应申报税费种信息
     * 策略：先删后插，一次同步即一份完整快照；若无行则写一条占位记录（is_effective=false）
     *  @param accountSetsId 账套ID
     * @param tableData     表格行数据，每行 Map 的 key 支持：taxType/taxItem/taxSubitem/taxPeriod/declarePeriod/validFrom/validTo/mainAddFlag，或中文：税（费）种、税目、子目 等
     * @param flag
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveForAccountSet(Long accountSetsId, List<Map<String, String>> tableData, String flag) {
        if (accountSetsId == null) {
            throw new IllegalArgumentException("accountSetsId 不能为空");
        }
        if ("2".equals(flag)){
            TaxDeclare entity = new TaxDeclare();
            entity.setAccountSetsId(accountSetsId);
            taxDeclareRepository.save(entity);
            return;
        }
        List<Map<String, String>> rows = tableData != null ? tableData : Collections.emptyList();
        taxDeclareRepository.deleteByAccountSetsId(accountSetsId);
        if (CollUtil.isEmpty(rows)) {
            TaxDeclare placeholder = new TaxDeclare();
            placeholder.setAccountSetsId(accountSetsId);
            placeholder.setTaxType("无应申报税费种");
            placeholder.setTaxItem("-");
            placeholder.setTaxSubitem("-");
            placeholder.setTaxPeriod("-");
            placeholder.setDeclarePeriod("-");
            placeholder.setValidFrom(LocalDate.now());
            placeholder.setValidTo(LocalDate.of(9999, 12, 31));
            placeholder.setMainAddFlag("主税");
            placeholder.setIsEffective(false);
            taxDeclareRepository.save(placeholder);
            return;
        }

        for (Map<String, String> row : rows) {
            String taxType = getStr(row, "taxType", "税（费）种");
            String taxItem = getStr(row, "taxItem", "税目");
            String taxSubitem = getStr(row, "taxSubitem", "子目");
            String taxPeriod = getStr(row, "taxPeriod", "纳税（费）期限");
            String declarePeriod = getStr(row, "declarePeriod", "申报期限");
            String validFromStr = getStr(row, "validFrom", "认定有效期起");
            String validToStr = getStr(row, "validTo", "认定有效期止");
            String mainAddFlag = getStr(row, "mainAddFlag", "主附税标志");

            if (StringUtils.isBlank(taxType)) taxType = "-";
            if (StringUtils.isBlank(taxItem)) taxItem = "-";
            if (StringUtils.isBlank(taxSubitem)) taxSubitem = "-";
            if (StringUtils.isBlank(taxPeriod)) taxPeriod = "-";
            if (StringUtils.isBlank(declarePeriod)) declarePeriod = "-";
            if (StringUtils.isBlank(mainAddFlag)) mainAddFlag = "主税";

            LocalDate validFrom = parseDate(validFromStr, LocalDate.now());
            LocalDate validTo = parseDate(validToStr, LocalDate.of(9999, 12, 31));

            TaxDeclare entity = new TaxDeclare();
            entity.setAccountSetsId(accountSetsId);
            entity.setTaxType(taxType);
            entity.setTaxItem(taxItem);
            entity.setTaxSubitem(taxSubitem);
            entity.setTaxPeriod(taxPeriod);
            entity.setDeclarePeriod(declarePeriod);
            entity.setValidFrom(validFrom);
            entity.setValidTo(validTo);
            entity.setMainAddFlag(mainAddFlag);
            entity.setIsEffective(true);
            taxDeclareRepository.save(entity);
        }
    }

    private static String getStr(Map<String, String> row, String en, String zh) {
        String v = row.get(en);
        if (StringUtils.isNotBlank(v)) return v.trim();
        v = row.get(zh);
        return v != null ? v.trim() : null;
    }

    private static LocalDate parseDate(String s, LocalDate defaultVal) {
        if (StringUtils.isBlank(s)) return defaultVal;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    public List<TaxDeclare> findByAccountSetsId(Long accountSetsId) {
        return taxDeclareRepository.findByAccountSetsId(accountSetsId);
    }

    /** 该账套应申报税费种数据最近一次更新时间（供前端展示、判定是否重新抓取） */
    public java.time.LocalDateTime getLastUpdateTime(Long accountSetsId) {
        return taxDeclareRepository.findMaxUpdateTimeByAccountSetsId(accountSetsId).orElse(null);
    }
}
