package cn.gson.financial.account.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.account.entity.Account;
import cn.gson.financial.account.entity.InitialBalanceEntry;
import cn.gson.financial.account.service.AccountCompletionService;
import cn.gson.financial.kernel.common.SubjectExcelUtils;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.BalanceImportVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import com.aliyun.core.utils.StringUtils;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

import cn.gson.financial.kernel.model.vo.SubjectVo;

import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;

/**
 * 科目管理控制器
 * 提供科目完善和期初导入的REST API接口
 */
@RestController
@RequestMapping("/account")
@CrossOrigin(origins = "*")
public class AccountManagementController {

    @Autowired
    private AccountCompletionService accountCompletionService;

    @Autowired
    private SubjectService subjectService;

    @Autowired
    private UserService userService;
    @Autowired
    private SubjectExcelUtils excelUtils;
    @Autowired
    private BalanceInitialService balanceInitialService;
    @Autowired
    private BalanceCalcService balanceCalcService;

    /**
     * 科目完善接口
     * 根据初始化科目信息和待完善科目信息，完善科目信息
     *
     * @return 完善后的科目信息列表
     */
    @PostMapping("/complete")
    @SaIgnore
    public ResponseEntity<Map<String, Object>> completeAccounts(@RequestBody HashMap<String,String> req) {
        try {
            String taxNumber = req.get("taxNumber");
            String fileName = req.get("fileName");
            if (StringUtils.isEmpty(taxNumber)) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "当前账套ID不能为空");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            AccountSets accountSets = userService.uaGetAccountSetsId(taxNumber);
            if (StringUtils.isEmpty(fileName)) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "文件名不能为空");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            // 从Excel文件读取pendingAccounts数据
            List<Account> pendingAccounts = readPendingAccountsFromExcel(fileName);
            if (pendingAccounts == null || pendingAccounts.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Excel文件中未找到有效的科目数据");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            // 后端根据账套ID获取系统初始化科目
            List<SubjectVo> subjectVoList = subjectService.list(accountSets.getId());
            Map<String, SubjectVo> subjectByCode = subjectVoList.stream()
                    .collect(Collectors.toMap(SubjectVo::getCode, v -> v, (a, b) -> a));
            List<Account> initialAccounts = subjectVoList.stream()
                    .map(vo -> {
                        Account account = new Account(vo.getCode(), vo.getName(), vo.getBalanceDirection());
                        // 设置父类科目名称
                        if (vo.getParentId() != null) {
                            SubjectVo parentSubject = subjectByCode.get(vo.getParentId().toString());
                            if (parentSubject != null) {
                                account.setParentName(parentSubject.getName());
                            }
                        }
                        return account;
                    })
                    .collect(Collectors.toList());

            // 将SubjectVo转换为Map格式，供Service使用
            Map<String, Object> subjectByCodeMap = subjectVoList.stream()
                    .collect(Collectors.toMap(
                            SubjectVo::getCode,
                            vo -> {
                                Map<String, Object> voMap = new HashMap<>();
                                voMap.put("type", vo.getType());
                                voMap.put("name", vo.getName());
                                voMap.put("code", vo.getCode());
                                voMap.put("balanceDirection", vo.getBalanceDirection());
                                return voMap;
                            },
                            (a, b) -> a
                    ));

            List<Map<String, Object>> serviceResult = accountCompletionService.completeAccounts(initialAccounts, pendingAccounts, subjectByCodeMap);

            for(Map<String, Object> map : serviceResult){
                System.out.println(map);
            }

            // 构建响应数据
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "科目完善成功");
            response.put("data", serviceResult);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "科目完善失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }



