package cn.gson.financial.rpa;

import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.ReportService;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.AccountBookQueryVO;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AccountBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import java.io.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CheckData {
    private final AccountBookService accountBookService;
    private final ReportService reportService;
    // 检查资产负债表start
    /**
     * 检查资产负债
     * templateKey:zcfz
     * localCurrencyId
     * reset false
     * dimension:TERM
     * @return
     */
    public String checkZcfz(UserVo userVo,String templateKey, Long localCurrencyId, Boolean reset, String dimension){
        //获取所有企业账套
        File logFile = new File("E:\\log\\资产负债表不平.txt");
        if(logFile.exists()){
            logFile.delete();
        }
        List<AccountSets> accountSetsList = userVo.getAccountSetsList();
        String resultError = "";
        for(int i=0;i<accountSetsList.size();i++){
            AccountSets accountSets = accountSetsList.get(i);
            LocalDate enableDate = accountSets.getEnableDate(); // 账套启用日期
            LocalDate currentAccountDate = accountSets.getCurrentAccountDate(); // 当前账期

            //根据账套获取所有账期
            List<YearMonth> monthList = getMonthsBetween(enableDate,currentAccountDate);

            for(YearMonth month:monthList){
                try {
                    String msg = "";
                    // 查询每个账期的资产负债表
                    List<ReportItemsDto> items = reportService.items(templateKey, accountSets.getId(), reset);
                    List<ReportItemsDto> resultList = reportService.reportData(items, userVo.getAccountSets(), templateKey, userVo.getLocalCurrencyId(), month.atEndOfMonth(), dimension);
                    // 检查资产负债表数据是否平衡。 年初余额相比、期末余额相比
                    // 取出resultList title 为资产总计的那一行
                    ReportItemsDto total = resultList.stream().filter(item -> item.getTitle().equals("资产总计")).findFirst().get();
                    // 取出resultList title 为负债和所有者权益总计的那一行
                    ReportItemsDto total2 = resultList.stream().filter(item -> item.getTitle().equals("负债和所有者权益（或股东权益）总计")).findFirst().get();
                    // 比较资产总计和负债和所有者权益总计是否相等
                    if(!total.getValues().get("年初余额").equals(total2.getValues().get("年初余额")) || !total.getValues().get("期末余额").equals(total2.getValues().get("期末余额"))){
                        msg = accountSets.getCompanyName() +"账期："+month+" 资产负债表数据不平衡\n\r";
                    }
                    logFile.createNewFile();
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile,true),"UTF-8"));
                    writer.write(msg);
                    writer.flush();
                    writer.close();
                } catch (Exception e) {
                    resultError += e.getMessage();
                    e.printStackTrace();
                }

            }
        }




        // 遍历所有的账套

        return null;
    }

    public  List<YearMonth> getMonthsBetween(LocalDate startDate, LocalDate endDate) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth start = YearMonth.from(startDate);
        YearMonth end = YearMonth.from(endDate);

        while (!start.isAfter(end)) {
            months.add(start);
            start = start.plusMonths(1);
        }

        return months;
    }
    // 检查资产负债表end


    // 检查科目余额start
    /**
     * 检查科目余额
     * @return
     */
    public String checkkmye(@SessionUser UserVo userVo){
//        List<AccountSets> accountSetsList = userVo.getAccountSetsList();
        // 获取所有账套
        File logFile = new File("E:\\log\\kemuye.txt");
        if(logFile.exists()){
            logFile.delete();
        }
        List<AccountSets> accountSetsList = userVo.getAccountSetsList();
        for(int i =0;i<accountSetsList.size();i++){
            String msg ="";
            AccountSets accountSets = accountSetsList.get(i);
            // 获取每个账套当前账期
            LocalDate currentAccountDate = accountSets.getCurrentAccountDate();
            AccountBookQueryVO query =  new AccountBookQueryVO();
            query.setAccountSetsId(accountSets.getId());
            query.setCurrencyId(userVo.getLocalCurrencyId());

            query.setEnd(currentAccountDate);
            query.setStart(currentAccountDate);
            query.setLevelEnd(4);
            query.setLevelStart(1);
            query.setAuxiliary(false);
            query.setShowNumPrice(false);
            List<BalanceDto> result = accountBookService.subjectBalance(query);
            //遍历result，当balanceDirection 为 借并且parentTreeId 等于 0 时 ，期初借方总计等于beginBalance累加
            //当balanceDirection 为 贷并且parentTreeId 等于 0 时 ，期初贷方总计等于beginBalance累加
            //当balanceDirection 为 借并且parentTreeId 等于 0 时 ，期末借方总计等于endBalance累加
            //当balanceDirection 为 贷并且parentTreeId 等于 0 时 ，期末贷方总计等于endBalance累加
            BigDecimal beginJie = new BigDecimal(0);
            BigDecimal beginDai = new BigDecimal(0);
            BigDecimal endJie = new BigDecimal(0);
            BigDecimal endDai = new BigDecimal(0);
            A:for (int j = 0;j<result.size();j++){
                if(result.get(j).getParentTreeId()!=0)
                    continue A;
                if("借".equals(result.get(j).getBalanceDirection() )){
                    beginJie = beginJie.add(result.get(j).getBeginBalance());
                    endJie = endJie.add(result.get(j).getEndBalance());
                }else if ("贷".equals(result.get(j).getBalanceDirection())   ){
                    beginDai = beginDai.add(result.get(j).getBeginBalance());
                    endDai = endDai.add(result.get(j).getEndBalance());
                }
            }
            // 根据账套名称、账期查找对应的excel表格row.get(row.size()-1).getCellValue(2)
            String dataStr = currentAccountDate.getYear()+"年" +currentAccountDate.getMonthValue()+"账期";
            String filePath ="C:\\Users\\23614\\Desktop\\ee\\tar\\target\\"+accountSets.getCompanyName()+"\\"+dataStr+"\\"+accountSets.getCompanyName()+"_"+dataStr+"_科目余额.xlsx";
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(filePath);
                Workbook workbook = new XSSFWorkbook(fis);
                Sheet sheet = workbook.getSheet("科目余额表");
                if (sheet == null) {
                    throw new RuntimeException("未找到工作表：科目余额表");
                }
                Row row = sheet.getRow(sheet.getLastRowNum());
                BigDecimal beginJieExcel = new BigDecimal(row.getCell(2).toString());
                BigDecimal beginDaiExcel = new BigDecimal(row.getCell(3).toString());
                BigDecimal endJieExcel = new BigDecimal(row.getCell(8).toString());
                BigDecimal endDaiExcel = new BigDecimal(row.getCell(9).toString());
                if(!beginJie.equals(beginJieExcel) || !beginDai.equals(beginDaiExcel) || !endJie.equals(endJieExcel) || !endDai.equals(endDaiExcel)){
                    msg = accountSets.getCompanyName()+dataStr+"科目余额表数据与excel数据不一致\n\t";
                }
                logFile.createNewFile();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile,true),"UTF-8"));
                writer.write(msg.toString());
                writer.flush();
                writer.close();

            } catch (Exception e) {
                e.printStackTrace();
            }




        }






        // 科目余额表总计数据与excel最后一行总计数据对比
        // 如果不相等，输出日志。




//        queryVO.setAccountSetsId(accountSetsId);
//        return JsonResult.successful(accountBookService.subjectBalance(queryVO));
        return null;
    }

    // 检查科目余额end
}
