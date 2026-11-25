package cn.gson.financial.job;

import cn.gson.financial.kernel.model.entity.AppConfig;
import cn.gson.financial.kernel.model.entity.AutoBackupConfig;
import cn.gson.financial.kernel.model.entity.QAutoBackupConfig;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.AppConfigService;
import cn.gson.financial.kernel.service.BackupService;
import cn.gson.financial.service.MailService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoBackupJop extends AbsService {

    private final QAutoBackupConfig qAutoBackupConfig = QAutoBackupConfig.autoBackupConfig;

    private final BackupService backupService;

    private final MailService mailService;

    private final AppConfigService configService;

    /**
     * 每天凌晨3点进行备份操作
     */
    @Async
    @Scheduled(cron = "0 0 3 * * *")
    public void backup() {
        LocalDate now = LocalDate.now();
        int dayOfMonth = now.getDayOfMonth();
        BooleanExpression expression = qAutoBackupConfig.enabled.isTrue()
                .and(qAutoBackupConfig.mail.isNotEmpty());
        // 2月的最后一天，时间设置为大于等于这一天的日期都要进行备份
        if (now.getMonth().equals(Month.FEBRUARY) && dayOfMonth == now.lengthOfMonth()) {
            expression = expression.and(qAutoBackupConfig.backupDay.goe(dayOfMonth));
        } else {
            expression = expression.and(qAutoBackupConfig.backupDay.eq(dayOfMonth));
        }

        List<AutoBackupConfig> backupConfigs = bqf.selectFrom(qAutoBackupConfig).where(expression).fetch();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        log.info("{}自动备份任务总数：{}", dateStr, backupConfigs.size());

        if (CollUtil.isNotEmpty(backupConfigs)) {
            AppConfig config = configService.load();
            int total = 1;
            TimeInterval timer = DateUtil.timer();
            timer.start("autoBackup");
            for (AutoBackupConfig backupConfig : backupConfigs) {
                String timerKey = backupConfig.getAccountSetsId() + ":" + backupConfig.getMail();
                try {
                    log.info("\n开始备份:{}，当前正在备份：{}/{}", timerKey, total, backupConfigs.size());
                    timer.start(timerKey);
                    BackupService.Result result = backupService.generateBackup(backupConfig.getAccountSetsId());
                    MimeMessageHelper mimeMessageHelper = mailService.mimeMessageHelper();
                    mimeMessageHelper.setSubject("【%s】%s-%s".formatted(config.getName(), dateStr, result.title()));
                    mimeMessageHelper.setText(result.title());
                    mimeMessageHelper.setTo(backupConfig.getMail());
                    mimeMessageHelper.addAttachment(result.title(), result.zipFile());

                    //发送邮寄
                    mailService.sendMailCode(backupConfig.getMail(), mimeMessageHelper, config.getName());
                } catch (Exception e) {
                    log.error("{},{}备份发送失败~", backupConfig.getAccountSetsId(), backupConfig.getMail(), e);
                } finally {
                    log.info("\n{}备份耗时：{}", timerKey, timer.interval(timerKey));
                }
            }
            log.info("\n备份总耗时：{}", timer.interval("autoBackup"));
        }
    }
}
