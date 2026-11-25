package cn.gson.financial.rpa;

import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.common.SubjectExcelUtils;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.model.vo.VoucherImportVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExcelReaderUtil {
    private final SubjectExcelUtils excelUtils;
    private final SubjectService subjectService;
    private final CheckoutService checkoutService;
    private final CurrencyService currencyService;
    private final AccountingCategoryService accountingCategoryService;
    private final BalanceCalcService balanceCalcService;
    private final VoucherService voucherService;
    private final UserService userService;

    public static void handleVoucher(String path){
        try{
        FileInputStream file = new FileInputStream(path);
        Workbook workbook = createWorkbook(file,path);

        // 2. 遍历所有工作表
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);

            // 3. 获取所有合并区域并取消合并
            for (int j = sheet.getNumMergedRegions() - 1; j >= 0; j--) {
                CellRangeAddress mergedRegion = sheet.getMergedRegion(j);

                // 4. 获取合并区域左上角单元格内容
                Row firstRow = sheet.getRow(mergedRegion.getFirstRow());
                Cell firstCell = firstRow.getCell(mergedRegion.getFirstColumn());
                Object cellValue = getCellValue(firstCell);

                // 5. 取消合并
                sheet.removeMergedRegion(j);

                // 6. 填充内容到原合并区域的所有单元格
                for (int row = mergedRegion.getFirstRow(); row <= mergedRegion.getLastRow(); row++) {
                    Row currentRow = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
                    for (int col = mergedRegion.getFirstColumn(); col <= mergedRegion.getLastColumn(); col++) {
                        Cell cell = currentRow.getCell(col) == null ? currentRow.createCell(col) : currentRow.getCell(col);
                        setCellValue(cell, cellValue);
                    }
                }
            }

        }

        // 7. 保存修改后的文件
        FileOutputStream outFile = new FileOutputStream(path);
        workbook.write(outFile);
        workbook.close();
        outFile.close();
        file.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    /**
     * 批量导入凭证数据
     * @param userVo
     * @return
     */
    public String importVoucher(UserVo userVo) {
        String result = "";
        List<AccountSets> accountSetsList =userVo.getAccountSetsList();
        int count = 0;
        File logFile = new File("E:\\log\\pingzhenglog.txt");
        if(logFile.exists()){
            logFile.delete();
        }
        for(int j=0;j<accountSetsList.size();j++){
            AccountSets accountSets = accountSetsList.get(j);
            if(!"济南锦上篷布有限公司".equals(accountSets.getCompanyName())) continue;
            String filePath = "E:\\data\\pingzheng\\"+accountSets.getCompanyName()+"\\";
            List<File> fileList = getFilteredDirectories(new File(filePath));
            if(fileList.size()>0) {
                for (int k = 0; k < fileList.size(); k++) {
                    StringBuilder msg = new StringBuilder();
                    try {
                        String fileName = filePath + fileList.get(k).getName() + "\\" + accountSets.getCompanyName() + "_" + fileList.get(k).getName() + "至" + fileList.get(k).getName() + ".xls";
                        String path = writeToNewExcel4Voucher(fileName, "E:\\data\\pingzhengshengcheng", count++,accountSets.getCompanyName() + "_" + fileList.get(k).getName() + "至" + fileList.get(k).getName());
//                        if(path!=null){
//                            File file = new File(path);
//                            FileInputStream input = new FileInputStream(file);
//
//                            Long accountSetsId = accountSets.getId();
//                            // 最后结账日期
//                            LocalDate lastChecked = checkoutService.last(accountSets);
//                            CurrencyDto localCurrency = currencyService.fetchLocal(accountSetsId);
//                            List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
//                            //待插入的辅助项
//                            List<Object> newAcDetails = new ArrayList<>();
//                            cn.hutool.poi.excel.ExcelReader reader = ExcelUtil.getReader(input);
//                            reader.addHeaderAlias("日期", "date");
//                            reader.addHeaderAlias("凭证号", "voucherNumber");
//                            reader.addHeaderAlias("摘要", "digest");
//                            reader.addHeaderAlias("科目编码", "subjectCode");
//                            reader.addHeaderAlias("科目名称", "subjectTitle");
//                            reader.addHeaderAlias("外币代码", "foreignCurrencyCode");
//                            reader.addHeaderAlias("借方数量", "debitNumber");
//                            reader.addHeaderAlias("借方外币", "debitForeignCurrency");
//                            reader.addHeaderAlias("借方本币", "debitCurrency");
//                            reader.addHeaderAlias("贷方数量", "creditNumber");
//                            reader.addHeaderAlias("贷方外币", "creditForeignCurrency");
//                            reader.addHeaderAlias("贷方本币", "creditCurrency");
//                            for (AccountingCategoryDto c : categories) {
//                                reader.addHeaderAlias(c.getName() + "编码", "code" + c.getId());
//                                reader.addHeaderAlias(c.getName() + "名称", "name" + c.getId());
//                            }
//
//                            List<Map<String, Object>> maps = reader.readAll();
//                            reader.clearHeaderAlias();
//                            reader.addHeaderAlias("编码", "code");
//                            reader.addHeaderAlias("名称", "name");
//                            //导入的参考辅助数据
//                            Map<AccountingCategoryDto, Map<String, AcDetails>> acMap = new HashMap<>();
//                            for (AccountingCategoryDto c : categories) {
//                                reader.setSheet("参考" + c.getName());
//                                List<AcDetails> acDetails = reader.readAll(AcDetails.class);
//                                try {
//                                    Map<String, AcDetails> detailsMap = acDetails.stream().collect(Collectors.toMap(AcDetails::getCode, a -> a));
//                                    acMap.put(c, detailsMap);
//                                } catch (IllegalStateException e) {
//                                    result =  "参考" + c.getName() + "，编码有重复，请检查数据~";
//                                }
//                            }
//
//                            //已经查询到的辅助明细
//                            Map<String, Object> exitAcd = new HashMap<>();
//                            //已查询到的科目
//                            Map<String, Subject> exitSubject = new HashMap<>();
//
//                            List<VoucherImportVo> importVos = new ArrayList<>();
//                            for (int i = 0, mapsSize = maps.size(); i < mapsSize; i++) {
//                                Map<String, Object> map = maps.get(i);
//                                JSONObject row = new JSONObject(map);
//                                VoucherImportVo voucherImportVo = BeanUtil.toBean(row, VoucherImportVo.class, CopyOptions.create().ignoreNullValue());
//                                for (AccountingCategoryDto c : categories) {
//                                    String code = row.getString("code" + c.getId());
//                                    String name = row.getString("name" + c.getId());
//                                    if (StrUtil.isNotEmpty(code)) {
//                                        Map<String, AcDetails> detailsMap = acMap.get(c);
//                                        if (detailsMap != null) {
//                                            AcDetails acDetails = detailsMap.get(code);
//                                            if (acDetails != null) {
//                                                name = acDetails.getName();
//                                            }
//                                        }
//                                        Assert.notEmpty(name, "第" + (i + 2) + "行，辅助项" + c.getName() + "名称未设置，请处理～");
//                                        Object acd = exitAcd.get(c.getCategoryType().name() + ":" + code);
//                                        if (acd == null) {
//                                            acd = SpringUtil.getBean(c.getCategoryType().dataService).loadByCode(accountSetsId, c.getId(), code);
//                                            if (acd == null) {
//                                                AcDetails acd2 = ReflectUtil.newInstance(c.getCategoryType().entity);
//                                                acd2.setCategoryType(c.getCategoryType());
//                                                acd2.setCategoryId(c.getId());
//                                                acd2.setCode(code);
//                                                acd2.setName(name);
//                                                acd2.setAccountSetsId(accountSetsId);
//                                                acd2.setEnable(true);
//                                                acd2.setId(voucherService.generateId());
//                                                newAcDetails.add(acd2);
//                                                acd = acd2;
//                                            }
//                                            exitAcd.put(c.getCategoryType().name() + ":" + code, acd);
//                                        }
//                                        voucherImportVo.getAccountingCategoryDetails().add((AcDetails) acd);
//                                    }
//                                }
//                                importVos.add(voucherImportVo);
//                            }
//
//                            Map<String, List<VoucherImportVo>> voucherMap = importVos.stream().collect(Collectors.groupingBy(d -> d.getDate() + d.getVoucherNumber()));
//                            List<VoucherDto> voucherList = new ArrayList<>();
//                            voucherMap.forEach((key, list) -> {
//                                VoucherImportVo vo = list.get(0);
//                                String tips = vo.getDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
//                                List<String> wordCode = StrUtil.split(vo.getVoucherNumber(), "-");
//                                Assert.isTrue(wordCode.size() == 2, tips + "【" + vo.getVoucherNumber() + "】凭证号，格式错误。格式例：记-1");
//                                tips += "【" + vo.getVoucherNumber() + "】凭证号，";
//
//                                VoucherDto voucher = new VoucherDto();
//                                voucher.setWord(wordCode.get(0));
//                                voucher.setCode(Integer.valueOf(wordCode.get(1)));
//                                voucher.setRemark(vo.getRemark());
//                                voucher.setCreateMember(userVo.getId());
//                                voucher.setCreateDate(LocalDateTime.now());
//                                voucher.setDebitAmount(vo.getDebitCurrency());
//                                voucher.setCreditAmount(vo.getCreditCurrency());
//                                voucher.setAccountSetsId(accountSetsId);
//
//                                voucher.setVoucherDate(vo.getDate());
//                                voucher.setYear(voucher.getVoucherDate().getYear());
//                                voucher.setMonth(voucher.getVoucherDate().getMonthValue());
//                                voucher.setIsTemplate(false);
//                                voucher.setYearMonth(voucher.getVoucherDate().withDayOfMonth(1));
//
//                                List<VoucherDetailsDto> details = new ArrayList<>();
//                                BigDecimal debitAmount = BigDecimal.ZERO, creditAmount = BigDecimal.ZERO;
//                                for (VoucherImportVo vi : list) {
//                                    if (StrUtil.isEmpty(vi.getSubjectCode())) {
//                                        throw new ServiceException(tips + "科目编码不能为空,请核对数据~");
//                                    }
//                                    VoucherDetailsDto vd = new VoucherDetailsDto();
//                                    Subject subject = exitSubject.get(vi.getSubjectCode());
//                                    if (subject == null) {
//                                        subject = subjectService.listByCode(accountSetsId, vi.getSubjectCode());
//                                        if (subject == null) {
//                                            throw new ServiceException(tips + "科目编码【" + vi.getSubjectCode() + "】不存在,请先导入科目~");
//                                        }
//                                        exitSubject.put(vi.getSubjectCode(), subject);
//                                    }
//                                    vd.setSubjectId(subject.getId());
//                                    vd.setSummary(vi.getDigest());
//                                    vd.setCurrencyId(localCurrency.getId());
//                                    vd.setVoucherDate(vi.getDate());
//                                    vd.setDebitAmount(vi.getDebitCurrency());
//                                    vd.setCreditAmount(vi.getCreditCurrency());
//                                    vd.setCreditNum(vi.getCreditNumber());
//                                    vd.setDebitNum(vi.getDebitNumber());
//                                    debitAmount = NumberUtil.add(debitAmount, vi.getDebitCurrency());
//                                    creditAmount = NumberUtil.add(creditAmount, vi.getCreditCurrency());
//                                    vd.setAccountSetsId(accountSetsId);
//                                    vd.setAuxiliary(vi.getAccountingCategoryDetails());
//
//                                    if (vi.getCreditNumber() != null && !NumberUtil.equals(vi.getCreditNumber(), BigDecimal.ZERO)) {
//                                        vd.setNum(vi.getCreditNumber());
//                                    } else {
//                                        vd.setNum(vi.getDebitNumber());
//                                    }
//
//                                    if (vd.getNum() != null && !NumberUtil.equals(vd.getNum(), BigDecimal.ZERO)) {
//                                        vd.setPrice(NumberUtil.div(vd.getAmount(), vd.getNum()));
//                                    }
//                                    if (StrUtil.isNotEmpty(vi.getDigest()) && vi.getDigest().contains("结转损益")) {
//                                        vd.setCarryForward(true);
//                                        voucher.setCarryForward(true);
//                                    }
//                                    details.add(vd);
//                                }
//                                if (!NumberUtil.equals(debitAmount, creditAmount)) {
//                                    throw new ServiceException(tips + "借贷不平衡,借方金额：" + debitAmount + ",贷方金额：" + creditAmount + ",请核对数据~");
//                                }
//                                voucher.setDetails(details);
//                                voucher.setDebitAmount(debitAmount);
//                                voucher.setCreditAmount(creditAmount);
//                                voucherList.add(voucher);
//                            });
//
//                            // 检查导入凭证日期，必须是当前未结转账期之前的数据
//                            Optional<LocalDate> minVoucherDate = voucherList.stream().map(VoucherDto::getVoucherDate).min(LocalDate::compareTo);
//                            if (minVoucherDate.isPresent()) {
//                                Assert.isTrue(minVoucherDate.get().isAfter(lastChecked.plusDays(-1)), "请导入" + lastChecked.format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "之后的数据");
//                                lastChecked = minVoucherDate.get();
//                            }
//
//                            accountingCategoryService.saveAll(newAcDetails);
//                            this.voucherService.importVoucher(voucherList, accountSets);
//
//                            balanceCalcService.calcSynchronization(accountSetsId, YearMonth.from(lastChecked));
//
//                            StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(userVo.getId()));
//                        }
                    } catch (ServiceException | IllegalArgumentException e) {
                        log.error("导入失败", e);
                        msg.append(accountSets.getCompanyName()).append( "导入失败：").append(e.getMessage()).append("\n\r");
                    } catch (Exception e) {
                        log.error("导入失败", e);
                        msg.append(accountSets.getCompanyName()).append( "导入失败：").append(e.getMessage()).append("\n\r");
                    }

                    try {
                        logFile.createNewFile();
                        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile,true),"UTF-8"));
                        writer.write(msg.toString());
                        writer.flush();
                        writer.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return result;
    }


    /**
     * 批量导入科目数据
     * @param userVo
     * @return
     * @throws Exception
     */
    public boolean importSubject(UserVo userVo) throws Exception {
        List<AccountSets> accountSetsList =userVo.getAccountSetsList();
        int count = 0;
        List<String> test = new ArrayList<>();
        for(int i=0;i<accountSetsList.size();i++){


            AccountSets accountSets = accountSetsList.get(i);

            String filePath = "C:\\Users\\23614\\Desktop\\target\\"+accountSets.getCompanyName()+"\\";
            List<File> list = getFilteredDirectories(new File(filePath));
            for(int k=0;k<list.size();k++){
                String fileName = filePath+list.get(k).getName()+"\\"+accountSets.getCompanyName()+"_"+list.get(k).getName()+"_科目余额.xlsx";
                String path = writeToNewExcel(fileName,"C:\\Users\\Desktop\\import\\temp",count++);
                File file = new File(path);
                FileInputStream input = new FileInputStream(file);
                try{
                    List<SubjectVo> voucherList = excelUtils.readExcel(input, accountSets.getEncoding(), accountSets.getSubjectCategory(), accountSets.getId());
                    subjectService.importSubject(accountSets, voucherList);
                }catch (Exception e){
                    test.add(accountSets.getCompanyName()+"###"+e.getMessage());
                    System.out.println("================"+accountSets.getCompanyName()+"==================");
                    e.printStackTrace();
                }
            }
        }
        return true;
    }
    //将科目数据放置在新的excel文件中。
    public  String writeToNewExcel(String inputFilePath, String outputDir,int count) throws Exception {
        // 读取原始Excel数据
        List<Map<String, String>> data = readExcelColumns(inputFilePath);

        // 创建新的工作簿和工作表
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Sheet1");

        // 创建表头
        String[] headers = {"科目编码", "科目名称", "科目类别", "余额方向", "数量核算", "辅助核算"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // 填充数据
        int rowNum = 1;
        for (Map<String, String> rowData : data) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(rowData.get("code"));
            row.createCell(1).setCellValue(rowData.get("name"));
            row.createCell(2).setCellValue(rowData.get("type"));
            row.createCell(3).setCellValue(rowData.get("fangxiang"));
            row.createCell(4).setCellValue(""); // 数量核算为空
            row.createCell(5).setCellValue(""); // 辅助核算为空
        }

        // 自动调整列宽
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // 写入新的Excel文件
        String outputFilePath = outputDir + File.separator + "processed_data"+count+".xlsx";
        try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
            workbook.write(fos);
        }

        // 关闭工作簿
        workbook.close();

        return outputFilePath;
    }

    // 读取科目信息表
    public List<Map<String,String>> readExcelColumns(String filePath) throws Exception {
        List<Map<String,String>> result = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(new File(filePath))) {
            Workbook workbook = createWorkbook(fis, filePath);
            Sheet sheet = workbook.getSheetAt(0); // 第一个工作表

            for (Row row : sheet) {
                HashMap<String,String> data = new HashMap<>();
                if (row.getRowNum() <= 3) continue; // 跳过标题行
                if(row.getCell(0)==null||row.getCell(0).toString().isEmpty())   continue;
                String code = row.getCell(0).toString();
                data.put("code",code);
                data.put("name",row.getCell(1).toString());
                Cell value = row.getCell(2);
                if(value!=null){
                    data.put("fangxiang","借");
                }else{
                    data.put("fangxiang","贷");
                }
                if("1".equals(code.substring(0,1))){
                    data.put("type","资产");
                }else if ("2".equals(code.substring(0,1))){
                    data.put("type","负债");
                }else if ("3".equals(code.substring(0,1))){
                    data.put("type","权益");
                }else if ("4".equals(code.substring(0,1))){
                    data.put("type","成本");
                }else if ("5".equals(code.substring(0,1))){
                    data.put("type","损益");
                }else{
                    data.put("type","未知");
                }
                result.add(data);
            }
        }
        return result;
    }

    /**
     * 将凭证数据存入临时文件
     * @param inputFilePath
     * @param outputDir
     * @param count
     * @return
     * @throws Exception
     */
    public  String writeToNewExcel4Voucher(String inputFilePath, String outputDir, int count,String companyName) {
        try {
            // 读取原始Excel数据
            List<Map<String, String>> data = readExcelColumns4Voucher(inputFilePath);
            if(data==null) return null;
            // 创建新的工作簿和工作表
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("待导入凭证数据");

            // 创建表头
            String[] headers = {"日期", "凭证号", "摘要", "科目编码", "科目名称", "外币代码", "借方数量", "借方外币", "借方本币", "贷方数量", "贷方外币", "贷方本币", "客户编码", "客户名称", "供应商编码", "供应商名称", "职员编码", "职员名称", "部门编码", "部门名称", "组织机构编码", "组织机构名称", "项目编码", "项目名称", "存货编码", "存货名称", "现金流量项目编码", "现金流量项目名称"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // 填充数据
            int rowNum = 1;
            for (Map<String, String> rowData : data) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(rowData.get("日期"));
                row.createCell(1).setCellValue(rowData.get("凭证号"));
                row.createCell(2).setCellValue(rowData.get("摘要"));
                row.createCell(3).setCellValue(rowData.get("科目编码"));
                row.createCell(4).setCellValue(rowData.get("科目名称"));
                row.createCell(5).setCellValue(rowData.get("外币代码"));
                row.createCell(6).setCellValue(rowData.get("借方数量"));
                row.createCell(7).setCellValue(rowData.get("借方外币"));
                row.createCell(8).setCellValue(rowData.get("借方本币"));
                row.createCell(9).setCellValue(rowData.get("贷方数量"));
                row.createCell(10).setCellValue(rowData.get("贷方外币"));
                row.createCell(11).setCellValue(rowData.get("贷方本币"));
                row.createCell(12).setCellValue(rowData.get("客户编码"));
                row.createCell(13).setCellValue(rowData.get("客户名称"));
                row.createCell(14).setCellValue(rowData.get("供应商编码"));
                row.createCell(15).setCellValue(rowData.get("供应商名称"));
                row.createCell(16).setCellValue(rowData.get("职员编码"));
                row.createCell(17).setCellValue(rowData.get("职员名称"));
                row.createCell(18).setCellValue(rowData.get("部门编码"));
                row.createCell(19).setCellValue(rowData.get("部门名称"));
                row.createCell(20).setCellValue(rowData.get("组织机构编码"));
                row.createCell(21).setCellValue(rowData.get("组织机构名称"));
                row.createCell(22).setCellValue(rowData.get("项目编码"));
                row.createCell(23).setCellValue(rowData.get("项目名称"));
                row.createCell(24).setCellValue(rowData.get("存货编码"));
                row.createCell(25).setCellValue(rowData.get("存货名称"));
                row.createCell(26).setCellValue(rowData.get("现金流量编码"));
                row.createCell(27).setCellValue(rowData.get("现金流量名称"));
            }

            // 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // 写入新的Excel文件
            String outputFilePath = outputDir + File.separator + companyName + ".xlsx";
            try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
                workbook.write(fos);
            }

            // 关闭工作簿
            workbook.close();
            return outputFilePath;
        }catch (Exception e){
            e.printStackTrace();
            return null;
        }

    }

    /**
     * 读取凭证信息
     * @param filePath
     * @return
     * @throws Exception
     */
    public List<Map<String,String>> readExcelColumns4Voucher(String filePath){
        handleVoucher(filePath);
        List<Map<String,String>> result = new ArrayList<>();

        try{
            FileInputStream fis = new FileInputStream(new File(filePath));
            Workbook workbook = createWorkbook(fis, filePath);
            Sheet sheet = workbook.getSheetAt(0); // 第一个工作表

            for (Row row : sheet) {
                HashMap<String,String> data = new HashMap<>();
                if (row.getRowNum() <= 2) continue; // 跳过标题行
                if(row.getCell(0)==null||row.getCell(0).toString().isEmpty())   continue;
                data.put("日期",row.getCell(0).toString());
                data.put("凭证号",row.getCell(1).toString());
                data.put("摘要",row.getCell(2).toString());
                String value = row.getCell(3).toString();
                String[] values = value.split("\\s",2);
                data.put("科目编码",values[0]);
                data.put("科目名称",values[1]);
                data.put("外币代码","");
                data.put("借方数量","");
                data.put("借方外币","");
                data.put("借方本币",row.getCell(9).toString());
                data.put("贷方数量","");
                data.put("贷方外币","");
                data.put("贷方本币",row.getCell(10).toString());
                data.put("客户编码","");
                data.put("客户名称","");
                data.put("供应商编码","");
                data.put("供应商名称","");
                data.put("职员编码","");
                data.put("职员名称","");
                data.put("部门编码","");
                data.put("部门名称","");
                data.put("组织机构名称","");
                data.put("项目编码","");
                data.put("项目名称","");
                data.put("存货编码","");
                data.put("存货名称","");
                data.put("现金流编码","");
                data.put("现金流名称","");
                result.add(data);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return result;
    }

    // 根据excel文件扩展名创建Workbook对象
    private static Workbook createWorkbook(FileInputStream fis, String filePath) throws Exception {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1);
        if ("xls".equalsIgnoreCase(extension)) {
            return new HSSFWorkbook(fis); // 处理xls格式
        } else if ("xlsx".equalsIgnoreCase(extension)) {
            return new XSSFWorkbook(fis); // 处理xlsx格式
        }
        throw new IllegalArgumentException("不支持的文件格式");
    }

    private static String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    // 避免科学计数法
                    return String.format("%.0f", cell.getNumericCellValue());
                }

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                return cell.getCellFormula();

            default:
                return "";
        }
    }

    public static List<File> getParentFiles(File folder) {
        List<File> result = new ArrayList<>();
        if (folder.exists() && folder.isDirectory()) {
            // 获取当前文件夹下的所有文件和文件夹
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }


    /**
     * 获取指定文件夹下的所有子文件夹，排除年报
     * @param folder
     * @return
     */
    public static List<File> getFilteredDirectories(File folder) {
        List<File> result = new ArrayList<>();
        if (folder.exists() && folder.isDirectory()) {
            // 获取当前文件夹下的所有文件和文件夹
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String folderName = file.getName();
                        // 过滤掉以 2023、2024、2025 命名的文件夹
                        if (!folderName.equals("2023年") && !folderName.equals("2024年") && !folderName.equals("2025年")) {
                            result.add(file);
//                            // 递归调用 getFilteredDirectories 方法处理子文件夹
//                            result.addAll(getFilteredDirectories(file));
                        }
                    }
                }
            }
        }
        sortDirectoriesByDate(result);
        return result;
    }
    public static void sortDirectoriesByDate(List<File> directories) {
        // 定义正则表达式来匹配年份和账期
        Pattern pattern = Pattern.compile("(\\d{4})年(\\d+)账期");
        Collections.sort(directories, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                String name1 = f1.getName();
                String name2 = f2.getName();
                Matcher matcher1 = pattern.matcher(name1);
                Matcher matcher2 = pattern.matcher(name2);
                if (matcher1.find() && matcher2.find()) {
                    int year1 = Integer.parseInt(matcher1.group(1));
                    int period1 = Integer.parseInt(matcher1.group(2));
                    int year2 = Integer.parseInt(matcher2.group(1));
                    int period2 = Integer.parseInt(matcher2.group(2));
                    if (year1 != year2) {
                        return year1 - year2;
                    } else {
                        return period1 - period2;
                    }
                }
                return name1.compareTo(name2);
            }
        });
    }
    // 获取单元格值并处理不同类型
    private static Object getCellValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC: return cell.getNumericCellValue();
            case BOOLEAN: return cell.getBooleanCellValue();
            default: return "";
        }
    }

    // 根据数据类型设置单元格值
    private static void setCellValue(Cell cell, Object value) {
        if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Double) {
            cell.setCellValue((Double) value);
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        }
    }
}
