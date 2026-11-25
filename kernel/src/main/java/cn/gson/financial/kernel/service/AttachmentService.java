package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.AttachmentDto;
import cn.gson.financial.kernel.model.entity.Attachment;
import cn.gson.financial.kernel.model.entity.QAttachment;
import cn.gson.financial.kernel.model.entity.QUser;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.hibernate.FileInfo;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.AttachmentMapper;
import cn.gson.financial.kernel.model.repo.AttachmentRepository;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttachmentService extends AbsService {

    private final AttachmentRepository attachmentRepository;

    private final AttachmentMapper attachmentMapper;

    private final AppConfig appConfig;

    private final QUser qUser = QUser.user;

    private final QAttachment qAttachment = QAttachment.attachment;

    public PageResults<AttachmentDto> list(Long accountSetsId, Page page) {
        PagedList<Tuple> attachments = bqf.selectFrom(qAttachment)
                .select(qAttachment, qUser.realName.coalesce(qUser.nickname))
                .innerJoin(qUser).on(qUser.id.eq(qAttachment.userId))
                .where(qAttachment.accountSetsId.eq(accountSetsId))
                .orderBy(qAttachment.bakTime.desc(), qAttachment.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(attachments.stream()
                .map((tuple -> attachmentMapper.
                        toDto(tuple.get(qAttachment), tuple.get(qUser.realName.coalesce(qUser.nickname))))).toList(),
                page, attachments.getTotalSize());
    }

    /**
     * 保存Attachment对象
     *
     * @param attachment 要保存的Attachment对象
     */
    @Transactional
    public void save(Attachment attachment) {
        // 保存attachment
        attachmentRepository.save(attachment);
    }

    /**
     * 根据Attachment的id删除Attachment
     *
     * @param primaryKey
     */
    @Transactional
    public void delete(PrimaryKey primaryKey) {
        attachmentRepository.deleteById(primaryKey);
    }

    /**
     * 关联来源位置
     *
     * @param accountSetsId
     * @param billList
     * @param sourceId
     */
    @Async
    @Transactional
    public void relateSource(Long accountSetsId, List<FileInfo> billList, Long sourceId) {
        if (CollUtil.isNotEmpty(billList)) {
            List<String> atts = billList.stream().map(s -> s.getUrl().replaceFirst("/attachment/", "")).toList();
            jqf.update(qAttachment)
                    .set(qAttachment.sourceId, sourceId)
                    .where(qAttachment.accountSetsId.eq(accountSetsId)
                            .and(qAttachment.path.in(atts)))
                    .execute();
        }
    }

    /**
     * 解除附件关联
     *
     * @param accountSetsId
     * @param attachmentType
     * @param voucherIds
     */
    @Async
    @Transactional
    public void relieveSource(Long accountSetsId, AttachmentType attachmentType, List<Long> voucherIds) {
        List<Attachment> attachments = bqf.selectFrom(qAttachment)
                .where(qAttachment.accountSetsId.eq(accountSetsId)
                        .and(qAttachment.type.eq(attachmentType))
                        .and(qAttachment.sourceId.in(voucherIds)))
                .fetch();
        File photoDir = appConfig.getUploadRoot("ast-" + accountSetsId);

        for (Attachment attachment : attachments) {
            //删除文件
            FileUtil.del(new File(photoDir, attachment.getPath()));
        }
        //删除数据
        attachmentRepository.deleteAll(attachments);
    }

    /**
     * 获取附件目录占用空间大小
     *
     * @param accountSetsId
     * @return
     */
    public long getAttachmentSize(long accountSetsId) {
        File photoDir = appConfig.getUploadRoot("ast-" + accountSetsId);
        long size = 0;
        if (photoDir.exists()) {
            size += FileUtil.size(photoDir);
        }
        return size;
    }
}
