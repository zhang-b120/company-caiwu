package cn.gson.financial.agent.initialbalance.service;

import cn.gson.financial.agent.initialbalance.dto.InitialBalanceImportResponse;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 将工作流期初导入结果应用到指定账套：先校验未结账，再写入新科目与期初，最后同步余额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialBalanceApplyService {

    private final CheckoutService checkoutService;
    private final SubjectService subjectService;
    private final BalanceInitialService balanceInitialService;
    private final BalanceCalcService balanceCalcService;
    private final CurrencyService currencyService;

    /**
     * 将接口返回的 newSubjects、initialBalances 写入当前账套。
     * 若账套已结账则抛出异常，不执行写入。
     *
     * @param accountSets 目标账套
     * @param response    工作流 /import 接口返回数据（success 且 data 非空）
     * @return 摘要：新增科目数、期初条数
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplyResult applyToAccountSet(AccountSets accountSets, InitialBalanceImportResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) {
            throw new ServiceException("工作流返回数据无效，无法导入");
        }

        if (checkoutService.beginCheck(accountSets)) {
            throw new ServiceException("该账套已结账，不允许导入期初");
        }

        InitialBalanceImportResponse.ResponseData data = response.getData();
        List<InitialBalanceImportResponse.NewSubject> newSubjects = data.getNewSubjects() != null ? data.getNewSubjects() : Collections.emptyList();
        List<InitialBalanceImportResponse.InitialBalance> initialBalances = data.getInitialBalances() != null ? data.getInitialBalances() : Collections.emptyList();

        SaveNewSubjectsResult saveResult = saveNewSubjects(accountSets, newSubjects);
        int addedSubjects = saveResult.getAddedCount();
        List<ApplyResult.InvalidEncodingSubject> invalidEncodingNewSubjects = saveResult.getInvalidEncodingNewSubjects();
        Map<String, String> workflowCodeToExistingCode = saveResult.getDuplicateNameWorkflowCodeToExistingCode();
        List<String> skippedBalanceCodes = new ArrayList<>();
        List<BalanceInitialDto> balanceDtos = buildBalanceInitialDtos(accountSets, initialBalances, workflowCodeToExistingCode, skippedBalanceCodes);
        if (CollUtil.isNotEmpty(balanceDtos)) {
            Map<String, SubjectVo> subjectVosByCode = subjectService.list(accountSets.getId()).stream()
                    .collect(Collectors.toMap(Subject::getCode, s -> s, (a, b) -> a));
            List<BalanceInitialDto> expanded = expandWithParentPlaceholders(accountSets, balanceDtos, subjectVosByCode);
            balanceInitialService.clear(accountSets.getId());
            balanceInitialService.updateInitBalance(expanded, accountSets, subjectVosByCode);
        }
        balanceCalcService.calcSynchronization(accountSets.getId(), java.time.YearMonth.from(accountSets.getEnableDate()));

        return new ApplyResult(addedSubjects, balanceDtos != null ? balanceDtos.size() : 0, invalidEncodingNewSubjects, skippedBalanceCodes);
    }

    /**
     * 将 newSubjects 中尚未存在的科目按 parent 顺序新增到账套。
     * 若同父级下已有同名同类型科目，则不新增，将该工作流编码映射到已有科目，期初余额记入已有科目。
     * 不符合账套科目编码规则的项不写入，并收集到 invalidEncodingNewSubjects 中返回。
     */
    private SaveNewSubjectsResult saveNewSubjects(AccountSets accountSets, List<InitialBalanceImportResponse.NewSubject> newSubjects) {
        List<ApplyResult.InvalidEncodingSubject> invalidEncodingNewSubjects = new ArrayList<>();
        Map<String, String> duplicateNameWorkflowCodeToExistingCode = new HashMap<>();
        if (CollUtil.isEmpty(newSubjects)) {
            return new SaveNewSubjectsResult(0, invalidEncodingNewSubjects, duplicateNameWorkflowCodeToExistingCode);
        }

        Map<String, Subject> codeToSubject = subjectService.list(accountSets.getId()).stream()
                .collect(Collectors.toMap(Subject::getCode, s -> s, (a, b) -> a));

        List<Integer> encoding = accountSets.getEncoding();
        int encLen = encoding != null ? encoding.stream().reduce(0, Integer::sum) : 10;
        boolean validateEncoding = CollUtil.isNotEmpty(encoding);

        List<InitialBalanceImportResponse.NewSubject> sorted = new ArrayList<>(newSubjects);
        sorted.sort(Comparator.comparingInt((InitialBalanceImportResponse.NewSubject n) -> n.getCode() != null ? n.getCode().length() : 0)
                .thenComparing(n -> StrUtil.nullToEmpty(n.getCode())));

        int added = 0;
        for (InitialBalanceImportResponse.NewSubject n : sorted) {
            if (StrUtil.isBlank(n.getCode())) {
                continue;
            }
            if (codeToSubject.containsKey(n.getCode())) {
                continue;
            }
            if (validateEncoding && !SubjectUtil.isValidCode(n.getCode().trim(), encoding)) {
                String rule = encoding.stream().map(String::valueOf).collect(Collectors.joining("-"));
                invalidEncodingNewSubjects.add(new ApplyResult.InvalidEncodingSubject(
                        n.getCode(), StrUtil.trimToEmpty(n.getName()), rule));
                continue;
            }

            Long parentId = 0L;
            int level = 1;
            if (StrUtil.isNotBlank(n.getParentCode())) {
                Subject parent = codeToSubject.get(n.getParentCode());
                if (parent != null) {
                    parentId = parent.getId();
                    level = (parent.getLevel() != null ? parent.getLevel() : 1) + 1;
                }
            }

            String name = StrUtil.trimToEmpty(n.getName());
            String type = StrUtil.trimToEmpty(n.getType());
            Subject existingSameName = findExistingSubjectByParentAndNameAndType(codeToSubject.values(), parentId, name, type);
            if (existingSameName != null) {
                duplicateNameWorkflowCodeToExistingCode.put(n.getCode().trim(), existingSameName.getCode());
                codeToSubject.put(n.getCode().trim(), existingSameName);
                continue;
            }

            Subject subject = new Subject();
            subject.setAccountSetsId(accountSets.getId());
            subject.setCode(n.getCode().trim());
            subject.setName(name);
            subject.setType(type);
            subject.setBalanceDirection(StrUtil.emptyToDefault(n.getBalanceDirection(), "借"));
            subject.setParentId(parentId);
            subject.setLevel(level);
            subject.setLeaf(true);
            subject.setStatus(true);
            subject.setSystemDefault(false);
            subject.setMnemonicCode("");
            subject.setFullName(StrUtil.trimToEmpty(n.getName()));
            if (StrUtil.isNotBlank(n.getUnit())) {
                subject.setUnit(n.getUnit().trim());
            }

            try {
                subjectService.save(subject, encLen);
                codeToSubject.put(subject.getCode(), subject);
                added++;
            } catch (Exception e) {
                log.warn("新增科目失败 code={}, msg={}", n.getCode(), e.getMessage());
                throw new ServiceException("新增科目失败: " + n.getCode() + " - " + e.getMessage());
            }
        }
        return new SaveNewSubjectsResult(added, invalidEncodingNewSubjects, duplicateNameWorkflowCodeToExistingCode);
    }

    /** 同父级下同名同类型取第一个（用于「不重复新增、期初记入已有科目」） */
    private static Subject findExistingSubjectByParentAndNameAndType(
            java.util.Collection<Subject> subjects, Long parentId, String name, String type) {
        if (subjects == null || name == null) return null;
        Long pId = parentId != null ? parentId : 0L;
        for (Subject s : subjects) {
            Long sParent = s.getParentId() != null ? s.getParentId() : 0L;
            if (sParent.equals(pId) && name.equals(StrUtil.nullToEmpty(s.getName()))
                    && type.equals(StrUtil.nullToEmpty(s.getType()))) {
                return s;
            }
        }
        return null;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class SaveNewSubjectsResult {
        private final int addedCount;
        private final List<ApplyResult.InvalidEncodingSubject> invalidEncodingNewSubjects;
        /** 因同名未新增的工作流编码 -> 已有科目编码，期初记入已有科目 */
        private final Map<String, String> duplicateNameWorkflowCodeToExistingCode;
    }

    /**
     * @param skippedBalanceCodes 出参：因科目不存在而跳过的期初科目编码，调用方传入可写列表，本方法会往里追加
     */
    private List<BalanceInitialDto> buildBalanceInitialDtos(AccountSets accountSets,
                                                           List<InitialBalanceImportResponse.InitialBalance> initialBalances,
                                                           Map<String, String> workflowCodeToExistingCode,
                                                           List<String> skippedBalanceCodes) {
        if (CollUtil.isEmpty(initialBalances)) {
            return Collections.emptyList();
        }
        if (skippedBalanceCodes == null) {
            skippedBalanceCodes = new ArrayList<>();
        }

        Map<String, SubjectVo> codeToSubject = subjectService.list(accountSets.getId()).stream()
                .collect(Collectors.toMap(Subject::getCode, s -> s, (a, b) -> a));

        if (workflowCodeToExistingCode == null) {
            workflowCodeToExistingCode = Collections.emptyMap();
        }

        CurrencyDto local = currencyService.fetchLocal(accountSets.getId());
        Long currencyId = local != null ? local.getId() : null;
        if (currencyId == null) {
            throw new ServiceException("账套未设置本币，无法导入期初");
        }

        List<BalanceInitialDto> list = new ArrayList<>();
        for (InitialBalanceImportResponse.InitialBalance ib : initialBalances) {
            if (StrUtil.isBlank(ib.getCode())) {
                continue;
            }
            String lookupCode = ib.getCode();
            SubjectVo sub = codeToSubject.get(lookupCode);
            if (sub == null && workflowCodeToExistingCode.containsKey(lookupCode)) {
                lookupCode = workflowCodeToExistingCode.get(lookupCode);
                sub = codeToSubject.get(lookupCode);
            }
            if (sub == null) {
                skippedBalanceCodes.add(ib.getCode().trim());
                log.warn("期初导入跳过不存在的科目: {}", ib.getCode());
                continue;
            }

            String systemDir = StrUtil.emptyToDefault(sub.getBalanceDirection(), "借");
            String dataDir = StrUtil.emptyToDefault(ib.getBalanceDirection(), "").trim();
            boolean oppositeDirection = StrUtil.isNotEmpty(dataDir) && !normalizeDirection(dataDir).equals(normalizeDirection(systemDir));

            BigDecimal beginBalance = ib.getBeginBalance() != null ? ib.getBeginBalance() : BigDecimal.ZERO;
            BigDecimal yearTotalDebit = ib.getYearTotalDebit() != null ? ib.getYearTotalDebit() : BigDecimal.ZERO;
            BigDecimal yearTotalCredit = ib.getYearTotalCredit() != null ? ib.getYearTotalCredit() : BigDecimal.ZERO;
            BigDecimal yearTotal = ib.getYearTotal();
            if (oppositeDirection) {
                beginBalance = beginBalance.negate();
                if (yearTotal != null) {
                    yearTotal = yearTotal.negate();
                }
                // yearTotalDebit、yearTotalCredit 不取反，原样录入
            }

            BalanceInitialDto dto = new BalanceInitialDto();
            dto.setAccountSetsId(accountSets.getId());
            dto.setSubjectId(sub.getId());
            dto.setSubjectCode(sub.getCode());
            dto.setAuxiliaryDetailId(0L);
            dto.setCurrencyId(currencyId);
            dto.setBalanceDirection(systemDir);
            dto.setBeginBalance(beginBalance);
            dto.setBeginBalanceFor(dto.getBeginBalance());
            dto.setYearTotalDebit(yearTotalDebit);
            dto.setYearTotalDebitFor(dto.getYearTotalDebit());
            dto.setYearTotalCredit(yearTotalCredit);
            dto.setYearTotalCreditFor(dto.getYearTotalCredit());
            dto.setYearTotal(yearTotal);
            dto.setYearTotalFor(dto.getYearTotal());
            dto.setBeginQuantity(ib.getBeginQuantity() != null ? ib.getBeginQuantity() : BigDecimal.ZERO);
            dto.setYearTotalCreditQuantity(BigDecimal.ZERO);
            dto.setYearTotalDebitQuantity(BigDecimal.ZERO);
            dto.setYearTotalQuantity(BigDecimal.ZERO);
            dto.setCreditQuantity(BigDecimal.ZERO);
            dto.setDebitQuantity(BigDecimal.ZERO);
            dto.setTreeId(sub.getId());
            dto.setParentTreeId(sub.getParentId() != null ? sub.getParentId() : 0L);
            dto.setLeaf(Boolean.TRUE.equals(sub.getLeaf()));
            dto.setLevel(sub.getLevel() != null ? sub.getLevel() : 1);

            if (dto.getYearTotal() == null) {
                dto.calculateYearTotal();
            }
            list.add(dto);
        }

        return list.stream().filter(BalanceInitialDto::isUsed).collect(Collectors.toList());
    }

    /** 统一为「借」或「贷」便于比较 */
    private static String normalizeDirection(String dir) {
        if (dir == null) return "借";
        String s = dir.trim();
        if (s.contains("贷") || "贷".equals(s)) return "贷";
        return "借";
    }

    /**
     * 补全上级科目占位（金额为 0），使带 subjectVos 的 updateInitBalance 能按科目树汇总到一级，
     * 与手动保存期初后的数据形态一致（既有一级汇总也有末级明细）。
     */
    private List<BalanceInitialDto> expandWithParentPlaceholders(AccountSets accountSets,
                                                                 List<BalanceInitialDto> balanceDtos,
                                                                 Map<String, SubjectVo> subjectVosByCode) {
        if (CollUtil.isEmpty(balanceDtos)) {
            return balanceDtos;
        }
        Map<Long, SubjectVo> idToVo = subjectVosByCode.values().stream()
                .collect(Collectors.toMap(SubjectVo::getId, s -> s, (a, b) -> a));
        Set<Long> needIds = new HashSet<>();
        for (BalanceInitialDto d : balanceDtos) {
            Long id = d.getSubjectId();
            needIds.add(id);
            SubjectVo v = idToVo.get(id);
            while (v != null && v.getParentId() != null && v.getParentId() != 0L) {
                needIds.add(v.getParentId());
                v = idToVo.get(v.getParentId());
            }
        }
        Set<Long> haveIds = balanceDtos.stream().map(BalanceInitialDto::getSubjectId).collect(Collectors.toSet());
        List<BalanceInitialDto> result = new ArrayList<>(balanceDtos);
        Long currencyId = balanceDtos.get(0).getCurrencyId();
        for (Long needId : needIds) {
            if (haveIds.contains(needId)) {
                continue;
            }
            SubjectVo vo = idToVo.get(needId);
            if (vo == null) {
                continue;
            }
            BalanceInitialDto zero = new BalanceInitialDto();
            zero.setAccountSetsId(accountSets.getId());
            zero.setSubjectId(vo.getId());
            zero.setSubjectCode(vo.getCode());
            zero.setAuxiliaryDetailId(0L);
            zero.setCurrencyId(currencyId);
            zero.setBalanceDirection(StrUtil.emptyToDefault(vo.getBalanceDirection(), "借"));
            zero.setBeginBalance(BigDecimal.ZERO);
            zero.setBeginBalanceFor(BigDecimal.ZERO);
            zero.setYearTotalDebit(BigDecimal.ZERO);
            zero.setYearTotalDebitFor(BigDecimal.ZERO);
            zero.setYearTotalCredit(BigDecimal.ZERO);
            zero.setYearTotalCreditFor(BigDecimal.ZERO);
            zero.setYearTotal(BigDecimal.ZERO);
            zero.setYearTotalFor(BigDecimal.ZERO);
            zero.setBeginQuantity(BigDecimal.ZERO);
            zero.setYearTotalCreditQuantity(BigDecimal.ZERO);
            zero.setYearTotalDebitQuantity(BigDecimal.ZERO);
            zero.setYearTotalQuantity(BigDecimal.ZERO);
            zero.setCreditQuantity(BigDecimal.ZERO);
            zero.setDebitQuantity(BigDecimal.ZERO);
            zero.setTreeId(vo.getId());
            zero.setParentTreeId(vo.getParentId() != null ? vo.getParentId() : 0L);
            zero.setLeaf(Boolean.TRUE.equals(vo.getLeaf()));
            zero.setLevel(vo.getLevel() != null ? vo.getLevel() : 1);
            result.add(zero);
            haveIds.add(needId);
        }
        return result;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ApplyResult {
        private int newSubjectsCount;
        private int initialBalancesCount;
        /** 因不符合账套科目编码规则而未写入的新科目（编码、名称、账套编码规则） */
        private List<InvalidEncodingSubject> invalidEncodingNewSubjects;
        /** 因科目不存在而跳过的期初科目编码列表 */
        private List<String> skippedBalanceCodes;

        public ApplyResult(int newSubjectsCount, int initialBalancesCount, List<InvalidEncodingSubject> invalidEncodingNewSubjects) {
            this(newSubjectsCount, initialBalancesCount, invalidEncodingNewSubjects, null);
        }

        public ApplyResult(int newSubjectsCount, int initialBalancesCount, List<InvalidEncodingSubject> invalidEncodingNewSubjects, List<String> skippedBalanceCodes) {
            this.newSubjectsCount = newSubjectsCount;
            this.initialBalancesCount = initialBalancesCount;
            this.invalidEncodingNewSubjects = invalidEncodingNewSubjects != null ? invalidEncodingNewSubjects : Collections.emptyList();
            this.skippedBalanceCodes = skippedBalanceCodes != null ? skippedBalanceCodes : Collections.emptyList();
        }

        @lombok.Data
        @lombok.AllArgsConstructor
        @lombok.NoArgsConstructor
        public static class InvalidEncodingSubject {
            private String code;
            private String name;
            /** 账套编码规则，如 4-3-3-2 */
            private String encodingRule;
        }
    }
}
