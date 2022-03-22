package io.rackshift.mybatis.mapper.ext;

import io.rackshift.model.TaskDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface ExtTaskMapper {
    List<Map> list(TaskDTO param);
    List<Map> query(TaskDTO param);
}