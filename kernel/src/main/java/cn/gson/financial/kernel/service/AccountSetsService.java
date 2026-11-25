package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.DateUtil;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.crm.req.GetAccountSetInfoReq;
import cn.gson.financial.kernel.model.crm.resp.GetAccountSetInfoResp;
import cn.gson.financial.kernel.model.dto.AccountSetsDto;
import cn.gson.financial.kernel.model.dto.MaintenanceStatsDto;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.dto.UpdateIndividualTaxDto;
import cn.gson.financial.kernel.model.dto.InvoiceStatusDetailDto;
import cn.gson.financial.kernel.model.dto.TaxStatusDetailDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.individualTax.dto.UpdateIndividualTaxCodeDto;
import cn.gson.financial.kernel.model.mapper.AccountSetsMapper;
import cn.gson.financial.kernel.model.repo.AccountSetsAccountingStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsIndividualTaxStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsInitialBalanceStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsInvoiceStatusItemRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsInvoiceStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsTaxStatusItemRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsTaxStatusRepository;
import cn.gson.financial.kernel.model.repo.IndividualTaxRepository;
import cn.gson.financial.kernel.model.repo.SubjectRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.gson.financial.kernel.model.repo.UserRepository;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.model.ua.request.SetZeroDeclareReq;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.Tuple;
import com.querydsl.core.BooleanBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountSetsService extends AbsService implements BackupProcessor {
    private final AccountSetsMapper accountSetsMapper;

    private final List<AccountSetsProcessor> accountSetsProcessors;

    private final AccountSetsRepository accountSetsRepository;

    private final UserAccountSetsRepository userAccountSetsRepository;

    private final IndividualTaxRepository individualTaxRepository;

    private final SubjectService subjectService;

    private final ReportService reportService;

    private final UserService userService;

    private final UserRepository userRepository;

    private final SubjectRepository subjectRepository;

    private final RoleService roleService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;

    private final QUser qUser = QUser.user;

    private final QRole qRole = QRole.role;

    private final QCheckout qCheckout = QCheckout.checkout;

    private final QVoucher qVoucher = QVoucher.voucher;

    private final QAccountSetsTemplate qAccountSetsTemplate = QAccountSetsTemplate.accountSetsTemplate;

    private final BalanceCalcService balanceCalcService;

    private final CheckoutService checkoutService;
    private final TaxService taxService;
    private final QInvoice qInvoice = QInvoice.invoice;
    private final QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;
    private final QEmployee qEmployee = QEmployee.employee;
    private final QSalary qSalary = QSalary.salary;
    private final QTimedTaskPayment qTimedTaskPayment = QTimedTaskPayment.timedTaskPayment;
    private final QFileInfo qFileInfo = QFileInfo.fileInfo;
    private final QCashierJournal qCashierJournal = QCashierJournal.cashierJournal;

    private final FileInfoService fileInfoService;
    private final CashierJournalService cashierJournalService;
    private final InvoiceService invoiceService;
    private final SalaryVoucherTemplateService salaryVoucherTemplateService;
    private final SalaryVoucherService salaryVoucherService;
    private final AssetsDepreciationService assetsDepreciationService;
    private final VoucherService voucherService;
    private final BalanceInitialService balanceInitialService;
    private final AccountSetsInitialBalanceStatusRepository accountSetsInitialBalanceStatusRepository;
    private final AccountSetsAccountingStatusRepository accountSetsAccountingStatusRepository;
    private final AccountSetsInvoiceStatusRepository accountSetsInvoiceStatusRepository;
    private final AccountSetsInvoiceStatusItemRepository accountSetsInvoiceStatusItemRepository;
    private final AccountSetsTaxStatusRepository accountSetsTaxStatusRepository;
    private final AccountSetsTaxStatusItemRepository accountSetsTaxStatusItemRepository;
    private final AccountSetsIndividualTaxStatusRepository accountSetsIndividualTaxStatusRepository;

    public List<AccountSetsDto> myAccountSets(Long uid) {
        return myAccountSets(uid, null);
    }

    public List<AccountSetsDto> myAccountSets(Long uid, LocalDate queryPeriod) {
        // 先获取账套列表
        List<Tuple> tuples = bqf.selectFrom(qUserAccountSets)
                .select(qAccountSets, qUser.realName.coalesce(qUser.nickname), qRole.name)
                .leftJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                .leftJoin(qUser).on(qUser.id.eq(qAccountSets.creatorId))
                .leftJoin(qRole).on(qRole.id.eq(qUserAccountSets.roleId).and(qUserAccountSets.accountSetsId.eq(qRole.accountSetsId)))
                .where(qUserAccountSets.userId.eq(uid))
                .orderBy(
                        qUserAccountSets.current.desc(),
                        qAccountSets.displayOrder.asc().nullsLast(),
                        qAccountSets.id.desc()
                )
                .fetch();

        if (CollUtil.isEmpty(tuples)) {
            return new ArrayList<>();
        }

        // 获取所有账套ID列表
        List<Long> accountSetsIdList = tuples.stream()
                .map(tuple -> {
                    AccountSets accountSets = tuple.get(qAccountSets);
                    return accountSets != null ? accountSets.getId() : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 批量查询员工数量（按账套分组）
        Map<Long, Long> employeeCountMap = new HashMap<>();
        if (CollUtil.isNotEmpty(accountSetsIdList)) {
            List<Tuple> employeeTuples = bqf.selectFrom(qEmployee)
                    .select(qEmployee.accountSetsId, qEmployee.count())
                    .where(qEmployee.accountSetsId.in(accountSetsIdList))
                    .groupBy(qEmployee.accountSetsId)
                    .fetch();

            if (CollUtil.isNotEmpty(employeeTuples)) {
                employeeCountMap = employeeTuples.stream()
                        .collect(Collectors.toMap(
                                item -> item.get(qEmployee.accountSetsId),
                                item -> item.get(qEmployee.count())
                        ));
            }
        }

        // 批量查询期初录入是否完成（该账套是否存在期初余额数据）
        Map<Long, Long> initialBalanceCountMap = balanceInitialService.countByAccountSetsIds(accountSetsIdList);

        // 批量查询各类状态主表
        Map<Long, AccountSetsInitialBalanceStatus> initialStatusMap = accountSetsInitialBalanceStatusRepository
                .findByAccountSetsIdIn(accountSetsIdList).stream()
                .filter(s -> s.getAccountSetsId() != null)
                .collect(Collectors.toMap(AccountSetsInitialBalanceStatus::getAccountSetsId, s -> s, (a, b) -> a));

        Map<String, AccountSetsAccountingStatus> accountingStatusMap = accountSetsAccountingStatusRepository
                .findByAccountSetsIdIn(accountSetsIdList).stream()
                .filter(s -> s.getAccountSetsId() != null && s.getPeriod() != null)
                .collect(Collectors.toMap(s -> s.getAccountSetsId() + ":" + s.getPeriod(), s -> s, (a, b) -> a));

        Map<String, AccountSetsInvoiceStatus> invoiceStatusMap = accountSetsInvoiceStatusRepository
                .findByAccountSetsIdIn(accountSetsIdList).stream()
                .filter(s -> s.getAccountSetsId() != null && s.getPeriod() != null)
                .collect(Collectors.toMap(s -> s.getAccountSetsId() + ":" + s.getPeriod(), s -> s, (a, b) -> a));

        Map<String, AccountSetsTaxStatus> taxStatusMap = accountSetsTaxStatusRepository
                .findByAccountSetsIdIn(accountSetsIdList).stream()
                .filter(s -> s.getAccountSetsId() != null && s.getPeriod() != null)
                .collect(Collectors.toMap(s -> s.getAccountSetsId() + ":" + s.getPeriod(), s -> s, (a, b) -> a));

        Map<String, AccountSetsIndividualTaxStatus> individualTaxStatusMap = accountSetsIndividualTaxStatusRepository
                .findByAccountSetsIdIn(accountSetsIdList).stream()
                .filter(s -> s.getAccountSetsId() != null && s.getPeriod() != null)
                .collect(Collectors.toMap(s -> s.getAccountSetsId() + ":" + s.getPeriod(), s -> s, (a, b) -> a));

        // 创建final变量供lambda表达式使用
        final Map<Long, Long> finalEmployeeCountMap = employeeCountMap;
        final Map<Long, Long> finalInitialBalanceCountMap = initialBalanceCountMap;
        final Map<Long, AccountSetsInitialBalanceStatus> finalInitialStatusMap = initialStatusMap;
        final Map<String, AccountSetsAccountingStatus> finalAccountingStatusMap = accountingStatusMap;
        final Map<String, AccountSetsInvoiceStatus> finalInvoiceStatusMap = invoiceStatusMap;
        final Map<String, AccountSetsTaxStatus> finalTaxStatusMap = taxStatusMap;
        final Map<String, AccountSetsIndividualTaxStatus> finalIndividualStatusMap = individualTaxStatusMap;
        final LocalDate finalQueryPeriod = queryPeriod != null ? queryPeriod.withDayOfMonth(1) : null;

        // 转换为DTO并填充员工数量、五类状态
        return tuples.stream()
                .filter(tuple -> tuple.get(qAccountSets) != null) // 过滤掉AccountSets为null的记录（账套可能已被删除）
                .map(tuple -> {
            AccountSets accountSets = tuple.get(qAccountSets);
            AccountSetsDto dto = accountSetsMapper.toDto(accountSets);
            dto.setCreatorName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
            dto.setRoleName(tuple.get(qRole.name));

            Long accountSetsId = dto.getId();
            if (accountSetsId != null) {
                Long employeeCount = finalEmployeeCountMap.getOrDefault(accountSetsId, 0L);
                dto.setEmployeeTotalCount(employeeCount);
                dto.setInitialBalanceCompleted(finalInitialBalanceCountMap.getOrDefault(accountSetsId, 0L) > 0);

                // 期初录入：取启用账期对应行；做账/取票/报税/个税：取「查询账期」（未传则为当前账期）对应行
                LocalDate enablePeriod = dto.getEnableDate() != null ? dto.getEnableDate().withDayOfMonth(1) : null;
                LocalDate currentPeriod = dto.getCurrentAccountDate() != null ? dto.getCurrentAccountDate().withDayOfMonth(1) : null;
                LocalDate statusPeriod = finalQueryPeriod != null ? finalQueryPeriod : currentPeriod;

                AccountSetsInitialBalanceStatus initialStatus = finalInitialStatusMap.get(accountSetsId);
                String keyCurrent = statusPeriod != null ? accountSetsId + ":" + statusPeriod : null;

                AccountSetsAccountingStatus accountingStatus = keyCurrent != null ? finalAccountingStatusMap.get(keyCurrent) : null;
                AccountSetsInvoiceStatus invoiceStatus = keyCurrent != null ? finalInvoiceStatusMap.get(keyCurrent) : null;
                AccountSetsTaxStatus taxStatus = keyCurrent != null ? finalTaxStatusMap.get(keyCurrent) : null;
                AccountSetsIndividualTaxStatus individualStatus = keyCurrent != null ? finalIndividualStatusMap.get(keyCurrent) : null;

                dto.setInitialBalanceStatus(normalizeStatus(initialStatus != null ? initialStatus.getStatus() : null));
                dto.setAccountingStatus(normalizeStatus(accountingStatus != null ? accountingStatus.getStatus() : null));
                dto.setInvoiceStatus(normalizeStatus(invoiceStatus != null ? invoiceStatus.getStatus() : null));
                dto.setTaxStatus(normalizeStatus(taxStatus != null ? taxStatus.getStatus() : null));
                dto.setIndividualTaxStatus(normalizeStatus(individualStatus != null ? individualStatus.getStatus() : null));
            } else {
                dto.setEmployeeTotalCount(0L);
                dto.setInitialBalanceCompleted(false);
                dto.setInitialBalanceStatus(0);
                dto.setAccountingStatus(0);
                dto.setInvoiceStatus(0);
                dto.setTaxStatus(0);
                dto.setIndividualTaxStatus(0);
            }

            return dto;
        }).toList();
    }

    /**
     * 将状态值标准化为首页使用的 -1~3（null 视为 -1 未加入）
     */
    private int normalizeStatus(Integer status) {
        if (status == null) {
            return -1;
        }
        return status;
    }

    public List<TaxDto> newMyAccountSets(Long uid, Long accountSetsId, LocalDate taxEndTime, String province) {
        TaxDto taxDto = new TaxDto();
        taxDto.setUserId(uid);
        taxDto.setAccountSetsId(accountSetsId);
        taxDto.setTaxEndTime(taxEndTime);
        if (StringUtils.isNotEmpty(province)) {
            taxDto.setProvince(province);
        }

        List<TaxDto> taxDtoList = taxService.selectTaxItem(taxDto);
        return taxDtoList;
    }
    /**
     * 一键报税数据
     */
    public List<TaxDto> accountSetsOfOneKey(Long uid, String accountPeriod, String province,String companyName) {
        TaxDto taxDto = new TaxDto();
        taxDto.setUserId(uid);
        taxDto.setCompanyName(companyName);
        taxDto.setAccountPeriod(accountPeriod);
        if (StringUtils.isNotEmpty(province)) {
            taxDto.setProvince(province);
        }

        List<TaxDto> taxDtoList = taxService.selectTaxItem(taxDto);
        return taxDtoList;
    }

    /**
     * 获取用户账套的维护统计信息
     * 统计个税密码为空的账套数和员工为零的账套数
     *
     * @param userId 用户ID
     * @return 维护统计信息
     */
    public MaintenanceStatsDto getMaintenanceStats(Long userId) {
        // 获取用户关联的所有账套ID列表（不仅仅是创建的，包括分配给用户的）
        List<Long> accountSetsIdList = bqf.selectFrom(qUserAccountSets)
                .select(qUserAccountSets.accountSetsId)
                .where(qUserAccountSets.userId.eq(userId))
                .fetch();

        if (CollUtil.isEmpty(accountSetsIdList)) {
            MaintenanceStatsDto stats = new MaintenanceStatsDto();
            stats.setEmptyPasswordCount(0L);
            stats.setEmptyEmployeeCount(0L);
            stats.setUnCheckoutCount(0L);
            return stats;
        }

        // 统计个税密码为空的账套数（在用户关联的所有账套中，只统计启用的账套，且排除不能报个税的账套）
        long emptyPasswordCount = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.in(accountSetsIdList)
                        .and(qAccountSets.deactivated.isFalse().or(qAccountSets.deactivated.isNull()))
                        .and(qAccountSets.individualTaxCode.isNull()
                                .or(qAccountSets.individualTaxCode.isEmpty()))
                        .and(qAccountSets.individualTax.isNull()
                                .or(qAccountSets.individualTax.isEmpty())))
                .fetchCount();

        // 统计所有账套的员工数量（按账套分组）
        Map<Long, Long> employeeCountMap = new HashMap<>();
        List<Tuple> employeeTuples = bqf.selectFrom(qEmployee)
                .select(qEmployee.accountSetsId, qEmployee.count())
                .where(qEmployee.accountSetsId.in(accountSetsIdList))
                .groupBy(qEmployee.accountSetsId)
                .fetch();

        if (CollUtil.isNotEmpty(employeeTuples)) {
            employeeCountMap = employeeTuples.stream()
                    .collect(Collectors.toMap(
                            item -> item.get(qEmployee.accountSetsId),
                            item -> item.get(qEmployee.count())
                    ));
        }

        // 创建final变量供lambda表达式使用
        final Map<Long, Long> finalEmployeeCountMap = employeeCountMap;

        // 统计员工为零的账套数（只统计启用的账套，且排除不能报个税的账套）
        List<Long> enabledAccountSetsIdList = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id)
                .where(qAccountSets.id.in(accountSetsIdList)
                        .and(qAccountSets.deactivated.isFalse().or(qAccountSets.deactivated.isNull()))
                        .and(qAccountSets.individualTax.isNull()
                                .or(qAccountSets.individualTax.isEmpty())))
                .fetch();

        long emptyEmployeeCount = enabledAccountSetsIdList.stream()
                .filter(accountSetsId -> !finalEmployeeCountMap.containsKey(accountSetsId) || finalEmployeeCountMap.get(accountSetsId) == 0)
                .count();

        // 统计未结转到当前月的账套数量
        // 获取当前月份（年-月）
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        YearMonth currentYearMonth = YearMonth.from(currentMonth);

        // 查询所有启用的账套及其当前记账日期
        List<Tuple> accountSetsWithDates = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id, qAccountSets.currentAccountDate)
                .where(qAccountSets.id.in(accountSetsIdList)
                        .and(qAccountSets.deactivated.isFalse().or(qAccountSets.deactivated.isNull())))
                .fetch();

        long unCheckoutCount = accountSetsWithDates.stream()
                .filter(tuple -> {
                    LocalDate currentAccountDate = tuple.get(qAccountSets.currentAccountDate);
                    if (currentAccountDate == null) {
                        // 如果当前记账日期为空，认为未结转到当前月
                        return true;
                    }
                    // 将当前记账日期转换为年-月，与当前月份比较
                    YearMonth accountYearMonth = YearMonth.from(currentAccountDate);
                    // 如果账套的当前记账日期不是当前月份，说明未结转到当前月
                    return !accountYearMonth.equals(currentYearMonth);
                })
                .count();

        MaintenanceStatsDto stats = new MaintenanceStatsDto();
        stats.setEmptyPasswordCount(emptyPasswordCount);
        stats.setEmptyEmployeeCount(emptyEmployeeCount);
        stats.setUnCheckoutCount(unCheckoutCount);
        return stats;
    }

    @Transactional
    public void addUser(Long accountSetsId, String mobile, Long roleId) {
        User user = bqf.selectFrom(qUser).where(qUser.mobile.eq(mobile)).fetchFirst();
        if (user == null) {
            throw new ServiceException("此手机号未注册！", 501);
        }

        if (bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                        .and(qUserAccountSets.userId.eq(user.getId())))
                .fetchCount() > 0) {
            throw new ServiceException("亲，此用户已经拥有此账套权限！", 502);
        }

        this.userNumCheck(accountSetsId, mobile);

        // 检查用户是否已有其他账套
        boolean hasOtherAccountSets = bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.userId.eq(user.getId()))
                .fetchCount() > 0;

        // 如果用户已有其他账套，不改变当前账套；如果没有，则设置为当前账套
        if (!hasOtherAccountSets) {
            // 用户没有其他账套，将新分配的账套设置为当前账套
            // 先将所有账套的 current 设置为 false（虽然此时应该没有其他账套，但为了安全）
            jqf.update(qUserAccountSets)
                    .set(qUserAccountSets.current, false)
                    .where(qUserAccountSets.userId.eq(user.getId()))
                    .execute();
        }
        // 如果用户已有其他账套，不执行任何更新，保持原有当前账套不变

        //添加关系
        UserAccountSets userAccountSets = new UserAccountSets();
        userAccountSets.setAccountSetsId(accountSetsId);
        userAccountSets.setUserId(user.getId());
        userAccountSets.setRoleId(roleId);
        // 如果用户已有其他账套，新分配的账套不设置为当前账套；如果没有，则设置为当前账套
        userAccountSets.setCurrent(!hasOtherAccountSets);
        userAccountSets.setCreator(false);
        userAccountSetsRepository.save(userAccountSets);
    }


    @Transactional
    public User addNewUser(Long accountSetsId, String mobile, Long roleId) {
        this.userNumCheck(accountSetsId, mobile);

        Role role = roleService.load(accountSetsId, roleId);
        User user = new User();
        user.setMobile(mobile);
        user.setNickname(role.getName());
        user.setRealName(role.getName() + mobile.substring(7));
        user.setAccountSetsNum(2);
        user.setPassword(DigestUtil.sha256Hex(mobile.substring(5)));
        this.userRepository.save(user);

        UserAccountSets userAccountSets = new UserAccountSets();
        userAccountSets.setAccountSetsId(accountSetsId);
        userAccountSets.setUserId(user.getId());
        userAccountSets.setRoleId(roleId);
        userAccountSets.setCurrent(true);
        userAccountSets.setCreator(false);
        this.userAccountSetsRepository.save(userAccountSets);
        return user;
    }


    @Transactional
    public User removeUser(Long accountSetsId, Long uid) {
        UserAccountSets userAccountSets = bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId).and(qUserAccountSets.userId.eq(uid)))
                .fetchFirst();
        jqf.delete(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId).and(qUserAccountSets.userId.eq(uid)))
                .execute();

        //重置用户默认账套
        Optional<User> user = this.userRepository.findById(uid);
        if (userAccountSets.getCurrent()) {
            setUserCurrentAs(user.get());
        }
        return user.get();
    }

    /**
     * 设置用户默认账套
     *
     * @param user
     */
    @Transactional
    public void setUserCurrentAs(User user) {
        List<UserAccountSets> accountSets =
                bqf.selectFrom(qUserAccountSets)
                        .where(qUserAccountSets.userId.eq(user.getId()))
                        .fetch();
        if (!accountSets.isEmpty()) {
            UserAccountSets current = accountSets.get(0);
            jqf.update(qUserAccountSets)
                    .set(qUserAccountSets.current, true)
                    .where(qUserAccountSets.id.eq(current.getId())
                            .and(qUserAccountSets.userId.eq(user.getId()))
                            .and(qUserAccountSets.accountSetsId.eq(current.getAccountSetsId())))
                    .execute();
        }
    }

    @Transactional
    public void updateUserRole(Long accountSetsId, Long uid, Long roleId) {
        jqf.update(qUserAccountSets)
                .set(qUserAccountSets.roleId, roleId)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId).and(qUserAccountSets.userId.eq(uid)))
                .execute();
    }


    @Transactional
    public void handOver(Long accountSetsId, Long currentUserId, String mobile) {
        AccountSets accountSets = this.accountSetsRepository.getReferenceById(accountSetsId);
        UserAccountSets userAccountSets = bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId).and(qUserAccountSets.userId.eq(currentUserId)))
                .fetchFirst();
        //获取手机号的用户信息
        User user = this.userRepository.findByMobile(mobile);

        // 用户必须已存在于系统中，不允许自动创建
        if (user == null) {
            throw new ServiceException("此手机号未注册！请先让该用户注册后再设置为管理员。", 501);
        }

        //获取账套管理员角色
        Role role = roleService.getAstAdmin(accountSetsId);

        // 检查用户账套数量限制
        long count = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creatorId.eq(user.getId()))
                .fetchCount();
        Assert.isTrue(count < user.getAccountSetsNum(), "此用户购买的账套数为" + user.getAccountSetsNum() + "套,已超上线~");

        boolean relate = this.userAccountSetsRepository.selectCount(accountSetsId, user.getId()) > 0;

        //用户未关联时，进行关联
        if (!relate) {
            UserAccountSets newUserAccountSets = new UserAccountSets();
            newUserAccountSets.setAccountSetsId(accountSetsId);
            newUserAccountSets.setUserId(user.getId());
            newUserAccountSets.setCreator(true);
            newUserAccountSets.setRoleId(role.getId());
            // 设置为管理员时，不自动设置为当前账套（用户需要自己选择）
            newUserAccountSets.setCurrent(false);
            this.userAccountSetsRepository.save(newUserAccountSets);
        } else {
            //账套修改为创建者
            jqf.update(qUserAccountSets)
                    .set(qUserAccountSets.creator, true)
                    .where(qUserAccountSets.userId.eq(user.getId())
                            .and(qUserAccountSets.accountSetsId.eq(accountSets.getId())))
                    .execute();
        }

        // 修改账套拥有者
        accountSets.setCreatorId(user.getId());
        this.accountSetsRepository.save(accountSets);

        // 保留原创建人的使用权限，仅取消其“创建人/管理员”标记
        jqf.update(qUserAccountSets)
                .set(qUserAccountSets.creator, false)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                        .and(qUserAccountSets.userId.eq(currentUserId)))
                .execute();
    }

    /**
     * 批量改变用户权限
     * 保留管理员权限，删除其他非管理员权限，添加新选择的用户权限
     *
     * @param accountSetsIds 账套ID列表
     * @param userMobiles    要保留/添加的用户手机号列表
     */
    @Transactional
    public void batchChangeUserPermission(List<Long> accountSetsIds, List<String> userMobiles) {
        if (CollUtil.isEmpty(accountSetsIds)) {
            return;
        }

        // 按账套ID排序，避免死锁
        List<Long> sortedIds = new ArrayList<>(accountSetsIds);
        sortedIds.sort(Long::compareTo);

        for (Long accountSetsId : sortedIds) {
            try {
                changeUserPermission(accountSetsId, userMobiles);
            } catch (Exception e) {
                log.error("批量改变用户权限失败，账套ID: {}", accountSetsId, e);
                throw new ServiceException("批量改变用户权限失败，账套ID: " + accountSetsId + "，错误: " + e.getMessage(), 500);
            }
        }
    }

    /**
     * 改变单个账套的用户权限
     * 保留创建人权限，删除其他所有用户权限，添加新选择的用户权限
     *
     * @param accountSetsId 账套ID
     * @param userMobiles   要保留/添加的用户手机号列表
     */
    @Transactional
    public void changeUserPermission(Long accountSetsId, List<String> userMobiles) {
        // 获取账套信息，找到创建人ID
        AccountSets accountSets = accountSetsRepository.getReferenceById(accountSetsId);
        Long creatorId = accountSets.getCreatorId();

        // 获取管理员角色（用于给新添加的用户分配角色）
        Role adminRole = roleService.getAstAdmin(accountSetsId);

        // 获取当前账套的所有用户
        List<UserVo> currentUsers = userService.listByAccountSetId(accountSetsId);

        // 将要保留的用户手机号转换为用户ID
        Set<Long> keepUserIds = new HashSet<>();
        // 首先添加创建人ID（必须保留）
        if (creatorId != null) {
            keepUserIds.add(creatorId);
        }
        // 然后添加用户选择的用户ID
        if (CollUtil.isNotEmpty(userMobiles)) {
            for (String mobile : userMobiles) {
                User user = userRepository.findByMobile(mobile);
                if (user != null) {
                    keepUserIds.add(user.getId());
                }
            }
        }

        // 删除不在保留列表中的所有用户权限（包括其他管理员）
        for (UserVo user : currentUsers) {
            Long userId = user.getId();
            // 如果不在保留列表中，删除权限
            if (!keepUserIds.contains(userId)) {
                UserAccountSets userAccountSets = bqf.selectFrom(qUserAccountSets)
                        .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                                .and(qUserAccountSets.userId.eq(userId)))
                        .fetchFirst();
                if (userAccountSets != null) {
                    jqf.delete(qUserAccountSets)
                            .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                                    .and(qUserAccountSets.userId.eq(userId)))
                            .execute();

                    // 如果删除的是当前账套，需要重置用户默认账套
                    if (userAccountSets.getCurrent()) {
                        Optional<User> userEntity = userRepository.findById(userId);
                        if (userEntity.isPresent()) {
                            setUserCurrentAs(userEntity.get());
                        }
                    }
                }
            }
        }

        // 添加新选择的用户权限（如果还没有权限）
        if (CollUtil.isNotEmpty(userMobiles)) {
            for (String mobile : userMobiles) {
                User user = userRepository.findByMobile(mobile);
                if (user == null) {
                    continue; // 用户不存在，跳过
                }

                // 检查用户是否已有权限
                boolean hasPermission = bqf.selectFrom(qUserAccountSets)
                        .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                                .and(qUserAccountSets.userId.eq(user.getId())))
                        .fetchCount() > 0;

                if (!hasPermission) {
                    // 添加权限
                    this.userNumCheck(accountSetsId, mobile);

                    // 检查用户是否已有其他账套
                    boolean hasOtherAccountSets = bqf.selectFrom(qUserAccountSets)
                            .where(qUserAccountSets.userId.eq(user.getId()))
                            .fetchCount() > 0;

                    // 添加关系
                    UserAccountSets userAccountSets = new UserAccountSets();
                    userAccountSets.setAccountSetsId(accountSetsId);
                    userAccountSets.setUserId(user.getId());
                    userAccountSets.setRoleId(adminRole.getId()); // 使用管理员角色
                    userAccountSets.setCurrent(!hasOtherAccountSets);
                    userAccountSets.setCreator(false);
                    userAccountSetsRepository.save(userAccountSets);
                }
            }
        }
    }

    /**
     * 批量移交账套
     *
     * @param accountSetsIds 账套ID列表
     * @param currentUserId  当前用户ID
     * @param mobile         目标手机号
     */
    @Transactional
    public void batchHandOver(List<Long> accountSetsIds, Long currentUserId, String mobile) {
        if (CollUtil.isEmpty(accountSetsIds)) {
            return;
        }
        // 按账套ID排序，避免死锁（确保所有操作按相同顺序获取锁）
        List<Long> sortedIds = new ArrayList<>(accountSetsIds);
        sortedIds.sort(Long::compareTo);

        // 添加重试机制处理死锁
        for (Long accountSetsId : sortedIds) {
            int maxRetries = 3;
            int retryCount = 0;
            boolean success = false;

            while (retryCount < maxRetries && !success) {
                try {
                    handOver(accountSetsId, currentUserId, mobile);
                    success = true;
                } catch (Exception e) {
                    // 如果是死锁异常，进行重试
                    if (e.getMessage() != null && e.getMessage().contains("Deadlock")) {
                        retryCount++;
                        if (retryCount >= maxRetries) {
                            log.error("批量移交账套失败，账套ID: {}, 重试{}次后仍失败", accountSetsId, maxRetries, e);
                            throw new ServiceException("批量移交账套时发生死锁，请稍后重试", 500);
                        }
                        // 等待随机时间后重试（避免多个请求同时重试）
                        try {
                            Thread.sleep(50 + (long)(Math.random() * 100));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ServiceException("操作被中断", 500);
                        }
                        log.warn("批量移交账套发生死锁，账套ID: {}, 第{}次重试", accountSetsId, retryCount);
                    } else {
                        // 非死锁异常直接抛出
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * 更新科目编码设置
     *
     * @param accountSetsId
     * @param model
     * @param newModel
     */
    @Transactional
    public void updateEncode(Long accountSetsId, List<Integer> model, List<Integer> newModel) {
        Integer len = newModel.stream().reduce(Integer::sum).orElse(0);
        AccountSets accountSets = accountSetsRepository.getReferenceById(accountSetsId);
        accountSets.setEncoding(newModel);
        this.accountSetsRepository.save(accountSets);

        //更新所有科目编码
        List<Subject> subjects = this.subjectRepository.selectByAccountSetsId(accountSetsId);

        subjects.forEach(subject -> {
            String code = SubjectUtil.updateCode(subject.getCode(), model, newModel);
            subject.setCode(code);
            subject.setQueryCode(StrUtil.padAfter(code, len, "0"));
        });

        subjectService.batchSave(accountSetsId, subjects);
    }

    /**
     * 保存账套
     *
     * @param entity        账套
     * @param isUserCurrent 是否用户当前使用账套
     */
    @Transactional
    public void save(AccountSets entity, boolean isUserCurrent) {
        save(entity, true, isUserCurrent);
    }


    /**
     * 规范化字符串用于比较（去除两端空格，全角符号转半角）
     *
     * @param str 原始字符串
     * @return 规范化后的字符串
     */
    private String normalizeForCompare(String str) {
        if (StrUtil.isEmpty(str)) {
            return str;
        }
        return str.trim()
                .replace("，", ",") // 全角逗号转半角
                .replace("。", ".") // 全角句号转半角
                .replace("（", "(") // 全角左括号转半角
                .replace("）", ")") // 全角右括号转半角
                .replace("【", "[") // 全角左方括号转半角
                .replace("】", "]") // 全角右方括号转半角
                .replace("：", ":") // 全角冒号转半角
                .replace("；", ";") // 全角分号转半角
                .replace("！", "!") // 全角感叹号转半角
                .replace("？", "?") // 全角问号转半角
                .replace("－", "-") // 全角减号转半角
                .replace("—", "-") // 全角破折号转半角
                .replace("…", "...") // 全角省略号转半角
                .replace("'", "'") // 全角单引号转半角
                .replace("\"", "\"") // 全角双引号转半角
                .replace("'", "'") // 全角单引号转半角
                .replace("\"", "\"") // 全角双引号转半角
                .replace("￥", "¥") // 全角人民币符号转半角
                .replace("％", "%") // 全角百分号转半角
                .replace("＆", "&") // 全角与号转半角
                .replace("＊", "*") // 全角星号转半角
                .replace("＋", "+") // 全角加号转半角
                .replace("＝", "=") // 全角等号转半角
                .replace("＜", "<") // 全角小于号转半角
                .replace("＞", ">") // 全角大于号转半角
                .replace("～", "~") // 全角波浪号转半角
                .replace("＠", "@") // 全角at符号转半角
                .replace("＃", "#") // 全角井号转半角
                .replace("＄", "$") // 全角美元符号转半角
                .replace("＾", "^") // 全角脱字符转半角
                .replace("｛", "{") // 全角左大括号转半角
                .replace("｝", "}") // 全角右大括号转半角
                .replace("｜", "|") // 全角竖线转半角
                .replace("＼", "\\") // 全角反斜杠转半角
                .replace("／", "/") // 全角斜杠转半角
                .replace("｀", "`") // 全角反引号转半角
                .replace("　", " "); // 全角空格转半角空格
    }

    /**
     * 导入前预校验（只读，不开事务）：配额、公司名/信用代码去重、模板匹配
     * 在进入 batchSave 的大事务之前调用，提前暴露问题
     */
    public List<String> preValidateForImport(List<AccountSets> entities, Long creatorId) {
        List<String> errors = new ArrayList<>();

        // 1. 配额校验
        User user = bqf.selectFrom(qUser).where(qUser.id.eq(creatorId)).fetchFirst();
        long existingCount = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creatorId.eq(creatorId))
                .fetchCount();
        long totalAfterImport = existingCount + entities.size();
        if (user.getAccountSetsNum() < totalAfterImport) {
            errors.add("账套数量不足：当前已有 " + existingCount + " 个，本次导入 " + entities.size()
                    + " 个，总计需要 " + totalAfterImport + " 个，但上限为 " + user.getAccountSetsNum() + " 个");
            return errors;
        }

        // 2. 一次性查出数据库中所有公司名和信用代码（规范化后）用于去重
        Set<String> dbCompanyNames = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.companyName.isNotNull().and(qAccountSets.companyName.ne("")))
                .fetch()
                .stream()
                .map(AccountSets::getCompanyName)
                .filter(StrUtil::isNotEmpty)
                .map(this::normalizeForCompare)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> dbCreditCodes = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.isNotNull().and(qAccountSets.creditCode.ne("")))
                .fetch()
                .stream()
                .map(AccountSets::getCreditCode)
                .filter(StrUtil::isNotEmpty)
                .map(this::normalizeForCompare)
                .collect(java.util.stream.Collectors.toSet());

        for (AccountSets entity : entities) {
            String normalizedName = normalizeForCompare(entity.getCompanyName());
            String normalizedCode = normalizeForCompare(entity.getCreditCode());

            // 同一公司的多个问题归集到一条错误
            List<String> issues = new ArrayList<>();
            if (normalizedName != null && dbCompanyNames.contains(normalizedName)) {
                issues.add("单位名称已存在");
            }
            if (normalizedCode != null && dbCreditCodes.contains(normalizedCode)) {
                issues.add("统一社会信用代码已存在");
            }
            if (!issues.isEmpty()) {
                errors.add("「" + entity.getCompanyName() + "」" + String.join("、", issues));
            }

            // 规范化实体本身（预校验阶段提前做好，batchSave 时跳过重复检查）
            if (normalizedName != null) entity.setCompanyName(normalizedName);
            if (normalizedCode != null) entity.setCreditCode(normalizedCode);
        }

        // 3. 模板匹配校验
        Set<VatType> vatTypes = entities.stream()
                .map(e -> e.getVatType() != null ? e.getVatType() : VatType.SMALL_SCALE)
                .collect(java.util.stream.Collectors.toSet());
        for (VatType vt : vatTypes) {
            AccountSetsTemplate tpl = bqf.selectFrom(qAccountSetsTemplate)
                    .where(qAccountSetsTemplate.vatType.eq(vt))
                    .orderBy(qAccountSetsTemplate.id.asc())
                    .fetchFirst();
            if (tpl == null) {
                errors.add("未找到纳税人类型「" + vt + "」对应的账套模板，请联系管理员配置");
            }
        }

        return errors;
    }

    /**
     *
     * @param entity        账套
     * @param init          是否初始化
     * @param isUserCurrent 是否用户当前使用账套
     */
    @Transactional
    public void save(AccountSets entity, boolean init, boolean isUserCurrent) {
        User user = bqf.selectFrom(qUser).where(qUser.id.eq(entity.getCreatorId())).fetchFirst();
        long count = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creatorId.eq(entity.getCreatorId()))
                .fetchCount();

        Assert.isTrue(user.getAccountSetsNum() >= count + 1, "账套创建上线为：" + user.getAccountSetsNum() + "个，请联系服务商购买~");

        // 规范化公司名和税号（去除两端空格，全角转半角）
        final String normalizedCompanyName;
        if (StrUtil.isNotEmpty(entity.getCompanyName())) {
            normalizedCompanyName = normalizeForCompare(entity.getCompanyName());
            entity.setCompanyName(normalizedCompanyName);
        } else {
            normalizedCompanyName = null;
        }

        final String normalizedCreditCode;
        if (StrUtil.isNotEmpty(entity.getCreditCode())) {
            normalizedCreditCode = normalizeForCompare(entity.getCreditCode());
            entity.setCreditCode(normalizedCreditCode);
        } else {
            normalizedCreditCode = null;
        }

        // 检查公司名是否重复（使用规范化后的值进行比较）
        if (normalizedCompanyName != null) {
            long companyNameCount = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.companyName.isNotNull()
                            .and(qAccountSets.companyName.ne(""))
                    )
                    .fetch()
                    .stream()
                    .map(AccountSets::getCompanyName)
                    .filter(StrUtil::isNotEmpty)
                    .map(this::normalizeForCompare)
                    .filter(name -> name.equals(normalizedCompanyName))
                    .count();

            Assert.isTrue(companyNameCount == 0, "单位名称已经存在！");
        }

        // 检查税号（统一社会信用代码）是否重复（使用规范化后的值进行比较）
        if (normalizedCreditCode != null) {
            long creditCodeCount = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.creditCode.isNotNull()
                            .and(qAccountSets.creditCode.ne(""))
                    )
                    .fetch()
                    .stream()
                    .map(AccountSets::getCreditCode)
                    .filter(StrUtil::isNotEmpty)
                    .map(this::normalizeForCompare)
                    .filter(code -> code.equals(normalizedCreditCode))
                    .count();

            Assert.isTrue(creditCodeCount == 0, "统一社会信用代码（税号）已经存在！");
        }

        AccountSetsTemplate accountSetsTemplate;
        if (entity.getAstId() != null) {
            accountSetsTemplate = bqf.selectFrom(qAccountSetsTemplate)
                    .where(qAccountSetsTemplate.id.eq(entity.getAstId()))
                    .fetchFirst();
        } else {
            // 未指定模板时，按 vatType 自动匹配第一个模板（不过滤 enabled，兼容历史数据）
            VatType vatType = entity.getVatType() != null ? entity.getVatType() : VatType.SMALL_SCALE;
            accountSetsTemplate = bqf.selectFrom(qAccountSetsTemplate)
                    .where(qAccountSetsTemplate.vatType.eq(vatType))
                    .orderBy(qAccountSetsTemplate.id.asc())
                    .fetchFirst();
            if (accountSetsTemplate != null) {
                entity.setAstId(accountSetsTemplate.getId());
            }
        }

        Assert.notNull(accountSetsTemplate, "未找到匹配的账套模板，请联系管理员配置模板~");

        entity.setVatType(accountSetsTemplate.getVatType());
        entity.setAccountStandard(accountSetsTemplate.getAccountStandard());
        entity.setSubjectCategory(accountSetsTemplate.getSubjectCategory());

        if (CollUtil.isEmpty(entity.getEncoding())) {
            entity.setEncoding(accountSetsTemplate.getEncoding());
        }

        if (CollUtil.isEmpty(entity.getSubjectCategory())) {
            entity.setSubjectCategory(accountSetsTemplate.getSubjectCategory());
        }

        accountSetsRepository.saveAndFlush(entity);

        // 新建账套时，初始化随手记表 fxy_financial_account_sets_notes（用于备注与排序）
        if (entity.getId() != null) {
            Map<String, Object> notesParams = new HashMap<>();
            notesParams.put("accountSetsId", entity.getId());
            notesParams.put("notes", entity.getRemark() == null ? "" : entity.getRemark());
            try {
                lazyDao.executeSql("insertAccountSetsNotes", notesParams);
            } catch (Exception ex) {
                // 若已存在（极小概率重复插入）或其他错误，不影响主流程
                log.warn("insertAccountSetsNotes failed for accountSetsId={}", entity.getId(), ex);
            }
        }

        if (init) {
            //再初始化账套
            accountSetsProcessors.forEach(processor -> processor.init(entity, accountSetsTemplate));
            //当前用户更新默认账套
            if (isUserCurrent) {
                jqf.update(qUserAccountSets)
                        .set(qUserAccountSets.current, false)
                        .where(qUserAccountSets.userId.eq(entity.getCreatorId()))
                        .execute();
            }

            Role astAdmin = roleService.getAstAdmin(entity.getId());

            //加入关联表
            UserAccountSets userAccountSets = new UserAccountSets();
            userAccountSets.setAccountSetsId(entity.getId());
            userAccountSets.setUserId(entity.getCreatorId());
            userAccountSets.setRoleId(astAdmin.getId());
            userAccountSets.setCreator(false);
            userAccountSets.setCurrent(isUserCurrent);
            userAccountSetsRepository.save(userAccountSets);
            UserAccountSets adminUserAccountSets = new UserAccountSets();
            //用来向管理员，这里先写19554509313为管理员
            Long i = bqf.selectFrom(qUser).select(qUser.id).where(qUser.mobile.eq("19554509313")).fetchFirst();
            int i1 = i.compareTo(entity.getCreatorId());
            if (i1 != 0) {
                adminUserAccountSets.setUserId(i);
                adminUserAccountSets.setAccountSetsId(entity.getId());
                adminUserAccountSets.setRoleId(astAdmin.getId());
                adminUserAccountSets.setCreator(true);
                adminUserAccountSets.setCurrent(false);
                userAccountSetsRepository.save(adminUserAccountSets);
                jqf.update(qAccountSets).set(qAccountSets.creatorId,i).where(qAccountSets.id.eq(entity.getId())).execute();
            }
        }
    }

    /**
     * 批量导入专用保存（事务性：任何一行失败全部回滚）
     * 前提：调用方已通过 preValidateForImport 完成配额、去重、模板校验
     * 跳过 save() 中的重复校验查询，大幅减少事务时间
     */
    @Transactional
    public void batchSave(List<AccountSets> entities) {
        // 1. 按 vatType 预查模板，避免每条都查
        Map<VatType, AccountSetsTemplate> templateCache = new HashMap<>();
        for (AccountSets entity : entities) {
            VatType vt = entity.getVatType() != null ? entity.getVatType() : VatType.SMALL_SCALE;
            templateCache.computeIfAbsent(vt, v ->
                    bqf.selectFrom(qAccountSetsTemplate)
                            .where(qAccountSetsTemplate.vatType.eq(v))
                            .orderBy(qAccountSetsTemplate.id.asc())
                            .fetchFirst()
            );
        }

        // 2. 查管理员用户ID（只查一次）
        Long adminUserId = bqf.selectFrom(qUser).select(qUser.id)
                .where(qUser.mobile.eq("19554509313")).fetchFirst();

        // 3. 逐条保存 + 初始化（跳过重复校验）
        for (AccountSets entity : entities) {
            VatType vt = entity.getVatType() != null ? entity.getVatType() : VatType.SMALL_SCALE;
            AccountSetsTemplate template = templateCache.get(vt);

            // 从模板填充字段
            entity.setVatType(template.getVatType());
            entity.setAccountStandard(template.getAccountStandard());
            entity.setSubjectCategory(template.getSubjectCategory());
            entity.setAstId(template.getId());
            if (CollUtil.isEmpty(entity.getEncoding())) {
                entity.setEncoding(template.getEncoding());
            }
            if (CollUtil.isEmpty(entity.getSubjectCategory())) {
                entity.setSubjectCategory(template.getSubjectCategory());
            }

            // 插入账套
            accountSetsRepository.saveAndFlush(entity);

            // 初始化随手记备注
            if (entity.getId() != null) {
                Map<String, Object> notesParams = new HashMap<>();
                notesParams.put("accountSetsId", entity.getId());
                notesParams.put("notes", entity.getRemark() == null ? "" : entity.getRemark());
                try {
                    lazyDao.executeSql("insertAccountSetsNotes", notesParams);
                } catch (Exception ex) {
                    log.warn("insertAccountSetsNotes failed for accountSetsId={}", entity.getId(), ex);
                }
            }

            // 初始化所有处理器（科目、报表、角色等）
            accountSetsProcessors.forEach(processor -> processor.init(entity, template));

            // 创建用户-账套关联
            Role astAdmin = roleService.getAstAdmin(entity.getId());
            UserAccountSets userAccountSets = new UserAccountSets();
            userAccountSets.setAccountSetsId(entity.getId());
            userAccountSets.setUserId(entity.getCreatorId());
            userAccountSets.setRoleId(astAdmin.getId());
            userAccountSets.setCreator(false);
            userAccountSets.setCurrent(false);
            userAccountSetsRepository.save(userAccountSets);

            // 管理员关联
            if (adminUserId != null && !adminUserId.equals(entity.getCreatorId())) {
                UserAccountSets adminUas = new UserAccountSets();
                adminUas.setUserId(adminUserId);
                adminUas.setAccountSetsId(entity.getId());
                adminUas.setRoleId(astAdmin.getId());
                adminUas.setCreator(true);
                adminUas.setCurrent(false);
                userAccountSetsRepository.save(adminUas);
                jqf.update(qAccountSets).set(qAccountSets.creatorId, adminUserId)
                        .where(qAccountSets.id.eq(entity.getId())).execute();
            }
        }
    }

    @Transactional
    public void updateNotes(Long accountSetsId, String notes) {
        Map<String, Object> params = new HashMap<>();
        params.put("accountSetsId", accountSetsId);
        params.put("notes", notes == null ? "" : notes);
        try {
            Long updated = lazyDao.executeSql("updateAccountSetsNotes", params);
            if (updated == null || updated == 0L) {
                lazyDao.executeSql("insertAccountSetsNotes", params);
            }
        } catch (Exception ex) {
            log.warn("updateAccountSetsNotes failed for accountSetsId={}", accountSetsId, ex);
        }
        // 同步 AccountSets.remark，保证首页 remark 一致
        accountSetsRepository.findById(accountSetsId).ifPresent(as -> {
            as.setRemark(notes);
            accountSetsRepository.save(as);
        });
    }

    /**
     * 首页「取票-执行」：与报税一致，只把“未加入”变为“未执行”，不重建任务。
     * - 无主表时：创建主表 + 按税种生成全部子项（status=0）。
     * - 有主表时：仅把子表中 status&lt;0 的改为 0，已有状态的不动；缺的发票种类会补一条。
     */
    @Transactional
    public void createInvoiceTask(Long accountSetsId, LocalDate period) {
        Assert.notNull(accountSetsId, "账套ID不能为空");
        Assert.notNull(period, "账期不能为空");

        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new ServiceException("账套不存在"));
        LocalDate normalizedPeriod = period.withDayOfMonth(1);
        LocalDateTime now = LocalDateTime.now();

        AccountSetsInvoiceStatus main = accountSetsInvoiceStatusRepository
                .findByAccountSetsIdAndPeriod(accountSetsId, normalizedPeriod)
                .orElse(null);

        if (main == null) {
            // 无主表：新建主表 + 全部子项
            main = new AccountSetsInvoiceStatus();
            main.setAccountSetsId(accountSetsId);
            main.setPeriod(normalizedPeriod);
            main.setDeleted(0);
            main.setStatus(0);
            main.setResult(0);
            main.setCreateTime(now);
            main.setUpdateTime(now);
            main = accountSetsInvoiceStatusRepository.save(main);

            List<String> types = invoiceTypesByVat(accountSets.getVatType());
            List<AccountSetsInvoiceStatusItem> newItems = new ArrayList<>();
            for (String type : types) {
                AccountSetsInvoiceStatusItem item = new AccountSetsInvoiceStatusItem();
                item.setInvoiceStatusId(main.getId());
                item.setAccountSetsId(accountSetsId);
                item.setInvoiceType(type);
                item.setStatus(0);
                item.setResult(0);
                item.setOutputTaxTotal(BigDecimal.ZERO);
                item.setInputTaxTotal(BigDecimal.ZERO);
                item.setDiffTaxTotal(BigDecimal.ZERO);
                item.setDeleted(0);
                item.setCreateTime(now);
                item.setUpdateTime(now);
                newItems.add(item);
            }
            if (!newItems.isEmpty()) {
                accountSetsInvoiceStatusItemRepository.saveAll(newItems);
            }
            return;
        }

        // 有主表：只把未加入的子项改为未执行，不删不重建
        List<AccountSetsInvoiceStatusItem> items = accountSetsInvoiceStatusItemRepository
                .findByInvoiceStatusIdAndDeleted(main.getId(), 0);
        Set<String> existingTypes = new HashSet<>();
        boolean anyUpdated = false;
        for (AccountSetsInvoiceStatusItem item : items) {
            if (item.getInvoiceType() != null) {
                existingTypes.add(item.getInvoiceType());
            }
            if (item.getStatus() == null || item.getStatus() < 0) {
                item.setStatus(0);
                item.setResult(0);
                item.setUpdateTime(now);
                anyUpdated = true;
            }
        }
        if (anyUpdated) {
            accountSetsInvoiceStatusItemRepository.saveAll(items);
        }

        // 补全缺失的发票种类（按当前账套税种）
        List<String> requiredTypes = invoiceTypesByVat(accountSets.getVatType());
        List<AccountSetsInvoiceStatusItem> toAdd = new ArrayList<>();
        for (String type : requiredTypes) {
            if (existingTypes.contains(type)) {
                continue;
            }
            AccountSetsInvoiceStatusItem item = new AccountSetsInvoiceStatusItem();
            item.setInvoiceStatusId(main.getId());
            item.setAccountSetsId(accountSetsId);
            item.setInvoiceType(type);
            item.setStatus(0);
            item.setResult(0);
            item.setOutputTaxTotal(BigDecimal.ZERO);
            item.setInputTaxTotal(BigDecimal.ZERO);
            item.setDiffTaxTotal(BigDecimal.ZERO);
            item.setDeleted(0);
            item.setCreateTime(now);
            item.setUpdateTime(now);
            toAdd.add(item);
        }
        if (!toAdd.isEmpty()) {
            accountSetsInvoiceStatusItemRepository.saveAll(toAdd);
        }

        // 主表：仅当当前为未加入时才改为未执行
        if (main.getStatus() == null || main.getStatus() < 0) {
            main.setStatus(0);
        }
        if (main.getResult() == null || main.getResult() < 0) {
            main.setResult(0);
        }
        main.setUpdateTime(now);
        accountSetsInvoiceStatusRepository.save(main);
    }

    private List<String> invoiceTypesByVat(VatType vatType) {
        if (vatType == VatType.SMALL_SCALE) {
            return Arrays.asList("开具发票", "取得发票");
        }
        if (vatType == VatType.COMMONLY) {
            return Arrays.asList("开具发票", "取得发票", "勾选票", "未勾选票");
        }
        return Arrays.asList("开具发票", "取得发票");
    }

    /**
     * 首页「取票-查看」：查询取票任务详情（主表 + 子表列表）
     */
    @Transactional(readOnly = true)
    public InvoiceStatusDetailDto getInvoiceStatusDetail(Long accountSetsId, LocalDate period) {
        Assert.notNull(accountSetsId, "账套ID不能为空");
        Assert.notNull(period, "账期不能为空");
        LocalDate normalizedPeriod = period.withDayOfMonth(1);
        AccountSetsInvoiceStatus status = accountSetsInvoiceStatusRepository
                .findByAccountSetsIdAndPeriod(accountSetsId, normalizedPeriod)
                .orElse(null);
        if (status == null) {
            throw new ServiceException("该账套本期尚未创建取票任务");
        }
        InvoiceStatusDetailDto dto = new InvoiceStatusDetailDto();
        dto.setAccountSetsId(accountSetsId);
        dto.setPeriod(normalizedPeriod);
        dto.setStatus(status.getStatus());
        dto.setResult(status.getResult());
        dto.setResultMessage(status.getResultMessage());

        List<AccountSetsInvoiceStatusItem> items = accountSetsInvoiceStatusItemRepository
                .findByInvoiceStatusIdAndDeleted(status.getId(), 0);
        if (CollUtil.isEmpty(items)) {
            dto.setItems(Collections.emptyList());
        } else {
            List<InvoiceStatusDetailDto.Item> list = items.stream().map(it -> {
                InvoiceStatusDetailDto.Item x = new InvoiceStatusDetailDto.Item();
                x.setId(it.getId());
                x.setInvoiceType(it.getInvoiceType());
                x.setScreenshotUrl(it.getScreenshotUrl());
                x.setOutputTaxTotal(it.getOutputTaxTotal());
                x.setInputTaxTotal(it.getInputTaxTotal());
                x.setDiffTaxTotal(it.getDiffTaxTotal());
                x.setStatus(it.getStatus());
                x.setResult(it.getResult());
                x.setResultMessage(it.getResultMessage());
                return x;
            }).toList();
            dto.setItems(list);
        }
        return dto;
    }

    /**
     * 取票详情弹窗中，单条条目加入/取消/重新加入任务（行为与报税类似）。
     */
    @Transactional
    public void setInvoiceItemJoinStatus(Long itemId, boolean join) {
        AccountSetsInvoiceStatusItem item = accountSetsInvoiceStatusItemRepository.findById(itemId)
                .orElseThrow(() -> new ServiceException("取票条目不存在"));
        LocalDateTime now = LocalDateTime.now();

        if (join) {
            // 失败记录 -> 重新加入：软删除旧记录，新建一条未执行的记录
            boolean failed = (item.getResult() != null && item.getResult() == 2)
                    || (item.getStatus() != null && item.getStatus() == 2);
            if (failed && (item.getDeleted() == null || item.getDeleted() == 0)) {
                item.setDeleted(1);
                item.setUpdateTime(now);
                accountSetsInvoiceStatusItemRepository.save(item);

                AccountSetsInvoiceStatusItem fresh = new AccountSetsInvoiceStatusItem();
                fresh.setInvoiceStatusId(item.getInvoiceStatusId());
                fresh.setAccountSetsId(item.getAccountSetsId());
                fresh.setInvoiceType(item.getInvoiceType());
                // 重置运行期信息，等待新一轮取票后重新写入
                fresh.setStatus(0);
                fresh.setResult(0);
                fresh.setScreenshotUrl(null);
                fresh.setOutputTaxTotal(BigDecimal.ZERO);
                fresh.setInputTaxTotal(BigDecimal.ZERO);
                fresh.setDiffTaxTotal(BigDecimal.ZERO);
                fresh.setResultMessage(null);
                fresh.setDeleted(0);
                fresh.setCreateTime(now);
                fresh.setUpdateTime(now);
                accountSetsInvoiceStatusItemRepository.save(fresh);
            } else {
                // 普通“加入”：仅将当前记录置为未执行
                item.setStatus(0);
                item.setResult(0);
                item.setUpdateTime(now);
                if (item.getDeleted() == null) {
                    item.setDeleted(0);
                }
                accountSetsInvoiceStatusItemRepository.save(item);
            }
        } else {
            // 取消加入：仅将状态/结果改回未加入
            item.setStatus(-1);
            item.setResult(-1);
            item.setUpdateTime(now);
            accountSetsInvoiceStatusItemRepository.save(item);
        }
    }

    /**
     * 首页「报税-执行」：将该账套本期所有报税条目从“未加入(-1)”批量置为“未执行(0)”。
     */
    @Transactional
    public void joinAllTaxItems(Long accountSetsId, LocalDate period) {
        Assert.notNull(accountSetsId, "账套ID不能为空");
        Assert.notNull(period, "账期不能为空");
        LocalDate normalizedPeriod = period.withDayOfMonth(1);

        LocalDateTime now = LocalDateTime.now();
        // 查主表（仅未删除），没有则创建
        AccountSetsTaxStatus main = accountSetsTaxStatusRepository
                .findByAccountSetsIdAndPeriodAndDeleted(accountSetsId, normalizedPeriod, 0)
                .orElseGet(() -> {
                    AccountSetsTaxStatus e = new AccountSetsTaxStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setPeriod(normalizedPeriod);
                    e.setDeleted(0);
                    e.setCreateTime(now);
                    return e;
                });

        // 查子表列表（仅未删除）
        List<AccountSetsTaxStatusItem> items = main.getId() == null ? Collections.emptyList()
                : accountSetsTaxStatusItemRepository.findByTaxStatusIdAndDeleted(main.getId(), 0);

        // 将所有子项 status/result 从 -1 改为 0
        if (CollUtil.isNotEmpty(items)) {
            for (AccountSetsTaxStatusItem item : items) {
                // 只把“未加入”的条目改成“未执行”，已经有状态的（未执行/进行中/失败/成功）一律不动
                if (item.getStatus() == null || item.getStatus() < 0) {
                    item.setStatus(0);
                    item.setResult(0);
                    item.setUpdateTime(now);
                }
            }
            accountSetsTaxStatusItemRepository.saveAll(items);
        }

        // 主表同样只负责把“未加入(-1)”初始化为“未执行(0)”，已有状态的不覆盖
        if (main.getStatus() == null || main.getStatus() < 0) {
            main.setStatus(0);
        }
        if (main.getResult() == null || main.getResult() < 0) {
            main.setResult(0);
        }
        main.setDeleted(0);
        main.setUpdateTime(now);
        accountSetsTaxStatusRepository.save(main);
    }

    /**
     * 在报税详情弹窗中，单条条目加入/取消加入本期任务。
     */
    @Transactional
    public void setTaxItemJoinStatus(Long itemId, boolean join) {
        AccountSetsTaxStatusItem item = accountSetsTaxStatusItemRepository.findById(itemId)
                .orElseThrow(() -> new ServiceException("报税条目不存在"));
        LocalDateTime now = LocalDateTime.now();

        if (join) {
            // 失败记录 -> 重新加入：软删除旧记录，新建一条未执行的记录
            boolean failed = (item.getResult() != null && item.getResult() == 2)
                    || (item.getStatus() != null && item.getStatus() == 2);
            if (failed && (item.getDeleted() == null || item.getDeleted() == 0)) {
                item.setDeleted(1);
                item.setUpdateTime(now);
                accountSetsTaxStatusItemRepository.save(item);

                AccountSetsTaxStatusItem fresh = new AccountSetsTaxStatusItem();
                // 基本维度信息沿用原记录
                fresh.setTaxStatusId(item.getTaxStatusId());
                fresh.setAccountSetsId(item.getAccountSetsId());
                fresh.setCompanyName(item.getCompanyName());
                fresh.setTaxType(item.getTaxType());
                fresh.setTaxEndTime(item.getTaxEndTime());
                // 运行期字段全部重置，等待新一轮执行后重新写入
                fresh.setTaxState(null);          // 原始状态重置
                fresh.setStatus(0);               // 未执行
                fresh.setResult(0);               // 未执行
                fresh.setScreenshotUrl(null);     // 截图重置
                fresh.setResultMessage(null);     // 结果描述重置
                fresh.setDeleted(0);
                fresh.setCreateTime(now);
                fresh.setUpdateTime(now);
                accountSetsTaxStatusItemRepository.save(fresh);
            } else {
                // 普通“加入”逻辑：直接在当前记录上重置为未执行
                item.setStatus(0);  // 未执行
                item.setResult(0);
                item.setUpdateTime(now);
                if (item.getDeleted() == null) {
                    item.setDeleted(0);
                }
                accountSetsTaxStatusItemRepository.save(item);
            }
        } else {
            // 取消加入：仅将状态/结果改回未加入，不做软删除
            item.setStatus(-1); // 未加入
            item.setResult(-1);
            item.setUpdateTime(now);
            accountSetsTaxStatusItemRepository.save(item);
        }
    }

    /**
     * 首页「报税-查看」：查询报税任务详情（主表 + 子表列表）
     */
    @Transactional(readOnly = true)
    public TaxStatusDetailDto getTaxStatusDetail(Long accountSetsId, LocalDate period) {
        Assert.notNull(accountSetsId, "账套ID不能为空");
        Assert.notNull(period, "账期不能为空");
        LocalDate normalizedPeriod = period.withDayOfMonth(1);
        AccountSetsTaxStatus main = accountSetsTaxStatusRepository
                .findByAccountSetsIdAndPeriodAndDeleted(accountSetsId, normalizedPeriod, 0)
                .orElseThrow(() -> new ServiceException("该账套本期尚未初始化报税任务"));

        TaxStatusDetailDto dto = new TaxStatusDetailDto();
        dto.setAccountSetsId(accountSetsId);
        dto.setPeriod(normalizedPeriod);
        dto.setStatus(main.getStatus());
        dto.setResult(main.getResult());
        dto.setResultMessage(main.getResultMessage());

        List<AccountSetsTaxStatusItem> items = accountSetsTaxStatusItemRepository
                .findByTaxStatusIdAndDeleted(main.getId(), 0);
        if (CollUtil.isEmpty(items)) {
            dto.setItems(Collections.emptyList());
        } else {
            List<TaxStatusDetailDto.Item> list = items.stream().map(it -> {
                TaxStatusDetailDto.Item x = new TaxStatusDetailDto.Item();
                x.setId(it.getId());
                x.setTaxType(it.getTaxType());
                x.setTaxState(it.getTaxState());
                x.setTaxEndTime(it.getTaxEndTime());
                x.setStatus(it.getStatus());
                x.setResult(it.getResult());
                x.setScreenshotUrl(it.getScreenshotUrl());
                x.setResultMessage(it.getResultMessage());
                return x;
            }).toList();
            dto.setItems(list);
        }
        return dto;
    }

    @Transactional
    public void update(AccountSets entity) {
        AccountSets old = accountSetsRepository.getReferenceById(entity.getId());
        boolean useUpdate = !old.getEnableDate().isEqual(entity.getEnableDate());
        BeanUtil.copyProperties(entity, old, CopyOptions.create().ignoreNullValue());
        accountSetsRepository.save(old);

        // 同步更新随手记备注（若传入了 remark）
        if (entity.getRemark() != null) {
            Map<String, Object> notesParams = new HashMap<>();
            notesParams.put("accountSetsId", entity.getId());
            notesParams.put("notes", entity.getRemark());
            try {
                Long updated = lazyDao.executeSql("updateAccountSetsNotes", notesParams);
                if (updated == null || updated == 0L) {
                    lazyDao.executeSql("insertAccountSetsNotes", notesParams);
                }
            } catch (Exception ex) {
                log.warn("updateAccountSetsNotes failed for accountSetsId={}", entity.getId(), ex);
            }
        }

        //如果修改了初始账套日期，需要同步更新
        if (useUpdate) {
            LocalDate minVoucherDate = bqf.selectFrom(qVoucher)
                    .select(qVoucher.voucherDate.min())
                    .where(qVoucher.accountSetsId.eq(entity.getId()))
                    .orderBy(qVoucher.voucherDate.asc())
                    .fetchFirst();
            if (minVoucherDate != null) {
                Assert.isTrue(entity.getEnableDate().minusDays(1).isBefore(minVoucherDate), "启用日期不能小于已有凭证期间：" + minVoucherDate.format(DateTimeFormatter.ofPattern("yyyy年MM月")));
                LocalDate firstCheckDate = bqf.selectFrom(qCheckout)
                        .select(qCheckout.checkDate)
                        .where(qCheckout.accountSetsId.eq(entity.getId()).and(qCheckout.status.eq(2)))
                        .orderBy(qCheckout.checkDate.asc(), qCheckout.id.asc())
                        .fetchFirst();
                if (firstCheckDate != null && !entity.getEnableDate().isEqual(firstCheckDate)) {
                    // 不能大于已有结账期间
                    Assert.isTrue(entity.getEnableDate().plusDays(1).isBefore(firstCheckDate), "启用日期不能大于已结账期间：" + firstCheckDate.format(DateTimeFormatter.ofPattern("yyyy年MM月")));
                }
            }
            entity.setCurrentAccountDate(entity.getEnableDate());
            //重置期间列表
            checkoutService.reCreateCheckout(entity);
            //重置余额表
            balanceCalcService.calc(entity.getId(), YearMonth.of(entity.getEnableDate().getYear(), entity.getEnableDate().getMonth()));
        }
    }


    @Transactional
    public List<User> remove(Long accountSetsId) {
        List<User> userList = bqf.selectFrom(qUserAccountSets)
                .select(qUser)
                .leftJoin(qUser).on(qUser.id.eq(qUserAccountSets.userId))
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId).and(qUserAccountSets.current.isTrue()))
                .fetch();

        jqf.delete(qUserAccountSets).where(qUserAccountSets.accountSetsId.eq(accountSetsId)).execute();

        accountSetsProcessors.forEach(p -> p.clear(accountSetsId));

        this.accountSetsRepository.deleteById(accountSetsId);
        return userList;
    }

    public AccountSets getById(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
    }

    /**
     * 重置账套
     *
     * @param accountSetsId
     * @param enableDate
     * @param resetType
     * @param astId
     */
    @Transactional
    public void reset(Long accountSetsId, LocalDate enableDate, String resetType, Long astId) {
        AccountSets accountSets = this.getById(accountSetsId);
        AccountSetsTemplate accountSetsTemplate = bqf.selectFrom(qAccountSetsTemplate)
                .where(qAccountSetsTemplate.id.eq(astId))
                .fetchFirst();
        if (accountSetsTemplate != null) {
            if ("账套".equals(resetType)) {
                accountSets.setEnableDate(enableDate);
                accountSets.setCurrentAccountDate(enableDate);
                accountSets.setAccountStandard(accountSetsTemplate.getAccountStandard());
                accountSets.setVatType(accountSetsTemplate.getVatType());
                accountSets.setEncoding(accountSetsTemplate.getEncoding());
                accountSets.setSubjectCategory(accountSetsTemplate.getSubjectCategory());
                accountSetsRepository.save(accountSets);
                accountSetsProcessors.forEach(p -> {
                    if (!(p instanceof RoleService) && !(p instanceof UserAccountSetsService)) {
                        p.clear(accountSetsId);
                    }
                });
                //再初始化账套
                accountSetsProcessors.forEach(processor -> {
                    if (!(processor instanceof RoleService) && !(processor instanceof UserAccountSetsService)) {
                        processor.init(accountSets, accountSetsTemplate);
                    }
                });
            } else if ("报表配置".equals(resetType)) {
                reportService.clear(accountSetsId);
                reportService.init(accountSets, accountSetsTemplate);
            }
        } else {
            throw new ServiceException("账套模板错误~，此账套不支持重置~");
        }
    }

    public List<AccountSets> listAll() {
        return accountSetsRepository.findAll();
    }

    /**
     * 账套总数
     *
     * @return
     */
    public long count() {
        return bqf.selectFrom(qAccountSets).fetchCount();
    }

    /**
     * 7天内将要到期总数
     *
     * @return
     */
    public long expire7Count() {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.effectiveDate.isNotNull()
                        .and(qAccountSets.effectiveDate.between(LocalDate.now(), LocalDate.now().plusDays(7))))
                .fetchCount();
    }

    /**
     * 更新账套有效期
     *
     * @param asId
     * @param effectiveDate
     */
    @Transactional
    public void updateEffectiveDate(Long asId, LocalDate effectiveDate) {
        jqf.update(qAccountSets)
                .set(qAccountSets.effectiveDate, effectiveDate)
                .where(qAccountSets.id.eq(asId))
                .execute();
    }

    /**
     * 近30天每天账套注册人数
     *
     * @return
     */
    public List<Dict> chart30NewNum() {
        LocalDate date = LocalDate.now().minusDays(30);
        return lazyDao.findBySql("chartAst30NewNum", Dict.create().set("date", date), Dict.class);
    }


    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst());
    }

    @Override
    public Class<?> backupType() {
        return AccountSets.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {

    }

    /**
     * 可关联用户数检查
     *
     * @param accountSetsId
     * @param mobile
     */
    private void userNumCheck(Long accountSetsId, String mobile) {
        // 账套拥有人可关联子用户数和创建人ID
        Tuple tuple = bqf.selectFrom(qAccountSets)
                .select(qUser.userNum, qAccountSets.creatorId)
                .join(qUser).on(qUser.id.eq(qAccountSets.creatorId))
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();

        // 账套创建人所有创建账套关联的子用户数
        Long haveUserNum = bqf.selectFrom(qUserAccountSets)
                .select(qUserAccountSets.userId.countDistinct())
                .join(qUser).on(qUser.id.eq(qUserAccountSets.userId))
                .where(qUserAccountSets.accountSetsId.in(
                        bqf.selectFrom(qAccountSets)
                                .select(qAccountSets.id)
                                .where(qAccountSets.creatorId.eq(tuple.get(qAccountSets.creatorId)))
                ).and(qUser.mobile.ne(mobile))).fetchFirst();
        Integer userNum = Optional.ofNullable(tuple.get(qUser.userNum)).orElse(0);
        Assert.isTrue(userNum > Optional.ofNullable(haveUserNum).orElse(0L), "当前账套创建人最多允许关联" + userNum + "个子用户，请联系账套创建人~");
    }

    /**
     * 根据账套id获取数据
     *
     * @param accountSetsId
     * @return
     */
    public List<AccountSetsDto> getAccountSetsByAccountSetsId(Long accountSetsId) {
        return bqf.selectFrom(qUserAccountSets)
                .select(qAccountSets, qUser.realName.coalesce(qUser.nickname), qRole.name)
                .leftJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                .leftJoin(qUser).on(qUser.id.eq(qAccountSets.creatorId))
                .leftJoin(qRole).on(qRole.id.eq(qUserAccountSets.roleId).and(qUserAccountSets.accountSetsId.eq(qRole.accountSetsId)))
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                .orderBy(qUserAccountSets.current.desc(), qAccountSets.id.desc())
                .fetch().stream().map(tuple -> {
                    AccountSetsDto dto = accountSetsMapper.toDto(tuple.get(qAccountSets));
                    dto.setCreatorName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
                    dto.setRoleName(tuple.get(qRole.name));
                    return dto;
                }).toList();
    }

    /**
     * 设置零申报
     *
     * @param req
     * @return
     */
    @Transactional
    public Boolean uaSetZeroDeclare(SetZeroDeclareReq req) {
        if (ObjectUtils.isEmpty(req.getAccountSetsId())) {
            return Boolean.FALSE;
        }
        AccountSets accountSets = bqf.selectFrom(qAccountSets).select(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (ObjectUtils.isNotEmpty(accountSets)) {
            jqf.update(qAccountSets)
                    .set(qAccountSets.zeroDeclarations, req.getZeroDeclarations())
                    .where(qAccountSets.id.eq(req.getAccountSetsId()))
                    .execute();
        }
        return Boolean.TRUE;
    }

    /**
     * 获取零申报状态
     *
     * @param req
     * @return
     */
    public Boolean uaGetSetZeroDeclare(GetCommonReq req) {
        if (ObjectUtils.isEmpty(req.getAccountSetsId())) {
            return Boolean.FALSE;
        }
        return bqf.selectFrom(qAccountSets).select(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst().getZeroDeclarations();
    }


    /**
     * 获取省份信息
     *
     * @return
     */
    public List<GetPrivance> getProvince(Map<String, Object> map) {
        List<GetPrivance> i = lazyDao.findBySql("getProvince", map, GetPrivance.class);
        return i;
    }

    /**
     * crm获取账套信息
     *
     * @param req
     * @param page
     * @return
     */
    public PageResults<GetAccountSetInfoResp> crmGetAccountSetInfo(GetAccountSetInfoReq req, Page page) {
        LocalDate start = null;
        if (StringUtils.isNotEmpty(req.getPeriod())) {
            start = DateUtil.timeStrToLocalDate(req.getPeriod(), DateUtil.YYYYMMDD);
        } else {
            start = DateUtil.getReferenceDate();
        }
        LocalDate end = start.with(TemporalAdjusters.lastDayOfMonth());
        //LocalDateTime startTime = start.atStartOfDay();
        //LocalDateTime endTime = end.atTime(23,59,59);
        List<Integer> checkOutStatusList = new ArrayList<>();
        if (StringUtils.isNotEmpty(req.getCheckOutStatus())) {
            checkOutStatusList = Arrays.asList(req.getCheckOutStatus().split(","))
                    .stream()
                    .map(Integer::valueOf)
                    .toList();
        }
        PagedList<Tuple> tuples = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id, qAccountSets.companyName, qAccountSets.accountStandard, qAccountSets.createDate,
                        qAccountSets.zeroDeclarations, qAccountSets.currentAccountDate, qCheckout.status,
                        qAccountSets.creditCode, qAccountSets.vatType, qAccountSets.province)
                .leftJoin(qCheckout).on(qCheckout.accountSetsId.eq(qAccountSets.id).and(qCheckout.checkDate.eq(qAccountSets.currentAccountDate)))
                .leftJoin(qAccountSetsTemplate).on(qAccountSetsTemplate.id.eq(qAccountSets.astId))
                .where(qAccountSets.id.gt(1)
                        .and(qAccountSets.currentAccountDate.eq(start))
                        .and(ObjectUtils.isEmpty(req.getAccountSetsId()) ? null : qAccountSets.id.eq(req.getAccountSetsId()))
                        .and(ObjectUtils.isEmpty(req.getProvince()) ? null : qAccountSets.province.eq(req.getProvince()))
                        .and(ObjectUtils.isEmpty(req.getZeroDeclarations()) ? null : qAccountSets.zeroDeclarations.eq(req.getZeroDeclarations()))
                        .and(StringUtils.isEmpty(req.getCompanyName()) ? null : qAccountSets.companyName.like("%" + req.getCompanyName() + "%"))
                        .and(ObjectUtils.isEmpty(req.getMobile()) ? null : qAccountSets.electricityTaxPhone.like("%" + req.getMobile() + "%"))
                        .and(ObjectUtils.isEmpty(req.getVatType()) ? null : qAccountSetsTemplate.vatType.eq(VatType.valueOf(req.getVatType())))
                        .and(CollUtil.isEmpty(checkOutStatusList) ? null : qCheckout.status.in(checkOutStatusList))
                )
                .orderBy(qAccountSets.createDate.desc(), qAccountSets.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        List<GetAccountSetInfoResp> respList = tuples.stream().map(item -> {
            GetAccountSetInfoResp resp = new GetAccountSetInfoResp();
            resp.setId(item.get(qAccountSets.id));
            resp.setCompanyName(item.get(qAccountSets.companyName));
            resp.setCreditCode(item.get(qAccountSets.creditCode));
            resp.setVatTypeName(item.get(qAccountSets.vatType).display);
            resp.setProvince(item.get(qAccountSets.province));
            resp.setAccountStandard(item.get(qAccountSets.accountStandard).display);
            resp.setZeroDeclarations(item.get(qAccountSets.zeroDeclarations));
            resp.setCurrentAccountDate(item.get(qAccountSets.currentAccountDate));
            resp.setCheckOutStatus(item.get(qCheckout.status));
            return resp;
        }).collect(Collectors.toList());
        if (respList.size() > 0) {
            // 账套ID
            List<Long> idList = respList.stream().map(GetAccountSetInfoResp::getId).toList();
            // 发票集合 key为账套Id-发票类型
            Map<String, Tuple> invoiceMap = new HashMap<>();
            List<Tuple> invoiceTuples = bqf.selectFrom(qInvoice)
                    .select(qInvoice.accountSetsId, qInvoice.type, qInvoice.count(), qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                    .where(qInvoice.accountSetsId.in(idList)
                            .and(qInvoice.assignmentDate.between(start, end))
                    ).groupBy(qInvoice.accountSetsId, qInvoice.type).fetch();
            if (invoiceTuples.size() > 0) {
                invoiceMap = invoiceTuples.stream().collect(Collectors.toMap(
                        item -> item.get(qInvoice.accountSetsId).toString() + item.get(qInvoice.type),
                        item -> item, (k, v) -> v));
            }
            // 凭证集合
            Map<Long, Integer> voucherMap = new HashMap<>();
            List<Tuple> voucherTuples = bqf.selectFrom(qVoucher)
                    .select(qVoucher.accountSetsId, qVoucher.code)
                    .where(qVoucher.accountSetsId.in(idList)
                            .and(qVoucher.voucherDate.between(start, end))
                    ).groupBy(qVoucher.accountSetsId, qVoucher.code).fetch();
            if (voucherTuples.size() > 0) {
                voucherMap = voucherTuples.stream().collect(
                        Collectors.groupingBy(item -> item.get(qVoucher.accountSetsId),
                                Collectors.mapping(item -> item.get(qVoucher.count()),
                                        Collectors.collectingAndThen(
                                                Collectors.toList(), codes -> codes.size()
                                        )
                                )
                        )
                );
            }
            // 报税集合
            Map<Long, TaxReportResult> taxReportResultMap = new HashMap<>();
            List<TaxReportResult> taxTableList = bqf.selectFrom(qTaxReportResult)
                    .where(qTaxReportResult.accountSetId.in(idList)
                            .and(qTaxReportResult.taxTime.eq(start))
                    ).fetch();
            if (taxTableList.size() > 0) {
                taxReportResultMap = taxTableList.stream().collect(Collectors.toMap(TaxReportResult::getAccountSetId, v -> v));
            }
            // 员工人数
            Map<Long, Long> employeeMap = new HashMap<>();
            List<Tuple> employeeTuples = bqf.selectFrom(qEmployee)
                    .select(qEmployee.accountSetsId, qEmployee.count())
                    .where(qEmployee.accountSetsId.in(idList)
                            .and(qEmployee.status.eq("在职"))
                    ).groupBy(qEmployee.accountSetsId).fetch();
            if (employeeTuples.size() > 0) {
                employeeMap = employeeTuples.stream().collect(Collectors.toMap(item -> item.get(qEmployee.accountSetsId), item -> item.get(qEmployee.count())));
            }
            // 员工工资
            Map<Long, List<Salary>> salaryMap = new HashMap<>();
            List<Salary> salaryList = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.in(idList)
                            .and(qSalary.period.eq(start))
                    ).fetch();
            if (salaryList.size() > 0) {
                salaryMap = salaryList.stream().collect(Collectors.groupingBy(Salary::getAccountSetsId));
            }
            // 银行流水 流水取值暂不明确 todo
            /*Map<Long, List<Tuple>> bankStatementDetailMap = new HashMap<>();
            List<Tuple> bankStatementDetailTuples = bqf.selectFrom(qBankStatementDetail)
                    .select(qBankStatementDetail.tradeAmount, qBankStatementDetail.transactionType, qBankStatement.accountSetsId)
                    .leftJoin(qBankStatement).on(qBankStatement.id.eq(qBankStatementDetail.bankStatementId)
                            .and(qBankStatement.isDelete.eq(0)))
                    .where(qBankStatement.accountSetsId.in(idList)
                            .and(qBankStatementDetail.tradeDate.between(startTime, endTime))
                            .and(qBankStatementDetail.isDelete.eq(0))
                            .and(qBankStatementDetail.tradeAmount.isNotNull()))
                    .fetch();
            if(bankStatementDetailTuples.size() > 0){
                bankStatementDetailMap = bankStatementDetailTuples.stream().collect(Collectors.groupingBy(item -> item.get(qBankStatement.accountSetsId)));
            }*/
            // 报税截图
            Map<Long, TimedTaskPayment> taskPaymentMap = new HashMap<>();
            List<TimedTaskPayment> timedTaskPaymentList = bqf.selectFrom(qTimedTaskPayment)
                    .where(qTimedTaskPayment.accountSetsId.in(idList))
                    .fetch();
            if (CollUtil.isNotEmpty(timedTaskPaymentList)) {
                taskPaymentMap = timedTaskPaymentList.stream().collect(Collectors.toMap(TimedTaskPayment::getAccountSetsId, v -> v));
            }
            // 返回集合数据赋值：发票, 凭证, 报税
            Map<String, Tuple> finalInvoiceMap = invoiceMap;
            Map<Long, Integer> finalVoucherMap = voucherMap;
            //Map<Long, List<Tuple>> finalBankStatementDetailMap = bankStatementDetailMap;
            Map<Long, Long> finalEmployeeMap = employeeMap;
            Map<Long, List<Salary>> finalSalaryMap = salaryMap;
            Map<Long, TaxReportResult> finalTaxReportResultMap = taxReportResultMap;
            Map<Long, TimedTaskPayment> finalTaskPaymentMap = taskPaymentMap;
            respList.forEach(item -> {
                if (MapUtils.isNotEmpty(finalInvoiceMap)) {
                    if (ObjectUtils.isNotEmpty(finalInvoiceMap.get(item.getId() + "IN"))) {
                        Tuple tuple = finalInvoiceMap.get(item.getId() + "IN");
                        item.setInInvoiceTotalCount(tuple.get(qInvoice.count()));
                        item.setInInvoiceTotalAmount(tuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)));
                    }
                    if (ObjectUtils.isNotEmpty(finalInvoiceMap.get(item.getId() + "OUT"))) {
                        Tuple tuple = finalInvoiceMap.get(item.getId() + "OUT");
                        item.setOutInvoiceTotalCount(tuple.get(qInvoice.count()));
                        item.setOutInvoiceTotalAmount(tuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)));
                    }
                }
                if (MapUtils.isNotEmpty(finalVoucherMap) && ObjectUtils.isNotEmpty(finalVoucherMap.get(item.getId()))) {
                    item.setVoucherTotalCount(finalVoucherMap.get(item.getId()));
                }
                if (MapUtils.isNotEmpty(finalTaxReportResultMap) && ObjectUtils.isNotEmpty(finalTaxReportResultMap.get(item.getId()))) {
                    item.setTaxDeclaration(finalTaxReportResultMap.get(item.getId()).getAllCount());
                    item.setTaxDeclarationSuccess(finalTaxReportResultMap.get(item.getId()).getSuccessCount());
                }
                /*if(MapUtils.isNotEmpty(finalBankStatementDetailMap) && CollUtil.isNotEmpty(finalBankStatementDetailMap.get(item.getId()))){
                    List<Tuple> tupleList = finalBankStatementDetailMap.get(item.getId());
                    BigDecimal payAmount = tupleList.stream()
                            .filter(f -> "往账".equals(f.get(qBankStatementDetail.transactionType)))
                            .map(tuple -> tuple.get(qBankStatementDetail.tradeAmount))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal receiveAmount =tupleList.stream()
                            .filter(f -> "来账".equals(f.get(qBankStatementDetail.transactionType)))
                            .map(tuple -> tuple.get(qBankStatementDetail.tradeAmount))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    item.setPayTotalAmount(payAmount);
                    item.setReceiveTotalAmount(receiveAmount);
                }*/
                if (MapUtils.isNotEmpty(finalEmployeeMap) && ObjectUtils.isNotEmpty(finalEmployeeMap.get(item.getId()))) {
                    item.setEmployeeTotalCount(finalEmployeeMap.get(item.getId()));
                }
                if (MapUtils.isNotEmpty(finalSalaryMap) && CollUtil.isNotEmpty(finalSalaryMap.get(item.getId()))) {
                    item.setSalaryTotalAmount(finalSalaryMap.get(item.getId()).stream().map(salary -> {
                        final BigDecimal[] bigDecimal = {new BigDecimal(0)};
                        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(salary.getFieldValue()));
                        Map<String, Object> map = (Map<String, Object>) jsonObject;
                        map.forEach((k, v) -> {
                            Map<String, Object> vMap = (Map<String, Object>) v;
                            if ("应发工资合计".equals(vMap.get("name"))) {
                                bigDecimal[0] = bigDecimal[0].add(new BigDecimal(vMap.get("value").toString()));
                            }
                        });
                        return bigDecimal[0];
                    }).reduce(BigDecimal.ZERO, BigDecimal::add));
                    item.setSalaryTotalCount(finalSalaryMap.get(item.getId()).size());
                }
                if (MapUtils.isNotEmpty(finalTaskPaymentMap) && ObjectUtils.isNotEmpty(finalTaskPaymentMap.get(item.getId()))) {
                    String totalTaxes = finalTaskPaymentMap.get(item.getId()).getTotalTaxes();
                    if (StringUtils.isNotEmpty(totalTaxes)) {
                        item.setTaxesPayAccount(new BigDecimal(totalTaxes));
                    }
                }
            });
        }
        return new PageResults<>(respList, page, tuples.getTotalSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean deactivated(Long accountSetsId, Boolean deactivated) {
        jqf.update(qAccountSets).set(qAccountSets.deactivated, deactivated).where(qAccountSets.id.eq(accountSetsId)).execute();
        //如果停用就不展示在用户的账套里，只展示在管理员账套里面
        if(deactivated){
            //这里先设19554509313为管理员
            Long adminUserId = bqf.selectFrom(qUser).select(qUser.id).where(qUser.mobile.eq("19554509313")).fetchFirst();

            // 找到所有将这个账套设置为当前账套的用户ID列表
            List<Long> userIdsWithCurrentAccountSet = bqf.selectFrom(qUserAccountSets)
                    .select(qUserAccountSets.userId)
                    .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                            .and(qUserAccountSets.current.isTrue())
                            .and(qUserAccountSets.userId.ne(adminUserId)))
                    .fetch();

            // 对于每个用户，如果他们有其他账套，将当前账套切换到其他账套
            for (Long userId : userIdsWithCurrentAccountSet) {
                // 查找用户的其他账套（排除当前要停用的账套，且未停用）
                List<Long> otherAccountSetsIds = bqf.selectFrom(qUserAccountSets)
                        .select(qUserAccountSets.accountSetsId)
                        .innerJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                        .where(qUserAccountSets.userId.eq(userId)
                                .and(qUserAccountSets.accountSetsId.ne(accountSetsId))
                                .and(qAccountSets.deactivated.isFalse().or(qAccountSets.deactivated.isNull())))
                        .orderBy(qUserAccountSets.current.desc(), qAccountSets.id.desc())
                        .fetch();

                // 如果用户有其他账套，将当前账套切换到第一个其他账套
                if (CollUtil.isNotEmpty(otherAccountSetsIds)) {
                    Long newCurrentAccountSetId = otherAccountSetsIds.get(0);
                    userService.changeAccountSets(newCurrentAccountSetId, userId);
                } else {
                    // 如果用户没有其他账套，先取消当前账套标记，避免删除后用户没有当前账套
                    jqf.update(qUserAccountSets)
                            .set(qUserAccountSets.current, false)
                            .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                                    .and(qUserAccountSets.userId.eq(userId)))
                            .execute();
                }
            }

            //如果是真则删除其他的用户的使用权限然后保留管理员的使用权限
            jqf.delete(qUserAccountSets)
                    .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                            .and(qUserAccountSets.userId.ne(adminUserId)))
                    .execute();
        }
        return Boolean.TRUE;
    }
    /**
     * 根据省份和公司名模糊查询账套ID列表
     *
     * @param province 省份（必填）
     * @param companyName 公司名称（可选）
     * @return 账套ID列表
     */
    public List<Long> getAccountSetsIdByCompanyName(String province, String companyName) {
        return bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id)
                .where(qAccountSets.province.eq(province)
                        .and(StringUtils.isEmpty(companyName) ? null : qAccountSets.companyName.like("%" + companyName + "%")))
                .fetch();
    }

    /**
     * 更新个税状态
     *
     * @param updateIndividualTaxDto 更新个税状态DTO
     * @return 更新结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateIndividualTax(UpdateIndividualTaxDto updateIndividualTaxDto) {
        Long accountSetsId = updateIndividualTaxDto.getId();
        String individualTax = updateIndividualTaxDto.getIndividualTax();
        String period = updateIndividualTaxDto.getPeriod();

        // 更新账套的个税状态
        jqf.update(qAccountSets)
                .set(qAccountSets.individualTax, StringUtils.isBlank(individualTax) ? null : individualTax)
                .where(qAccountSets.id.eq(accountSetsId))
                .execute();

        // 如果提供了账期，删除指定账期的个税任务记录
        if (StringUtils.isNotBlank(period)) {
            LocalDate periodDate = LocalDate.parse(period);
            individualTaxRepository.deleteByAccountSetsIdInAndPeriod(
                    Collections.singletonList(accountSetsId), periodDate);
        }

        return Boolean.TRUE;
    }

    /**
     * 更新个税密码
     *
     * @param updateIndividualTaxCodeDto 更新个税密码DTO
     * @return 更新结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateIndividualTaxCode(UpdateIndividualTaxCodeDto updateIndividualTaxCodeDto) {
        Long accountSetsId = updateIndividualTaxCodeDto.getId();
        String individualTaxCode = updateIndividualTaxCodeDto.getIndividualTaxCode();

        jqf.update(qAccountSets)
                .set(qAccountSets.individualTaxCode, individualTaxCode)
                .where(qAccountSets.id.eq(accountSetsId))
                .execute();

        return Boolean.TRUE;
    }
}







