package cn.gson.financial.kernel.model.individualTax.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.individualTax.dto.AddIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.AuditIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.DeleteIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.IndividualTaxSelectDto;
import cn.gson.financial.kernel.model.individualTax.resp.IndividualTaxSelectResp;
import cn.gson.financial.kernel.model.individualTax.resp.IndividualTaxListResp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.IndividualTaxRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.gson.financial.kernel.model.repo.UserImageStorageConfigRepository;
import cn.gson.financial.kernel.service.AbsService;
import cn.hutool.core.lang.Assert;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class IndividualTaxService extends AbsService {


    private final IndividualTaxRepository individualTaxRepository;
    private final UserImageStorageConfigRepository userImageStorageConfigRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final UserAccountSetsRepository userAccountSetsRepository;
    private final QIndividualTax qIndividualTax = QIndividualTax.individualTax;
     private final QAccountSets qAccountSets = QAccountSets.accountSets;

    /**
     * 查询该用户下该账期内的 工资和人员录入 情况
     */
    public IndividualTaxListResp getIndividualTaxList(IndividualTaxSelectDto individualTaxSelectDto) {
        String period = individualTaxSelectDto.getPeriod();

        List<IndividualTaxSelectResp> individualTaxSelectRespList = lazyDao.findBySql("getAccountSetsOfIndividualTax", individualTaxSelectDto.toMap(), IndividualTaxSelectResp.class);
        // 统计每个账套在该账期的工资条数量
        List<Map> salaryStats = lazyDao.findBySql("getSalaryOfIndividualTax", individualTaxSelectDto.toMap(), Map.class);
        Map<Long, Integer> accountSetsIdToCount = salaryStats.stream().collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> ((Number) m.get("numSalary")).intValue()));
        // 统计每个账套是否存在员工
        List<Map> employeeStats = lazyDao.findBySql("getEmployeeOfIndividualTax", individualTaxSelectDto.toMap(), Map.class);
        Map<Long, Integer> accountSetsIdToEmp = employeeStats.stream().collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> ((Number) m.get("numEmployee")).intValue()));

        //统计已经加入任务列表的
        List<Map> existStats = lazyDao.findBySql("getExistOfIndividualTax", individualTaxSelectDto.toMap(), Map.class);
        Map<Long, Integer> accountSetsIdToExist = existStats.stream().collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> ((Number) m.get("isExist")).intValue()));
        //统计加入任务列表的执行情况
        List<Map> resultStats = lazyDao.findBySql("getResultOfIndividualTax", individualTaxSelectDto.toMap(), Map.class);
        Map<Long, Integer> accountSetsIdToresult = resultStats.stream().collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> ((Number) m.get("result")).intValue()));
        Map<Long, String> accountSetsIdToFailedReason = resultStats.stream()
                .filter(m -> m.get("failedReason") != null)
                .collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> (String) m.get("failedReason")));
        Map<Long, String> accountSetsIdToImgName = resultStats.stream()
                .filter(m -> m.get("imgName") != null)
                .collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> (String) m.get("imgName")));
        Map<Long, Integer> accountSetsIdToAuditStatus = resultStats.stream()
                .filter(m -> m.get("auditStatus") != null)
                .collect(Collectors.toMap(m -> ((Number) m.get("accountSetsId")).longValue(), m -> ((Number) m.get("auditStatus")).intValue()));

        // 查询用户的图片存储配置（存储类型1表示本地存储）
        Long userId = individualTaxSelectDto.getUserId();
        Optional<UserImageStorageConfig> byUserIdAndStorageType = userImageStorageConfigRepository.findByUserIdAndStorageType(userId, 1);
        String basePath =byUserIdAndStorageType .map(UserImageStorageConfig::getStorageRoot).orElse("");

        individualTaxSelectRespList.forEach(r -> {
            r.setPeriod(period); // 设置账期
            Integer cnt = accountSetsIdToCount.get(r.getAccountSetsId());
            r.setNumSalary(cnt == null ? 0 : cnt);
            Integer ec = accountSetsIdToEmp.get(r.getAccountSetsId());
            r.setNumEmployee(ec == null ? 0 : ec);
            Integer exc = accountSetsIdToExist.get(r.getAccountSetsId());
            r.setIsExist(exc == null ? 0 : exc);
            Integer rc = accountSetsIdToresult.get(r.getAccountSetsId());
            r.setResult(rc == null ? 0 : rc);
            String failedReason = accountSetsIdToFailedReason.get(r.getAccountSetsId());
            r.setFailedReason(failedReason);

            // 设置图片名和完整URL
            String imgName = accountSetsIdToImgName.get(r.getAccountSetsId());
            r.setImgName(imgName);
            // 如果存在图片名和基础路径，则拼接完整URL
            if (StringUtils.hasText(imgName) && StringUtils.hasText(basePath)) {
                // 检测路径分隔符类型（Windows使用\，Unix使用/）
                String separator = basePath.contains("\\") ? "\\" : "/";
                // 确保path以分隔符结尾，imgName不以分隔符开头
                String path = basePath.endsWith(separator) ? basePath : basePath + separator;
                // 移除imgName开头的分隔符（可能是/或\）
                String fileName = imgName;
                if (fileName.startsWith("/") || fileName.startsWith("\\")) {
                    fileName = fileName.substring(1);
                }
                r.setImgUrl(path + fileName);
            }

            // 设置审核状态
            Integer auditStatus = accountSetsIdToAuditStatus.get(r.getAccountSetsId());
            r.setAuditStatus(auditStatus == null ? 0 : auditStatus);

            // 个税密码和个税状态字段已经通过SQL查询自动映射到响应对象中
            // SQL查询中已经包含了 ffas.individual_tax_code as individualTaxCode 和 ffas.individual_tax as individualTax
            // SQLToy会自动将下划线命名转换为驼峰命名并映射到Java对象字段
            // 如果字段值为null，保持为null即可，前端会处理空值显示
        });
        /**
         * 根据传入的条件，来筛选符合的账套
         */
        // 如果 isSalary = 1，筛选有工资的（numSalary > 0）
        // 如果 isEmployee = 1，筛选有员工的（numEmployee > 0）
        // 如果都为0，展示全部
        if (individualTaxSelectDto.getIsSalary() != null && individualTaxSelectDto.getIsSalary() == 1) {
            individualTaxSelectRespList = individualTaxSelectRespList.stream().filter(r -> r.getNumSalary() != null && r.getNumSalary() > 0).collect(Collectors.toList());
        }
        if (individualTaxSelectDto.getIsEmployee() != null && individualTaxSelectDto.getIsEmployee() == 1) {
            individualTaxSelectRespList = individualTaxSelectRespList.stream().filter(r -> r.getNumEmployee() != null && r.getNumEmployee() > 0).collect(Collectors.toList());
        }
        // 若 isExist = 1，只看已在任务列表；若 isExist = 0，只看未加入任务列表
        if (individualTaxSelectDto.getIsExist() != null) {
            if (individualTaxSelectDto.getIsExist() == 1) {
                individualTaxSelectRespList = individualTaxSelectRespList.stream().filter(r -> r.getIsExist() != null && r.getIsExist() == 1).collect(Collectors.toList());
            } else if (individualTaxSelectDto.getIsExist() == 0) {
                individualTaxSelectRespList = individualTaxSelectRespList.stream().filter(r -> r.getIsExist() == null || r.getIsExist() == 0).collect(Collectors.toList());
            }
        }
        // 若 result = 2，只看成功；若 result = 1，只看失败；若 result = 0，只看未执行
        if (individualTaxSelectDto.getResult() != null) {
            int targetResult = individualTaxSelectDto.getResult();
            individualTaxSelectRespList = individualTaxSelectRespList.stream()
                    .filter(r -> r.getResult() != null && r.getResult().equals(targetResult))
                    .collect(Collectors.toList());
        }
        // 审核状态筛选：0-未审核，1-审核通过，2-待更新
        if (individualTaxSelectDto.getAuditStatus() != null) {
            int targetAuditStatus = individualTaxSelectDto.getAuditStatus();
            individualTaxSelectRespList = individualTaxSelectRespList.stream()
                    .filter(r -> r.getAuditStatus() != null && r.getAuditStatus().equals(targetAuditStatus))
                    .collect(Collectors.toList());
        }

        // 个税状态筛选（任务筛选模式下使用）
        if (individualTaxSelectDto.getIndividualTaxCan() != null && individualTaxSelectDto.getIndividualTaxCan()) {
            // 只显示能报个税的（individualTax为空或空字符串）
            individualTaxSelectRespList = individualTaxSelectRespList.stream()
                    .filter(r -> r.getIndividualTax() == null || (r.getIndividualTax() != null && r.getIndividualTax().trim().isEmpty()))
                    .collect(Collectors.toList());
        } else if (individualTaxSelectDto.getIndividualTaxCannot() != null && individualTaxSelectDto.getIndividualTaxCannot()) {
            // 只显示不能报个税的（individualTax不为空）
            individualTaxSelectRespList = individualTaxSelectRespList.stream()
                    .filter(r -> r.getIndividualTax() != null && !r.getIndividualTax().trim().isEmpty())
                    .collect(Collectors.toList());
        }

        // 排序：无论选择与否，始终按 isExist 降序；
        // 若 isSalary=1 则其次按 numSalary 降序；若 isEmployee=1 则其次按 numEmployee 降序
        Comparator<IndividualTaxSelectResp> comparator = Comparator.comparing(IndividualTaxSelectResp::getIsExist, Comparator.nullsFirst(Integer::compareTo)).reversed();

        if (individualTaxSelectDto.getIsSalary() != null && individualTaxSelectDto.getIsSalary() == 1) {
            comparator = comparator.thenComparing(Comparator.comparing(IndividualTaxSelectResp::getNumSalary, Comparator.nullsFirst(Integer::compareTo)).reversed());
        } else if (individualTaxSelectDto.getIsEmployee() != null && individualTaxSelectDto.getIsEmployee() == 1) {
            comparator = comparator.thenComparing(Comparator.comparing(IndividualTaxSelectResp::getNumEmployee, Comparator.nullsFirst(Integer::compareTo)).reversed());
        }

        individualTaxSelectRespList.sort(comparator);

        // 统计审核通过数：在任务列表中且已执行的记录中，审核状态为1的数量
        int auditedCount = (int) resultStats.stream()
                .filter(m -> {
                    Integer result = m.get("result") != null ? ((Number) m.get("result")).intValue() : null;
                    Integer auditStatus = m.get("auditStatus") != null ? ((Number) m.get("auditStatus")).intValue() : 0;
                    // 只统计已执行（result != 0）且审核通过（auditStatus == 1）的记录
                    return result != null && result != 0 && auditStatus == 1;
                })
                .count();

        // 统计需要报个税的总账套数（排除individualTax不为空的账套）
        List<Map> totalCountStats = lazyDao.findBySql("getTotalAccountSetsCountOfIndividualTax", individualTaxSelectDto.toMap(), Map.class);
        int totalAccountSetsCount = 0;
        if (!totalCountStats.isEmpty() && totalCountStats.get(0).get("totalCount") != null) {
            totalAccountSetsCount = ((Number) totalCountStats.get(0).get("totalCount")).intValue();
        }

        // 创建响应包装类
        IndividualTaxListResp resp = new IndividualTaxListResp();
        resp.setList(individualTaxSelectRespList);
        resp.setAuditedCount(auditedCount);
        resp.setTotalAccountSetsCount(totalAccountSetsCount);

        return resp;
    }

    /**
     * 根据前端传入的信息插入个税任务表
     *
     * @param addIndividualTaxDto
     */
    public Boolean addIndividualTaxList(AddIndividualTaxDto addIndividualTaxDto) {
        String period = addIndividualTaxDto.getPeriod();
        List<Long> accountSetsIdList = addIndividualTaxDto.getAccountSetsIdList();
        if (accountSetsIdList == null || accountSetsIdList.isEmpty()) {
            return Boolean.TRUE;
        }
        List<IndividualTax>  individualTaxList = new ArrayList<>();
        accountSetsIdList.forEach(accountSetsId -> {
            IndividualTax individualTax = new IndividualTax();
            individualTax.setAccountSetsId(accountSetsId);
            individualTax.setPeriod(LocalDate.parse(period));
            individualTax.setResult(0);
            individualTaxList.add(individualTax);
        });
        individualTaxRepository.saveAll(individualTaxList);
        return Boolean.TRUE;
    }
