package io.rackshift.mybatis.mapper.ext;

import io.rackshift.model.DiskDto;
import io.rackshift.mybatis.domain.BareMetal;

import java.util.List;

public interface ExtDiskMapper {
    List<DiskDto> selectAllDisks();
}
