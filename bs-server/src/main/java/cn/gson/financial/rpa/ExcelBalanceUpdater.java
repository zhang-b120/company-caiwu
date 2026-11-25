package cn.gson.financial.rpa;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.BalanceImportVo;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.BalanceInitialService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
@Service
@Slf4j
@RequiredArgsConstructor
public class ExcelBalanceUpdater {
    private final SubjectService subjectService;

    private final BalanceInitialService balanceInitialService;
    private final BalanceCalcService balanceCalcService;

//    public static void main(String[] args) throws Exception {
//        String dataPath = "C:\\Users\\23614\\Desktop\\qwe\\山东富淏源数控科技有限公司.xlsx";
//        String targetPath = "C:\\Users\\23614\\Desktop\\期初模板 - 副本\\山东富淏源数控科技有限公司-财务初始余额导入模板20250313.xlsx";
//
//
//            // 读取数据
//            HashMap<String,Double> map = convertExcelToList(dataPath);
//            //存储数据
//            updateInitialBalances(targetPath, map);
//    }
    public String doTask(UserVo userVo) {
        List<AccountSets> accountSetsList =userVo.getAccountSetsList();
        List<String> result = new ArrayList<>();
        List<String> success = new ArrayList<>();
        File logFile = new File("E:\\log\\qichulog.txt");
        if(logFile.exists()){
            logFile.delete();
        }

        for(AccountSets accountSets : accountSetsList){
            StringBuffer msg = new StringBuffer();
            String dataPath = "E:\\data\\qichu\\"+accountSets.getCompanyName()+".xlsx";
            String targetPath = "E:\\data\\qichumoban\\"+accountSets.getCompanyName()+".xlsx";


            try{
                // 读取数据
                HashMap<String,Double> map = convertExcelToList(dataPath);
                //存储数据
                updateInitialBalances(targetPath, map);
                ZipSecureFile.setMinInflateRatio(0.005);
                File file = new File(targetPath);
                FileInputStream input = new FileInputStream(file);
                ExcelReader reader = ExcelUtil.getReader(input);
                // 处理一下带公式的单元格，直接计算出结果填写上去
                FormulaEvaluator evaluator = reader.getWorkbook().getCreationHelper().createFormulaEvaluator();
                reader.read((cell, o) -> {
                    if (Objects.requireNonNull(cell.getCellType()) == CellType.FORMULA) {
                        CellValue evaluate = evaluator.evaluate(cell);
                        cell.setCellValue(evaluate.getNumberValue());
                    }
                });

                List<BalanceInitialDto> details = new ArrayList<>();
                List<BalanceImportVo> balanceImportVos = new ArrayList<>();
                List<Object> objects = reader.readRow(0);
                if (reader.readRow(1).stream().anyMatch(s -> "数量".equalsIgnoreCase(s.toString()))) {
                    //带数量核算
                    for (int i = 0; i < objects.size(); i++) {
                        Cell cell = reader.getOrCreateCell(i, 1);
                        String value = cell.getStringCellValue();
                        if (!objects.get(i).equals(value)) {
                            cell.setCellValue(objects.get(i) + value);
                        } else if (objects.get(i) != null && StrUtil.isEmpty(objects.get(i).toString())) {
                            cell.setCellValue(objects.get(i).toString());
                        }
                    }
                    balanceImportVos = reader.read(1, 2, reader.getRowCount(), BalanceImportVo.class);
                } else {
                    balanceImportVos = reader.readAll(BalanceImportVo.class);
                }

                Map<String, SubjectVo> subjectVoMap = subjectService.list(accountSets.getId()).stream().collect(Collectors.toMap(SubjectVo::getCode, s -> s));
                for (BalanceImportVo importVo : balanceImportVos) {
                    BalanceInitialDto balance = new BalanceInitialDto();
                    balance.setAccountSetsId(accountSets.getId());
                    balance.setCurrencyId(importVo.getCurrencyId());
                    if (balance.getCurrencyId() == null || balance.getCurrencyId() == 0L) {
                        balance.setCurrencyId(userVo.getLocalCurrencyId());
                    }
                    Assert.isTrue(subjectVoMap.containsKey(importVo.getCode()), importVo.getCode() + "在账套中不存在，请先创建科目~");
                    SubjectVo subjectVo = subjectVoMap.get(importVo.getCode());
                    balance.setSubjectId(subjectVo.getId());
                    balance.setBalanceDirection(subjectVo.getBalanceDirection());
                    balance.setAuxiliaryDetailId(importVo.getAuxiliaryDetailId());
                    balance.setSubjectCode(importVo.getCode());
                    balance.setTreeId(subjectVo.getId());
                    balance.setParentTreeId(subjectVo.getParentId());
                    balance.setLeaf(subjectVo.getLeaf());

                    if (balance.getLeaf()) {
                        balance.setBeginBalance(importVo.getBeginBalance());
                        balance.setYearTotalDebit(importVo.getYearTotalDebit());
                        balance.setYearTotalCredit(importVo.getYearTotalCredit());
                        balance.setYearTotal(importVo.getYearTotal());
                        balance.setBeginQuantity(importVo.getBeginQuantity());
                        balance.setYearTotalCreditQuantity(importVo.getYearTotalCreditQuantity());
                        balance.setYearTotalDebitQuantity(importVo.getYearTotalDebitQuantity());
                        balance.setYearTotalQuantity(importVo.getYearTotalQuantity());
                    }

                    balance.calculateYearTotal();
                    details.add(balance);
                }

                Set<Long> auxiliaryDetailIds = details.stream().filter(d -> d.getAuxiliaryDetailId() != null && d.getAuxiliaryDetailId() != 0)
                        .map(BalanceInitialDto::getAuxiliaryDetailId).collect(Collectors.toSet());

                if (!auxiliaryDetailIds.isEmpty()) {
                    Assert.isTrue(balanceInitialService.checkAuxiliaryDetailIds(auxiliaryDetailIds, accountSets.getId()),
                            "期初模板错误，请重新下载模板导入~");
                    msg.append(accountSets.getCompanyName()).append("期初模板错误，请重新下载模板导入~；；");
                }

                // 所有科目编码
                Set<String> allCodeSet = details.stream().map(BalanceInitialDto::getUniCode).collect(Collectors.toSet());
                // 根节点科目数据行
                List<BalanceInitialDto> leafBalance = details.stream().filter(b -> allCodeSet.stream().filter(c -> !c.equals(b.getUniCode()))
                        .noneMatch(c -> c.startsWith(b.getUniCode()))).toList();
                // 跟节点科目编码
                Set<String> leafCodeSet = leafBalance.stream().map(BalanceInitialDto::getUniCode).collect(Collectors.toSet());

                // 过滤出非根节点，然后计算累计
                List<BalanceInitialDto> notLeafBalance = details.stream().filter(b -> !leafCodeSet.contains(b.getUniCode())).map(d -> {
                    // 复制一个新对象
                    BalanceInitialDto newDto = new BalanceInitialDto();
                    newDto.setSubjectId(d.getSubjectId());
                    newDto.setBalanceDirection(d.getBalanceDirection());
                    newDto.setAccountSetsId(accountSets.getId());
                    newDto.setAuxiliaryDetailId(d.getAuxiliaryDetailId());
                    newDto.setCurrencyId(d.getCurrencyId());
                    leafBalance.stream().filter(b -> b.getUniCode().startsWith(d.getUniCode())).forEach(od -> {
                        accumulatedCalculation(newDto, od);
                    });
                    return newDto;
                }).toList();

                details = CollUtil.unionAll(leafBalance, notLeafBalance);
                //过滤掉没有值的行
                details = details.stream().filter(BalanceInitialDto::isUsed).collect(Collectors.toList());
                balanceInitialService.updateInitBalance(details, userVo.getAccountSets());
                balanceCalcService.calc(userVo.getAccountSets().getId(), YearMonth.from(userVo.getAccountSets().getEnableDate()));

                success.add("++++++++++++++++++++++++++++++++++++++++"+accountSets.getCompanyName()+"导入成功");
            }catch (Exception e){
                result.add(accountSets.getCompanyName()+"导入失败"+e.getMessage());
                msg.append(accountSets.getCompanyName()).append("导入失败"+e.getMessage()+"...\n\r");
                e.getMessage();
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


        //导入数据
        return null;
    }
    public static void updateInitialBalances(String excelPath, HashMap<String, Double> map) throws Exception {
        // 将List转换为Map<科目编码, 期初余额>
        try (FileInputStream fis = new FileInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(fis)) { // 支持.xlsx格式

            Sheet sheet = workbook.getSheet("财务初始余额表");
            if (sheet == null) {
                throw new RuntimeException("未找到工作表：财务初始余额表");
            }

            for (Row row : sheet) {
                // 跳过标题行（第一行）
                if (row.getRowNum() == 0) continue;

                // 读取科目编码（A列）
                Cell codeCell = row.getCell(0);
                if (codeCell == null) continue;
                String subjectCode = codeCell.getStringCellValue().trim();

                // 检查是否需要更新
                if (!map.containsKey(subjectCode)) continue;

                // 定位到期初余额列（G列）
                Cell balanceCell = row.getCell(6);
                if (balanceCell == null) {
                    balanceCell = row.createCell(6); // 若单元格不存在则创建
                }

                // 检查单元格是否锁定
                CellStyle style = balanceCell.getCellStyle();
                if (style.getLocked()) {
                    System.out.println("[跳过] 科目编码 " + subjectCode + " 的单元格被锁定");
                    continue;
                }

                // 更新值
                double newBalance = map.get(subjectCode);
                balanceCell.setCellValue(newBalance);
                System.out.println("[更新] 科目编码 " + subjectCode + " 的期初余额为：" + newBalance);
            }

            // 保存修改后的文件（覆盖原文件）
            try (FileOutputStream fos = new FileOutputStream(excelPath)) {
                workbook.write(fos);
            }
        }
    }

    public static  HashMap<String,Double> convertExcelToList(String filePath) {
        HashMap<String,Double> map = new HashMap<>();

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet("科目余额表");
            if (sheet == null) {
                throw new RuntimeException("未找到工作表：科目余额表");
            }

            // 跳过前4行标题（第0-3行）
            for (int i = 4; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                // 读取科目编码（A列）
                Cell codeCell = row.getCell(0);
                String subjectCode = (codeCell != null) ? codeCell.toString().trim() : "";
                // 跳过空科目编码的行（如汇总行）
                if (subjectCode.isEmpty()) continue;
                // 创建对象并填充数据
                String name = row.getCell(1).toString();
                String value ="0";
                if("待认证进项税额".equals(name)){
                    if(row.getCell(3)!=null&& StringUtils.isNotEmpty(row.getCell(3).toString())){
                        value = (Double.valueOf(row.getCell(3).toString().trim())*-1)+"";
                    }else{
                        value = (row.getCell(2) != null) ? row.getCell(2).toString().trim() : "0";
                    }
                }else{
                    value = (row.getCell(2) != null) ? row.getCell(2).toString().trim() : "0";
                }


//                if(row.getCell(2)!=null&& StringUtils.isNotEmpty(row.getCell(2).toString())){
//                    value = row.getCell(2).toString().trim();
//                }else if(row.getCell(3)!=null&& StringUtils.isNotEmpty(row.getCell(3).toString())){
//                    value = row.getCell(3).toString().trim();
//                }else{
//                    value = "0";
//                }
                map.put(subjectCode, Double.valueOf(value));

            }

        } catch (Exception e) {
            throw new RuntimeException("读取Excel失败: " + e.getMessage(), e);
        }

        return map;
    }

    private void accumulatedCalculation(BalanceInitialDto balanceDto, BalanceInitialDto d) {
        if (balanceDto.getBalanceDirection().equals(d.getBalanceDirection())) {
            balanceDto.setBeginBalance(NumberUtil.add(d.getBeginBalance(), balanceDto.getBeginBalance()));
            balanceDto.setBeginBalanceFor(NumberUtil.add(d.getBeginBalanceFor(), balanceDto.getBeginBalanceFor()));
            balanceDto.setBeginQuantity(NumberUtil.add(d.getBeginQuantity(), balanceDto.getBeginQuantity()));
            balanceDto.setYearTotal(NumberUtil.add(d.getYearTotal(), balanceDto.getYearTotal()));
            balanceDto.setYearTotalFor(NumberUtil.add(d.getYearTotalFor(), balanceDto.getYearTotalFor()));
        } else {
            balanceDto.setBeginBalance(NumberUtil.sub(balanceDto.getBeginBalance(), d.getBeginBalance()));
            balanceDto.setBeginBalanceFor(NumberUtil.sub(balanceDto.getBeginBalanceFor(), d.getBeginBalanceFor()));
            balanceDto.setBeginQuantity(NumberUtil.sub(balanceDto.getBeginQuantity(), d.getBeginQuantity()));
            balanceDto.setYearTotal(NumberUtil.sub(balanceDto.getYearTotal(), d.getYearTotal()));
            balanceDto.setYearTotalFor(NumberUtil.sub(balanceDto.getYearTotalFor(), d.getYearTotalFor()));
        }
        balanceDto.setYearTotalCredit(NumberUtil.add(d.getYearTotalCredit(), balanceDto.getYearTotalCredit()));
        balanceDto.setYearTotalDebit(NumberUtil.add(d.getYearTotalDebit(), balanceDto.getYearTotalDebit()));
        balanceDto.setYearTotalCreditFor(NumberUtil.add(d.getYearTotalCreditFor(), balanceDto.getYearTotalCreditFor()));
        balanceDto.setYearTotalDebitFor(NumberUtil.add(d.getYearTotalDebitFor(), balanceDto.getYearTotalDebitFor()));
        balanceDto.setYearTotalCreditQuantity(NumberUtil.add(d.getYearTotalCreditQuantity(), balanceDto.getYearTotalCreditQuantity()));
        balanceDto.setYearTotalDebitQuantity(NumberUtil.add(d.getYearTotalDebitQuantity(), balanceDto.getYearTotalDebitQuantity()));

    }

}
