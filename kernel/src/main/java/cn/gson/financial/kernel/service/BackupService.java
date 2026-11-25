package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BackUpDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.AutoBackupConfigRepository;
import cn.gson.financial.kernel.model.repo.BackUpRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (BackUp)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-07 11:11:36</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BackupService extends AbsService {

    private final List<BackupProcessor> backupProcessors;

    private final List<AccountSetsProcessor> accountSetsProcessors;

    private final QBackup qBackUp = QBackup.backup;

    private final QUser qUser = QUser.user;

    private final QAutoBackupConfig qAutoBackupConfig = QAutoBackupConfig.autoBackupConfig;

    private final AutoBackupConfigRepository autoBackupConfigRepository;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QAccountSetsTemplate qAccountSetsTemplate = QAccountSetsTemplate.accountSetsTemplate;

    private final QCheckout qCheckout = QCheckout.checkout;

    private final BackUpRepository backupRepository;

    private final UserAccountSetsRepository userAccountSetsRepository;

    private final AccountSetsService accountSetsService;

    private final RoleService roleService;

    private final char[] ZIP_PASSWORD = "891123".toCharArray();

    private final AppConfig appConfig;

    public List<BackUpDto> list(Long acId) {
        return bqf.selectFrom(qBackUp)
                .select(qBackUp, qUser.realName.coalesce(qUser.nickname))
                .leftJoin(qUser).on(qUser.id.eq(qBackUp.userId))
                .where(qBackUp.accountSetsId.eq(acId))
                .orderBy(qBackUp.id.desc())
                .fetch().stream().collect(ArrayList::new, (list, tuple) -> {
                    BackUpDto dto = BeanUtil.toBean(tuple.get(qBackUp), BackUpDto.class);
                    dto.setUserName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
                    list.add(dto);
                }, List::addAll);
    }

    @Transactional
    public void delete(Long backupId, Long accountSetsId) {
        Backup backUp = bqf.selectFrom(qBackUp)
                .where(qBackUp.accountSetsId.eq(accountSetsId).and(qBackUp.id.eq(backupId)))
                .fetchFirst();
        FileUtil.del(new File(appConfig.getUploadRoot("ast-" + accountSetsId + "/backup"), backUp.getPath()));
        jqf.delete(qBackUp).where(qBackUp.id.eq(backupId)).execute();
    }

    @Transactional
    public void backup(Long userId, Long accountSetsId) {
        try {
            Result result = generateBackup(accountSetsId);
            Backup backUp = new Backup();
            backUp.setAccountSetsId(accountSetsId);
            backUp.setTitle(result.title());
            backUp.setPath(result.title());
            backUp.setSize(result.zipFile().length());
            backUp.setBakTime(LocalDateTime.now());
            backUp.setUserId(userId);
            backUp.setBakType(0);
            backupRepository.save(backUp);
        } catch (Exception e) {
            throw new RuntimeException("备份文件压缩失败~", e);
        }
    }

    public Result generateBackup(Long accountSetsId) throws IOException {
        File bakRoot = appConfig.getUploadRoot("ast-" + accountSetsId + "/backup");
        File bakTempRoot = appConfig.getUploadRoot("ast-" + accountSetsId + "/backup/tem" + System.currentTimeMillis());
        Set<String> bakTypes = new HashSet<>();
        backupProcessors.forEach(s -> {
            String jsonData = s.backup(accountSetsId);
            if (StrUtil.isNotEmpty(jsonData)) {
                String bakType = s.backupType().getSimpleName();
                Assert.isFalse(bakTypes.contains(bakType), bakType + "名称冲突请检查配置~");
                File backData = new File(bakTempRoot, StrUtil.toCamelCase(bakType, '.') + ".json");
                FileUtil.writeString(jsonData, backData, StandardCharsets.UTF_8);
                bakTypes.add(bakType);
            }
        });
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        String title = accountSets.getId() + "-" + accountSets.getCompanyName() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";

        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        zipParameters.setCompressionLevel(CompressionLevel.HIGHER);
        zipParameters.setEncryptionMethod(EncryptionMethod.AES);
        ZipFile zipFile = new ZipFile(new File(bakRoot, title), ZIP_PASSWORD);
        try (zipFile) {
            zipFile.addFiles(FileUtil.loopFiles(bakTempRoot), zipParameters);
        } catch (ZipException e) {
            throw new ServiceException("备份文件打包失败~", e);
        } finally {
            FileUtil.del(bakTempRoot);
        }

        return new Result(title, zipFile.getFile());
    }

    @Transactional
    public void changeAuto(Long acId, Boolean status) {
        AutoBackupConfig autoBackupConfig = bqf.selectFrom(qAutoBackupConfig)
                .where(qAutoBackupConfig.accountSetsId.eq(acId))
                .fetchFirst();
        if (autoBackupConfig == null) {
            autoBackupConfig = new AutoBackupConfig();
            autoBackupConfig.setEnabled(true);
            autoBackupConfig.setAccountSetsId(acId);
            autoBackupConfigRepository.save(autoBackupConfig);
        } else {
            jqf.update(qAutoBackupConfig)
                    .set(qAutoBackupConfig.enabled, status)
                    .where(qAutoBackupConfig.accountSetsId.eq(acId))
                    .execute();
        }
    }

    @Transactional
    public void changeAutoConfig(Long acId, AutoBackupConfig config) {
        jqf.update(qAutoBackupConfig)
                .set(qAutoBackupConfig.mail, config.getMail())
                .set(qAutoBackupConfig.backupDay, config.getBackupDay())
                .where(qAutoBackupConfig.accountSetsId.eq(acId))
                .execute();
    }

    @SneakyThrows
    @Transactional
    public void recover(Long backupId, Long userId, Integer type, Long accountSetsId) {
        Backup backUp = bqf.selectFrom(qBackUp)
                .where(qBackUp.accountSetsId.eq(accountSetsId).and(qBackUp.id.eq(backupId)))
                .fetchFirst();
        File backFile = new File(appConfig.getUploadRoot("ast-" + accountSetsId + "/backup"), backUp.getPath());
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(backFile, ZIP_PASSWORD);
            String extractPath = backFile.getPath().replace(".zip", "");
            zipFile.extractAll(extractPath);

            List<File> files = FileUtil.loopFiles(extractPath);
            Map<String, String> dataJson = new HashMap<>();
            files.forEach(file -> dataJson.put(FileUtil.mainName(file), FileUtil.readString(file, StandardCharsets.UTF_8)));

            FileUtil.del(extractPath);
            UserAccountSets userAccountSets = null;

            if (type == 1) {
                AccountSets accountSets;
                if (dataJson.containsKey("AccountSets")) {
                    accountSets = JSON.parseObject(dataJson.get("AccountSets"), AccountSets.class);
                    accountSets.setCreatorId(userId);
                    Assert.isTrue(bqf.selectFrom(qAccountSetsTemplate).where(qAccountSetsTemplate.id.eq(accountSets.getAstId()))
                                          .fetchCount() > 0, "非本系统备份数据，不允许恢复~");
                } else {
                    accountSets = bqf.selectFrom(qAccountSets)
                            .where(qAccountSets.id.eq(accountSetsId))
                            .fetchFirst();
                }

                AccountSets newAs = BeanUtil.toBean(accountSets, AccountSets.class, CopyOptions.create().setIgnoreProperties("id"));
                newAs.setCompanyName("复制账套-" + RandomUtil.randomString(3) + "-" + newAs.getCompanyName());
                accountSetsService.save(newAs, false, true);

                userAccountSets = new UserAccountSets();
                userAccountSets.setAccountSetsId(newAs.getId());
                userAccountSets.setUserId(accountSets.getCreatorId());
                userAccountSets.setCreator(true);
                userAccountSets.setCurrent(false);
                userAccountSets.setRoleId(0L);
                userAccountSets.setId(snowflakeId.generate());
                userAccountSetsRepository.save(userAccountSets);
                accountSetsId = newAs.getId();
            } else {
                if (dataJson.containsKey("AccountSets")) {
                    AccountSets accountSets = JSON.parseObject(dataJson.get("AccountSets"), AccountSets.class);
                    Assert.isTrue(accountSetsId.equals(accountSets.getId()), "非本账套备份，不允覆盖还原~");
                }

                for (AccountSetsProcessor ap : accountSetsProcessors) {
                    ap.clear(accountSetsId);
                }
            }

            for (BackupProcessor bp : backupProcessors) {
                if (StrUtil.isNotEmpty(dataJson.get(bp.backupType().getSimpleName()))) {
                    bp.recover(accountSetsId, dataJson.get(bp.backupType().getSimpleName()));
                }
            }

            if (userAccountSets != null) {
                Role astAdmin = roleService.getAstAdmin(accountSetsId);
                userAccountSets.setRoleId(astAdmin.getId());
                userAccountSetsRepository.save(userAccountSets);
            }

            // 新建账套时，覆盖账套启用日期和当前期间
            if (type == 1) {
                Tuple checkDate = bqf.selectFrom(qCheckout)
                        .select(qCheckout.checkDate.max(), qCheckout.checkDate.min())
                        .where(qCheckout.accountSetsId.eq(accountSetsId))
                        .fetchFirst();
                if (checkDate != null) {
                    jqf.update(qAccountSets)
                            .set(qAccountSets.enableDate, checkDate.get(qCheckout.checkDate.min()))
                            .set(qAccountSets.currentAccountDate, checkDate.get(qCheckout.checkDate.max()))
                            .where(qAccountSets.id.eq(accountSetsId))
                            .execute();
                }
            }
        } catch (ZipException e) {
            throw new RuntimeException("备份文件解压失败~", e);
        } finally {
            assert zipFile != null;
            zipFile.close();
        }
    }

    public File download(Long backupId, Long accountSetsId) {
        Backup backUp = bqf.selectFrom(qBackUp)
                .where(qBackUp.accountSetsId.eq(accountSetsId).and(qBackUp.id.eq(backupId)))
                .fetchFirst();
        return new File(appConfig.getUploadRoot("ast-" + accountSetsId + "/backup"), backUp.getPath());
    }

    @Transactional
    public void saveUpload(String originalFilename, InputStream inputStream, AccountSets accountSets, Long userId) {
        File bakRoot = appConfig.getUploadRoot("ast-" + accountSets.getId() + "/backup");
        originalFilename = "本地上传-" + accountSets.getCompanyName() + "-备份文件." + FileNameUtil.getSuffix(originalFilename);

        Assert.isTrue(bakRoot.canWrite(), "备份路径没有写入权限，请检查服务器配置~");

        File backFile = new File(bakRoot, originalFilename);
        FileUtil.writeFromStream(inputStream, backFile);
        FileUtil.mkdir(bakRoot);
        Backup backUp = new Backup();
        backUp.setAccountSetsId(accountSets.getId());
        backUp.setTitle(originalFilename);
        backUp.setPath(originalFilename);
        backUp.setSize(backFile.length());
        backUp.setBakTime(LocalDateTime.now());
        backUp.setUserId(userId);
        backUp.setBakType(1);
        backupRepository.save(backUp);
    }

    public AutoBackupConfig loadAutoInfo(Long acId) {
        return bqf.selectFrom(qAutoBackupConfig)
                .where(qAutoBackupConfig.accountSetsId.eq(acId))
                .fetchFirst();
    }

    public record Result(String title, File zipFile) {
    }
}

