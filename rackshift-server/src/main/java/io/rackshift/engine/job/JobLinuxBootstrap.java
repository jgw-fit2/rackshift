package io.rackshift.engine.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.rackshift.model.RSException;
import io.rackshift.mybatis.domain.OutBand;
import io.rackshift.mybatis.mapper.TaskMapper;
import io.rackshift.service.OutBandService;
import io.rackshift.utils.IPMIUtil;
import io.rackshift.utils.JSONUtils;
import io.rackshift.utils.LogUtil;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

import java.util.Map;

@Jobs("Job.Linux.Bootstrap")
public class JobLinuxBootstrap extends BaseJob {
    public JobLinuxBootstrap() {

    }

    /**
     * @param taskId             task 表的 id
     * @param instanceId         task表中 graphObjct 字段每一个具体子任务的 id
     * @param context            task表中 graphObjct 字段每一个具体子任务的 json 对象
     * @param taskMapper
     * @param applicationContext
     * @param rabbitTemplate
     */
    public JobLinuxBootstrap(String taskId, String instanceId, JSONObject context, TaskMapper taskMapper, ApplicationContext applicationContext, RabbitTemplate rabbitTemplate) {
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.context = context;
        this.options = context.getJSONObject("options");
        this._status = context.getString("state");
        this.taskMapper = taskMapper;
        this.task = taskMapper.selectByPrimaryKey(taskId);
        this.bareMetalId = context.getString("bareMetalId");
        this.applicationContext = applicationContext;
        this.job = (Map<String, Class>) applicationContext.getBean("job");
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void run() {
        LogUtil.info("Job.Linux.Bootstrap run. bareMetalId:" + bareMetalId);
        JSONObject r = new JSONObject();
        r.put("identifier", bareMetalId);
        LogUtil.info("Start Request Command");
        this.subscribeForRequestCommand((o) -> {
            JSONArray taskArr = new JSONArray();
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "");
            taskArr.add(cmd);
            r.put("tasks", taskArr);
            return r.toJSONString();
        });
        LogUtil.info("Start Request Profile");
        this.subscribeForRequestProfile(o -> this.options.getString("profile"));
        LogUtil.info("Start Request Options");
        this.subscribeForRequestOptions(o -> JSONUtils.merge(this.options, this.renderOptions).toJSONString());

        this.subscribeForCompleteCommands(o -> {
            this.complete();
            return "ok";
        });
    }

}