    @PostMapping("/importFinancialInit")
    @SaIgnore
    public JsonResult importFinancialInit(@RequestBody Map<String,String> req) {
        try {
            String taxNumber = req.get("taxNumber");
            String filePath = req.get("filePath");
            String oldFile = req.get("oldFile");
            AccountSets accountSets = userService.uaGetAccountSetsId(taxNumber);
            File file = new File(filePath);
            InputStream fis = new FileInputStream(file);
            List<SubjectVo> voucherList = excelUtils.readExcel(fis, accountSets.getEncoding(), accountSets.getSubjectCategory(), accountSets.getId());
            subjectService.importSubject(accountSets, voucherList);
            exportToLocal(taxNumber);
            Map<String,String> map = new HashMap<>();
            map.put("taxNumber",taxNumber);
            map.put("fileName",oldFile);
            map.put("subjectFile",filePath);
            importInitialBalance(map);
            return JsonResult.successful();
        } catch (Exception e) {
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 生成期初数据模板
     * @param taxNumber
     * @throws Exception
     */
    public void exportToLocal(String taxNumber) throws Exception {
        try {
            BalanceService.Query query = new BalanceService.Query();
            AccountSets accountSets = userService.uaGetAccountSetsId(taxNumber);
            query.setAccountSets(accountSets);
//            query.setLocalCurrencyId(userVo.getLocalCurrencyId());
            List<BalanceDto> balanceDtoList = balanceInitialService.list(accountSets.getId(), query);

            ExcelWriter writer;
            boolean isUnit = balanceDtoList.stream().anyMatch(v -> StrUtil.isNotEmpty(v.getUnit()));
            if (isUnit) {
                writer = withUnit(balanceDtoList);
            } else {
                writer = withoutUnit(balanceDtoList);
            }

            XSSFWorkbook workbook = (XSSFWorkbook) writer.getWorkbook();
            POIXMLProperties properties = workbook.getProperties();
            properties.getCoreProperties().setTitle(accountSets.getCompanyName() + "-财务初始余额导入模板");
            properties.getCoreProperties().setKeywords(isUnit ? "带单位" : "不带单位");
            properties.commit();

            // 创建目录
            String directoryPath = "E:\\qykj\\file\\oldAccount";
            File directory = new File(directoryPath);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String fileName = taxNumber + "期初导入模板.xlsx";
            String filePath = directoryPath + File.separator + fileName;

            // 写入文件
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
            }

            writer.close();


        } catch (Exception e) {
            throw new Exception("生成期初数据模板错误");
        }
    }

    /**
     * 期初余额数据解析接口
     * 从Excel文件解析期初余额数据并返回整理后的结果
     *
     * @return 解析后的期初余额数据
     */
    public void importInitialBalance(Map<String,String> req) {
        try {
            String taxNumber = req.get("taxNumber");
            String fileName = req.get("fileName");
            String subjectFile = req.get("subjectFile");

            AccountSets accountSets = userService.uaGetAccountSetsId(taxNumber);
            if (accountSets == null) {
                System.out.println("账套信息不存在");
            }

            // 根据taxNumber 获取导入期初的模板 ，文件路径为E:\qykj\file\oldAccount\+taxNumber+期初导入模板.xlsx
            String templatePath = "E:\\qykj\\file\\oldAccount" + File.separator + taxNumber + "期初导入模板.xlsx";
            File templateFile = new File(templatePath);
            
            if (!templateFile.exists()) {
                System.out.println("期初导入模板文件不存在: " + templatePath);
            }

            // 根据fileName 获取科目余额表数据
            if (fileName == null || fileName.trim().isEmpty()) {
                System.out.println("文件名不能为空");
            }

            // 构建科目余额表文件路径
            String balanceSheetPath = "E:\\qykj\\file\\oldAccount\\" + fileName;
            File balanceSheetFile = new File(balanceSheetPath);
            
            if (!balanceSheetFile.exists()) {
                System.out.println("科目余额表文件不存在: " + balanceSheetPath);
            }

            // 构建科目对照文件路径并读取映射（旧编码 -> 模板编码）
            if (subjectFile == null || subjectFile.trim().isEmpty()) {
                System.out.println("科目对照文件名（subjectFile）不能为空");
            }
            File subjectMapFile = new File(subjectFile);
            if (!subjectMapFile.exists()) {
                System.out.println("科目对照文件不存在: " + subjectFile);
            }

            Map<String, String> oldToTemplateCodeMap = readSubjectMappingFromExcel(subjectFile);

            // 从科目余额表Excel导入数据
            List<InitialBalanceEntry> importedEntries = readInitialBalanceFromExcel(balanceSheetPath);

            if (importedEntries == null || importedEntries.isEmpty()) {
                System.out.println("科目余额表Excel文件中未找到有效的期初余额数据 ");
            }

            // 将科目余额表中的数据同步到导入期初模板中（按编码映射），并填写到Excel模板
            List<InitialBalanceEntry> syncedEntries = syncDataToTemplate(importedEntries, templatePath, oldToTemplateCodeMap);

            if (syncedEntries == null || syncedEntries.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "数据同步到模板失败");
            }
            // 将同步后的数据填写到Excel模板中
            String filledTemplatePath = fillDataToExcelTemplate(syncedEntries, templatePath, accountSets.getId(), oldToTemplateCodeMap);
            if (filledTemplatePath == null) {
                System.out.println("数据填写到Excel模板失败");
            }

            //调用接口，将期初数据导入到系统中
            initBalanceValue(taxNumber+"期初导入模板_已填写数据.xlsx",accountSets.getId(),accountSets);

        } catch (Exception e) {
            System.out.println("期初余额数据解析失败: " + e.getMessage());
        }
    }

    //导入期初
    public void initBalanceValue(String fileName,Long accountSetsId,AccountSets accountSets){
        try {
            ZipSecureFile.setMinInflateRatio(0.005);
            File file = new File("E:\\qykj\\file\\oldAccount\\"+fileName);
            InputStream fis = new FileInputStream(file);
            ExcelReader reader = ExcelUtil.getReader(fis);

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

            Map<String, SubjectVo> subjectVoMap = subjectService.list(accountSetsId).stream().collect(Collectors.toMap(SubjectVo::getCode, s -> s));
            for (BalanceImportVo importVo : balanceImportVos) {
                BalanceInitialDto balance = new BalanceInitialDto();
                balance.setAccountSetsId(accountSetsId);
                balance.setCurrencyId(importVo.getCurrencyId());
                if (balance.getCurrencyId() == null || balance.getCurrencyId() == 0L) {
                    balance.setCurrencyId(440876916778475525L);
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
                Assert.isTrue(balanceInitialService.checkAuxiliaryDetailIds(auxiliaryDetailIds, accountSetsId),
                        "期初模板错误，请模板导入~");
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
                newDto.setAccountSetsId(accountSetsId);
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
            balanceInitialService.updateInitBalance(details, accountSets);
            balanceCalcService.calc(accountSets.getId(), YearMonth.from(accountSets.getEnableDate()));
        } catch (ServiceException | IllegalArgumentException e) {
            System.out.println(e.getMessage());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }



    /**
     * 从Excel文件读取待完善科目数据
     * 智能识别表头列位置，并根据编号自动构建层级关系
     *
     * @param fileName Excel文件名
     * @return 科目列表
     * @throws IOException 文件读取异常
     */
    private List<Account> readPendingAccountsFromExcel(String fileName) throws IOException {
        List<Account> accounts = new ArrayList<>();

        // 构建文件路径（假设文件存放在特定目录）
        String filePath =  "E:\\qykj\\file\\oldAccount" + File.separator + fileName;
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("文件不存在: " + filePath);
        }

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // 读取第一个工作表

            // 查找表头行，确定各列位置
            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex == -1) {
                throw new IOException("未找到有效的表头行");
            }

            Row headerRow = sheet.getRow(headerRowIndex);
            int codeColumnIndex = findColumnIndex(headerRow, "科目编码", "科目编号", "编号", "编码","代码","科目代码");
            int nameColumnIndex = findColumnIndex(headerRow, "科目名称", "名称");
            int balanceDirectionColumnIndex = findColumnIndex(headerRow, "余额方向", "方向");
            int parentCodeColumnIndex = findColumnIndex(headerRow, "父科目编码", "父编码", "上级编码");

            if (codeColumnIndex == -1 || nameColumnIndex == -1) {
                throw new IOException("未找到必要的列：科目编码和科目名称");
            }

            // 构建编号到科目的映射，用于后续构建层级关系
            Map<String, Account> codeToAccountMap = new HashMap<>();

            // 读取数据行
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String code = getCellValueAsString(row.getCell(codeColumnIndex));
                String name = getCellValueAsString(row.getCell(nameColumnIndex));
                String balanceDirection = balanceDirectionColumnIndex != -1 ? getCellValueAsString(row.getCell(balanceDirectionColumnIndex)) : null;
                String parentCode = parentCodeColumnIndex != -1 ? getCellValueAsString(row.getCell(parentCodeColumnIndex)) : null;

                // 仅当“科目编码”存在时，才认为该行为有效科目信息
                if (code != null && !code.trim().isEmpty()) {
                    if (name == null || name.trim().isEmpty()) {
                        continue; // 无名称也跳过
                    }
                    Account account = new Account();
                    account.setCode(code.trim());
                    account.setName(name.trim());
                    account.setBalanceDirection(balanceDirection != null && !balanceDirection.trim().isEmpty() ? balanceDirection.trim() : null);
                    account.setParentCode(parentCode != null && !parentCode.trim().isEmpty() ? parentCode.trim() : null);

                    // 存储到映射中，用于构建层级关系
                    codeToAccountMap.put(account.getCode(), account);

                    accounts.add(account);
                }
            }

            // 根据编号自动构建层级关系
            buildHierarchicalNames(accounts, codeToAccountMap);
        }