//    /**
//     * 根据前端传入的信息插入个税任务表
//     * 自动过滤掉"不能报"的账套，只添加"能报"的账套
//     *
//     * @param addIndividualTaxDto
//     * @return Map包含成功添加的数量和被排除的账套名称列表
//     */
//    @Transactional
//    public Map<String, Object> addIndividualTaxList(AddIndividualTaxDto addIndividualTaxDto) {
//        String period = addIndividualTaxDto.getPeriod();
//        Long userId = addIndividualTaxDto.getUserId();
//        List<Long> accountSetsIdList = addIndividualTaxDto.getAccountSetsIdList();
//
//        Map<String, Object> result = new HashMap<>();
//        result.put("addedCount", 0);
//        result.put("excludedNames", new ArrayList<String>());
//
//        if (accountSetsIdList == null || accountSetsIdList.isEmpty() || userId == null) {
//            return result;
//        }
//
//        // 先过滤出属于该用户的账套ID列表（使用QueryDSL提高查询效率）
//        QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;
//        List<Long> userAccountSetsIds = bqf.selectFrom(qUserAccountSets)
//                .select(qUserAccountSets.accountSetsId)
//                .where(qUserAccountSets.userId.eq(userId)
//                        .and(qUserAccountSets.accountSetsId.in(accountSetsIdList)))
//                .fetch()
//                .stream()
//                .filter(id -> id != null)
//                .distinct()
//                .collect(Collectors.toList());
//
//        if (userAccountSetsIds.isEmpty()) {
//            return result;
//        }
//
//        // 分离能报和不能报的账套，以及已审核过的账套
//        List<Long> validAccountSetsIds = new ArrayList<>();
//        List<String> excludedAccountSetsNames = new ArrayList<>();
//        List<String> auditedAccountSetsNames = new ArrayList<>();
//
//        LocalDate periodDate = LocalDate.parse(period);
//
//        // 只查询该用户账套在该账期下的个税任务记录
//        List<IndividualTax> existingTaxList = individualTaxRepository.findByAccountSetsIdInAndPeriod(userAccountSetsIds, periodDate);
//        // 构建账套ID到最新审核状态的映射（每个账套取ID最大的记录，即最新的记录）
//        Map<Long, Integer> accountSetsIdToAuditStatus = new HashMap<>();
//        for (Long accountSetsId : userAccountSetsIds) {
//            // 找到该账套在该账期下ID最大的记录（最新的记录）
//            Optional<IndividualTax> latestTax = existingTaxList.stream()
//                    .filter(t -> t.getAccountSetsId() != null && t.getAccountSetsId().equals(accountSetsId))
//                    .max(Comparator.comparing(IndividualTax::getId, Comparator.nullsFirst(Long::compareTo)));
//            if (latestTax.isPresent()) {
//                Integer auditStatus = latestTax.get().getAuditStatus() != null ? latestTax.get().getAuditStatus() : 0;
//                accountSetsIdToAuditStatus.put(accountSetsId, auditStatus);
//            }
//        }
//
//        for (Long accountSetsId : userAccountSetsIds) {
//            Optional<AccountSets> accountSetsOpt = accountSetsRepository.findById(accountSetsId);
//            if (accountSetsOpt.isPresent()) {
//                AccountSets accountSets = accountSetsOpt.get();
//                // 如果 individualTax 不为空且不为空字符串，表示不能报个税
//                if (StringUtils.hasText(accountSets.getIndividualTax())) {
//                    excludedAccountSetsNames.add(accountSets.getCompanyName());
//                } else {
//                    // 检查该账套在该账期是否已经审核通过（只有auditStatus = 1才不允许再次加入任务）
//                    Integer auditStatus = accountSetsIdToAuditStatus.get(accountSetsId);
//                    if (auditStatus != null && auditStatus == 1) {
//                        // 已经审核通过的账套，不允许再次加入任务
//                        auditedAccountSetsNames.add(accountSets.getCompanyName());
//                    } else {
//                        // 能报个税且未审核通过（包括待更新状态）的账套，添加到有效列表
//                        validAccountSetsIds.add(accountSetsId);
//                    }
//                }
//            }
//        }
//
//        // 只添加能报个税且未审核过的账套
//        if (!validAccountSetsIds.isEmpty()) {
//            List<IndividualTax> individualTaxList = new ArrayList<>();
//            validAccountSetsIds.forEach(accountSetsId -> {
//                IndividualTax individualTax = new IndividualTax();
//                individualTax.setAccountSetsId(accountSetsId);
//                individualTax.setPeriod(periodDate);
//                individualTax.setResult(0);
//                individualTax.setAuditStatus(0);
//                individualTaxList.add(individualTax);
//            });
//            individualTaxRepository.saveAll(individualTaxList);
//            result.put("addedCount", individualTaxList.size());
//        }
//
//        // 合并被排除的账套名称（不能报个税的 + 已审核过的）
//        List<String> allExcludedNames = new ArrayList<>(excludedAccountSetsNames);
//        if (!auditedAccountSetsNames.isEmpty()) {
//            allExcludedNames.addAll(auditedAccountSetsNames);
//        }
//        result.put("excludedNames", allExcludedNames);
//        return result;
//    }

    /**
     * 根据账套ID列表和账期删除个税任务
     *
     * @param deleteIndividualTaxDto
     */
    @Transactional
    public void deleteIndividualTaxList(DeleteIndividualTaxDto deleteIndividualTaxDto) {
        String period = deleteIndividualTaxDto.getPeriod();
        List<Long> accountSetsIdList = deleteIndividualTaxDto.getAccountSetsIdList();
        if (accountSetsIdList == null || accountSetsIdList.isEmpty() || period == null || period.isEmpty()) {
            return;
        }
        LocalDate periodDate = LocalDate.parse(period);
        individualTaxRepository.deleteByAccountSetsIdInAndPeriod(accountSetsIdList, periodDate);
    }

    /**
     * 审核个税任务（只有任务列表中执行过的才允许审核）
     * 说明：任务列表中的记录是指该账套在该账期有记录，且已执行过（result不为0）
     *
     * @param auditIndividualTaxDto 审核DTO
     */
    @Transactional
    public void auditIndividualTaxList(AuditIndividualTaxDto auditIndividualTaxDto) {
        String period = auditIndividualTaxDto.getPeriod();
        List<Long> accountSetsIdList = auditIndividualTaxDto.getAccountSetsIdList();
        if (accountSetsIdList == null || accountSetsIdList.isEmpty() || period == null || period.isEmpty()) {
            return;
        }
        LocalDate periodDate = LocalDate.parse(period);

        // 查询指定账套和账期的所有记录
        List<IndividualTax> taxList = individualTaxRepository.findByAccountSetsIdInAndPeriod(accountSetsIdList, periodDate);

        // 筛选符合条件的记录：每个账套取最新的一条记录，且必须已执行过（result不为0）
        // 因为任务列表中的记录就是该账套在该账期的记录，所以只要存在记录且已执行过就可以审核
        Map<Long, IndividualTax> latestTaxMap = new HashMap<>();
        for (IndividualTax tax : taxList) {
            Long accountSetsId = tax.getAccountSetsId();
            // 只处理已执行过的记录（result不为0）
            if (tax.getResult() != null && tax.getResult() != 0) {
                IndividualTax existing = latestTaxMap.get(accountSetsId);
                // 如果该账套还没有记录，或者当前记录的ID更大（更新），则更新
                if (existing == null || (tax.getId() != null && existing.getId() != null && tax.getId() > existing.getId())) {
                    latestTaxMap.put(accountSetsId, tax);
                }
            }
        }

        // 更新审核状态为审核通过
        List<IndividualTax> validTaxList = new ArrayList<>(latestTaxMap.values());
        validTaxList.forEach(tax -> tax.setAuditStatus(1));
        individualTaxRepository.saveAll(validTaxList);
    }

    /**
     * 回退审核状态（将审核通过改回未审核）
     * 只有任务列表中执行过的且已审核通过的记录才允许回退
     *
     * @param auditIndividualTaxDto 审核DTO
     */
    @Transactional
    public void rollbackAuditIndividualTaxList(AuditIndividualTaxDto auditIndividualTaxDto) {
        String period = auditIndividualTaxDto.getPeriod();
        List<Long> accountSetsIdList = auditIndividualTaxDto.getAccountSetsIdList();
        if (accountSetsIdList == null || accountSetsIdList.isEmpty() || period == null || period.isEmpty()) {
            return;
        }
        LocalDate periodDate = LocalDate.parse(period);

        // 查询指定账套和账期的所有记录
        List<IndividualTax> taxList = individualTaxRepository.findByAccountSetsIdInAndPeriod(accountSetsIdList, periodDate);

        // 筛选符合条件的记录：每个账套取最新的一条记录，且必须已执行过（result不为0）且已审核通过（auditStatus=1）或待更新（auditStatus=2）
        Map<Long, IndividualTax> latestTaxMap = new HashMap<>();
        for (IndividualTax tax : taxList) {
            Long accountSetsId = tax.getAccountSetsId();
            // 只处理已执行过且已审核通过或待更新的记录
            if (tax.getResult() != null && tax.getResult() != 0
                    && tax.getAuditStatus() != null && (tax.getAuditStatus() == 1 || tax.getAuditStatus() == 2)) {
                IndividualTax existing = latestTaxMap.get(accountSetsId);
                // 如果该账套还没有记录，或者当前记录的ID更大（更新），则更新
                if (existing == null || (tax.getId() != null && existing.getId() != null && tax.getId() > existing.getId())) {
                    latestTaxMap.put(accountSetsId, tax);
                }
            }
        }

        // 更新审核状态为未审核
        List<IndividualTax> validTaxList = new ArrayList<>(latestTaxMap.values());
        validTaxList.forEach(tax -> tax.setAuditStatus(0));
        individualTaxRepository.saveAll(validTaxList);
    }

    /**
     * 设置个税任务为待更新状态（只有任务列表中执行过的才允许设置）
     * 说明：任务列表中的记录是指该账套在该账期有记录，且已执行过（result不为0）
     *
     * @param auditIndividualTaxDto 审核DTO
     */
    @Transactional
    public void setPendingIndividualTaxList(AuditIndividualTaxDto auditIndividualTaxDto) {
        String period = auditIndividualTaxDto.getPeriod();
        List<Long> accountSetsIdList = auditIndividualTaxDto.getAccountSetsIdList();
        if (accountSetsIdList == null || accountSetsIdList.isEmpty() || period == null || period.isEmpty()) {
            return;
        }
        LocalDate periodDate = LocalDate.parse(period);

        // 查询指定账套和账期的所有记录
        List<IndividualTax> taxList = individualTaxRepository.findByAccountSetsIdInAndPeriod(accountSetsIdList, periodDate);

        // 筛选符合条件的记录：每个账套取最新的一条记录，且必须已执行过（result不为0）
        Map<Long, IndividualTax> latestTaxMap = new HashMap<>();
        for (IndividualTax tax : taxList) {
            Long accountSetsId = tax.getAccountSetsId();
            // 只处理已执行过的记录（result不为0）
            if (tax.getResult() != null && tax.getResult() != 0) {
                IndividualTax existing = latestTaxMap.get(accountSetsId);
                // 如果该账套还没有记录，或者当前记录的ID更大（更新），则更新
                if (existing == null || (tax.getId() != null && existing.getId() != null && tax.getId() > existing.getId())) {
                    latestTaxMap.put(accountSetsId, tax);
                }
            }
        }

        // 更新审核状态为待更新（2）
        List<IndividualTax> validTaxList = new ArrayList<>(latestTaxMap.values());
        validTaxList.forEach(tax -> tax.setAuditStatus(2));
        individualTaxRepository.saveAll(validTaxList);
    }

    /**
     * 将本地文件系统路径转换为HTTP可访问路径
     * 例如：D:\\applyIndividualImg\\ -> /assets/applyIndividualImg/
     *
     * @param localPath 本地文件系统路径
     * @return HTTP相对路径
     */
    private String convertLocalPathToHttpPath(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            return "";
        }

        // 如果已经是HTTP路径（以/开头且不包含盘符），直接返回
        if (localPath.startsWith("/") && !localPath.matches("^[A-Za-z]:.*")) {
            return localPath;
        }

        // 处理Windows路径（如 D:\\applyIndividualImg\\）
        String normalizedPath = localPath.replace("\\", "/");

        // 提取目录名（去掉盘符和路径分隔符）
        // 例如：D:/applyIndividualImg/ -> applyIndividualImg
        String dirName = "";
        if (normalizedPath.contains(":")) {
            // Windows路径，提取最后一个目录名
            String[] parts = normalizedPath.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (StringUtils.hasText(parts[i]) && !parts[i].matches("^[A-Za-z]:$")) {
                    dirName = parts[i];
                    break;
                }
            }
        } else {
            // Unix路径，提取最后一个目录名
            String[] parts = normalizedPath.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (StringUtils.hasText(parts[i])) {
                    dirName = parts[i];
                    break;
                }
            }
        }

        // 转换为HTTP路径：/assets/目录名/
        if (StringUtils.hasText(dirName)) {
            return "/assets/" + dirName + "/";
        }

        // 如果无法提取目录名，使用默认路径
        return "/assets/individualTax/";
    }
    public Boolean individualTaxTaskIsExist(Long accountSetsId,LocalDate period){
        AccountSets accountSets = bqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
        if (ObjectUtils.isEmpty(period)){
            period = accountSets.getCurrentAccountDate();
        }
        long individualTaxCount = bqf.selectFrom(qIndividualTax)
                .where(qIndividualTax.accountSetsId.eq(accountSetsId)
                        .and(qIndividualTax.period.eq(period))
                ).fetchCount();
        if (individualTaxCount != 0) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;

    }
}

