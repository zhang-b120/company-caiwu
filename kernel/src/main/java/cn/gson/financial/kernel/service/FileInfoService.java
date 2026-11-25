package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.common.ExcelRecognizeUtils;
import cn.gson.financial.kernel.common.MapToEntityUtils;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.FileInfoDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.FileInfoRepository;
import cn.gson.financial.kernel.model.vo.FileInfoVo;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.lang.Assert;
import com.blazebit.persistence.PagedList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-07-16
 * @Description 文件信息服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileInfoService extends AbsService {

    private final FileInfoRepository fileInfoRepository;
    private final QFileInfo qFileInfo = QFileInfo.fileInfo;
    private final BankExcelService bankExcelService;
    private final CashierJournalService cashierJournalService;
    private final CashierAccountService cashierAccountService;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QCashierAccount qCashierAccount = QCashierAccount.cashierAccount;
    private final QBankLib qBankLib = QBankLib.bankLib;

    @Value("${customize-config.screen-bank-path}")
    private String saveFinancialBankPath;

    @Value("${static-url.base-url}")
    private String baseUrl;

    @Value("${static-url.financial-bank}")
    private String financialBankUrl;

    /**
     * 上传文件
     *
     * @param file
     * @param accountSetsId
     * @param period
     * @param type
     * @param fileName
     * @param businessCode
     * @param businessName
     * @return
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Boolean uploadFile(MultipartFile file, Long accountSetsId, LocalDate period, String type,
                              String fileName, String businessCode, String businessName) {
        if(StringUtils.isEmpty(fileName)){
            fileName = file.getOriginalFilename();
        }
        String fileSuffix = FileNameUtil.getSuffix(fileName);
        // 获取文件保存路径
        String savePath = getSavePath(type);
        Assert.notEmpty(savePath, "文件上传失败，文件保存路径不存在");
        // 拼装文件名 账套ID-时间戳
        String saveFileName = accountSetsId + "-" + System.currentTimeMillis()  + "." + fileSuffix;
        try {
            File dir = new File(savePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File saveFile = new File(dir, saveFileName);
            // 保存文件
            file.transferTo(saveFile);

            FileInfo fileInfo = new FileInfo();
            fileInfo.setAccountSetsId(accountSetsId);
            fileInfo.setFilePath(baseUrl + financialBankUrl + saveFileName);
            fileInfo.setOriginalFileName(fileName);
            fileInfo.setFileName(saveFileName);
            fileInfo.setFileSuffix(fileSuffix);
            fileInfo.setAccountSetPeriod(period);
            fileInfo.setType(type);
            fileInfo.setCreateTime(LocalDateTime.now());
            fileInfo.setIsDelete(0);
            fileInfo.setIsDeal(0);
            fileInfo.setBusinessCode(businessCode);
            fileInfo.setBusinessName(businessName);
            // 处理文件逻辑放在单独事务中，出现异常不影响上面的保存
//            try {
//                if (dealFile(fileInfo, accountSetsId)) {
//                    fileInfo.setIsDeal(1);
//                } else {
//                    fileInfo.setIsDeal(0);
//                }
//            } catch (Exception e) {
//                fileInfo.setIsDeal(0);
//            }
            fileInfo.setIsDeal(0);
            // 将处理结果写回数据库（独立于处理事务）
            saveFile(fileInfo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Boolean.TRUE;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileInfo saveFile(FileInfo fileInfo){
        return fileInfoRepository.save(fileInfo);
    }

    /**
     * 根据上传类型 获取保存
     *
     * @param type
     * @return
     */
    private String getSavePath(String type) {
        // todo
        // 后期常用可改为枚举获取或进行其他合理优化
        String path = null;
        if ("bankStatement".equals(type)) {
            path = saveFinancialBankPath;
        }
        return path;
    }

    /**
     * 获取银行文件列表
     *
     * @param accountSetsId
     * @param page
     * @param type
     * @param period
     * @return
     */
    public PageResults<FileInfoDto> list(Long accountSetsId, Page page, String type, LocalDate period) {
        PagedList<FileInfo> fileInfoList = bqf.selectFrom(qFileInfo)
                .where(qFileInfo.accountSetsId.eq(accountSetsId)
                        .and(qFileInfo.type.eq(type))
                        .and(qFileInfo.accountSetPeriod.eq(period))
                        .and(qFileInfo.isDelete.eq(0))
                )
                .orderBy(qFileInfo.createTime.desc(), qFileInfo.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        if(ObjectUtils.isEmpty(fileInfoList)){
            return null;
        }
        return new PageResults<>(fileInfoList.stream().map(item -> {
            FileInfoDto dto = CopierCopyUtils.convertTo(item, FileInfoDto.class);
            dto.setFileName(item.getFileName() + "." + item.getFileSuffix());
            return dto;
        }).toList(), page, fileInfoList.getTotalSize());
    }

    /**
     * 删除文件
     * @param accountSetsId
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteFile(Long accountSetsId, Long id) {
        jqf.update(qFileInfo)
                .set(qFileInfo.isDelete, 1)
                .where(qFileInfo.id.eq(id)
                        .and(qFileInfo.accountSetsId.eq(accountSetsId))
                )
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 修改业务编码
     * @param vo
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateBusinessCode(FileInfoVo vo) {
        if(ObjectUtils.isEmpty(vo.getId()) || StringUtils.isEmpty(vo.getBusinessCode()) || StringUtils.isEmpty(vo.getBusinessName())){
            return Boolean.FALSE;
        }
        jqf.update(qFileInfo)
                .set(qFileInfo.businessCode, vo.getBusinessCode())
                .set(qFileInfo.businessName, vo.getBusinessName())
                .where(qFileInfo.id.eq(vo.getId()))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 处理文件
     *
     * @param id
     * @param accountSets
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean dealFile(Long id, AccountSets accountSets) {
        Boolean flag = Boolean.FALSE;
        if(ObjectUtils.isEmpty(id)){
            return flag;
        }
        FileInfo fileInfo = bqf.selectFrom(qFileInfo)
                .where(qFileInfo.id.eq(id))
                .fetchFirst();
        if("bankStatement".equals(fileInfo.getType())){
            flag = recognizeExcel(fileInfo, accountSets, Boolean.FALSE);
        }
        return flag;
    }

    /**
     * 处理文件
     * @param fileInfo
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Boolean dealFile(FileInfo fileInfo, Long accountSetsId){
        Boolean flag = Boolean.FALSE;
        if(ObjectUtils.isEmpty(fileInfo)){
            return flag;
        }
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if(ObjectUtils.isEmpty(accountSets)){
            return flag;
        }
        try {
            if("bankStatement".equals(fileInfo.getType())){
                flag = recognizeExcel(fileInfo, accountSets, Boolean.TRUE);
            }
        }catch (Exception e){
            System.out.println(e);
        }
        return flag;
    }

    /**
     * 识别银行文件
     * @param fileInfo
     * @param accountSets
     * @param isAutoRecognize
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean recognizeExcel(FileInfo fileInfo, AccountSets accountSets, Boolean isAutoRecognize){
        Assert.isFalse(StringUtils.isEmpty(fileInfo.getBusinessCode()), "银行编码为空，请先修改银行！");
        BankLib bankLib = bqf.selectFrom(qBankLib)
                .where(qBankLib.bankCode.eq(fileInfo.getBusinessCode())
                        .and(qBankLib.enabled.eq(ExcelRecognizeUtils.ENABLED_YES))
                        .and(isAutoRecognize ? qBankLib.isAutoRecognize.eq(1) : null)
                        .and(qBankLib.isDelete.eq(0)))
                .fetchFirst();
        Assert.isFalse(ObjectUtils.isEmpty(bankLib), "未获取到已启用银行，请先进行新增并启用！");
        String filePath = saveFinancialBankPath + "\\" + fileInfo.getFileName();
        CashierAccount cashierAccount = bqf.selectFrom(qCashierAccount)
                .where(qCashierAccount.accountSetsId.eq(accountSets.getId())
                        .and(qCashierAccount.type.eq(CashierAccount.CashierAccountType.银行存款))
                        .and(qCashierAccount.name.eq(fileInfo.getBusinessName())))
                .fetchFirst();
        if(ObjectUtils.isEmpty(cashierAccount)){
            cashierAccount = new CashierAccount();
            cashierAccount.setCurrencyId(433722807319433217L);
            cashierAccount.setAccountSetsId(accountSets.getId());
            cashierAccount.setType(CashierAccount.CashierAccountType.银行存款);
            cashierAccount.setName(fileInfo.getBusinessName());
            cashierAccount.setCode(fileInfo.getBusinessCode());
            cashierAccount = cashierAccountService.save(cashierAccount);
        }
        List<Map<String, Object>> mapList = bankExcelService.recognizeBankExcel(filePath, fileInfo.getBusinessCode(), fileInfo.getType());
        if(CollUtil.isNotEmpty(mapList)){
            //特殊处理ioTypeId字段
            CashierAccount finalCashierAccount = cashierAccount;
            mapList.forEach(item -> {
                Map<String, Object> hashMap = new HashMap<>(item);
                if (hashMap.containsKey("ioTypeId")) {
                    if ("收入".equals(hashMap.get("ioTypeId"))) {
                        hashMap.put("ioTypeId", "433750601758855785");
                    } else if ("支出".equals(hashMap.get("ioTypeId"))) {
                        hashMap.put("ioTypeId", "433751381758855786");
                    }
                }
                CashierJournal cashierJournal = MapToEntityUtils.mapToEntity(hashMap, CashierJournal.class);
                cashierJournal.setAccountSetsId(fileInfo.getAccountSetsId());
                cashierJournal.setAccountId(finalCashierAccount.getId());
                cashierJournal.setInit(Boolean.FALSE);
                cashierJournalService.save(cashierJournal, accountSets.getEnableDate());
            });
        }
        if(!isAutoRecognize) {
            jqf.update(qFileInfo)
                    .set(qFileInfo.isDeal, 1)
                    .where(qFileInfo.id.eq(fileInfo.getId()))
                    .execute();
        }
        return Boolean.TRUE;
    }
}