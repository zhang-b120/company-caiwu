package cn.gson.financial.agent.workbench.service;

import cn.gson.financial.agent.taxlogin.service.TaxBrowserManagerService;
import cn.gson.financial.kernel.model.dto.AccountSetsDto;
import cn.gson.financial.kernel.model.dto.WorkbenchAccountDto;
import cn.gson.financial.kernel.model.entity.AccountSetsInvoiceStatusItem;
import cn.gson.financial.kernel.model.entity.TaxLoginLog;
import cn.gson.financial.kernel.model.repo.AccountSetsInvoiceStatusItemRepository;
import cn.gson.financial.kernel.model.repo.TaxLoginLogRepository;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作台服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkbenchService extends AbsService {
    
    private final AccountSetsService accountSetsService;
    private final AccountSetsInvoiceStatusItemRepository invoiceStatusItemRepository;
    private final TaxBrowserManagerService browserManagerService;
    private final TaxLoginLogRepository loginLogRepository;
    
    /**
     * 获取工作台概览数据
     * @param userId 用户ID
     * @param queryPeriod 查询账期（YYYY-MM格式，可为null）
     * @param statusFilter 状态筛选：all-所有, active-正常, warning-需关注, error-异常
     * @param searchKeyword 搜索关键词（账套名称或税号）
     * @return 工作台账套列表
     */
    public List<WorkbenchAccountDto> getOverview(Long userId, LocalDate queryPeriod, 
                                                   String statusFilter, String searchKeyword) {
        // 调用AccountSetsService获取账套列表
        List<AccountSetsDto> accountSetsList = accountSetsService.myAccountSets(userId, queryPeriod);
        
        if (CollUtil.isEmpty(accountSetsList)) {
            return List.of();
        }
        
        // 获取所有账套ID
        List<Long> accountSetsIds = accountSetsList.stream()
                .map(AccountSetsDto::getId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        
        // 计算发票下载进度（基于AccountSetsInvoiceStatusItem）
        Map<Long, Integer> invoiceProgressMap = calculateInvoiceProgress(accountSetsIds, queryPeriod);
        
        // 计算凭证制作进度（暂时返回0，后续完善）
        Map<Long, Integer> voucherProgressMap = calculateVoucherProgress(accountSetsIds, queryPeriod);

        // 计算当前在线的账套（基于浏览器管理服务）
        java.util.Set<String> browserKeys = browserManagerService.getAllBrowserKeys();
        java.util.Set<Long> onlineAccountSetIds = browserKeys.stream()
                .map(browserManagerService::getMetadata)
                .filter(meta -> meta != null && meta.getAccountSetsId() != null)
                .map(TaxBrowserManagerService.BrowserMetadata::getAccountSetsId)
                .collect(Collectors.toSet());

        // 统计今日登录次数（批量一次查询，避免 N+1）
        Map<Long, Integer> loginCountMap = new java.util.HashMap<>();
        if (!accountSetsIds.isEmpty()) {
            loginLogRepository.countTodayLoginByAccountSetsIds(accountSetsIds)
                    .forEach(row -> loginCountMap.put((Long) row[0], ((Number) row[1]).intValue()));
        }
        
        // 转换为WorkbenchAccountDto并填充进度和登录状态
        return accountSetsList.stream().map(dto -> {
            WorkbenchAccountDto workbenchDto = new WorkbenchAccountDto();
            BeanUtil.copyProperties(dto, workbenchDto);
            
            // 设置税号
            workbenchDto.setTaxNumber(dto.getCreditCode());
            
            // 设置发票下载进度
            Long accountSetsId = dto.getId();
            if (accountSetsId != null) {
                workbenchDto.setInvoiceProgress(invoiceProgressMap.getOrDefault(accountSetsId, 0));
                workbenchDto.setVoucherProgress(voucherProgressMap.getOrDefault(accountSetsId, 0));
                
                // 登录状态：如果当前账套在浏览器管理服务中存在有效浏览器，则视为ONLINE，否则OFFLINE
                if (onlineAccountSetIds.contains(accountSetsId)) {
                    workbenchDto.setTaxLoginStatus("ONLINE");
                } else {
                    workbenchDto.setTaxLoginStatus("OFFLINE");
                }
                // 登录次数与限制：按登录日志统计今日登录次数，限制暂定为每小时5次
                workbenchDto.setTaxSwitchCount(loginCountMap.getOrDefault(accountSetsId, 0));
                workbenchDto.setTaxSwitchLimit(5);
            }
            
            return workbenchDto;
        }).collect(Collectors.toList());
    }
    
    /**
     * 计算发票下载进度
     * 基于AccountSetsInvoiceStatusItem表，统计已完成（status=3或result=1）的数量占总数的比例
     */
    private Map<Long, Integer> calculateInvoiceProgress(List<Long> accountSetsIds, LocalDate queryPeriod) {
        if (CollUtil.isEmpty(accountSetsIds)) {
            return Map.of();
        }
        
        // 查询所有发票状态项
        List<AccountSetsInvoiceStatusItem> items = invoiceStatusItemRepository
                .findByAccountSetsIdIn(accountSetsIds);
        
        // 按账套ID分组统计
        Map<Long, List<AccountSetsInvoiceStatusItem>> itemsByAccount = items.stream()
                .filter(item -> item.getAccountSetsId() != null)
                .collect(Collectors.groupingBy(AccountSetsInvoiceStatusItem::getAccountSetsId));
        
        return itemsByAccount.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            List<AccountSetsInvoiceStatusItem> accountItems = entry.getValue();
                            if (CollUtil.isEmpty(accountItems)) {
                                return 0;
                            }
                            
                            // 统计已完成的数量（status=3成功 或 result=1成功）
                            long completedCount = accountItems.stream()
                                    .filter(item -> (item.getStatus() != null && item.getStatus() == 3) ||
                                                   (item.getResult() != null && item.getResult() == 1))
                                    .count();
                            
                            // 计算进度百分比
                            return (int) (completedCount * 100 / accountItems.size());
                        }
                ));
    }
    
    /**
     * 计算凭证制作进度
     * 基于做账状态（accountingStatus）估算进度
     * 如果做账状态为成功(3)，进度为100%；进行中(1)为50%；失败(2)为0%；其他为0%
     */
    private Map<Long, Integer> calculateVoucherProgress(List<Long> accountSetsIds, LocalDate queryPeriod) {
        // 基于做账状态估算进度，后续可以基于实际凭证生成任务计算更精确的进度
        // 这里暂时使用accountingStatus来估算
        return accountSetsIds.stream()
                .collect(Collectors.toMap(id -> id, id -> 0));
    }
}
