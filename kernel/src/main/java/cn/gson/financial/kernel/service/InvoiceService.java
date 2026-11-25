package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.common.InvoiceVoucherUtils;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.InvoiceItemsRepository;
import cn.gson.financial.kernel.model.repo.InvoiceRepository;
import cn.gson.financial.kernel.model.ua.resp.GetInvoiceSumInfoResp;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.util.function.Tuple2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Invoice)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-19 21:07:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceService extends AbsService implements AccountSetsProcessor, BackupProcessor, VoucherProcessor {
    private static final QInvoice qInvoice = QInvoice.invoice;

    private final QVoucher qVoucher = QVoucher.voucher;

    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;

    private final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;

    private final InvoiceRepository invoiceRepository;

    private final InvoiceItemsRepository itemsRepository;

    private final AllTaskService allTaskService;
    @Lazy
    @Resource
    private VoucherService voucherService;

    private final SubjectService subjectService;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QSubject qSubject = QSubject.subject;
    private final BalanceCalcService balanceCalcService;

    @Transactional
    public Invoice save(InvoiceDto invoiceDto) {
        Invoice invoice;
        if (invoiceDto.getId() != null) {
            Invoice original = invoiceRepository.getReferenceById(new PrimaryKey(invoiceDto.getId(), invoiceDto.getAccountSetsId()));
            Assert.isNull(original.getVoucherId(), "已生成凭证不允许变更~");
            // 发票号变更了
            if (!original.getInvoiceNumber().equals(invoiceDto.getInvoiceNumber())) {
                Assert.isTrue(bqf.selectFrom(qInvoice)
                                      .where(qInvoice.accountSetsId.eq(invoiceDto.getAccountSetsId())
                                              .and(qInvoice.id.ne(invoiceDto.getId()))
                                              .and(qInvoice.invoiceNumber.eq(invoiceDto.getInvoiceNumber())))
                                      .fetchCount() == 0, invoiceDto.getInvoiceNumber() + " 发票号已存在，请勿重复录入~");
            }

            BeanUtil.copyProperties(invoiceDto, original, CopyOptions.create().ignoreNullValue());
            invoice = invoiceRepository.save(original);
            jqf.delete(qInvoiceItems)
                    .where(qInvoiceItems.invoiceId.eq(invoice.getId()))
                    .execute();
        } else {
            if (StrUtil.isNotEmpty(invoiceDto.getInvoiceNumber())) {
                Assert.isTrue(bqf.selectFrom(qInvoice)
                                      .where(qInvoice.accountSetsId.eq(invoiceDto.getAccountSetsId())
                                              .and(qInvoice.invoiceNumber.eq(invoiceDto.getInvoiceNumber())))
                                      .fetchCount() == 0, invoiceDto.getInvoiceNumber() + " 发票号已存在，请勿重复录入~");
            }
            Invoice entity = BeanUtil.toBean(invoiceDto, Invoice.class);
            entity.setShowStatus("普票");
            entity.setAssignmentDate(entity.getTradeDate());
            invoice = invoiceRepository.save(entity);
        }

        if (invoice.getHaveDetail() && CollUtil.isNotEmpty(invoiceDto.getItems())) {
            itemsRepository.saveAll(invoiceDto.getItems().stream().collect(ArrayList::new, (list, tuple) -> {
                InvoiceItems items = BeanUtil.toBean(tuple, InvoiceItems.class);
                items.setAccountSetsId(invoiceDto.getAccountSetsId());
                items.setInvoiceId(invoice.getId());
                items.setId(null);
                list.add(items);
            }, List::addAll));
        }

        return invoice;
    }

    public PageResults<InvoiceDto> list(InvoiceType type, Long accountSetId, Page page, InvoiceQuery query) {
        if (CollUtil.isNotEmpty(query.getGoodsName())) {
            // 商品过滤
            query.builder.and(qInvoice.id.in(bqf.selectFrom(qInvoiceItems)
                    .select(qInvoiceItems.invoiceId)
                    .where(qInvoiceItems.accountSetsId.eq(accountSetId).and(qInvoiceItems.goodsName.in(query.getGoodsName())))));
        }
        BlazeJPAQuery<Tuple> jpaQuery = bqf.selectFrom(qInvoice)
                .select(qInvoice, qInvoiceBusinessType.title, qVoucher.word, qVoucher.code)
                .leftJoin(qInvoiceBusinessType)
                .on(qInvoiceBusinessType.id.eq(qInvoice.invoiceBusinessTypeId).and(qInvoiceBusinessType.accountSetsId.eq(accountSetId)))
                .leftJoin(qVoucher).on(qVoucher.id.eq(qInvoice.voucherId).and(qInvoiceBusinessType.accountSetsId.eq(accountSetId)))
                .where(qInvoice.accountSetsId.eq(accountSetId).and(qInvoice.type.eq(type))
                        .and(CollUtil.isEmpty(query.showStatus) ? null : qInvoice.showStatus.in(query.showStatus))
                        .and(query.builder)
                )
                .orderBy(qInvoice.assignmentDate.desc(), qInvoice.id.desc());
        if (query.download) {
            List<Tuple> fetch = jpaQuery.fetch();
            return new PageResults<>(toDtoList(fetch, accountSetId), page, fetch.size());
        } else {
            PagedList<Tuple> pagedList = jpaQuery
                    .fetchPage(page.getOffset(), page.getOffsetEnd());
            return new PageResults<>(toDtoList(pagedList, accountSetId), page, pagedList.getTotalSize());
        }
    }

    /**
     * 汇总
     * @param accountSetId
     * @param query
     * @return
     */
    public InvoiceDto summary(InvoiceType type, Long accountSetId, InvoiceQuery query) {
        if (CollUtil.isNotEmpty(query.getGoodsName())) {
            // 商品过滤
            query.builder.and(qInvoice.id.in(bqf.selectFrom(qInvoiceItems)
                    .select(qInvoiceItems.invoiceId)
                    .where(qInvoiceItems.accountSetsId.eq(accountSetId).and(qInvoiceItems.goodsName.in(query.getGoodsName())))));
        }

        Tuple tuple = bqf.selectFrom(qInvoice)
                .select(qInvoice.amount.sum(), qInvoice.taxAmount.sum(), qInvoice.taxAmountExcluded.sum())
                .leftJoin(qInvoiceBusinessType)
                .on(qInvoiceBusinessType.id.eq(qInvoice.invoiceBusinessTypeId).and(qInvoiceBusinessType.accountSetsId.eq(accountSetId)))
                .leftJoin(qVoucher).on(qVoucher.id.eq(qInvoice.voucherId).and(qInvoiceBusinessType.accountSetsId.eq(accountSetId)))
                .where(qInvoice.accountSetsId.eq(accountSetId).and(qInvoice.type.eq(type)).and(query.builder))
                .fetchFirst();
        InvoiceDto dto = new InvoiceDto();
        if (tuple != null) {
            dto.setAmount(tuple.get(qInvoice.amount.sum()));
            dto.setTaxAmount(tuple.get(qInvoice.taxAmount.sum()));
            dto.setTaxAmountExcluded(tuple.get(qInvoice.taxAmountExcluded.sum()));
        }
        return dto;
    }

    /**
     * 工单 122 · L1-INVOICE-AGG · 进销项汇总聚合（账面口径，按 assignmentDate 认证/开具日）。
     * <p>薄封装既有 invoice / invoice_items 表查询，不新建业务逻辑。返回：
     * <ul>
     *   <li>{@code total}：合计 {amount, taxAmount, count}</li>
     *   <li>{@code monthly}：按月 [{month, amount, taxAmount, count}]</li>
     *   <li>{@code byTaxRate}：按 invoice_items.tax_rate 分布 [{taxRate, itemCount, tax}]</li>
     *   <li>{@code zeroRate}：零税额维度 {count, amount, caliber}（RISK-INVOICE-B · 零税率/免税/不征税合并，供 R15）</li>
     *   <li>{@code byState}：按 state 分布（红冲/作废率由调用方按 state 计）[{state, count, amount}]</li>
     *   <li>{@code topPartners}：前 N 大客户/供应商（按 customer_name 聚合金额）[{name, count, amount}]</li>
     * </ul>
     *
     * @param type OUT 销项 / IN 进项
     */
    public Map<String, Object> invoiceAggregate(InvoiceType type, Long accountSetId, LocalDate start, LocalDate end, int topN) {
        // 1) 发票级行（一次查，Java 侧分组，DB 可移植）
        List<Tuple> rows = bqf.selectFrom(qInvoice)
                .select(qInvoice.id, qInvoice.assignmentDate, qInvoice.amount, qInvoice.taxAmount,
                        qInvoice.taxAmountExcluded, qInvoice.state, qInvoice.customerName)
                .where(qInvoice.accountSetsId.eq(accountSetId).and(qInvoice.type.eq(type))
                        .and(start == null ? null : qInvoice.assignmentDate.goe(start))
                        .and(end == null ? null : qInvoice.assignmentDate.loe(end)))
                .fetch();

        BigDecimal totalAmount = BigDecimal.ZERO, totalTax = BigDecimal.ZERO;
        // RISK-INVOICE-B：零税额维度（taxAmount=0 的发票，含零税率/免税/不征税；账面无标记不细分）
        int zeroRateCount = 0;
        BigDecimal zeroRateAmount = BigDecimal.ZERO;     // 不含税金额（taxAmountExcluded，缺则取含税额）
        Map<String, BigDecimal[]> monthly = new TreeMap<>();      // month -> [amount, tax, count]
        Map<String, BigDecimal[]> byState = new LinkedHashMap<>(); // state -> [amount, count]
        Map<String, BigDecimal[]> byPartner = new HashMap<>();     // name  -> [amount, count]
        for (Tuple t : rows) {
            BigDecimal amt = nz(t.get(qInvoice.amount));
            BigDecimal tax = nz(t.get(qInvoice.taxAmount));
            totalAmount = totalAmount.add(amt);
            totalTax = totalTax.add(tax);
            if (tax.signum() == 0) {   // 零税额发票（账面口径）
                zeroRateCount++;
                BigDecimal excl = t.get(qInvoice.taxAmountExcluded);
                zeroRateAmount = zeroRateAmount.add(excl != null ? excl : amt);
            }
            LocalDate ad = t.get(qInvoice.assignmentDate);
            String mon = ad != null ? YearMonth.from(ad).toString() : "未知";
            accumulate(monthly, mon, amt, tax);
            InvoiceState st = t.get(qInvoice.state);
            String stKey = st != null ? st.name() : "未知";
            accumulate2(byState, stKey, amt);
            String name = t.get(qInvoice.customerName);
            if (StrUtil.isNotBlank(name)) accumulate2(byPartner, name, amt);
        }

        // 2) 税率分布（invoice_items.tax_rate，join 限定本账套+类型+日期范围）
        List<Tuple> itemRows = bqf.selectFrom(qInvoiceItems)
                .select(qInvoiceItems.taxRate, qInvoiceItems.tax.sum(), qInvoiceItems.count())
                .leftJoin(qInvoice).on(qInvoice.id.eq(qInvoiceItems.invoiceId).and(qInvoice.accountSetsId.eq(qInvoiceItems.accountSetsId)))
                .where(qInvoiceItems.accountSetsId.eq(accountSetId).and(qInvoice.type.eq(type))
                        .and(start == null ? null : qInvoice.assignmentDate.goe(start))
                        .and(end == null ? null : qInvoice.assignmentDate.loe(end)))
                .groupBy(qInvoiceItems.taxRate)
                .fetch();

        // 组装
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("amount", totalAmount);
        total.put("taxAmount", totalTax);
        total.put("count", rows.size());
        result.put("total", total);

        List<Map<String, Object>> monthlyList = new ArrayList<>();
        monthly.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", k);
            m.put("amount", v[0]);
            m.put("taxAmount", v[1]);
            m.put("count", v[2].intValue());
            monthlyList.add(m);
        });
        result.put("monthly", monthlyList);

        List<Map<String, Object>> taxRateList = new ArrayList<>();
        for (Tuple t : itemRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taxRate", t.get(qInvoiceItems.taxRate));
            m.put("tax", nz(t.get(qInvoiceItems.tax.sum())));
            m.put("itemCount", t.get(qInvoiceItems.count()));
            taxRateList.add(m);
        }
        result.put("byTaxRate", taxRateList);

        // RISK-INVOICE-B：零税额维度（零税率/免税/不征税合并；账面无标记不细分）→ R15
        Map<String, Object> zeroRate = new LinkedHashMap<>();
        zeroRate.put("count", zeroRateCount);
        zeroRate.put("amount", zeroRateAmount);
        zeroRate.put("caliber", "零税额发票（taxAmount=0，含零税率/免税/不征税；账面无免税标记故合并不细分）；amount=不含税金额(taxAmountExcluded，缺则取含税额)");
        result.put("zeroRate", zeroRate);

        List<Map<String, Object>> stateList = new ArrayList<>();
        byState.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", k);
            m.put("amount", v[0]);
            m.put("count", v[1].intValue());
            stateList.add(m);
        });
        result.put("byState", stateList);

        result.put("topPartners", byPartner.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0].compareTo(a.getValue()[0]))
                .limit(Math.max(1, topN))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("amount", e.getValue()[0]);
                    m.put("count", e.getValue()[1].intValue());
                    return m;
                }).collect(Collectors.toList()));

        return result;
    }

    private static void accumulate(Map<String, BigDecimal[]> map, String key, BigDecimal amt, BigDecimal tax) {
        BigDecimal[] v = map.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        v[0] = v[0].add(amt); v[1] = v[1].add(tax); v[2] = v[2].add(BigDecimal.ONE);
    }

    private static void accumulate2(Map<String, BigDecimal[]> map, String key, BigDecimal amt) {
        BigDecimal[] v = map.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        v[0] = v[0].add(amt); v[1] = v[1].add(BigDecimal.ONE);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * 所有商品列表
     * @param type
     * @param accountSetId
     * @return
     */
    public List<String> goodsNameList(InvoiceType type, Long accountSetId) {
        return bqf.selectFrom(qInvoiceItems)
                .select(qInvoiceItems.goodsName).distinct()
                .join(qInvoice).on(qInvoice.id.eq(qInvoiceItems.invoiceId).and(qInvoice.accountSetsId.eq(qInvoiceItems.accountSetsId)))
                .where(qInvoiceItems.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type)))
                .fetch();
    }

    /**
     * 批量转换DTO
     * @param tuples 查询结果
     * @param accountSetId 账套ID
     * @return
     */
    private List<InvoiceDto> toDtoList(List<Tuple> tuples, Long accountSetId) {
        if (CollUtil.isEmpty(tuples)) {
            return new ArrayList<>();
        }
        //获取发票明细数据
        List<Long> allInvoiceIds = tuples.stream()
                .filter(tuple -> tuple.get(qInvoice).getHaveDetail())
                .map(tuple -> tuple.get(qInvoice).getId())
                .collect(Collectors.toList());
        Map<Long, List<InvoiceItemsDto>> itemsMap = bqf.selectFrom(qInvoiceItems)
                .where(qInvoiceItems.invoiceId.in(allInvoiceIds))
                .fetch().stream()
                .collect(Collectors.groupingBy(
                        InvoiceItems::getInvoiceId,
                        Collectors.mapping(item -> BeanUtil.toBean(item, InvoiceItemsDto.class), Collectors.toList())
                ));

        //遍历tuples构建DTO，赋值明细
        return tuples.stream().map(tuple -> {
            InvoiceDto invoiceDto = BeanUtil.toBean(tuple.get(qInvoice), InvoiceDto.class);
            invoiceDto.setBusinessType(tuple.get(qInvoiceBusinessType.title));
            if (invoiceDto.getTaxAmount() != null && NumberUtil.isGreater(invoiceDto.getTaxAmount().abs(), BigDecimal.ZERO)) {
                invoiceDto.setRate(NumberUtil.mul(NumberUtil.div(invoiceDto.getTaxAmount(), invoiceDto.getAmount()), 100));
            }
            if (invoiceDto.getVoucherId() != null && StrUtil.isNotEmpty(tuple.get(qVoucher.word))) {
                invoiceDto.setVoucher(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
            } else {
                invoiceDto.setVoucherId(null);
            }
            invoiceDto.setItems(itemsMap.getOrDefault(invoiceDto.getId(), new ArrayList<>()));
            return invoiceDto;
        }).collect(Collectors.toList());
    }

    private void toDto(ArrayList<InvoiceDto> list, Tuple tuple) {
        InvoiceDto invoiceDto = BeanUtil.toBean(tuple.get(qInvoice), InvoiceDto.class);
        invoiceDto.setBusinessType(tuple.get(qInvoiceBusinessType.title));
        if (invoiceDto.getTaxAmount() != null && NumberUtil.isGreater(invoiceDto.getTaxAmount().abs(), BigDecimal.ZERO)) {
            invoiceDto.setRate(NumberUtil.mul(NumberUtil.div(invoiceDto.getTaxAmount(), invoiceDto.getTaxAmountExcluded()), 100));
        }
        if (invoiceDto.getHaveDetail()) {
            invoiceDto.setItems(bqf.selectFrom(qInvoiceItems)
                    .where(qInvoiceItems.invoiceId.eq(invoiceDto.getId()))
                    .fetch().stream().collect(ArrayList::new, (l, it) -> {
                        l.add(BeanUtil.toBean(it, InvoiceItemsDto.class));
                    }, List::addAll));
        }
        if (invoiceDto.getVoucherId() != null && StrUtil.isNotEmpty(tuple.get(qVoucher.word))) {
            invoiceDto.setVoucher(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
        } else {
            invoiceDto.setVoucherId(null);
        }
        list.add(invoiceDto);
    }

    @Transactional
    public void delete(List<Long> invoiceId, Long accountSetId) {
        List<Long> deleteIds = bqf.selectFrom(qInvoice)
                .select(qInvoice.id)
                .where(qInvoice.id.in(invoiceId)
                        .and(qInvoice.voucherId.isNull())
                        .and(qInvoice.accountSetsId.eq(accountSetId)))
                .fetch();
        if (CollUtil.isNotEmpty(deleteIds)) {
            jqf.delete(qInvoice)
                    .where(qInvoice.id.in(deleteIds)
                            .and(qInvoice.accountSetsId.eq(accountSetId)))
                    .execute();
            jqf.delete(qInvoiceItems)
                    .where(qInvoiceItems.invoiceId.in(deleteIds)
                            .and(qInvoiceItems.accountSetsId.eq(accountSetId)))
                    .execute();
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return Invoice.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<Invoice> entityList = JSON.parseArray(json, Invoice.class);
        for (Invoice entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            invoiceRepository.save(entity);
        }
    }
    @Transactional
    public void batchUpdateItem(List<Long> invoiceId, Long invoiceBusinessTypeId, Long accountSetId){
        jqf.update(qInvoice)
                .set(qInvoice.invoiceBusinessTypeId,  invoiceBusinessTypeId)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.voucherId.isNull())
                        .and(qInvoice.id.in(invoiceId)))
                .execute();

    }
    /**
     * 批量认证
     *
     * @param invoiceId
     * @param checkDate
     * @param accountSetId
     * @return
     */
    @Transactional
    public long batchCheck(List<Long> invoiceId, LocalDate checkDate, Long accountSetId) {
        return jqf.update(qInvoice)
                .set(qInvoice.checkDate, checkDate)
                .where(qInvoice.accountSetsId.eq(accountSetId).and(qInvoice.checkDate.isNull())
                        .and(qInvoice.id.in(invoiceId)))
                .execute();
    }

    /**
     * 取消认证
     *
     * @param invoiceId
     * @param accountSetId
     * @return
     */
    @Transactional
    public long batchUnCheck(List<Long> invoiceId, Long accountSetId) {
        return jqf.update(qInvoice)
                .setNull(qInvoice.checkDate)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.voucherId.isNull())
                        .and(qInvoice.id.in(invoiceId)))
                .execute();
    }

    /**
     * 批量修改发票显示状态（普票 / 已勾选 / 未勾选）
     *
     * 仅修改 showStatus 字段，不改变认证日期等其他信息
     *
     * @param invoiceId    发票ID集合
     * @param showStatus   目标显示状态
     * @param accountSetId 账套ID
     * @return 影响行数
     */
    @Transactional
    public long batchUpdateShowStatus(List<Long> invoiceId, String showStatus, Long accountSetId) {
        return jqf.update(qInvoice)
                .set(qInvoice.showStatus, showStatus)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.id.in(invoiceId)))
                .execute();
    }

    /**
     * 发票生成凭证
     *
     * @param voucherDtos
     * @param accountSet
     * @param id
     */
    @Transactional
    public void generateVoucher(List<VoucherDto> voucherDtos, AccountSets accountSet, Long id, Boolean isSysAuto) {
        voucherDtos.forEach(voucherDto -> {
            voucherDto.setCreateMember(id);
            voucherDto.setAccountSetsId(accountSet.getId());
            if(!isSysAuto) {
                voucherDto.getDetails().forEach(detail -> {
                    if (StrUtil.isNotEmpty(detail.getCustomerName())) {
                        Tuple2<Long, AcDetails> tuple = subjectService.createLeaf(Math.abs(detail.getSubjectId()), detail.getCustomerName(), accountSet);
                        detail.setSubjectId(tuple.getT1());
                        if (tuple.getT2().getId() != null) {
                            List<AcDetails> auxiliary = new ArrayList<>();
                            auxiliary.add(tuple.getT2());
                            detail.setAuxiliary(auxiliary);
                        }
                    }
                });
            }
            VoucherDto saved = voucherService.save(voucherDto, accountSet, isSysAuto);

            if (CollUtil.isNotEmpty(voucherDto.getInvoiceIds())) {
                for (Long invoiceId : voucherDto.getInvoiceIds()) {
                    jqf.update(qInvoice)
                            .set(qInvoice.voucherId, saved.getId())
                            .where(qInvoice.accountSetsId.eq(accountSet.getId())
                                    .and(qInvoice.id.eq(invoiceId)))
                            .execute();
                }
            } else {
                jqf.update(qInvoice)
                        .set(qInvoice.voucherId, saved.getId())
                        .where(qInvoice.accountSetsId.eq(accountSet.getId())
                                .and(qInvoice.id.eq(voucherDto.getInvoiceId())))
                        .execute();
            }
        });
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.update(qInvoice)
                .setNull(qInvoice.voucherId)
                .where(qInvoice.voucherId.eq(voucherId).and(qInvoice.accountSetsId.eq(accountSetId)))
                .execute();
    }

    /**
     * 检查期间是否有发票未生成凭证
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public boolean invoiceCheck(Long accountSetsId, Integer year, Integer month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        // FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：openapi/网页 关账前发票检查按「做账归属期」过滤
        // （assignmentDate 优先 + tradeDate 兜底），与内部做账口径一致，避免被指定到本期、开票在别月的未做账发票被漏检。
        // FN-FIX-CHECKOUT-PRECHECK-DATA-AWARE：只对「正常」发票要求凭证——作废/红冲票本不该独立生成凭证，
        // 不加状态过滤会把作废票当成「未做账发票」误判、永远过不了发票检查。正常发票无凭证仍判未通过（不放宽真缺凭证）。
        return bqf.selectFrom(qInvoice)
                       .where(qInvoice.accountSetsId.eq(accountSetsId)
                               .and(qInvoice.state.eq(InvoiceState.正常))
                               .and(cn.gson.financial.kernel.model.query.InvoicePeriodPredicate.byPeriod(qInvoice, start, end))
                               .and(qInvoice.voucherId.isNull()))
                       .fetchCount() == 0;
    }

    public BigDecimal getSummaryData(Long accountSetsId, LocalDate beginDate, LocalDate endDate) {
        return bqf.selectFrom(qInvoice).select(qInvoice.amount.sum())
                .where(qInvoice.accountSetsId.eq(accountSetsId).and(qInvoice.tradeDate.between(beginDate, endDate)))
                .fetchFirst();
    }
//获取每个账套发票的下载情况
    public List<InvoiceTaskDto> getDataList(List<AccountSets> AccountSetsList,String SearchDate) {
        List<InvoiceTaskDto> list = new ArrayList<>();
        //循环遍历AccountSetsList
        for(AccountSets accountSets : AccountSetsList)
        {
            if(accountSets.getDeactivated() != null && accountSets.getDeactivated()){
                continue;
            }
            InvoiceTaskDto invoiceTaskDto = new InvoiceTaskDto();
            Map<String,Object> map = new HashMap<>();
            //将currentBillDate转为LocalDate类型
            LocalDate data = LocalDate.parse(SearchDate+"-15");
            //获取这个月的第一天和最后一天
            map.put("searchDate",data);
            map.put("accountSetsId",accountSets.getId());
            //查询该账套下，该时间段内所有发票信息
            List<Invoice> invoiceList = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSets.getId()).and(qInvoice.assignmentDate.between(data.with(TemporalAdjusters.firstDayOfMonth()),data.with(TemporalAdjusters.lastDayOfMonth()))))
                    .fetch();
            //查询该账套下，该时间段内是否有下载{开具发票，取得发票，已勾选，未勾选}任务

            List<AllTaskDto>  taskDtoList = allTaskService.selectAllListByDate(map);
            //返回包含发票列表和任务完成情况账套的列表。
            invoiceTaskDto.setInvoiceList(invoiceList);
            invoiceTaskDto.setTaskDtoList(taskDtoList);
            invoiceTaskDto.setAccountSets(accountSets);
            list.add(invoiceTaskDto);
        }

        return list;
    }

    /**
     * 获取发票基本信息
     * @param accountSetsId
     * @param period
     * @return
     */
    public GetInvoiceSumInfoResp uaGetInvoiceSumInfo(Long accountSetsId, LocalDate period) {
        LocalDate start = period.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = period.with(TemporalAdjusters.lastDayOfMonth());
        GetInvoiceSumInfoResp resp = new GetInvoiceSumInfoResp();
        List<Tuple> tuples = bqf.selectFrom(qInvoice)
                .select(qInvoice.type, qInvoice.count(), qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                .where(qInvoice.accountSetsId.eq(accountSetsId).and(qInvoice.assignmentDate.between(start, end)))
                .groupBy(qInvoice.type)
                .fetch();

        tuples.forEach(item -> {

            if("IN".equals(item.get(qInvoice.type).name())){
                resp.setInCount(item.get(qInvoice.count()));
                resp.setInSum(item.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)));
            }
            if("OUT".equals(item.get(qInvoice.type).name())){
                resp.setOutCount(item.get(qInvoice.count()));
                resp.setOutSum(item.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)));
            }
        });


        return resp;
    }

    /**
     * 获取账套销项发票价税合计总额
     *  根据localDate判断所在季度
     * @param accountSetId
     * @param localDate
     * @return
     */
    public BigDecimal getOutInvoiceAmount(Long accountSetId, LocalDate localDate){
        LocalDate start = DateUtil.beginOfQuarter(DateTime.from(localDate.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = DateUtil.endOfQuarter(DateTime.from(start.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
        BigDecimal bigDecimal = bqf.selectFrom(qInvoice)
                .select(qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(InvoiceType.OUT))
                        .and(qInvoice.showStatus.in(Arrays.asList("普票", "已勾选", "未勾选")))
                        .and(qInvoice.assignmentDate.between(start, end))
                ).fetchOne();
        return bigDecimal;
    }

    public BigDecimal getOutInvoiceAmountJianZhu(Long accountSetId, LocalDate localDate){
        LocalDate start = DateUtil.beginOfQuarter(DateTime.from(localDate.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = DateUtil.endOfQuarter(DateTime.from(start.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
        List<Long> jianZhuIds = bqf.selectFrom(qInvoiceItems)
                .select(qInvoiceItems.invoiceId)
                .where(qInvoiceItems.goodsName.contains("建筑服务")
                        .and(qInvoiceItems.invoiceId.in(
                                bqf.selectFrom(qInvoice)
                                        .select(qInvoice.id)
                                        .where(qInvoice.accountSetsId.eq(accountSetId)
                                                .and(qInvoice.assignmentDate.between(start, end)))
                        )))
                .fetch();
        if (jianZhuIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal bigDecimal = bqf.selectFrom(qInvoice)
                .select(qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(InvoiceType.OUT))
                        .and(qInvoice.showStatus.in(Arrays.asList("普票", "已勾选", "未勾选")))
                        .and(qInvoice.assignmentDate.between(start, end))
                        .and(qInvoice.id.in(jianZhuIds))
                ).fetchOne();
        return bigDecimal;
    }


    @Transactional
    public void updateInvoiceAssignmentDate(Invoice invoice) {
        jqf.update(qInvoice).set(qInvoice.assignmentDate, invoice.getAssignmentDate())
                .where(qInvoice.id.eq(invoice.getId()))
                .execute();
    }

    public BigDecimal getInInvoiceAmount(Long accountSetId, LocalDate localDate) {
        LocalDate start = DateUtil.beginOfQuarter(DateTime.from(localDate.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = DateUtil.endOfQuarter(DateTime.from(start.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
        BigDecimal bigDecimal = bqf.selectFrom(qInvoice)
                .select(qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(InvoiceType.IN))
                        .and(qInvoice.showStatus.in(Arrays.asList("普票")))
                        .and(qInvoice.assignmentDate.between(start, end))
                ).fetchOne();
        return bigDecimal;
    }

    public String getInvoiceStatus(Long accountSetId, LocalDate localDate) {
        //根据查询该账套的发票任务是否完成。
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("accountSetsId",accountSetId);
        LocalDate start = DateUtil.beginOfQuarter(DateTime.from(localDate.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = DateUtil.endOfQuarter(DateTime.from(start.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                .toLocalDateTime().toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
        paramMap.put("start",start);
        paramMap.put("end",end);
        List<Integer> getInvoiceStatus = lazyDao.findBySql("getInvoiceStatus", paramMap, Integer.class);
            if(getInvoiceStatus.get(0) == 0){
                return "1";
            }
        return "0";
    }

    @Data
    public static class InvoiceQuery {
        private final BooleanBuilder builder = new BooleanBuilder();

        public LocalDate start;
        public LocalDate end;
        public List<String> goodsName;
        public List<String> category;
        public List<String> showStatus;
        public Boolean download = false;
        public List<Long> businessTypeId;


        public void setEnd(LocalDate end) {
            this.end = end;
            if (end != null) {
                LocalDate lastDayOfMonth = end.with(TemporalAdjusters.lastDayOfMonth());
                builder.and(qInvoice.assignmentDate.loe(lastDayOfMonth));
            }
        }

        public void setStart(LocalDate start) {
            this.start = start;
            if (start != null) {
                builder.and(qInvoice.assignmentDate.goe(start));
            }
        }

        public void setVoucherId(Boolean voucherId) {
            if (voucherId != null) {
                if (voucherId) {
                    builder.and(qInvoice.voucherId.isNotNull());
                } else {
                    builder.and(qInvoice.voucherId.isNull());
                }
            }
        }

        public void setState(InvoiceState state) {
            if (state != null) {
                builder.and(qInvoice.state.eq(state));
            }
        }

        public void setInvoiceNumber(String invoiceNumber) {
            if (StrUtil.isNotEmpty(invoiceNumber)) {
                builder.and(qInvoice.invoiceNumber.contains(invoiceNumber));
            }
        }

        public void setCategory(List<String> category) {

            if (ArrayUtil.isNotEmpty(category)) {
                this.category = category;
                builder.and(qInvoice.newCategory.in(category));
            }
        }
        public void setBusinessTypeId(List<Long> businessTypeId) {

            if (CollUtil.isNotEmpty(businessTypeId)) {
                this.businessTypeId = businessTypeId;
                builder.and(qInvoice.invoiceBusinessTypeId.in(businessTypeId));
            }
        }

        public void setCustomerName(String customerName) {
            if (StrUtil.isNotEmpty(customerName)) {
                builder.and(qInvoice.customerName.contains(customerName));
            }
        }
    }

    /**
     * 转换凭证
     * @param accountSetId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeVoucher(Long accountSetId){
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetId))
                .fetchFirst();
        LocalDate endDate = accountSets.getCurrentAccountDate().with(TemporalAdjusters.lastDayOfMonth());
        List<Invoice> invoiceList = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.voucherId.isNull())
                        .and(qInvoice.assignmentDate.between(accountSets.getCurrentAccountDate(), endDate)))
                .orderBy(qInvoice.assignmentDate.desc(), qInvoice.id.desc())
                .fetch();
        if(CollUtil.isNotEmpty(invoiceList)){
            List<Long> idList = invoiceList.stream().map(Invoice::getId).toList();
            Map<Long, List<InvoiceItemsDto>> itemDtoCollect = bqf.selectFrom(qInvoiceItems)
                    .where(qInvoiceItems.invoiceId.in(idList))
                    .fetch().stream()
                    .map(item -> CopierCopyUtils.convertTo(item, InvoiceItemsDto.class))
                    .collect(Collectors.groupingBy(InvoiceItemsDto::getInvoiceId));
            Integer voucherCode = bqf.selectFrom(qVoucher)
                    .select(qVoucher.code.max())
                    .where(qVoucher.accountSetsId.eq(accountSetId)
                            .and(qVoucher.year.eq(accountSets.getCurrentAccountDate().getYear()))
                            .and(qVoucher.month.eq(accountSets.getCurrentAccountDate().getMonthValue()))
                    ).fetchFirst();
            if(ObjectUtils.isEmpty(voucherCode) ){
                voucherCode = 0;
            }
            if(CollUtil.isNotEmpty(itemDtoCollect)) {
                for (int i = 0; i < invoiceList.size(); i++) {
                    voucherCode += 1;
                    InvoiceDto invoiceDto = CopierCopyUtils.convertTo(invoiceList.get(i), InvoiceDto.class);
                    if (itemDtoCollect.containsKey(invoiceDto.getId())) {
                        invoiceDto.setItems(itemDtoCollect.get(invoiceDto.getId()));
                    } else {
                        continue;
                    }
                    JSONObject jsonObject = InvoiceVoucherUtils.generateVoucher(invoiceDto, accountSets.getCompanyName(), voucherCode);
                    VoucherDto voucherDto = buildVoucherDto(jsonObject, invoiceDto, voucherCode, accountSets);
                    if(ObjectUtils.isNotEmpty(voucherDto)){
                        try {
                            List<VoucherDto> voucherDtoList = Arrays.asList(voucherDto);
                            this.generateVoucher(voucherDtoList, accountSets, null, true);
                            voucherDtoList.stream().map(VoucherDto::getVoucherDate).min(LocalDate::compareTo).ifPresent(date -> {
                                balanceCalcService.calcSynchronization(accountSets.getId(), YearMonth.from(date));
                            });
                        }catch (Exception e){
                            System.out.println("{"+ invoiceDto.getId() + "}保存凭证失败！" + e);
                        }
                    }
                }
            }
        }
        return Boolean.FALSE;
    }

    /**
     * 组装VoucherDto
     * @param jsonObject
     * @param voucherCode
     * @return 请求DTO
     */
    @Transactional(rollbackFor = Exception.class)
    public VoucherDto buildVoucherDto(JSONObject jsonObject, InvoiceDto invoiceDto, Integer voucherCode, AccountSets accountSets) {
        VoucherDto voucherDto = new VoucherDto();
        voucherDto.setInvoiceId(invoiceDto.getId());
        voucherDto.setWord("记");
        voucherDto.setCode(voucherCode);
        voucherDto.setVoucherDate(jsonObject.getObject("凭证日期", LocalDate.class));
        voucherDto.setCreditAmount(jsonObject.getBigDecimal("贷方总金额"));//dqi
        voucherDto.setDebitAmount(jsonObject.getBigDecimal("借方总金额"));//jie
        List<VoucherDetailsDto> detailsDtoList = new ArrayList<>();
        JSONArray debitList = jsonObject.getJSONArray("借方分录");
        JSONArray creditList = jsonObject.getJSONArray("贷方分录");
        if(CollUtil.isNotEmpty(debitList)){
            for (int i = 0; i < debitList.size(); i++) {
                JSONObject debit = debitList.getJSONObject(i);
                VoucherDetailsDto voucherDetailsDto = new VoucherDetailsDto();
                voucherDetailsDto.setCurrencyId(433722807319433217L);
                voucherDetailsDto.setCustomerName(invoiceDto.getCustomerName());
                voucherDetailsDto.setDebitAmount(debit.getBigDecimal("金额"));
                voucherDetailsDto.setSummary(debit.getString("摘要"));
                String subjectName = debit.getString("科目名称").replaceAll("-", "_");
                voucherDetailsDto.setSubjectName(subjectName);
                Long subjectId = getSubjectCode(subjectName, accountSets, "借");
                if(ObjectUtils.isEmpty(subjectId)){
                    continue;
                }
                voucherDetailsDto.setSubjectId(subjectId);
                detailsDtoList.add(voucherDetailsDto);
            }
        }
        if(CollUtil.isNotEmpty(creditList)){
            for (int i = 0; i < creditList.size(); i++) {
                JSONObject credit = creditList.getJSONObject(i);
                VoucherDetailsDto voucherDetailsDto = new VoucherDetailsDto();
                voucherDetailsDto.setCurrencyId(433722807319433217L);
                voucherDetailsDto.setCustomerName(invoiceDto.getCustomerName());
                voucherDetailsDto.setCreditAmount(credit.getBigDecimal("金额"));
                voucherDetailsDto.setSummary(credit.getString("摘要"));
                String subjectName = credit.getString("科目名称").replaceAll("-", "_");
                voucherDetailsDto.setSubjectName(subjectName);
                Long subjectId = getSubjectCode(subjectName, accountSets, "贷");
                if(ObjectUtils.isEmpty(subjectId)){
                    continue;
                }
                voucherDetailsDto.setSubjectId(subjectId);
                detailsDtoList.add(voucherDetailsDto);
            }
        }
        if(CollUtil.isNotEmpty(detailsDtoList)) {
            voucherDto.setDetails(detailsDtoList);
        }else{
            return null;
        }
        return voucherDto;
    }

    /**
     * 获取科目编码
     * @param subjectName
     * @param accountSets
     */
    @Transactional(rollbackFor = Exception.class)
    public Long getSubjectCode(String subjectName, AccountSets accountSets, String balanceDirection){
        List<Subject> subjectList = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSets.getId())
                        .and(qSubject.fullName.eq(subjectName))
                ).fetch();
        if(CollUtil.isNotEmpty(subjectList)){
            return subjectList.get(0).getId();
        }else{
            //以最后一个"_",分割，区分子级和父级，
            // 父级不存在，则返回null，
            // 父级存在，新增子级并返回
            int lastIndex = StringUtils.lastIndexOf(subjectName, '_');
            //如果没有则默认此为父级 返回null
            if (lastIndex == -1) {
               return null;
            }
            String parent = StringUtils.substring(subjectName, 0, lastIndex);
            String children = StringUtils.substring(subjectName, lastIndex + 1);
            List<Subject> parentSubjectList = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSets.getId())
                            .and(qSubject.fullName.eq(parent))
                    ).fetch();
            if(CollUtil.isEmpty(parentSubjectList)){
                return null;
            }else{
                Subject parentSubject = parentSubjectList.get(0);
                Subject save = new Subject();
                save.setAccountSetsId(accountSets.getId());
                save.setSystemDefault(false);
                save.setStatus(Boolean.TRUE);
                save.setBalanceDirection(balanceDirection);
                save.setType(parentSubject.getType());
                save.setParentId(parentSubject.getId());
                save.setCash(parentSubject.getCash());
                save.setLevel(parentSubject.getLevel() + 1);
                save.setName(children);
                save.setCode(getSubjectCode(parentSubject.getId(), accountSets.getId()));
                Integer encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);
                return subjectService.save(save, encLen).getId();
            }
        }
    }

    public String getSubjectCode(Long parentId, Long accountSetId){
        String code = bqf.selectFrom(qSubject)
                .select(qSubject.code.max())
                .where(qSubject.parentId.eq(parentId)
                        .and(qSubject.accountSetsId.eq(accountSetId)))
                .fetchFirst();
        if(StringUtils.isEmpty(code)){
            return "001";
        }
        //计算数值部分加1,保持原始字符串的前导零格式
        return String.format("%0" + code.length() + "d", Integer.parseInt(code) + 1);
    }
}

