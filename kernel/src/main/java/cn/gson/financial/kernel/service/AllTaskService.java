package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.dto.AllTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AllTaskService extends AbsService{



    public Long insert(AllTaskDto record){
        record.setTaskId(snowflakeId.generate());
        lazyDao.executeSql("insertTask",record);
        return record.getTaskId();
    }
    public Long insertItem(AllTaskDto record){
        record.setTaskItemId(snowflakeId.generate());
        lazyDao.executeSql("insertTaskItem",record);
        return record.getTaskId();
    }

    public Long updateById(AllTaskDto record) {
        Long row = lazyDao.executeSql("updateById",record);
        return row;
    }

    public List<AllTaskDto> selectAllList(AllTaskDto record) {
        List<AllTaskDto> list = lazyDao.findBySql("selectAllList",record.toMap(),AllTaskDto.class);
        return list;
    }

    public List<AllTaskDto> selectItemList(AllTaskDto record) {
        List<AllTaskDto> list = lazyDao.findBySql("selectItemList",record.toMap(),AllTaskDto.class);
        return list;
    }

    public void reUpdateItemById(AllTaskDto record) {
        Long list = lazyDao.executeSql("reUpdateItemById",record);
    }


    public void reUpdateAllById(AllTaskDto record) {
        Long list = lazyDao.executeSql("reUpdateAllById",record);
    }

    //查询当前账套，时间段内的发票信息
    public List<AllTaskDto> selectAllListByDate(Map<String,Object> map) {
        List<AllTaskDto> list = lazyDao.findBySql("selectAllListByDate", map, AllTaskDto.class);
        return list;
    }
}
