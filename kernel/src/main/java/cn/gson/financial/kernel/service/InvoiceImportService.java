package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.dto.InvoiceItemsDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.repo.InvoiceItemsRepository;
import cn.gson.financial.kernel.model.repo.InvoiceRepository;
import cn.gson.financial.kernel.model.vo.InvoiceImportVo;
import cn.gson.financial.kernel.model.vo.InvoiceItemsVo;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.gson.financial.kernel.model.dto.InvoiceItemsDto;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
/**
 *
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年09月02日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceImportService extends AbsService {


    private final QInvoice qInvoice = QInvoice.invoice;

    private final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;

    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;

    private final InvoiceRepository invoiceRepository;

    private final InvoiceItemsRepository itemsRepository;
    final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    /**
     *按季度保存发票
     * @param creditCode
     * @param multipartFile
     * @param accountSetId
     * @param map
     * @return
     */
    @Transactional
    public List<InvoiceDto> saveImportInvoiceSeason(String creditCode, InputStream multipartFile, Long accountSetId, Map<String,String> map) {
        String invoiceBusinessTypeTitle = "销售收入";
        BooleanExpression expression = qInvoiceBusinessType.accountSetsId.eq(accountSetId);
        expression = expression.and(qInvoiceBusinessType.title.eq(invoiceBusinessTypeTitle));
        Long id = bqf.selectFrom(qInvoiceBusinessType)
                .select(qInvoiceBusinessType.id)
                .where(expression)
                .fetchFirst();
        ExcelReader reader = ExcelUtil.getReader(multipartFile);
        List<String> sheetNames = reader.getSheetNames();
        Assert.isTrue(sheetNames.contains("发票基础信息"), "Excel的sheet名称中必须包含【发票基础信息】,请下载系统模板或使用税务系统导出原始表格~");
        reader.setSheet("发票基础信息");
        ImportVoUtil.setHeaderAlias(InvoiceImportVo.class, reader);
        List<InvoiceImportVo> invoiceImportVos = reader.readAll(InvoiceImportVo.class);
        Assert.isTrue(sheetNames.contains("信息汇总表")
                || sheetNames.contains("货物清单"), "Excel的sheet名称中必须包含【信息汇总表】或【货物清单】,请下载系统模板或使用税务系统导出原始表格");
        if (sheetNames.contains("信息汇总表")) {
            reader.setSheet("信息汇总表");
        } else {
            reader.setSheet("货物清单");
        }
        reader.clearHeaderAlias();
        ImportVoUtil.setHeaderAlias(InvoiceItemsVo.class, reader);
        List<InvoiceItemsVo> invoiceItemsVos = reader.readAll(InvoiceItemsVo.class);

        // 发票关键字匹配类型
        Map<InvoiceType, List<InvoiceBusinessType>> typeListMap = bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId))
                .fetch().stream()
                .filter(it -> CollUtil.isNotEmpty(it.getMatchWords()))
                .collect(Collectors.groupingBy(InvoiceBusinessType::getInvoiceType));

        List<InvoiceDto> invoiceDtos = new ArrayList<>();
        invoiceImportVos.stream().filter(s -> StrUtil.isNotEmpty(s.getNewCategory())).forEach(importVo -> {
            List<InvoiceItemsVo> invoiceItems;
            String invoiceNumber = importVo.getInvoiceNumber();
            if (StrUtil.isNotEmpty(invoiceNumber)) {
                invoiceItems = invoiceItemsVos.stream().filter(it -> invoiceNumber.equalsIgnoreCase(it.getInvoiceNumber())).toList();
            } else {
                String netNumber = importVo.getNetNumber();
                invoiceItems = invoiceItemsVos.stream().filter(it -> netNumber.equalsIgnoreCase(it.getNetNumber())).toList();
            }
            List<InvoiceItemsDto> itemsDtos = invoiceItems.stream().map(vo -> {
                InvoiceItemsDto dto = new InvoiceItemsDto();
                dto.setAccountSetsId(accountSetId);
                dto.setGoodsName(vo.getGoodsName());
                dto.setSpecification(vo.getSpecification());
                dto.setUnit(vo.getUnit());
                dto.setQuantity(vo.getQuantity());
                dto.setAmount(vo.getAmount());
                dto.setTaxRate(vo.getTaxRate());
                dto.setTax(vo.getTax());
                dto.setTaxedItems("应税货物");
                dto.setTaxCalculationMethod("一般计税方法-一般");
                return dto;
            }).toList();

            // 根据纳税人识别号判断发票类型，不匹配的发票直接跳过（不导入）
            InvoiceType invoiceType = importVo.getInvoiceType(creditCode);
            if (invoiceType == null) {
                // 纳税人识别号与销方/购方均不匹配，跳过当前发票
                return;
            }

            InvoiceDto invoiceDto = new InvoiceDto();
            invoiceDto.setAccountSetsId(accountSetId);
            invoiceDto.setNewCategory(importVo.getNewCategory());
            invoiceDto.setTradeDate(importVo.getTradeDate());
            invoiceDto.setInvoiceCode(importVo.getInvoiceCode());
            invoiceDto.setInvoiceNumber(StrUtil.emptyToDefault(importVo.getInvoiceNumber(), importVo.getNetNumber()));
            invoiceDto.setState(importVo.getState());
            invoiceDto.setType(invoiceType);

            // FN-INVOICE-COLLECT-MODE-CONFIG：season 路径=季度模式，归属期落季末月（按季采集）。
            // 月度模式由调用方分流走 saveImportInvoice→真实月。申报按 trade_date 季度汇总、与归属期解耦、不受影响。
            LocalDate endDate = LocalDate.parse(quarterEnd(String.valueOf(importVo.getTradeDate())));
            invoiceDto.setAssignmentDate(endDate);
            invoiceDto.setShowStatus("普票");
            if (InvoiceType.OUT.equals(invoiceDto.getType())) {
                invoiceDto.setCustomerName(importVo.getBuyerName());
                invoiceDto.setCreditCode(importVo.getBuyerNumber());
            } else {
                invoiceDto.setCustomerName(importVo.getPinName());
                invoiceDto.setCreditCode(importVo.getPinNumber());
            }

            // 智能商品关键字匹配
            if (!itemsDtos.isEmpty()) {
                List<InvoiceBusinessType> invoiceBusinessTypes = typeListMap.get(invoiceDto.getType());
                if (CollUtil.isNotEmpty(invoiceBusinessTypes)) {
                    for (InvoiceItemsDto itemsDto : itemsDtos) {
                        String goodsName = itemsDto.getGoodsName();
                        if (invoiceBusinessTypes.stream().anyMatch(it -> {
                            if (it.getMatchWords().stream().anyMatch(w -> goodsName.contains(w.toString()))) {
                                invoiceDto.setInvoiceBusinessTypeId(it.getId());
                                return true;
                            }
                            return false;
                        })) {
                            break;
                        }
                    }
                }
            }
            if("开具发票".equals(map.get("invoiceType"))){
                invoiceDto.setInvoiceBusinessTypeId(id);
            }
            invoiceDto.setTaxAmountExcluded(importVo.getTaxAmountExcluded());
            invoiceDto.setTaxAmount(importVo.getTaxAmount());
            invoiceDto.setAmount(importVo.getAmount());
            invoiceDto.setNote(importVo.getRemark());
            invoiceDto.setHaveDetail(!itemsDtos.isEmpty());
            invoiceDto.setItems(itemsDtos);
            invoiceDtos.add(invoiceDto);
        });
        this.batchSave(invoiceDtos);
        return invoiceDtos;
    }

    /**
     * 按月份保存发票
     * @param creditCode
     * @param multipartFile
     * @param accountSetId
     * @return
     */
    @Transactional
    public List<InvoiceDto> saveImportInvoice(String creditCode, InputStream multipartFile, Long accountSetId,Map<String,String> map) {
        String invoiceBusinessTypeTitle = "销售收入";
        BooleanExpression expression = qInvoiceBusinessType.accountSetsId.eq(accountSetId);
        expression = expression.and(qInvoiceBusinessType.title.eq(invoiceBusinessTypeTitle));
        Long id = bqf.selectFrom(qInvoiceBusinessType)
                .select(qInvoiceBusinessType.id)
                .where(expression)
                .fetchFirst();
        ExcelReader reader = ExcelUtil.getReader(multipartFile);
        List<String> sheetNames = reader.getSheetNames();
        Assert.isTrue(sheetNames.contains("发票基础信息"), "Excel的sheet名称中必须包含【发票基础信息】,请下载系统模板或使用税务系统导出原始表格~");
        reader.setSheet("发票基础信息");
        ImportVoUtil.setHeaderAlias(InvoiceImportVo.class, reader);
        List<InvoiceImportVo> invoiceImportVos = reader.readAll(InvoiceImportVo.class);
        Assert.isTrue(sheetNames.contains("信息汇总表")
                      || sheetNames.contains("货物清单"), "Excel的sheet名称中必须包含【信息汇总表】或【货物清单】,请下载系统模板或使用税务系统导出原始表格");
        if (sheetNames.contains("信息汇总表")) {
            reader.setSheet("信息汇总表");
        } else {
            reader.setSheet("货物清单");
        }
        reader.clearHeaderAlias();
        ImportVoUtil.setHeaderAlias(InvoiceItemsVo.class, reader);
        List<InvoiceItemsVo> invoiceItemsVos = reader.readAll(InvoiceItemsVo.class);

        // 发票关键字匹配类型
        Map<InvoiceType, List<InvoiceBusinessType>> typeListMap = bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId))
                .fetch().stream()
                .filter(it -> CollUtil.isNotEmpty(it.getMatchWords()))
                .collect(Collectors.groupingBy(InvoiceBusinessType::getInvoiceType));

        List<InvoiceDto> invoiceDtos = new ArrayList<>();
        invoiceImportVos.stream().filter(s -> StrUtil.isNotEmpty(s.getNewCategory())).forEach(importVo -> {
            List<InvoiceItemsVo> invoiceItems;
            String invoiceNumber = importVo.getInvoiceNumber();
            if (StrUtil.isNotEmpty(invoiceNumber)) {
                invoiceItems = invoiceItemsVos.stream().filter(it -> invoiceNumber.equalsIgnoreCase(it.getInvoiceNumber())).toList();
            } else {
                String netNumber = importVo.getNetNumber();
                invoiceItems = invoiceItemsVos.stream().filter(it -> netNumber.equalsIgnoreCase(it.getNetNumber())).toList();
            }
            List<InvoiceItemsDto> itemsDtos = invoiceItems.stream().map(vo -> {
                InvoiceItemsDto dto = new InvoiceItemsDto();
                dto.setAccountSetsId(accountSetId);
                dto.setGoodsName(vo.getGoodsName());
                dto.setSpecification(vo.getSpecification());
                dto.setUnit(vo.getUnit());
                dto.setQuantity(vo.getQuantity());
                dto.setAmount(vo.getAmount());
                dto.setTaxRate(vo.getTaxRate());
                dto.setTax(vo.getTax());
                dto.setTaxedItems("应税货物");
                dto.setTaxCalculationMethod("一般计税方法-一般");
                return dto;
            }).toList();

            // 根据纳税人识别号判断发票类型，不匹配的发票直接跳过（不导入）
            InvoiceType invoiceType = importVo.getInvoiceType(creditCode);
            if (invoiceType == null) {
                // 纳税人识别号与销方/购方均不匹配，跳过当前发票
                return;
            }

            InvoiceDto invoiceDto = new InvoiceDto();
            invoiceDto.setAccountSetsId(accountSetId);
            invoiceDto.setNewCategory(importVo.getNewCategory());
            invoiceDto.setTradeDate(importVo.getTradeDate());
            invoiceDto.setInvoiceCode(importVo.getInvoiceCode());
            invoiceDto.setInvoiceNumber(StrUtil.emptyToDefault(importVo.getInvoiceNumber(), importVo.getNetNumber()));
            invoiceDto.setState(importVo.getState());
            invoiceDto.setType(invoiceType);

            invoiceDto.setAssignmentDate(importVo.getTradeDate());
            invoiceDto.setShowStatus("普票");
            if (InvoiceType.OUT.equals(invoiceDto.getType())) {
                invoiceDto.setCustomerName(importVo.getBuyerName());
                invoiceDto.setCreditCode(importVo.getBuyerNumber());
            } else {
                invoiceDto.setCustomerName(importVo.getPinName());
                invoiceDto.setCreditCode(importVo.getPinNumber());
            }

            // 智能商品关键字匹配
            if (!itemsDtos.isEmpty()) {
                List<InvoiceBusinessType> invoiceBusinessTypes = typeListMap.get(invoiceDto.getType());
                if (CollUtil.isNotEmpty(invoiceBusinessTypes)) {
                    for (InvoiceItemsDto itemsDto : itemsDtos) {
                        String goodsName = itemsDto.getGoodsName();
                        if (invoiceBusinessTypes.stream().anyMatch(it -> {
                            if (it.getMatchWords().stream().anyMatch(w -> goodsName.contains(w.toString()))) {
                                invoiceDto.setInvoiceBusinessTypeId(it.getId());
                                return true;
                            }
                            return false;
                        })) {
                            break;
                        }
                    }
                }
            }
            if("开具发票".equals(map.get("invoiceType"))){
                invoiceDto.setInvoiceBusinessTypeId(id);
            }
            invoiceDto.setTaxAmountExcluded(importVo.getTaxAmountExcluded());
            invoiceDto.setTaxAmount(importVo.getTaxAmount());
            invoiceDto.setAmount(importVo.getAmount());
            invoiceDto.setNote(importVo.getRemark());
            invoiceDto.setHaveDetail(!itemsDtos.isEmpty());
            invoiceDto.setItems(itemsDtos);
            invoiceDtos.add(invoiceDto);
        });
        this.batchSave(invoiceDtos);
        return invoiceDtos;
    }
    public static String quarterEnd(String endDate) {
        Date dEnd = null;
        try {
            dEnd = sdf.parse(endDate);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(dEnd);
        int remainder = (calendar.get(Calendar.MONTH) + 1) % 3;
        int month = remainder != 0 ? calendar.get(Calendar.MONTH) + (3 - remainder) : calendar.get(Calendar.MONTH);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.setTime(calendar.getTime());
        return sdf.format(calendar.getTime());
    }
    /**
     * 从 JSON 结构列表解析发票并批量入库（供 OpenClaw JSON 回调使用）。
     * <p>
     * 字段映射：
     * <ul>
     *   <li>{@code invoice_type}   → newCategory（发票票种）</li>
     *   <li>{@code trade_date}     → tradeDate（YYYY-MM-DD）</li>
     *   <li>{@code invoice_code}   → invoiceCode</li>
     *   <li>{@code invoice_number} → invoiceNumber（与 net_number 二选一）</li>
     *   <li>{@code net_number}     → invoiceNumber（数电发票号码）</li>
     *   <li>{@code seller_id}      → 销方识别号（用于判断进/销项）</li>
     *   <li>{@code seller_name}    → 销方名称</li>
     *   <li>{@code buyer_id}       → 购方识别号</li>
     *   <li>{@code buyer_name}     → 购方名称</li>
     *   <li>{@code state}          → 发票状态（正常/已作废/已红冲-全额/已红冲-部分）</li>
     *   <li>{@code amount_ex_tax}  → 金额（不含税）</li>
     *   <li>{@code tax_amount}     → 税额</li>
     *   <li>{@code total_amount}   → 价税合计</li>
     *   <li>{@code remark}         → 备注</li>
     *   <li>{@code items}          → 货物清单（数组，含 goods_name/specification/unit/quantity/amount/tax_rate/tax）</li>
     * </ul>
     *
     * @param creditCode  本账套纳税人识别号（用于判断进销项）
     * @param accountSetId 账套 ID
     * @param rawList     原始 JSON 列表（每项为一张发票）
     * @return 实际解析并入库的发票 DTO 列表
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<InvoiceDto> saveImportInvoiceJson(String creditCode, Long accountSetId,
                                                  List<Map<String, Object>> rawList) {
        Assert.notEmpty(creditCode, "纳税人识别号不能为空");
        if (CollUtil.isEmpty(rawList)) {
            return List.of();
        }

        // 查询账套"销售收入"业务类型 id（销项发票默认归类）
        Long salesTypeId = bqf.selectFrom(qInvoiceBusinessType)
                .select(qInvoiceBusinessType.id)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId)
                        .and(qInvoiceBusinessType.title.eq("销售收入")))
                .fetchFirst();

        // 关键字匹配规则
        Map<InvoiceType, List<InvoiceBusinessType>> typeListMap = bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId))
                .fetch().stream()
                .filter(it -> CollUtil.isNotEmpty(it.getMatchWords()))
                .collect(Collectors.groupingBy(InvoiceBusinessType::getInvoiceType));

        List<InvoiceDto> invoiceDtos = new ArrayList<>();

        for (Map<String, Object> raw : rawList) {
            String sellerTaxNo = strVal(raw, "seller_id");
            String buyerTaxNo  = strVal(raw, "buyer_id");

            // 判断进/销项（与 InvoiceImportVo.getInvoiceType 逻辑一致）
            InvoiceType invoiceType = null;
            if (StrUtil.isNotBlank(sellerTaxNo) && creditCode.equals(sellerTaxNo)) {
                invoiceType = InvoiceType.OUT;
            } else if (StrUtil.isNotBlank(buyerTaxNo) && creditCode.equals(buyerTaxNo)) {
                invoiceType = InvoiceType.IN;
            }
            if (invoiceType == null) continue; // 不属于本账套，跳过

            // 发票状态
            InvoiceState state;
            try {
                state = switch (StrUtil.nullToEmpty(strVal(raw, "state"))) {
                    case "正常"        -> InvoiceState.正常;
                    case "已作废"      -> InvoiceState.作废;
                    case "已红冲-全额" -> InvoiceState.全额红冲;
                    case "已红冲-部分" -> InvoiceState.部分红冲;
                    default            -> InvoiceState.正常;
                };
            } catch (Exception e) {
                state = InvoiceState.正常;
            }

            // 货物明细
            List<Map<String, Object>> rawItems = raw.get("items") instanceof List
                    ? (List<Map<String, Object>>) raw.get("items") : List.of();
            List<InvoiceItemsDto> itemsDtos = rawItems.stream().map(it -> {
                InvoiceItemsDto dto = new InvoiceItemsDto();
                dto.setAccountSetsId(accountSetId);
                dto.setGoodsName(strVal(it, "goods_name"));
                dto.setSpecification(strVal(it, "specification"));
                dto.setUnit(strVal(it, "unit"));
                dto.setQuantity(bigDecVal(it, "quantity").doubleValue());
                dto.setAmount(bigDecVal(it, "amount"));
                dto.setTaxRate(bigDecVal(it, "tax_rate").multiply(java.math.BigDecimal.valueOf(100)).intValue());
                dto.setTax(bigDecVal(it, "tax"));
                dto.setTaxedItems("应税货物");
                dto.setTaxCalculationMethod("一般计税方法-一般");
                return dto;
            }).collect(Collectors.toList());

            InvoiceDto dto = new InvoiceDto();
            dto.setAccountSetsId(accountSetId);
            dto.setNewCategory(strVal(raw, "invoice_type"));
            dto.setTradeDate(parseLocalDate(strVal(raw, "trade_date")));
            dto.setInvoiceCode(strVal(raw, "invoice_code"));
            String invNum = StrUtil.isNotBlank(strVal(raw, "invoice_number"))
                    ? strVal(raw, "invoice_number") : strVal(raw, "net_number");
            dto.setInvoiceNumber(invNum);
            dto.setState(state);
            dto.setType(invoiceType);
            dto.setAssignmentDate(dto.getTradeDate());
            dto.setShowStatus("普票");
            if (InvoiceType.OUT.equals(invoiceType)) {
                dto.setCustomerName(strVal(raw, "buyer_name"));
                dto.setCreditCode(buyerTaxNo);
            } else {
                dto.setCustomerName(strVal(raw, "seller_name"));
                dto.setCreditCode(sellerTaxNo);
            }
            dto.setTaxAmountExcluded(bigDecVal(raw, "amount_ex_tax"));
            dto.setTaxAmount(bigDecVal(raw, "tax_amount"));
            dto.setAmount(bigDecVal(raw, "total_amount"));
            dto.setNote(strVal(raw, "remark"));
            dto.setHaveDetail(!itemsDtos.isEmpty());
            dto.setItems(itemsDtos);

            // 关键字匹配业务类型
            if (!itemsDtos.isEmpty()) {
                List<InvoiceBusinessType> matchTypes = typeListMap.get(invoiceType);
                if (CollUtil.isNotEmpty(matchTypes)) {
                    for (InvoiceItemsDto item : itemsDtos) {
                        String goodsName = StrUtil.nullToEmpty(item.getGoodsName());
                        boolean matched = matchTypes.stream().anyMatch(bt -> {
                            if (bt.getMatchWords().stream().anyMatch(w -> goodsName.contains(w.toString()))) {
                                dto.setInvoiceBusinessTypeId(bt.getId());
                                return true;
                            }
                            return false;
                        });
                        if (matched) break;
                    }
                }
            }
            // 销项无关键字匹配时归到"销售收入"
            if (InvoiceType.OUT.equals(invoiceType)
                    && dto.getInvoiceBusinessTypeId() == null && salesTypeId != null) {
                dto.setInvoiceBusinessTypeId(salesTypeId);
            }

            invoiceDtos.add(dto);
        }

        batchSave(invoiceDtos);
        return invoiceDtos;
    }

    // ── 辅助方法 ──
    private String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private BigDecimal bigDecVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private LocalDate parseLocalDate(String s) {
        if (StrUtil.isBlank(s)) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    @Transactional
    public void batchSave(List<InvoiceDto> invoiceDtos) {
        for (InvoiceDto invoiceDto : invoiceDtos) {
            // 已存在的发票号跳过
            Invoice invoice = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(invoiceDto.getAccountSetsId())
                            .and(qInvoice.invoiceNumber.eq(invoiceDto.getInvoiceNumber())))
                    .fetchFirst();
            if (invoice == null) {
                invoice = invoiceRepository.save(BeanUtil.toBean(invoiceDto, Invoice.class));
            } else if (invoiceDto.getHaveDetail() && CollUtil.isNotEmpty(invoiceDto.getItems())) {
                jqf.delete(qInvoiceItems)
                        .where(qInvoiceItems.invoiceId.eq(invoice.getId()).and(qInvoiceItems.accountSetsId.eq(invoiceDto.getAccountSetsId())))
                        .execute();
                invoice.setHaveDetail(true);
                if (invoice.getInvoiceBusinessTypeId() == null) {
                    invoice.setInvoiceBusinessTypeId(invoiceDto.getInvoiceBusinessTypeId());
                }
            }

            invoice.setNewCategory(invoiceDto.getNewCategory());

            if (invoiceDto.getHaveDetail() && CollUtil.isNotEmpty(invoiceDto.getItems())) {
                Invoice finalInvoice = invoice;
                itemsRepository.saveAll(invoiceDto.getItems().stream().collect(ArrayList::new, (list, tuple) -> {
                    InvoiceItems items = BeanUtil.toBean(tuple, InvoiceItems.class);
                    items.setAccountSetsId(invoiceDto.getAccountSetsId());
                    items.setInvoiceId(finalInvoice.getId());
                    items.setId(null);
                    list.add(items);
                }, List::addAll));
            }

            invoiceRepository.save(invoice);
        }
    }
}