        return accounts;
    }

    /**
     * 查找表头行
     *
     * @param sheet 工作表
     * @return 表头行索引，未找到返回-1
     */
    private int findHeaderRow(Sheet sheet) {
        for (int i = 0; i <= Math.min(10, sheet.getLastRowNum()); i++) { // 在前10行中查找
            Row row = sheet.getRow(i);
            if (row == null) continue;

            // 检查这一行是否包含表头关键词
            for (int j = 0; j < row.getLastCellNum(); j++) {
                Cell cell = row.getCell(j);
                String cellValue = getCellValueAsString(cell);
                if (cellValue != null && (
                        cellValue.contains("科目编码") || cellValue.contains("科目编号") ||cellValue.contains("科目代码")||
                                cellValue.contains("编号") || cellValue.contains("编码") || cellValue.contains("代码") ||
                                cellValue.contains("科目名称")
                )) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 查找列索引
     *
     * @param headerRow 表头行
     * @param keywords 关键词数组
     * @return 列索引，未找到返回-1
     */
    private int findColumnIndex(Row headerRow, String... keywords) {
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null) {
                for (String keyword : keywords) {
                    if (cellValue.contains(keyword)) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 根据编号自动构建层级关系
     *
     * @param accounts 科目列表
     * @param codeToAccountMap 编号到科目的映射
     */
    private void buildHierarchicalNames(List<Account> accounts, Map<String, Account> codeToAccountMap) {
        for (Account account : accounts) {
            if (account.getCode() == null) continue;

            // 根据编号长度和规则推断层级
            String hierarchicalName = buildHierarchicalName(account.getCode(), codeToAccountMap);
            if (hierarchicalName != null) {
                account.setName(hierarchicalName);
                
                // 设置父类科目名称
                if (hierarchicalName.contains(">")) {
                    int lastIndex = hierarchicalName.lastIndexOf(">");
                    String parentName = hierarchicalName.substring(0, lastIndex);
                    account.setParentName(parentName);
                }
            }
        }
    }

    /**
     * 构建层级名称
     *
     * @param code 科目编码
     * @param codeToAccountMap 编号到科目的映射
     * @return 层级名称
     */
    private String buildHierarchicalName(String code, Map<String, Account> codeToAccountMap) {
        // 查找直接父级编码
        String parentCode = inferParentCodeSmart(code, codeToAccountMap);

        if (parentCode != null) {
            Account currentAccount = codeToAccountMap.get(code);
            Account parentAccount = codeToAccountMap.get(parentCode);

            if (currentAccount != null && parentAccount != null) {
                // 直接使用父级科目的完整名称 + 当前科目名称
                return parentAccount.getName() + ">" + currentAccount.getName();
            }
        }

        // 如果没有父级，直接返回当前科目名称
        Account currentAccount = codeToAccountMap.get(code);
        return currentAccount != null ? currentAccount.getName() : null;
    }
    /**
     * 智能推断父级编码
     * 规则：从当前编码开始，逐个删除最后一个字符，形成前缀。
     *      只要这个前缀在现有编码映射中存在（有且仅有一个键命中，因为键唯一），就认定为父级编码。
     *
     * @param code 当前编码
     * @param codeToAccountMap 编码 -> 科目 的映射（readPendingAccountsFromExcel 已构建）
     * @return 父级编码；未找到则返回 null
     */
    private String inferParentCodeSmart(String code, Map<String, Account> codeToAccountMap) {
        if (code == null) return null;
        // 逐个删除最后一个字符，直到长度为 1 停止
        for (int len = code.length() - 1; len >= 1; len--) {
            String candidate = code.substring(0, len);
            if (codeToAccountMap.containsKey(candidate)) {
                return candidate; // 命中即返回（Map 键唯一，天然“仅有一个匹配到”）
            }
        }
        return null;
    }

    /**
     * 获取单元格的字符串值
     *
     * @param cell 单元格
     * @return 字符串值
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return null;
        }
    }

    // removed: unused extractSelfName

    // 字符串标准化：去除所有空白（含全角空格）、转小写、去除首尾空白
    // removed: unused normalizeString

    // 安全比较（标准化后比较，允许两者均为空时视为相等）
    // removed: unused equalsNormalized

    // 类别兼容判定：若要求类别为空则不限制；否则两者相等才算兼容
    // removed: unused isCategoryCompatible

    // 编码大类兼容：若任一编码为空则放宽；否则要求首字符一致（如 1资产、2负债 等）
    // removed: unused isCodeClassCompatible

    public String extractParentName(String accountName){
        if(accountName == null) return null;
        if(accountName.split(">").length==1) return null;
        int idx = accountName.split(">").length;
        return idx > 1 ? accountName.split(">")[idx-1] : accountName;
    }

    /**
     * 从Excel文件读取期初余额数据
     * 智能识别表头列位置，并根据要求解析数据
     * 支持合并单元格的处理
     *
     * @param filePath Excel文件路径
     * @return 期初余额记录列表
     * @throws IOException 文件读取异常
     */
    private List<InitialBalanceEntry> readInitialBalanceFromExcel(String filePath) throws IOException {
        List<InitialBalanceEntry> entries = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // 读取第一个工作表

            // 查找表头行，确定各列位置
            HeaderInfo headerInfo = findInitialBalanceHeader(sheet);
            if (headerInfo == null) {
                throw new IOException("未找到有效的表头行");
            }

            // 在读取数据前，定位期初余额的借方/贷方列
            int beginDebitCol = findFinancialColumnIndexImproved(sheet, headerInfo.dataStartRow - 1, "期初余额", "借方");
            int beginCreditCol = findFinancialColumnIndexImproved(sheet, headerInfo.dataStartRow - 1, "期初余额", "贷方");

            // 读取数据行
            for (int i = headerInfo.dataStartRow; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String code = getCellValueAsString(row.getCell(headerInfo.codeColumnIndex));
                String name = getCellValueAsString(row.getCell(headerInfo.nameColumnIndex));
                BigDecimal yearC = headerInfo.yearCColumnIndex != -1 ? getCellValueAsBigDecimal(row.getCell(headerInfo.yearCColumnIndex)) : BigDecimal.ZERO;
                BigDecimal yearD = headerInfo.yearDColumnIndex != -1 ? getCellValueAsBigDecimal(row.getCell(headerInfo.yearDColumnIndex)) : BigDecimal.ZERO;
                BigDecimal lastC = headerInfo.lastCColumnIndex != -1 ? getCellValueAsBigDecimal(row.getCell(headerInfo.lastCColumnIndex)) : BigDecimal.ZERO;
                BigDecimal lastD = headerInfo.lastDColumnIndex != -1 ? getCellValueAsBigDecimal(row.getCell(headerInfo.lastDColumnIndex)) : BigDecimal.ZERO;
                BigDecimal beginDebit = beginDebitCol != -1 ? getCellValueAsBigDecimal(row.getCell(beginDebitCol)) : BigDecimal.ZERO;
                BigDecimal beginCredit = beginCreditCol != -1 ? getCellValueAsBigDecimal(row.getCell(beginCreditCol)) : BigDecimal.ZERO;

                // 检查是否有空值，如果有则跳过这一行
                if (code == null || code.trim().isEmpty() ||
                        name == null || name.trim().isEmpty()) {
                    continue;
                }

                // 创建期初余额记录（将期初余额借/贷放入debitAmount/creditAmount）
                InitialBalanceEntry entry = new InitialBalanceEntry(
                        code.trim(),
                        name.trim(),
                        beginDebit,
                        beginCredit,
                        yearC,
                        yearD,
                        lastC,
                        lastD
                );

                entries.add(entry);
            }
        }

        return entries;
    }

    /**
     * 读取科目对照Excel：A列=科目编码(模板编码)，G列=旧账科目编码。
     * 返回映射：旧账科目编码(G) -> 科目编码(A)
     * Excel结构简单，直接按固定列读取，默认首行为表头，从第2行开始。
     */
    private Map<String, String> readSubjectMappingFromExcel(String filePath) throws IOException {
        Map<String, String> mapping = new HashMap<>();
        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return mapping;

            // 固定列：A=0, G=6；从第2行开始读取
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String templateCode = getCellValueAsString(row.getCell(0)); // A列 科目编码
                String oldCode = getCellValueAsString(row.getCell(6));      // G列 旧账科目编码
                if (templateCode == null || oldCode == null) continue;
                templateCode = templateCode.trim();
                oldCode = oldCode.trim();
                if (!templateCode.isEmpty() && !oldCode.isEmpty()) {
                    // 返回：旧 -> 新(模板)
                    mapping.put(oldCode, templateCode);
                }
            }
        } catch (Exception e) {
            System.err.println("读取科目对照文件失败: " + e.getMessage());
        }
        return mapping;
    }

    /**
     * 查找期初余额表头行
     *
     * @param sheet 工作表
     * @return 表头行索引，未找到返回-1
     */
    // removed: unused findInitialBalanceHeaderRow

    /**
     * 获取单元格的BigDecimal值
     *
     * @param cell 单元格
     * @return BigDecimal值
     */
    private BigDecimal getCellValueAsBigDecimal(Cell cell) {
        if (cell == null) return BigDecimal.ZERO;

        switch (cell.getCellType()) {
            case NUMERIC:
                return BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING:
                try {
                    String value = cell.getStringCellValue().trim();
                    if (value.isEmpty()) return BigDecimal.ZERO;
                    return new BigDecimal(value);
                } catch (NumberFormatException e) {
                    return BigDecimal.ZERO;
                }
            case FORMULA:
                try {
                    return BigDecimal.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    try {
                        return new BigDecimal(cell.getStringCellValue());
                    } catch (NumberFormatException ex) {
                        return BigDecimal.ZERO;
                    }
                }
            default:
                return BigDecimal.ZERO;
        }
    }

    // removed: unused inferCategory

    // removed: unused categoryByCodePrefix

    // removed: unused buildAccountRowWithCategory

    /**
     * 表头信息类
     * 用于存储Excel表头的列索引信息
     */
    private static class HeaderInfo {
        int codeColumnIndex;
        int nameColumnIndex;
        int yearCColumnIndex;
        int yearDColumnIndex;
        int lastCColumnIndex;
        int lastDColumnIndex;
        int dataStartRow;

        HeaderInfo(int codeColumnIndex, int nameColumnIndex, int yearCColumnIndex, int yearDColumnIndex,
                   int lastCColumnIndex, int lastDColumnIndex, int dataStartRow) {
            this.codeColumnIndex = codeColumnIndex;
            this.nameColumnIndex = nameColumnIndex;
            this.yearCColumnIndex = yearCColumnIndex;
            this.yearDColumnIndex = yearDColumnIndex;
            this.lastCColumnIndex = lastCColumnIndex;
            this.lastDColumnIndex = lastDColumnIndex;
            this.dataStartRow = dataStartRow;
        }
    }

    /**
     * 查找期初余额表头信息
     * 支持合并单元格的处理
     *
     * @param sheet 工作表
     * @return 表头信息，未找到返回null
     */
    private HeaderInfo findInitialBalanceHeader(Sheet sheet) {
        // 查找包含"科目编码"或"科目名称"的行
        int headerRowIndex = -1;
        for (int i = 0; i <= Math.min(10, sheet.getLastRowNum()); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            for (int j = 0; j < row.getLastCellNum(); j++) {
                Cell cell = row.getCell(j);
                String cellValue = getCellValueAsString(cell);
                if (cellValue != null && (
                        cellValue.contains("科目编码") || cellValue.contains("科目编号") ||
                                cellValue.contains("编码") || cellValue.contains("编号") ||
                                cellValue.contains("科目名称")
                )) {
                    headerRowIndex = i;
                    break;
                }
            }
            if (headerRowIndex != -1) break;
        }

        if (headerRowIndex == -1) return null;

        // 查找科目编码和科目名称列
        Row headerRow = sheet.getRow(headerRowIndex);
        int codeColumnIndex = findColumnIndex(headerRow, "科目编码", "科目编号", "编码", "编号");
        int nameColumnIndex = findColumnIndex(headerRow, "科目名称", "名称");

        if (codeColumnIndex == -1 || nameColumnIndex == -1) return null;

        // 查找财务数据列（处理合并单元格）
        int yearCColumnIndex = findFinancialColumnIndexImproved(sheet, headerRowIndex, "本年累计发生额", "借方");
        int yearDColumnIndex = findFinancialColumnIndexImproved(sheet, headerRowIndex, "本年累计发生额", "贷方");
        int lastCColumnIndex = findFinancialColumnIndexImproved(sheet, headerRowIndex, "期末余额", "借方");
        int lastDColumnIndex = findFinancialColumnIndexImproved(sheet, headerRowIndex, "期末余额", "贷方");

        // 打印调试信息
        System.out.println("=== 表头查找结果 ===");
        System.out.println("表头行索引: " + headerRowIndex);
        System.out.println("科目编码列: " + codeColumnIndex);
        System.out.println("科目名称列: " + nameColumnIndex);
        System.out.println("本年累计发生额借方列: " + yearCColumnIndex);
        System.out.println("本年累计发生额贷方列: " + yearDColumnIndex);
        System.out.println("期末余额借方列: " + lastCColumnIndex);
        System.out.println("期末余额贷方列: " + lastDColumnIndex);
        System.out.println("==================");

        // 数据开始行是表头行+1
        int dataStartRow = headerRowIndex + 1;

        return new HeaderInfo(codeColumnIndex, nameColumnIndex, yearCColumnIndex, yearDColumnIndex,
                lastCColumnIndex, lastDColumnIndex, dataStartRow);
    }

    /**
     * 改进的财务数据列查找方法
     * 更好地处理合并单元格和列位置
     *
     * @param sheet 工作表
     * @param headerRowIndex 表头行索引
     * @param categoryName 类别名称（如"期末余额"）
     * @param direction 方向（"借方"或"贷方"）
     * @return 列索引，未找到返回-1
     */
    private int findFinancialColumnIndexImproved(Sheet sheet, int headerRowIndex, String categoryName, String direction) {
        // 先查找类别列
        int categoryStartCol = -1;
        int categoryEndCol = -1;

        // 在表头行查找类别名称
        Row headerRow = sheet.getRow(headerRowIndex);
        for (int col = 0; col < headerRow.getLastCellNum(); col++) {
            Cell cell = headerRow.getCell(col);
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null && cellValue.contains(categoryName)) {
                categoryStartCol = col;

                // 查找合并单元格的结束列
                // 方法1：通过POI的合并单元格信息
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                    org.apache.poi.ss.util.CellRangeAddress range = sheet.getMergedRegion(i);
                    if (range.getFirstRow() == headerRowIndex && range.getFirstColumn() == col) {
                        categoryEndCol = range.getLastColumn();
                        break;
                    }
                }

                // 方法2：如果方法1失败，通过查找空单元格
                if (categoryEndCol == -1) {
                    for (int endCol = col; endCol < headerRow.getLastCellNum(); endCol++) {
                        if (headerRow.getCell(endCol) == null) {
                            categoryEndCol = endCol - 1;
                            break;
                        }
                    }
                    if (categoryEndCol == -1) {
                        categoryEndCol = headerRow.getLastCellNum() - 1;
                    }
                }

                break;
            }
        }

        if (categoryStartCol == -1) {
            System.out.println("未找到类别: " + categoryName);
            return -1;
        }

        // 在下一行查找方向（借方/贷方）
        Row directionRow = sheet.getRow(headerRowIndex + 1);
        if (directionRow == null) {
            System.out.println("方向行不存在: " + (headerRowIndex + 1));
            return -1;
        }

        System.out.println("查找类别: " + categoryName + ", 方向: " + direction + ", 列范围: " + categoryStartCol + " - " + categoryEndCol);

        // 在类别列范围内查找方向
        for (int col = categoryStartCol; col <= categoryEndCol; col++) {
            Cell cell = directionRow.getCell(col);
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null && cellValue.contains(direction)) {
                System.out.println("找到匹配列: " + col + ", 值: " + cellValue);
                return col;
            }
        }

        // 如果没找到，尝试模糊匹配
        for (int col = categoryStartCol; col <= categoryEndCol; col++) {
            Cell cell = directionRow.getCell(col);
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null) {
                // 借方匹配
                if (direction.contains("借方") && (cellValue.contains("借") || cellValue.contains("Dr") || cellValue.contains("D"))) {
                    System.out.println("模糊匹配借方列: " + col + ", 值: " + cellValue);
                    return col;
                }
                // 贷方匹配
                if (direction.contains("贷方") && (cellValue.contains("贷") || cellValue.contains("Cr") || cellValue.contains("C"))) {
                    System.out.println("模糊匹配贷方列: " + col + ", 值: " + cellValue);
                    return col;
                }
            }
        }

        System.out.println("未找到方向: " + direction + " 在类别: " + categoryName + " 中");
        return -1;
    }

    // removed: unused buildLocalFilePath

    /**
     * 将科目余额表中的数据同步到导入期初模板中
     * 
     * @param importedEntries 从科目余额表导入的数据
     * @param templatePath 期初导入模板文件路径
     * @return 同步后的数据列表
     */
    private List<InitialBalanceEntry> syncDataToTemplate(List<InitialBalanceEntry> importedEntries, String templatePath, Map<String, String> oldToTemplateCodeMap) {
        try {
            // 读取模板文件
            try (FileInputStream fis = new FileInputStream(templatePath);
                 Workbook workbook = WorkbookFactory.create(fis)) {
                
                Sheet sheet = workbook.getSheetAt(0); // 读取第一个工作表
                
                // 查找模板中的表头行
                int headerRowIndex = findTemplateHeaderRow(sheet);
                if (headerRowIndex == -1) {
                    System.out.println("模板中未找到有效的表头行");
                    return importedEntries; // 如果找不到表头，直接返回原数据
                }
                
                // 获取模板中的列索引
                Row headerRow = sheet.getRow(headerRowIndex);
                int templateCodeCol = findColumnIndex(headerRow, "科目编码", "科目编号", "编码", "编号");
                int templateNameCol = findColumnIndex(headerRow, "科目名称", "名称");
                int templateDebitCol = findColumnIndex(headerRow, "期初余额", "借方", "借");
                int templateCreditCol = findColumnIndex(headerRow, "期初余额", "贷方", "贷");
                int templateBalanceDirectionCol = findColumnIndex(headerRow, "余额方向", "方向");
                
                if (templateCodeCol == -1 || templateNameCol == -1) {
                    System.out.println("模板中未找到必要的列：科目编码和科目名称");
                    return importedEntries;
                }
                
                // 仅按编码匹配：构建 模板编码 -> 行号、模板编码 -> 余额方向
                Map<String, Integer> templateCodeToRowMap = new HashMap<>();
                Map<String, String> templateCodeToBalanceDirectionMap = new HashMap<>();
                
                for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    
                    String code = templateCodeCol != -1 ? getCellValueAsString(row.getCell(templateCodeCol)) : null;
                    if (code != null && !code.trim().isEmpty()) {
                        templateCodeToRowMap.put(code.trim(), i);
                        if (templateBalanceDirectionCol != -1) {
                            String balanceDirection = getCellValueAsString(row.getCell(templateBalanceDirectionCol));
                            if (balanceDirection != null && !balanceDirection.trim().isEmpty()) {
                                templateCodeToBalanceDirectionMap.put(code.trim(), balanceDirection.trim());
                            }
                        }
                    }
                }
                
                // 将导入的数据同步到模板中
                List<InitialBalanceEntry> syncedEntries = new ArrayList<>();
                for (InitialBalanceEntry entry : importedEntries) {
                    // 仅按编码映射匹配模板行
                    String mappedCode = oldToTemplateCodeMap != null ? oldToTemplateCodeMap.get(entry.getAccountNumber()) : null;
                    if (mappedCode != null && templateCodeToRowMap.containsKey(mappedCode)) {
                        Integer templateRowIndex = templateCodeToRowMap.get(mappedCode);
                        // 找到匹配的模板行，更新期初余额
                        Row templateRow = sheet.getRow(templateRowIndex);
                        
                        if (templateRow != null) {
                            // 获取模板中该科目的余额方向（按编码）
                            String templateBalanceDirection = templateCodeToBalanceDirectionMap.get(mappedCode);
                            
                            // 根据余额方向判断是否需要转换数据
                            BigDecimal adjustedLastC = entry.getLastC(); // 期末余额借方
                            BigDecimal adjustedLastD = entry.getLastD(); // 期末余额贷方
                            
                            if (templateBalanceDirection != null) {
                                if ("借".equals(templateBalanceDirection) || "借方".equals(templateBalanceDirection)) {
                                    // 科目余额方向为借方，期末余额应该在借方列
                                    if (entry.getLastD() != null && entry.getLastD().compareTo(BigDecimal.ZERO) > 0) {
                                        // 如果期末余额在贷方，需要转换到借方（乘以-1）
                                        adjustedLastC = entry.getLastD().multiply(new BigDecimal("-1"));
                                        adjustedLastD = BigDecimal.ZERO;
                                    }
                                } else if ("贷".equals(templateBalanceDirection) || "贷方".equals(templateBalanceDirection)) {
                                    // 科目余额方向为贷方，期末余额应该在贷方列
                                    if (entry.getLastC() != null && entry.getLastC().compareTo(BigDecimal.ZERO) > 0) {
                                        // 如果期末余额在借方，需要转换到贷方（乘以-1）
                                        adjustedLastD = entry.getLastC().multiply(new BigDecimal("-1"));
                                        adjustedLastC = BigDecimal.ZERO;
                                    }
                                }
                            }
                            
                            // 更新期初余额借方
                            if (templateDebitCol != -1 && adjustedLastC != null && adjustedLastC.compareTo(BigDecimal.ZERO) != 0) {
                                Cell debitCell = templateRow.getCell(templateDebitCol);
                                if (debitCell == null) {
                                    debitCell = templateRow.createCell(templateDebitCol);
                                }
                                debitCell.setCellValue(adjustedLastC.doubleValue());
                            }
                            
                            // 更新期初余额贷方
                            if (templateCreditCol != -1 && adjustedLastD != null && adjustedLastD.compareTo(BigDecimal.ZERO) != 0) {
                                Cell creditCell = templateRow.getCell(templateCreditCol);
                                if (creditCell == null) {
                                    creditCell = templateRow.createCell(templateCreditCol);
                                }
                                creditCell.setCellValue(adjustedLastD.doubleValue());
                            }
                        }
                        
                        // 添加到同步后的数据列表
                        syncedEntries.add(entry);
                    } // 不再按名称回退，也不写入模板
                }
                
                // 保存更新后的模板文件
                String updatedTemplatePath = templatePath.replace(".xlsx", "_已同步.xlsx");
                try (FileOutputStream fos = new FileOutputStream(updatedTemplatePath)) {
                    workbook.write(fos);
                }
                
                System.out.println("模板文件已更新并保存为: " + updatedTemplatePath);
                return syncedEntries;
                
            } catch (IOException e) {
                System.err.println("同步数据到模板失败: " + e.getMessage());
                return importedEntries; // 如果同步失败，返回原数据
            }
            
        } catch (Exception e) {
            System.err.println("同步数据到模板过程中发生错误: " + e.getMessage());
            return importedEntries; // 如果发生异常，返回原数据
        }
    }
    
    /**
     * 查找模板中的表头行
     * 
     * @param sheet 工作表
     * @return 表头行索引，未找到返回-1
     */
    private int findTemplateHeaderRow(Sheet sheet) {
        for (int i = 0; i <= Math.min(10, sheet.getLastRowNum()); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            
            // 检查这一行是否包含表头关键词
            for (int j = 0; j < row.getLastCellNum(); j++) {
                Cell cell = row.getCell(j);
                String cellValue = getCellValueAsString(cell);
                if (cellValue != null && (
                        cellValue.contains("科目编码") || cellValue.contains("科目编号") ||
                        cellValue.contains("编码") || cellValue.contains("编号") ||
                        cellValue.contains("科目名称") || cellValue.contains("名称")
                )) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 将同步后的数据填写到Excel模板中
     * 
     * @param syncedEntries 同步后的数据列表
     * @param templatePath 原始模板文件路径
     * @param accountSetId 账套ID
     * @return 填写数据后的模板文件路径，失败返回null
     */
    private String fillDataToExcelTemplate(List<InitialBalanceEntry> syncedEntries, String templatePath, Long accountSetId, Map<String, String> oldToTemplateCodeMap) {
        try {
            // 读取模板文件
            try (FileInputStream fis = new FileInputStream(templatePath);
                 Workbook workbook = WorkbookFactory.create(fis)) {
                
                Sheet sheet = workbook.getSheetAt(0); // 读取第一个工作表
                
                // 查找模板中的表头行
                int headerRowIndex = findTemplateHeaderRow(sheet);
                if (headerRowIndex == -1) {
                    System.out.println("模板中未找到有效的表头行");
                    return null;
                }
                
                // 获取模板中的列索引
                Row headerRow = sheet.getRow(headerRowIndex);
                int templateCodeCol = findColumnIndex(headerRow, "科目编码", "科目编号", "编码", "编号");
                int templateNameCol = findColumnIndex(headerRow, "科目名称", "名称");
                // 期初余额列（对应科目余额表的期末余额）
                int templateInitialDebitCol = findColumnIndex(headerRow, "期初余额", "借方", "借");
                int templateInitialCreditCol = findColumnIndex(headerRow, "期初余额", "贷方", "贷");
                // 借方累计列（对应科目余额表的本年累计发生额借方）
                int templateYearDebitCol = findColumnIndex(headerRow, "借方累计", "本年累计发生额", "借方", "借");
                // 贷方累计列（对应科目余额表的本年累计发生额贷方）
                int templateYearCreditCol = findColumnIndex(headerRow, "贷方累计", "本年累计发生额", "贷方", "贷");
                int templateBalanceDirectionCol = findColumnIndex(headerRow, "余额方向", "方向");
                
                if (templateCodeCol == -1 || templateNameCol == -1) {
                    System.out.println("模板中未找到必要的列：科目编码和科目名称");
                    return null;
                }
                
                // 构建模板中科目编码、名称到行索引的映射（优先编码匹配）
                // 仅按编码匹配：构建 模板编码 -> 行号、模板编码 -> 余额方向
                Map<String, Integer> templateCodeToRowMap = new HashMap<>();
                Map<String, String> templateCodeToBalanceDirectionMap = new HashMap<>();
                
                for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    String code = templateCodeCol != -1 ? getCellValueAsString(row.getCell(templateCodeCol)) : null;
                    if (code != null && !code.trim().isEmpty()) {
                        templateCodeToRowMap.put(code.trim(), i);
                        // 获取余额方向
                        if (templateBalanceDirectionCol != -1) {
                            String balanceDirection = getCellValueAsString(row.getCell(templateBalanceDirectionCol));
                            if (balanceDirection != null && !balanceDirection.trim().isEmpty()) {
                                templateCodeToBalanceDirectionMap.put(code.trim(), balanceDirection.trim());
                            }
                        }
                    }
                }
                
                // 将同步后的数据填写到模板中
                int filledRowCount = 0;
                for (InitialBalanceEntry entry : syncedEntries) {
                    // 仅按编码映射匹配模板行
                    String mappedCode = oldToTemplateCodeMap != null ? oldToTemplateCodeMap.get(entry.getAccountNumber()) : null;
                    if (mappedCode != null && templateCodeToRowMap.containsKey(mappedCode)) {
                        int templateRowIndex = templateCodeToRowMap.get(mappedCode);
                        // 找到匹配的模板行，填写数据
                        Row templateRow = sheet.getRow(templateRowIndex);
                        
                        if (templateRow != null) {
                            // 获取模板中该科目的余额方向（按编码）
                            String templateBalanceDirection = templateCodeToBalanceDirectionMap.get(mappedCode);
                            
                            // 期初余额方向调整：仅对期初余额（对应期末余额）按余额方向不一致时乘以-1；本年累计不做调整
                            BigDecimal adjustedLastC = entry.getLastC(); // 期末余额借方
                            BigDecimal adjustedLastD = entry.getLastD(); // 期末余额贷方
                            
                            if (templateBalanceDirection != null) {
                                if ("借".equals(templateBalanceDirection) || "借方".equals(templateBalanceDirection)) {
                                    // 科目余额方向为借方，期末余额应在借方列
                                    if (entry.getLastD() != null && entry.getLastD().compareTo(BigDecimal.ZERO) > 0) {
                                        adjustedLastC = entry.getLastD().multiply(new BigDecimal("-1"));
                                        adjustedLastD = BigDecimal.ZERO;
                                    }
                                } else if ("贷".equals(templateBalanceDirection) || "贷方".equals(templateBalanceDirection)) {
                                    // 科目余额方向为贷方，期末余额应在贷方列
                                    if (entry.getLastC() != null && entry.getLastC().compareTo(BigDecimal.ZERO) > 0) {
                                        adjustedLastD = entry.getLastC().multiply(new BigDecimal("-1"));
                                        adjustedLastC = BigDecimal.ZERO;
                                    }
                                }
                            }
                            
                            // 如需回填名称，可在此启用；当前仅按编码匹配，不强制写入名称
                            
                            // 填写期初余额（对应科目余额表的期末余额）
                            // 期初余额借方
                            if (templateInitialDebitCol != -1 && adjustedLastC != null && adjustedLastC.compareTo(BigDecimal.ZERO) != 0) {
                                Cell initialDebitCell = templateRow.getCell(templateInitialDebitCol);
                                if (initialDebitCell == null) {
                                    initialDebitCell = templateRow.createCell(templateInitialDebitCol);
                                }
                                initialDebitCell.setCellValue(adjustedLastC.doubleValue());
                            }
                            
                            // 期初余额贷方
                            if (templateInitialCreditCol != -1 && adjustedLastD != null && adjustedLastD.compareTo(BigDecimal.ZERO) != 0) {
                                Cell initialCreditCell = templateRow.getCell(templateInitialCreditCol);
                                if (initialCreditCell == null) {
                                    initialCreditCell = templateRow.createCell(templateInitialCreditCol);
                                }
                                initialCreditCell.setCellValue(adjustedLastD.doubleValue());
                            }
                            
                            // 填写借方累计（对应科目余额表的本年累计发生额借方）
                            if (templateYearDebitCol != -1 && entry.getYearC() != null && entry.getYearC().compareTo(BigDecimal.ZERO) != 0) {
                                Cell yearDebitCell = templateRow.getCell(templateYearDebitCol);
                                if (yearDebitCell == null) {
                                    yearDebitCell = templateRow.createCell(templateYearDebitCol);
                                }
                                yearDebitCell.setCellValue(entry.getYearC().doubleValue());
                            }
                            
                            // 填写贷方累计（对应科目余额表的本年累计发生额贷方）
                            if (templateYearCreditCol != -1 && entry.getYearD() != null && entry.getYearD().compareTo(BigDecimal.ZERO) != 0) {
                                Cell yearCreditCell = templateRow.getCell(templateYearCreditCol);
                                if (yearCreditCell == null) {
                                    yearCreditCell = templateRow.createCell(templateYearCreditCol);
                                }
                                yearCreditCell.setCellValue(entry.getYearD().doubleValue());
                            }
                            
                            filledRowCount++;
                        }
                    }
                }
                
                // 保存填写数据后的模板文件
                String filledTemplatePath = templatePath.replace(".xlsx", "_已填写数据.xlsx");
                try (FileOutputStream fos = new FileOutputStream(filledTemplatePath)) {
                    workbook.write(fos);
                }
                
                System.out.println("成功填写 " + filledRowCount + " 行数据到模板，文件保存为: " + filledTemplatePath);
                return filledTemplatePath;
                
            } catch (IOException e) {
                System.err.println("填写数据到Excel模板失败: " + e.getMessage());
                return null;
            }
            
        } catch (Exception e) {
            System.err.println("填写数据到Excel模板过程中发生错误: " + e.getMessage());
            return null;
        }
    }
    /**
     * 有单位核算的期初导入
     *
     * @param balanceVos
     * @return
     * @throws IOException
     */
    private ExcelWriter withUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,,借方累计,,贷方累计,,年初余额,,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        List<String> columns2 = StrUtil.split(",,,,,,数量,金额,数量,金额,数量,金额,数量,金额", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);
        writer.writeHeadRow(columns2);

        writer.merge(0, 1, 0, 0, "科目编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 1, 1, "科目名称", writer.getHeadCellStyle());
        writer.merge(0, 1, 2, 2, "方向", writer.getHeadCellStyle());
        writer.merge(0, 1, 3, 3, "辅助核算项目", writer.getHeadCellStyle());
        writer.merge(0, 1, 4, 4, "辅助核算编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 5, 5, "辅助核算名称", writer.getHeadCellStyle());
        writer.merge(0, 0, 6, 7, "期初余额", writer.getHeadCellStyle());
        writer.merge(0, 0, 8, 9, "借方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 10, 11, "贷方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 12, 13, "年初余额", writer.getHeadCellStyle());

        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.setColumnHidden(17, true);
        sheet.setColumnHidden(18, true);
        sheet.setColumnHidden(19, true);
        sheet.setColumnHidden(20, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);

        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);

        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        Short slFormat = StyleUtil.getFormat(writer.getWorkbook(), "0.0000;[Red](0.0000)");

        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);

        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());
            row.add(vo.isAd() ? "是" : "否");
            //辅助核算的名称
            row.add(vo.getAuxiliaryName());
            row.add(vo.getAuxiliaryCode());
            row.add(nullZeroToEmptyStr(vo.getBeginQuantity()));
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebitQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCreditQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));
            int currentRow = writer.getCurrentRow();

            writer.writeRow(row);

            Row r = writer.getOrCreateRow(currentRow);

            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 14; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }
                for (int i = 6; i < 14; i++) {
                    CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cellStyle.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cellStyle);
                }
            } else {
                for (int i = 6; i < 12; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }

            for (int i = 6; i < 12; i++) {
                Cell cell = r.getCell(i);
                CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
                if (i % 2 == 0) {
                    cellStyle.setDataFormat(slFormat);
                    //如果科目未设置数量核算，则需要关闭数量单元编辑
                    if (StrUtil.isEmpty(vo.getUnit())) {
                        cellStyle.setLocked(true);
                    }
                } else {
                    cellStyle.setDataFormat(format);
                }
                cell.setCellStyle(cellStyle);
            }

            Cell slCell = r.getCell(12);
            CellStyle slCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), slCell.getCellStyle());
            slCellStyle.setDataFormat(slFormat);
            StyleUtil.setAlign(slCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            slCell.setCellStyle(slCellStyle);
            slCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0)))");

            Cell jeCell = r.getCell(13);
            CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), jeCell.getCellStyle());
            cellStyle.setDataFormat(format);
            StyleUtil.setAlign(cellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            jeCell.setCellStyle(cellStyle);
            jeCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0)))");
        }

        return writer;
    }
    /**
     * 无单位核算的期初导入
     *
     * @param balanceVos
     * @return
     */
    private ExcelWriter withoutUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,借方累计,贷方累计,年初余额,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);

        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(10, true);
        sheet.setColumnHidden(11, true);
        sheet.setColumnHidden(12, true);
        sheet.setColumnHidden(13, true);
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);

        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);

        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);

        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());

            row.add(vo.isAd() ? "是" : "否");
            row.add(vo.getAuxiliaryCode());
            row.add(vo.getAuxiliaryName());
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));

            int currentRow = writer.getCurrentRow();
            writer.writeRow(row);
            Row r = writer.getOrCreateRow(currentRow);
            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 10; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }

                for (int i = 6; i < 10; i++) {
                    CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cellStyle.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cellStyle);
                }
            } else {
                for (int i = 6; i < 9; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }
            Cell cell = r.getCell(9);
            CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
            cellStyle.setDataFormat(format);
            StyleUtil.setAlign(cellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            cell.setCellStyle(cellStyle);
            cell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0)))");
        }
        return writer;
    }
    private Object nullZeroToEmptyStr(Number vo) {
        return vo == null || vo.longValue() == 0L ? "" : vo;
    }

    /**
     * 计算累计
     *
     * @param balanceDto
     * @param d
     */
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