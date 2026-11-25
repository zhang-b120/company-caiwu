package cn.gson.financial.timedTasks.service;

import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.dto.InvoiceItemsDto;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.TaxDeclare;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.repo.InvoiceItemsRepository;
import cn.gson.financial.kernel.model.repo.InvoiceRepository;
import cn.gson.financial.kernel.model.vo.InvoiceImportVo;
import cn.gson.financial.kernel.model.vo.InvoiceItemsVo;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.CheckInvoiceService;
import cn.gson.financial.kernel.service.InvoiceService;
import cn.gson.financial.kernel.service.TaxDeclareService;
import cn.gson.financial.kernel.service.TaxService;
import cn.gson.financial.kernel.tool.DateUtils;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.gson.financial.timedTasks.domain.dto.InvoiceTaskDetailDTO;
import cn.gson.financial.timedTasks.domain.req.*;
import cn.gson.financial.timedTasks.domain.resp.TaxDeclareListResp;
import cn.gson.financial.timedTasks.domain.resp.*;
import cn.gson.financial.timedTasks.domain.resp.InvoiceTaxSummaryResp;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.Tuple;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class TimedTaskService extends AbsService {
    private final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;
    private final QInvoice qInvoice = QInvoice.invoice;
    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;
    private final QEmployee qEmployee = QEmployee.employee;
    private final QFinancialProcess qFinancialProcess = QFinancialProcess.financialProcess;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QTimedTaskInvoice qTimedTaskInvoice = QTimedTaskInvoice.timedTaskInvoice;
    private final  QTaxTable qTaxTable = QTaxTable.taxTable;
    private final QTimedTaskDeclareSelfInspection qTimedTaskDeclareSelfInspection = QTimedTaskDeclareSelfInspection.timedTaskDeclareSelfInspection;
    private final QTaxPayer qTaxPayer = QTaxPayer.taxPayer;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CheckInvoiceService checkInvoiceService;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private InvoiceItemsRepository itemsRepository;
    @Autowired
    private TaxService taxService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private TaxDeclareService taxDeclareService;

    final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    public TimeTaskOfInvoiceResp doTask() {
        List<TimeTaskOfInvoiceResp> timeTaskOfInvoiceResp = lazyDao.findBySql("getTask", null, TimeTaskOfInvoiceResp.class);
        if (CollUtil.isEmpty(timeTaskOfInvoiceResp)) {
            return null;
        } else {
            return timeTaskOfInvoiceResp.get(0);
        }
    }

    public Boolean SaveTaxItem(SaveTaxItemReq saveTaxItemReq) {
        List<Map<String, String>> taxItem = saveTaxItemReq.getTableData();
        Map<String, Object> taxItemMap = new HashMap<>();
        taxItemMap.put("accountSetsId", saveTaxItemReq.getAccountSetsId());
        taxService.deleteTaxItem(taxItemMap);
        if ("暂无数据".equals(taxItem.get(0).get("事项名称"))) {
            return Boolean.TRUE;
        }
        //将报税的条目保存在数据库
        for (int j = 0; j < taxItem.size(); j++) {
            TaxDto taxDto = new TaxDto();
            taxDto.setAccountSetsId(saveTaxItemReq.getAccountSetsId());
            taxDto.setCompanyName(saveTaxItemReq.getCompanyName());
            taxDto.setTaxType(taxItem.get(j).get("事项名称"));
            taxDto.setTaxState(taxItem.get(j).get("标签状态"));
            String endTimeStr = taxItem.get(j).get("办理期限");
            taxDto.setFlag(taxItem.get(j).get("flag"));
            taxDto.setTaxEndTime(LocalDate.parse(endTimeStr));
            System.out.println(taxDto.getTaxEndTime());
            taxService.saveTaxItem(taxDto);
        }
        return Boolean.TRUE;

    }
    /**
     * 按季度保存发票
     */
    @Transactional(rollbackFor = Exception.class)
    public List<InvoiceDto> saveImportInvoiceSeason(String creditCode, InputStream multipartFile, Long accountSetId, String invoiceType) {
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
            InvoiceType invoiceTypeEnum = importVo.getInvoiceType(creditCode);
            if (invoiceTypeEnum == null) {
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

            invoiceDto.setType(invoiceTypeEnum);

            // FN-INVOICE-COLLECT-MODE-CONFIG：season 路径=季度模式，归属期落季末月（按季采集）。
            // 月度模式由调用方（saveInvoice 按 isQuarterlyInvoiceCollect 分流）走 saveImportInvoice→真实月。
            LocalDate endDate = DateUtils.getLastDayOfQuarter(importVo.getTradeDate());
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
            if("开具发票".equals(invoiceType)){
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
     */
    @Transactional(rollbackFor = Exception.class)
    public List<InvoiceDto> saveImportInvoice(String creditCode, InputStream multipartFile, Long accountSetsId, String invoiceType) {
        String invoiceBusinessTypeTitle = "销售收入";
        BooleanExpression expression = qInvoiceBusinessType.accountSetsId.eq(accountSetsId);
        expression = expression.and(qInvoiceBusinessType.title.eq(invoiceBusinessTypeTitle));
        Long id = bqf.selectFrom(qInvoiceBusinessType).select(qInvoiceBusinessType.id).where(expression).fetchFirst();
        ExcelReader reader = ExcelUtil.getReader(multipartFile);
        List<String> sheetNames = reader.getSheetNames();
        Assert.isTrue(sheetNames.contains("发票基础信息"), "Excel的sheet名称中必须包含【发票基础信息】,请下载系统模板或使用税务系统导出原始表格~");
        reader.setSheet("发票基础信息");
        ImportVoUtil.setHeaderAlias(InvoiceImportVo.class, reader);
        List<InvoiceImportVo> invoiceImportVos = reader.readAll(InvoiceImportVo.class);
        Assert.isTrue(sheetNames.contains("信息汇总表") || sheetNames.contains("货物清单"), "Excel的sheet名称中必须包含【信息汇总表】或【货物清单】,请下载系统模板或使用税务系统导出原始表格");
        if (sheetNames.contains("信息汇总表")) {
            reader.setSheet("信息汇总表");
        } else {
            reader.setSheet("货物清单");
        }
        reader.clearHeaderAlias();
        ImportVoUtil.setHeaderAlias(InvoiceItemsVo.class, reader);
        List<InvoiceItemsVo> invoiceItemsVos = reader.readAll(InvoiceItemsVo.class);

        // 发票关键字匹配类型
        Map<InvoiceType, List<InvoiceBusinessType>> typeListMap = bqf.selectFrom(qInvoiceBusinessType).where(qInvoiceBusinessType.accountSetsId.eq(accountSetsId)).fetch().stream().filter(it -> CollUtil.isNotEmpty(it.getMatchWords())).collect(Collectors.groupingBy(InvoiceBusinessType::getInvoiceType));

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
                dto.setAccountSetsId(accountSetsId);
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
            InvoiceType invoiceTypeEnum = importVo.getInvoiceType(creditCode);
            if (invoiceTypeEnum == null) {
                // 纳税人识别号与销方/购方均不匹配，跳过当前发票
                return;
            }

            InvoiceDto invoiceDto = new InvoiceDto();
            invoiceDto.setAccountSetsId(accountSetsId);
            invoiceDto.setNewCategory(importVo.getNewCategory());
            invoiceDto.setTradeDate(importVo.getTradeDate());
            invoiceDto.setInvoiceCode(importVo.getInvoiceCode());
            invoiceDto.setInvoiceNumber(StrUtil.emptyToDefault(importVo.getInvoiceNumber(), importVo.getNetNumber()));
            invoiceDto.setState(importVo.getState());

            invoiceDto.setType(invoiceTypeEnum);

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
            if ("开具发票".equals(invoiceType)) {
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
     * 批量保存发票
     * <p>
     * 优化：先批量查出已存在的发票，避免循环中交替 SELECT/INSERT 触发 Hibernate auto-flush，
     * 减少行锁持有时间，防止 Lock wait timeout。
     *
     * @param invoiceDtos
     */
    @Transactional
    public void batchSave(List<InvoiceDto> invoiceDtos) {
        if (invoiceDtos == null || invoiceDtos.isEmpty()) return;

        // 1. 批量预查已存在的发票，按 accountSetsId 分组查询
        Map<String, Invoice> existingMap = new java.util.HashMap<>();
        Map<Long, List<InvoiceDto>> grouped = invoiceDtos.stream()
                .collect(java.util.stream.Collectors.groupingBy(InvoiceDto::getAccountSetsId));
        for (Map.Entry<Long, List<InvoiceDto>> entry : grouped.entrySet()) {
            Long accountSetsId = entry.getKey();
            List<String> numbers = entry.getValue().stream()
                    .map(InvoiceDto::getInvoiceNumber)
                    .filter(n -> n != null && !n.isEmpty())
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            if (numbers.isEmpty()) continue;
            List<Invoice> existing = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetsId)
                            .and(qInvoice.invoiceNumber.in(numbers)))
                    .fetch();
            for (Invoice inv : existing) {
                existingMap.put(accountSetsId + "_" + inv.getInvoiceNumber(), inv);
            }
        }

        // 2. 遍历处理，不再触发交替 SELECT
        for (InvoiceDto invoiceDto : invoiceDtos) {
            String key = invoiceDto.getAccountSetsId() + "_" + invoiceDto.getInvoiceNumber();
            Invoice invoice = existingMap.get(key);
            if (invoice == null) {
                invoice = invoiceRepository.save(BeanUtil.toBean(invoiceDto, Invoice.class));
            } else if (invoiceDto.getHaveDetail() && CollUtil.isNotEmpty(invoiceDto.getItems())) {
                jqf.delete(qInvoiceItems).where(qInvoiceItems.invoiceId.eq(invoice.getId()).and(qInvoiceItems.accountSetsId.eq(invoiceDto.getAccountSetsId()))).execute();
                invoice.setHaveDetail(true);
                if (invoice.getInvoiceBusinessTypeId() == null) {
                    invoice.setInvoiceBusinessTypeId(invoiceDto.getInvoiceBusinessTypeId());
                }
            }

            // 仅当 tradeDate 与 assignmentDate 的月份差绝对值等于0时，才重设 assignmentDate
            LocalDate tradeDate = invoiceDto.getTradeDate();
            LocalDate assignmentDate = invoiceDto.getAssignmentDate();
            if (tradeDate != null && assignmentDate != null) {
                int monthsDiff = Math.abs((assignmentDate.getYear() - tradeDate.getYear()) * 12
                        + (assignmentDate.getMonthValue() - tradeDate.getMonthValue()));
                if (monthsDiff <=3 && ObjectUtils.isEmpty(invoice.getVoucherId())) {
                    invoice.setAssignmentDate(assignmentDate);
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

    public void saveGouXuanInvoice(InputStream fileInputStream, String invoiceType) {
        try {
            // 加载Excel文件
            Workbook workbook = new XSSFWorkbook(fileInputStream);
            // 获取第一个工作表（假设数据在第一个工作表）
            Sheet sheet = workbook.getSheetAt(0);

            // 3. 数据格式化工具
            DataFormatter dataFormatter = new DataFormatter();
            String checkDate = dataFormatter.formatCellValue(sheet.getRow(1).getCell(5));
            checkDate = checkDate.substring(0, 4) + "-" + checkDate.substring(4, 6) + "-01";
            // 4. 遍历行（跳过标题行）
            for (Row row : sheet) {
                Invoice invoice = new Invoice();
                if (row.getRowNum() < 3) continue;
                // 5. 读取单元格数据
                String code = dataFormatter.formatCellValue(row.getCell(4));

                String code1 = dataFormatter.formatCellValue(row.getCell(6));
                invoice.setAssignmentDate(LocalDate.parse(checkDate));
                invoice.setShowStatus(invoiceType);
                invoice.setInvoiceNumber(code);
                invoice.setInvoiceNumber1(code1);
                if (StringUtils.isNotEmpty(checkDate)) {
                    invoice.setCheckRiQi(LocalDate.parse(checkDate));
                    checkInvoiceService.updateByInvoiceNumber(invoice);
                }
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public void saveWeiGouXuanInvoice(InputStream fileInputStream, String invoiceType) {
        try {
            // 加载Excel文件
            Workbook workbook = new XSSFWorkbook(fileInputStream);
            // 获取第一个工作表（假设数据在第一个工作表）
            Sheet sheet = workbook.getSheetAt(0);

            // 3. 数据格式化工具
            DataFormatter dataFormatter = new DataFormatter();
            // 4. 遍历行（跳过标题行）
            for (Row row : sheet) {
                Invoice invoice = new Invoice();
                if (row.getRowNum() < 1) continue;
                // 5. 读取单元格数据

                String code = dataFormatter.formatCellValue(row.getCell(2));

                String code1 = dataFormatter.formatCellValue(row.getCell(4));
                invoice.setShowStatus(invoiceType);
                invoice.setInvoiceNumber(code);
                invoice.setInvoiceNumber1(code1);
                checkInvoiceService.updateByInvoiceNumber(invoice);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateInvoiceTask(UpdateStateOfInvoiceReq req) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", req.getTaskId());
        params.put("result", req.getResult());
        params.put("state", req.getState());
        params.put("failureReason", req.getFailureReason());
        lazyDao.executeSql("updateInvoiceTask", params);
        if(CollUtil.isNotEmpty(req.getResultDTOList())){
            req.getResultDTOList().forEach(item -> {
                Map<String, Object> resutlParams = new HashMap<>();
                resutlParams.put("timedTaskInvoiceId", req.getTaskId());
                resutlParams.put("invoiceType", item.getInvoiceType());
                resutlParams.put("count", item.getCount());
                resutlParams.put("amount", item.getAmount());
                resutlParams.put("reason", item.getReason());
                resutlParams.put("taxAmount", item.getTaxAmount());
                resutlParams.put("invoiceImg",item.getInvoiceImg());
                lazyDao.executeSql("insertInvoiceTaskResult", resutlParams);
            });
        }
    }

    public List<TimedTaskOfTaxResp> selectTimedTaskOfTax() {
        List<TimedTaskOfTaxResp> list = lazyDao.findBySql("selectTimeTaskOfTax", null, TimedTaskOfTaxResp.class);
        return list;
    }


    @Transactional(rollbackFor = Exception.class)
    public void updateTaskOfTaxStatus(UpdateStateOfTaxReq updateStateOfTaxReq) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", updateStateOfTaxReq.getId());
        params.put("result", updateStateOfTaxReq.getResult());
        params.put("state", updateStateOfTaxReq.getState());
        if (StrUtil.isNotEmpty(updateStateOfTaxReq.getFailureReason())) {
            params.put("failureReason", updateStateOfTaxReq.getFailureReason());
        }
        if (StrUtil.isNotEmpty(updateStateOfTaxReq.getImgUrl())) {
            params.put("imgUrl", updateStateOfTaxReq.getImgUrl());
        }
        lazyDao.executeSql("updateTaskOfTax", params);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTaskOfTaxItemStatus(UpdateStateOfTaxItemReq updateStateOfTaxItemReq) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", updateStateOfTaxItemReq.getId());
        params.put("result", updateStateOfTaxItemReq.getResult());
        params.put("state", updateStateOfTaxItemReq.getState());
        if (StrUtil.isNotEmpty(updateStateOfTaxItemReq.getFailureReason())) {
            params.put("failureReason", updateStateOfTaxItemReq.getFailureReason());
        }
        if (StrUtil.isNotEmpty(updateStateOfTaxItemReq.getImgUrl())) {
            params.put("imgUrl", updateStateOfTaxItemReq.getImgUrl());
        }
        lazyDao.executeSql("updateTaskOfTaxItem", params);
    }

    /**
     * 税款信息：无 id 则新增，有 id 则修改
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTaxPayment(UpdateTaxPaymentReq updateTaxPaymentReq) {
        Long id = updateTaxPaymentReq.getId();
        if (id == null) {
            // 新增
            Map<String, Object> params = new HashMap<>();
            params.put("accountSetsId", updateTaxPaymentReq.getAccountSetsId());
            params.put("checkDate", updateTaxPaymentReq.getCheckDate());
            params.put("result", updateTaxPaymentReq.getResult());
            params.put("state", updateTaxPaymentReq.getState());
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getImgUrl())) {
                params.put("imgUrl", updateTaxPaymentReq.getImgUrl());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getTotalTaxes())) {
                params.put("totalTaxes", updateTaxPaymentReq.getTotalTaxes());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getTaxPaymentHtml())) {
                params.put("taxPaymentHtml", updateTaxPaymentReq.getTaxPaymentHtml());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getFailureReason())) {
                params.put("failureReason", updateTaxPaymentReq.getFailureReason());
            }
            lazyDao.executeSql("insertTaxPayment", params);
        } else {
            // 修改
            Map<String, Object> params = new HashMap<>();
            params.put("id", id);
            params.put("result", updateTaxPaymentReq.getResult());
            params.put("state", updateTaxPaymentReq.getState());
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getImgUrl())) {
                params.put("imgUrl", updateTaxPaymentReq.getImgUrl());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getTotalTaxes())) {
                params.put("totalTaxes", updateTaxPaymentReq.getTotalTaxes());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getTaxPaymentHtml())) {
                params.put("taxPaymentHtml", updateTaxPaymentReq.getTaxPaymentHtml());
            }
            if (StrUtil.isNotEmpty(updateTaxPaymentReq.getFailureReason())) {
                params.put("failureReason", updateTaxPaymentReq.getFailureReason());
            }
            lazyDao.executeSql("updateTaxPayment", params);
        }
    }

    public String getSpecialCount(Map<String, String> params) {
        Map<String, Object> objectMap = new HashMap<>(params);
        if (lazyDao.getCount("invoiceCount", objectMap) == 0) {
            return "UNEXIST";
        } else {
            return "EXIST";
        }
    }

    public long getEmployeecount(Long accountSetsId) {
        long ll = bqf.selectFrom(qEmployee)
                .select(qEmployee.count())
                .where(qEmployee.accountSetsId.eq(accountSetsId).and(qEmployee.status.eq("在职")))
                .fetchFirst();
        return  ll;
    }

    public Map<String, String> getInvoiceAmount(Long accountSetsId) {
        //获取当前日期的上一季度
        int startMonth;
        LocalDate inputDate = LocalDate.now();
        int month = inputDate.getMonthValue();
        if (month >= 1 && month <= 3) {
            startMonth = 10;
        } else if (month >= 4 && month <= 6) {
            startMonth = 1;
        } else if (month >= 7 && month <= 9) {
            startMonth = 4;
        } else {
            startMonth = 7;
        }
        int year = inputDate.getYear();
        if (startMonth > 9) {
            year -= 1;
        }
        LocalDate startQuarter = LocalDate.of(year, startMonth, 1);
        BigDecimal outAddInInvoiceAmount = BigDecimal.ZERO;
        BigDecimal outInvoiceAmount = invoiceService.getOutInvoiceAmount(accountSetsId, startQuarter);
        BigDecimal outInvoiceAmountJianZhu = invoiceService.getOutInvoiceAmountJianZhu(accountSetsId, startQuarter);
        BigDecimal inInvoiceAmount = invoiceService.getInInvoiceAmount(accountSetsId, startQuarter);
        //如果inInvoiceAmount金额大于50000的话就加入进项金额不大于五万的话就不加入
        if (inInvoiceAmount.compareTo(new BigDecimal(50000)) > 0) {
            outAddInInvoiceAmount = outInvoiceAmount.add(inInvoiceAmount);
        } else {
            outAddInInvoiceAmount = outInvoiceAmount;
        }
        HashMap<String, String> InvoiceAmountMap = new HashMap<>();
        InvoiceAmountMap.put("outAddInInvoiceAmount", outAddInInvoiceAmount.toString());
        InvoiceAmountMap.put("outInvoiceAmountJianZhu", outInvoiceAmountJianZhu.toString());
        return InvoiceAmountMap;

    }

    @Transactional
    public void insertTaxReportResult(TaxReportResult result) {
        Map<String, Object> params = new HashMap<>();
        params.put("accountSetsId", result.getAccountSetId());
        lazyDao.executeSql("deleteTaxReportResult", result);
        lazyDao.executeSql("insertTaxReportResult", result);
    }

    public TimedTaskOfPaymentResp getTaxPaymentCompany() {
        List<TimedTaskOfPaymentResp> timedTaskOfPaymentResp = lazyDao.findBySql("getTaxPaymentCompany", null, TimedTaskOfPaymentResp.class);
        if (CollUtil.isEmpty(timedTaskOfPaymentResp)) {
            return null;
        } else {
            return timedTaskOfPaymentResp.get(0);
        }
    }

    /**
     * 根据账期查询报税任务表
     * 查询逻辑：
     * 1. 如果account_sets_id和tax_type都相同，则认为是同一条任务
     * 2. 因为id是递增的，要选取同一条任务中id最大的那条任务
     * 3. 只查询state为0的记录
     * 4. 排序规则：先按省份排序，再按企业（account_sets_id）排序，最后按税种的sort排序
     *    这样可以保证同一省份和企业的任务集中在一起，避免省份和企业来回切换
     * 5. 只返回一条记录
     */
    public TimedTaskTaxWithCreditCodeResp selectTimedTaskTaxByCheckDate(LocalDate checkDate, String province) {
        Map<String, Object> params = new HashMap<>();
        params.put("checkDate", checkDate);
        params.put("province", province);
        List<TimedTaskTaxWithCreditCodeResp> list = lazyDao.findBySql("selectTimedTaskTaxByCheckDate", params, TimedTaskTaxWithCreditCodeResp.class);
        if (CollUtil.isEmpty(list)) {
            return null;
        } else {
            return list.get(0);
        }
    }

    /**
     * 根据账期查询报税项任务表，每个账套id组中id最大的记录，只查询state为0的记录，只返回一条
     */
    public TimedTaskTaxItemWithCreditCodeResp selectTimedTaskTaxItemByCheckDate(LocalDate checkDate, String province) {
        Map<String, Object> params = new HashMap<>();
        params.put("checkDate", checkDate);
        params.put("province", province);
        List<TimedTaskTaxItemWithCreditCodeResp> list = lazyDao.findBySql("selectTimedTaskTaxItemByCheckDate", params, TimedTaskTaxItemWithCreditCodeResp.class);
        if (CollUtil.isEmpty(list)) {
            return null;
        } else {
            return list.get(0);
        }
    }

    /**
     * 根据账期查询税款表，每个账套id组中id最大的记录，只查询state为0的记录，只返回一条
     */
    public TimedTaskPaymentWithCreditCodeResp selectTimedTaskPaymentByCheckDate(LocalDate checkDate, String province) {
        Map<String, Object> params = new HashMap<>();
        params.put("checkDate", checkDate);
        params.put("province", province);
        List<TimedTaskPaymentWithCreditCodeResp> list = lazyDao.findBySql("selectTimedTaskPaymentByCheckDate", params, TimedTaskPaymentWithCreditCodeResp.class);
        if (CollUtil.isEmpty(list)) {
            return null;
        } else {
            return list.get(0);
        }
    }

    /**
     * 检查报税状态
     * @param checkDate 账期
     * @param accountSetsId 账套ID
     * @param taxType 税种
     * @return 检查结果：1表示已申报（报税任务result为1或报税表tax_state为"已申报"），0表示未申报
     */
    public CheckTaxStatusResp checkTaxStatus(LocalDate checkDate, Long accountSetsId, String taxType) {
        CheckTaxStatusResp resp = new CheckTaxStatusResp();

        // 查询报税任务表的result字段
        Map<String, Object> taskParams = new HashMap<>();
        taskParams.put("checkDate", checkDate);
        taskParams.put("accountSetsId", accountSetsId);
        taskParams.put("taxType", taxType);
        List<TimedTaskTaxResultResp> taskResultList = lazyDao.findBySql("selectTimedTaskTaxResult", taskParams, TimedTaskTaxResultResp.class);
        boolean taskResultSuccess = false;
        if (CollUtil.isNotEmpty(taskResultList) && taskResultList.get(0).getResult() != null) {
            taskResultSuccess = taskResultList.get(0).getResult() == 1;
        }

        // 查询报税表的tax_state字段
        Map<String, Object> taxTableParams = new HashMap<>();
        taxTableParams.put("accountSetsId", accountSetsId);
        taxTableParams.put("taxType", taxType);
        taxTableParams.put("currentDate", LocalDate.now());
        List<TaxTableStateResp> taxTableStateList = lazyDao.findBySql("selectTaxTableState", taxTableParams, TaxTableStateResp.class);
        boolean taxStateDeclared = false;
        if (CollUtil.isNotEmpty(taxTableStateList) && taxTableStateList.get(0).getTaxState() != null) {
            taxStateDeclared = "已申报".equals(taxTableStateList.get(0).getTaxState());
        }

        // 如果报税任务result为1或者报税表tax_state为"已申报"，则返回1，否则返回0
        if (taskResultSuccess || taxStateDeclared) {
            resp.setStatus(1);
        } else {
            resp.setStatus(0);
        }

        return resp;
    }

    /**
     * 根据账套id查询账套的增值税类型
     */
    public VatType getTaxType(Long accountSetsId) {
        return jqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).select(qAccountSets.vatType).fetchFirst();
    }

    /**
     * FN-INVOICE-COLLECT-MODE-CONFIG：本账套发票是否按季归集（小规模 且 采集模式=季度）。
     * 一般纳税人/账套不存在 → false（按月）。统一判据，替代散落的 vatType==SMALL_SCALE 决定按季的逻辑。
     */
    public boolean isQuarterlyInvoiceCollect(Long accountSetsId) {
        AccountSets as = jqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
        return as != null && as.isQuarterlyInvoiceCollect();
    }
    /**
     * 根据账套id和报税日期删除相应的记录，重新下载发票
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteTimeTaskInvoiceByAccountSetsId(Long accountSetsId,String period){
        try {
            // 解析period参数，假设格式为 "yyyy-MM"
            SimpleDateFormat periodFormat = new SimpleDateFormat("yyyy-MM");
            Date periodDate = periodFormat.parse(period);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(periodDate);
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1; // Calendar的月份从0开始

            jqf.delete(qTimedTaskInvoice)
                    .where(qTimedTaskInvoice.accountSetsId.eq(accountSetsId)
                            .and(qTimedTaskInvoice.checkDate.year().eq(year))
                            .and(qTimedTaskInvoice.checkDate.month().eq(month)))
                    .execute();
        } catch (Exception e) {
            log.error("删除定时任务发票记录失败", e);
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
@Transactional(rollbackFor = Exception.class)
    public void deleteTaskOfInvoice() {
        jqf.delete(qTimedTaskInvoice).where(qTimedTaskInvoice.failureReason.notLike("%不是办税员%").and(qTimedTaskInvoice.result.eq(1))).execute();
    }
@Transactional(rollbackFor = Exception.class)
    public void updateProcess() {
//        List<FinancialProcess>   selectProcess = lazyDao.findBySql("selectProcess",new HashMap<>(), FinancialProcess.class);
//        selectProcess.forEach(item->{
//            //根据其中的账套id修改
//            jqf.update(qFinancialProcess).set(qFinancialProcess.isTicketObtained,item.getIsTicketObtained()).set(qFinancialProcess.isTaxItemCompleted,item.getIsTaxItemCompleted()).where(qFinancialProcess.accountingPeriod.eq("2025-09-01").and(qFinancialProcess.accountSetsId.eq(item.getAccountSetsId()))).execute();
//        });
            bqf.selectFrom(qFinancialProcess).select(qFinancialProcess.accountSetsId).where(qFinancialProcess.accountingPeriod.eq("2025-09-01")).groupBy(qFinancialProcess.accountSetsId).fetch().forEach(
                    item->{
                        FinancialProcess financialProcess = bqf.selectFrom(qFinancialProcess).where(qFinancialProcess.accountSetsId.eq(item).and(qFinancialProcess.accountingPeriod.eq("2025-09-01"))).fetchFirst();
                        jqf.delete(qFinancialProcess).where(qFinancialProcess.accountSetsId.eq(financialProcess.getAccountSetsId()).and(qFinancialProcess.accountingPeriod.eq("2025-09-01")).and(qFinancialProcess.id.notIn(financialProcess.getId()))).execute();
                    }
            );
    }

    /**
     * 添加发票任务
     *
     * @param req
     * @return
     */
    public Boolean addTimedTaskInvoice(AddTimedTaskInvoiceReq req) {
        if (ObjectUtils.isEmpty(req)
                || CollUtil.isEmpty(req.getAccountSetsIdList())
                || ObjectUtils.isEmpty(req.getCheckDate())) {
            return Boolean.FALSE;
        }
        // 使用子查询实现：按 accountSetsId 和 checkDate 分组，获取每组中 id 最大的记录
        // 使用 id.in() 方式，子查询只执行一次，效率高（等价于 INNER JOIN 的效果）
        QTimedTaskInvoice qMax = new QTimedTaskInvoice("max");
        List<TimedTaskInvoice> taskInvoiceList = bqf.selectFrom(qTimedTaskInvoice)
                .where(qTimedTaskInvoice.accountSetsId.in(req.getAccountSetsIdList())
                        .and(qTimedTaskInvoice.checkDate.eq(req.getCheckDate()))
                        .and(qTimedTaskInvoice.result.eq(0))
                        .and(qTimedTaskInvoice.id.in(
                                bqf.selectFrom(qMax)
                                        .select(qMax.id.max())
                                        .where(qMax.accountSetsId.in(req.getAccountSetsIdList()))
                                        .groupBy(qMax.accountSetsId, qMax.checkDate)
                        )))
                .orderBy(qTimedTaskInvoice.id.desc())
                .fetch();
        Map<Long, TimedTaskInvoice> taskInvoice = CollUtil.isEmpty(taskInvoiceList)
                ? Collections.emptyMap()
                : taskInvoiceList.stream().collect(Collectors.toMap(
                        TimedTaskInvoice::getAccountSetsId,
                        v -> v,
                        (existing, replacement) -> existing)); // 保留第一个（最新的，因为已按id降序排序）
        req.getAccountSetsIdList().forEach(item -> {
            if(ObjectUtils.isNotEmpty(taskInvoice.getOrDefault(item, null))){
                return;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("accountSetsId", item);
            params.put("result", 0);
            params.put("state", 0);
            params.put("checkDate", req.getCheckDate());
            params.put("createTime", new Date());
            lazyDao.executeSql("insertInvoiceTask", params);
        });
        return Boolean.TRUE;
    }


    //获取每个账套发票的下载情况
    public List<GetInvoiceTaskDetailResp> getInvoiceTaskDetail(List<AccountSets> accountSetsList, String searchDate, String companyName, String vatType, Integer result, String province) {
        // 校验过滤
        if (StringUtils.isEmpty(searchDate)) {
            return Collections.emptyList();
        }
        if (StringUtils.isNotEmpty(companyName)) {
            accountSetsList = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.companyName.like("%" + companyName + "%"))
                    .fetch();
        }
        if (CollUtil.isEmpty(accountSetsList)) {
            return Collections.emptyList();
        }
        Stream<AccountSets> stream = accountSetsList.stream();
        List<AccountSets> filteredAccountSets = new ArrayList<>();
        List<AccountSets> list = stream
                .filter(Objects::nonNull)
                .filter(f -> vatType.equals(f.getVatType().name()))
                .filter(f -> f.getId() != null)
                .filter(f->f.getDeactivated().equals(false)).toList();
        if(StringUtils.isNotEmpty(province)){
            filteredAccountSets = list.stream().filter(f -> province.equals(f.getProvince())).toList();
        }else {
            filteredAccountSets = list;
        }
        if (CollUtil.isEmpty(filteredAccountSets)) {
            return Collections.emptyList();
        }

        Set<Long> accountSetsIdSet = filteredAccountSets.stream()
                .map(AccountSets::getId)
                .collect(Collectors.toSet());

        Map<String, Object> params = new HashMap<>();
        params.put("accountSetsId", accountSetsIdSet);
        params.put("searchDate", searchDate);
        if(ObjectUtils.isNotEmpty(result) && result!=3  ) {
            params.put("result", result);
        }
        params.put("province", province);

        List<InvoiceTaskDetailDTO> detailDTOList = lazyDao.findBySql("getInvoiceTaskDetail", params, InvoiceTaskDetailDTO.class);

        // 解析账期，获取月份的第一天和最后一天
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        try {
            // 尝试解析不同格式的日期
            LocalDate periodDate = null;
            if (searchDate.length() == 8) {
                // YYYYMMDD 格式
                periodDate = LocalDate.parse(searchDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
            } else if (searchDate.length() >= 10) {
                // YYYY-MM-DD 格式
                periodDate = LocalDate.parse(searchDate.substring(0, 10));
            }
            if (periodDate != null) {
                periodStart = periodDate.with(TemporalAdjusters.firstDayOfMonth());
                periodEnd = periodDate.with(TemporalAdjusters.lastDayOfMonth());
            }
        } catch (Exception e) {
            log.warn("解析账期失败: {}", searchDate, e);
        }

        // 批量查询系统发票信息，避免循环中多次访问数据库
        final LocalDate finalPeriodStart = periodStart;
        final LocalDate finalPeriodEnd = periodEnd;
        if (finalPeriodStart != null && finalPeriodEnd != null && CollUtil.isNotEmpty(detailDTOList)) {
            // 收集所有需要查询的账套ID
            Set<Long> accountSetsIds = detailDTOList.stream()
                    .filter(item -> item.getAccountSetsId() != null && StringUtils.isNotEmpty(item.getInvoiceType()))
                    .map(InvoiceTaskDetailDTO::getAccountSetsId)
                    .collect(Collectors.toSet());

            if (CollUtil.isNotEmpty(accountSetsIds)) {
                // 批量查询：使用tradeDate的发票（销项票、进项票）
                Map<String, Tuple> tradeDateStatsMap;
                try {
                    List<Tuple> tradeDateStats = bqf.selectFrom(qInvoice)
                            .select(
                                    qInvoice.accountSetsId,
                                    qInvoice.type,
                                    qInvoice.count(),
                                    qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                                    qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                            )
                            .where(qInvoice.accountSetsId.in(accountSetsIds)
                                    .and(qInvoice.tradeDate.between(finalPeriodStart, finalPeriodEnd)))
                            .groupBy(qInvoice.accountSetsId, qInvoice.type)
                            .fetch();

                    tradeDateStatsMap = tradeDateStats.stream().collect(Collectors.toMap(
                            tuple -> tuple.get(qInvoice.accountSetsId) + "_" + tuple.get(qInvoice.type),
                            tuple -> tuple,
                            (k, v) -> v
                    ));
                } catch (Exception e) {
                    log.warn("批量查询tradeDate发票统计信息失败: {}", e.getMessage());
                    tradeDateStatsMap = Collections.emptyMap();
                }

                // 批量查询：使用assignmentDate的进项票（已勾选）- 限制账期范围
                Map<String, Tuple> certifiedStatsMap;
                try {
                    List<Tuple> certifiedStats = bqf.selectFrom(qInvoice)
                            .select(
                                    qInvoice.accountSetsId,
                                    qInvoice.type,
                                    qInvoice.showStatus,
                                    qInvoice.count(),
                                    qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                                    qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                            )
                            .where(qInvoice.accountSetsId.in(accountSetsIds)
                                    .and(qInvoice.type.eq(InvoiceType.IN))
                                    .and(qInvoice.assignmentDate.between(finalPeriodStart, finalPeriodEnd))
                                    .and(qInvoice.showStatus.eq("已勾选")))
                            .groupBy(qInvoice.accountSetsId, qInvoice.type, qInvoice.showStatus)
                            .fetch();

                    certifiedStatsMap = certifiedStats.stream().collect(Collectors.toMap(
                            tuple -> tuple.get(qInvoice.accountSetsId) + "_" + tuple.get(qInvoice.type) + "_" + tuple.get(qInvoice.showStatus),
                            tuple -> tuple,
                            (k, v) -> v
                    ));
                } catch (Exception e) {
                    log.warn("批量查询已勾选发票统计信息失败: {}", e.getMessage());
                    certifiedStatsMap = Collections.emptyMap();
                }

                // 批量查询：未勾选的进项票 - 不限制账期，统计该账套下所有未勾选票
                Map<String, Tuple> uncertifiedStatsMap;
                try {
                    List<Tuple> uncertifiedStats = bqf.selectFrom(qInvoice)
                            .select(
                                    qInvoice.accountSetsId,
                                    qInvoice.type,
                                    qInvoice.showStatus,
                                    qInvoice.count(),
                                    qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                                    qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                            )
                            .where(qInvoice.accountSetsId.in(accountSetsIds)
                                    .and(qInvoice.type.eq(InvoiceType.IN))
                                    .and(qInvoice.showStatus.eq("未勾选")))
                            .groupBy(qInvoice.accountSetsId, qInvoice.type, qInvoice.showStatus)
                            .fetch();

                    uncertifiedStatsMap = uncertifiedStats.stream().collect(Collectors.toMap(
                            tuple -> tuple.get(qInvoice.accountSetsId) + "_" + tuple.get(qInvoice.type) + "_" + tuple.get(qInvoice.showStatus),
                            tuple -> tuple,
                            (k, v) -> v
                    ));
                } catch (Exception e) {
                    log.warn("批量查询未勾选发票统计信息失败: {}", e.getMessage());
                    uncertifiedStatsMap = Collections.emptyMap();
                }

                // 合并已勾选和未勾选的统计结果
                Map<String, Tuple> assignmentDateStatsMap = new HashMap<>();
                assignmentDateStatsMap.putAll(certifiedStatsMap);
                assignmentDateStatsMap.putAll(uncertifiedStatsMap);

                // 创建final变量供lambda使用
                final Map<String, Tuple> finalTradeDateStatsMap = tradeDateStatsMap;
                final Map<String, Tuple> finalAssignmentDateStatsMap = assignmentDateStatsMap;

                // 为每个item设置统计结果
                // 只统计任务表中有数据的发票类型（用于前端数据不匹配比较）
                detailDTOList.forEach(item -> {
                    if (StringUtils.isEmpty(item.getInvoiceType()) || item.getAccountSetsId() == null) {
                        return;
                    }

                    // 检查任务表中是否有数据，如果没有数据则不统计系统数据
                    Integer taskCount = item.getCount();
                    BigDecimal taskAmount = item.getAmount();
                    BigDecimal taskTaxAmount = item.getTaxAmount();

                    // 如果任务表中的count、amount、taxAmount都为0或null，则不统计系统数据
                    boolean hasTaskData = (taskCount != null && taskCount > 0) ||
                                        (taskAmount != null && taskAmount.compareTo(BigDecimal.ZERO) > 0) ||
                                        (taskTaxAmount != null && taskTaxAmount.compareTo(BigDecimal.ZERO) > 0);

                    if (!hasTaskData) {
                        // 任务表中没有数据，不统计系统数据，保持默认值0
                        item.setSystemCount(0);
                        item.setSystemAmount(BigDecimal.ZERO);
                        item.setSystemTaxAmount(BigDecimal.ZERO);
                        return;
                    }

                    String invoiceTypeStr = item.getInvoiceType();
                    Tuple statsTuple = null;

                    if ("销项票".equals(invoiceTypeStr)) {
                        statsTuple = finalTradeDateStatsMap.get(item.getAccountSetsId() + "_" + InvoiceType.OUT);
                    } else if ("进项票".equals(invoiceTypeStr)) {
                        statsTuple = finalTradeDateStatsMap.get(item.getAccountSetsId() + "_" + InvoiceType.IN);
                    } else if ("进项票-已勾选".equals(invoiceTypeStr)) {
                        statsTuple = finalAssignmentDateStatsMap.get(item.getAccountSetsId() + "_" + InvoiceType.IN + "_已勾选");
                    } else if ("进项票-未勾选".equals(invoiceTypeStr)) {
                        statsTuple = finalAssignmentDateStatsMap.get(item.getAccountSetsId() + "_" + InvoiceType.IN + "_未勾选");
                    }

                    if (statsTuple != null) {
                        Long count = statsTuple.get(qInvoice.count());
                        BigDecimal amountSum = statsTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO));
                        BigDecimal taxAmountSum = statsTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO));

                        // 统一精度，金额和税额保留2位小数
                        if (amountSum != null) {
                            amountSum = amountSum.setScale(2, RoundingMode.HALF_UP);
                        }
                        if (taxAmountSum != null) {
                            taxAmountSum = taxAmountSum.setScale(2, RoundingMode.HALF_UP);
                        }

                        item.setSystemCount(count != null ? count.intValue() : 0);
                        item.setSystemAmount(amountSum != null ? amountSum : BigDecimal.ZERO);
                        item.setSystemTaxAmount(taxAmountSum != null ? taxAmountSum : BigDecimal.ZERO);
                    } else {
                        // 没有查询到数据，设置默认值
                        item.setSystemCount(0);
                        item.setSystemAmount(BigDecimal.ZERO);
                        item.setSystemTaxAmount(BigDecimal.ZERO);
                    }
                });
            }
        }
        // 确保所有detailDTOList中的字段不为null，为空时返回0
        if (CollUtil.isNotEmpty(detailDTOList)) {
            detailDTOList.forEach(item -> {
                if (item.getCount() == null) {
                    item.setCount(0);
                }
                if (item.getAmount() == null) {
                    item.setAmount(BigDecimal.ZERO);
                }
                if (item.getTaxAmount() == null) {
                    item.setTaxAmount(BigDecimal.ZERO);
                }
            });
        }

        Map<Long, List<InvoiceTaskDetailDTO>> detailMap = CollUtil.isEmpty(detailDTOList)
                ? Collections.emptyMap()
                : detailDTOList.stream().collect(Collectors.groupingBy(InvoiceTaskDetailDTO::getAccountSetsId));

        // 根据result参数选择构建策略
        // 1. result 为空：返回所有账套的任务下载情况（包含已在任务表中的）
        if (ObjectUtils.isEmpty(result)) {
            return filteredAccountSets.stream()
                    .map(item -> buildRespFromAccountItem(item, detailMap.getOrDefault(item.getId(), Collections.emptyList())))
                    .toList();
        }

        // 2. result == 3：返回“尚未加入任务表”的账套（排除已在任务表中的）
        if (Integer.valueOf(3).equals(result)) {
            return filteredAccountSets.stream()
                    .filter(item -> !detailMap.containsKey(item.getId()))
                    .map(item -> buildRespFromAccountItem(item, Collections.emptyList()))
                    .toList();
        }

        // 3. 其它 result：只返回任务表中已有记录的账套
        return detailMap.values().stream()
                .filter(CollUtil::isNotEmpty)
                .map(this::buildRespFromDetailList)
                .toList();
    }
    /**
     * 获取发票销项/进项汇总信息
     *
     * @param userId              当前用户ID
     * @param startAssignmentDate 发票分配开始日期（yyyy-MM-dd）
     * @param endAssignmentDate   发票分配结束日期（yyyy-MM-dd）
     * @param checkDate           取票任务账期（yyyy-MM-dd HH:mm:ss 或数据库实际格式）
     * @param vatType             增值税类型（如 COMMONLY）
     * @return 按账套汇总的发票情况列表
     */
    public List<InvoiceTaxSummaryResp> getInvoiceTaxSummary(Long userId,
                                                            String startAssignmentDate,
                                                            String endAssignmentDate,
                                                            String checkDate,
                                                            String vatType) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("startAssignmentDate", startAssignmentDate);
        params.put("endAssignmentDate", endAssignmentDate);
        params.put("checkDate", checkDate);
        params.put("vatType", vatType);
        return lazyDao.findBySql("getInvoiceTaxSummary", params, InvoiceTaxSummaryResp.class);
    }
    /**
     * 排除已经
     */

    /**
     * 构建返回参数(accountSet)
     * @param account
     * @param detailList
     * @return
     */
    private GetInvoiceTaskDetailResp buildRespFromAccountItem(AccountSets account, List<InvoiceTaskDetailDTO> detailList) {
        GetInvoiceTaskDetailResp resp = new GetInvoiceTaskDetailResp();
        resp.setAccountSetsId(account.getId());
        resp.setCompanyName(account.getCompanyName());
        if (CollUtil.isNotEmpty(detailList)) {
            // 确保字段不为null
            detailList.forEach(item -> {
                if (item.getCount() == null) {
                    item.setCount(0);
                }
                if (item.getAmount() == null) {
                    item.setAmount(BigDecimal.ZERO);
                }
                if (item.getTaxAmount() == null) {
                    item.setTaxAmount(BigDecimal.ZERO);
                }
            });
            resp.setFailReason(detailList.get(0).getFailureReason());
            resp.setResult(detailList.get(0).getResult());
            resp.setDetailDTOList(detailList);
        } else {
            resp.setDetailDTOList(Collections.emptyList());
        }
        return resp;
    }

    /**
     * 构建返回参数（InvoiceTaskDetailDTO）
     * @param detailList
     * @return
     */
    private GetInvoiceTaskDetailResp buildRespFromDetailList(List<InvoiceTaskDetailDTO> detailList) {
        GetInvoiceTaskDetailResp resp = new GetInvoiceTaskDetailResp();
        resp.setAccountSetsId(detailList.get(0).getAccountSetsId());
        resp.setCompanyName(detailList.get(0).getCompanyName());
        resp.setResult(detailList.get(0).getResult());
        resp.setFailReason(detailList.get(0).getFailureReason());
        // 确保字段不为null
        detailList.forEach(item -> {
            if (item.getCount() == null) {
                item.setCount(0);
            }
            if (item.getAmount() == null) {
                item.setAmount(BigDecimal.ZERO);
            }
            if (item.getTaxAmount() == null) {
                item.setTaxAmount(BigDecimal.ZERO);
            }
        });
        resp.setDetailDTOList(detailList);
        return resp;
    }
    /**
     * 读取获取自检项的任务
     * @param
     * @return
     */
    /*
        任务是根据账套主表默认是当前月份，然后关联自检表来获取自检任务，返回给rpautil,传入的参数
        包括税号  ，账套id  ，任务id, 省份，增值税类型
     */
    public TimedTaskDeclareSelfInspectionResp getSelfInspectionTask(LocalDate period) {
        /**
         * 查询传入账期的自检任务
         */
        Map<String, Object> params = new HashMap<>();
        params.put("period", period);
        List<TimedTaskDeclareSelfInspectionResp> timedTaskDeclareSelfInspectionResp = lazyDao.findBySql("getSelfInspectionTask", params , TimedTaskDeclareSelfInspectionResp.class);
        /**
         *  如果没有自检任务，则返回空
         */
        if (CollUtil.isEmpty(timedTaskDeclareSelfInspectionResp)) {
            return null;
        } else {
            return timedTaskDeclareSelfInspectionResp.get(0);
        }
    }
    /**
     * 更新自检项的任务的状态
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateSelfInspectionTaskStatus(TimedTaskDeclareSelfInspectionReq req) {
        /**
         * 先根据账期和账套id查询自检任务是否有重复，有重复则删除
         */
            jqf.delete(qTimedTaskDeclareSelfInspection).where(qTimedTaskDeclareSelfInspection.period.eq(req.getPeriod()).and(qTimedTaskDeclareSelfInspection.accountSetsId.eq(req.getAccountSetsId()))).execute();
        /**
         * 然后将传入的数据转化为实体类，保存到数据库中
         */
        TimedTaskDeclareSelfInspection timedTaskDeclareSelfInspection = new TimedTaskDeclareSelfInspection();
        timedTaskDeclareSelfInspection.setCreditCode(req.getCreditCode());
        timedTaskDeclareSelfInspection.setImgUrl(req.getImgUrl());
        timedTaskDeclareSelfInspection.setProblemDescription(req.getProblemDescription());
        timedTaskDeclareSelfInspection.setPeriod(req.getPeriod());
        timedTaskDeclareSelfInspection.setAccountSetsId(req.getAccountSetsId());
        lazyDao.executeSql("insertSelfInspectionTaskStatus", timedTaskDeclareSelfInspection.toMap());

        return Boolean.TRUE;
    }

    /**
     * 查询需要检测办税的企业信息 - 手机号1
     * @return 只返回第一条数据，如果没有数据则返回null
     */
    public TaxPayerStatusResp getTaxPayer1Status(){
        List<TaxPayerStatusResp> taxPayerStatusResp = lazyDao.findBySql("getTaxPayer1Status", null, TaxPayerStatusResp.class);
        if (CollUtil.isEmpty(taxPayerStatusResp)) {
            return null;
        }
        return taxPayerStatusResp.get(0);
    }

    /**
     * 查询需要检测办税的企业信息 - 手机号2
     * @return 只返回第一条数据，如果没有数据则返回null
     */
    public TaxPayerStatusResp getTaxPayer2Status(){
        List<TaxPayerStatusResp> taxPayerStatusResp = lazyDao.findBySql("getTaxPayer2Status", null, TaxPayerStatusResp.class);
        if (CollUtil.isEmpty(taxPayerStatusResp)) {
            return null;
        }
        return taxPayerStatusResp.get(0);
    }

    /**
     * 查询需要检测办税的企业信息 - 手机号3
     * @return 只返回第一条数据，如果没有数据则返回null
     */
    public TaxPayerStatusResp getTaxPayer3Status(){
        List<TaxPayerStatusResp> taxPayerStatusResp = lazyDao.findBySql("getTaxPayer3Status", null, TaxPayerStatusResp.class);
        if (CollUtil.isEmpty(taxPayerStatusResp)) {
            return null;
        }
        return taxPayerStatusResp.get(0);
    }

    /**
     * 根据flag更新办税员状态
     * @param taxPayerStatusReq 请求参数，包含账套ID、flag和手机号状态
     * @return 更新结果，如果flag无效则返回null
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateTaxPayerStatus(TaxPayerStatusReq taxPayerStatusReq) {
        if (taxPayerStatusReq == null || StrUtil.isEmpty(taxPayerStatusReq.getFlag()) || taxPayerStatusReq.getAccountSetsId() == null) {
            return null;
        }

        Long accountSetsId = taxPayerStatusReq.getAccountSetsId();
        TaxPayer taxPayer = jqf.selectFrom(qTaxPayer).where(qTaxPayer.accountSetsId.eq(accountSetsId)).fetchFirst();

        if (taxPayer == null) {
            // 插入新记录
            taxPayer = new TaxPayer();
            taxPayer.setAccountSetsId(accountSetsId);
            switch (taxPayerStatusReq.getFlag()) {
                case "1":
                    taxPayer.setMobile1(taxPayerStatusReq.getStatus());
                    break;
                case "2":
                    taxPayer.setMobile2(taxPayerStatusReq.getStatus());
                    break;
                case "3":
                    taxPayer.setMobile3(taxPayerStatusReq.getStatus());
                    break;
                default:
                    return null;
            }
            // 使用 EntityManager 保存
            entityManager.persist(taxPayer);
        } else {
            // 更新现有记录
            switch (taxPayerStatusReq.getFlag()) {
                case "1":
                    taxPayer.setMobile1(taxPayerStatusReq.getStatus());
                    break;
                case "2":
                    taxPayer.setMobile2(taxPayerStatusReq.getStatus());
                    break;
                case "3":
                    taxPayer.setMobile3(taxPayerStatusReq.getStatus());
                    break;
                default:
                    return null;
            }
            // 使用 EntityManager 更新
            entityManager.merge(taxPayer);
        }
        return Boolean.TRUE;
    }

    /**
     * 根据flag获取办税情况
     * @param flag 1-手机号1(18963408530 小程序 娄朝), 2-手机号2(19906406841 小程序 王兴鹏), 3-手机号3(18006443296 本地 张宝)
     * @return 办税情况列表，如果flag无效则返回null
     */
    public TaxPayerStatusResp getTaxPayerStatus(String flag) {
        if (StrUtil.isEmpty(flag)) {
            return null;
        }
        return switch (flag) {
            case "1" -> getTaxPayer1Status(); // 手机号1 - 小程序 娄朝
            case "2" -> getTaxPayer2Status(); // 手机号2 - 小程序 王兴鹏
            case "3" -> getTaxPayer3Status(); // 手机号3 - 本地 张宝
            default -> null;
        };
    }

    /**
     * 根据flag获取办税情况
     * @param flag 1-手机号1(18963408530 小程序 娄朝), 2-手机号2(19906406841 小程序 王兴鹏), 3-手机号3(18006443296 本地 张宝)
     * @return 办税情况列表，如果flag无效则返回null
     */
    public AddTaxPayerResp addTaxPayer(String flag) {
        if (StrUtil.isEmpty(flag)) {
            return null;
        }
        return switch (flag) {
            case "2" -> addTaxPayer2(); // 手机号2 - 小程序 王兴鹏
            default -> null;
        };
    }

    private AddTaxPayerResp addTaxPayer2() {
        List<AddTaxPayerResp> addTaxPayerResp = lazyDao.findBySql("addTaxPayer2", null, AddTaxPayerResp.class);
        if (CollUtil.isEmpty(addTaxPayerResp)) {
            return null;
        }
        return addTaxPayerResp.get(0);
    }

    /**
     * 读取应申报税费种信息的任务（供另一个系统查询，逻辑同办税员 getTaxPayer1Status）
     * 返回一条尚未在 fxy_financial_tax_declare 中有记录的账套，另一系统抓取后通过 saveTaxDeclare 回传存库。
     * 取到任务后会调用 updateTaxBureauAddressAfterExecute 记录该省执行次数，用于省份轮询。
     */
    public TaxDeclareTaskResp getTaxDeclareTask() {
        List<TaxDeclareTaskResp> list = lazyDao.findBySql("getTaxDeclareTask", null, TaxDeclareTaskResp.class);
        if (CollUtil.isEmpty(list)) {
            return null;
        }
        TaxDeclareTaskResp task = list.get(0);
        if (StrUtil.isNotBlank(task.getProvince())) {
            lazyDao.executeSql("updateTaxBureauAddressAfterExecute", Collections.singletonMap("province", task.getProvince()));
        }
        return task;
    }

    /**
     * 保存应申报税费种信息（另一系统返回的数据：先删该账套下旧数据，再按 tableData 整批写入；无行时写一条占位记录）
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveTaxDeclare(SaveTaxDeclareReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return Boolean.FALSE;
        }
        taxDeclareService.saveForAccountSet(req.getAccountSetsId(), req.getTableData(),req.getFlag());
        return Boolean.TRUE;
    }

    /**
     * 按账套查询应申报税费种列表及最近更新时间（供前端展示、判定是否重新抓取）
     */
    public TaxDeclareListResp listTaxDeclare(Long accountSetsId) {
        if (accountSetsId == null) {
            TaxDeclareListResp empty = new TaxDeclareListResp();
            empty.setList(Collections.emptyList());
            empty.setLastUpdateTime(null);
            return empty;
        }
        List<TaxDeclare> list = taxDeclareService.findByAccountSetsId(accountSetsId);
        java.time.LocalDateTime lastUpdateTime = taxDeclareService.getLastUpdateTime(accountSetsId);
        TaxDeclareListResp resp = new TaxDeclareListResp();
        resp.setList(list);
        resp.setLastUpdateTime(lastUpdateTime);
        return resp;
    }

    /**
     * 查询代理渠道信息（按省份排序，返回第一条尚未申报的账套）
     */
    public AgentChannelInfoResp getAgentChannelInformation(GetAgentChannelInfoReq req) {
        String serviceStaffPattern = String.join("|", req.getServiceStaffList());
        Map<String, Object> params = new HashMap<>();
        params.put("userIds", req.getUserIds());
        params.put("serviceStaffPattern", serviceStaffPattern);
        params.put("functionPermissions", req.getFunctionPermissions());
        params.put("checkDate", req.getCheckDate());
        List<AgentChannelInfoResp> list = lazyDao.findBySql("getAgentChannelInformation", params, AgentChannelInfoResp.class);
        return CollUtil.isEmpty(list) ? null : list.get(0);
    }

}
