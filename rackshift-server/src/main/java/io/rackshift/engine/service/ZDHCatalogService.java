package io.rackshift.engine.service;

import com.alibaba.fastjson.JSONObject;
import io.rackshift.mybatis.domain.BareMetal;
import io.rackshift.mybatis.domain.Catalog;
import io.rackshift.mybatis.domain.CatalogExample;
import io.rackshift.mybatis.mapper.CatalogMapper;
import io.rackshift.service.BareMetalService;
import io.rackshift.service.ProfileService;
import io.rackshift.service.TaskService;
import io.rackshift.utils.UUIDUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ZDHCatalogService {
    @Resource
    private CatalogMapper catalogMapper;

    public void saveCatalog(String bareMetalId, JSONObject result) {
        Catalog catalog = new Catalog();
        catalog.setId(UUIDUtil.newUUID());
        catalog.setBareMetalId(bareMetalId);
        catalog.setSource(result.getString("source").trim());
        catalog.setData(result.getString("data"));
        catalog.setCreateTime(System.currentTimeMillis());
        CatalogExample catalogExample = new CatalogExample();
        CatalogExample.Criteria criteria = catalogExample.createCriteria();
        criteria.andBareMetalIdEqualTo(bareMetalId).andSourceEqualTo(result.getString("source").trim());
        //删除旧的数据，否则后续多raid磁盘查询处理修改麻烦
        catalogMapper.deleteByExample(catalogExample);
        catalogMapper.insert(catalog);
    }
}
