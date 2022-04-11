package io.rackshift.service;

import io.rackshift.model.DiskDto;
import io.rackshift.mybatis.domain.Disk;
import io.rackshift.mybatis.mapper.ext.ExtBareMetalMapper;
import io.rackshift.mybatis.mapper.ext.ExtDiskMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author jgw
 * 为云管物理机模块同步信息增加的接口
 */
@Service
public class DiskService {

    @Resource
    private ExtDiskMapper extDiskMapper;

    public  List<? extends Disk> getAllDisks(){
        List<DiskDto> disks = extDiskMapper.selectAllDisks();
        return disks;
    }

}
