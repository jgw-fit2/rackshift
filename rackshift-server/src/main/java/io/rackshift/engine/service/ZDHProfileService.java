package io.rackshift.engine.service;

import io.rackshift.mybatis.domain.BareMetal;
import io.rackshift.service.BareMetalService;
import io.rackshift.service.ProfileService;
import io.rackshift.service.TaskService;
import io.rackshift.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;

@Service
public class ZDHProfileService {
    @Resource
    private ProfileService profileService;
    @Resource
    private TaskService taskService;
    @Resource
    private BareMetalService bareMetalService;

    /**
     * 返回前端发现或者
     * 当有多块网卡设置为pxe模式时， 以物理机列表里的mac作为发现的判断不太合理
     * 现在简单修改：去网卡表里面查询
     * @param macs
     * @return
     */
    public String profiles(String macs) throws IOException, InterruptedException {
        LogUtil.info("查询正在运行中的任务：" + macs);
        if (StringUtils.isNotBlank(macs)) {

            BareMetal bareMetal = bareMetalService.getByPXEMACFromNetworkCard(macs);
            if (bareMetal == null) {
                LogUtil.info("创建PXE发现任务：" +macs );
                bareMetal = taskService.createBMAndDiscoveryGraph(macs);
            }
            return profileService.getProfileContentByName(taskService.getTaskProfile(bareMetal.getId()));
        }
        LogUtil.info("redirect.ipxe");
        return profileService.getDefaultProfile("redirect.ipxe");
    }

    public void test(String content, boolean test) {
        profileService.test(content, test);
    }
}
