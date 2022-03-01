package io.rackshift.strategy.statemachine;

import com.alibaba.fastjson.JSONObject;
import io.rackshift.config.PluginConfig;
import io.rackshift.constants.ExecutionLogConstants;
import io.rackshift.constants.ServiceConstants;
import io.rackshift.manager.BareMetalManager;
import io.rackshift.metal.sdk.IMetalProvider;
import io.rackshift.model.RSException;
import io.rackshift.model.WorkflowRequestDTO;
import io.rackshift.mybatis.domain.BareMetal;
import io.rackshift.mybatis.domain.Task;
import io.rackshift.mybatis.domain.TaskWithBLOBs;
import io.rackshift.service.ExecutionLogService;
import io.rackshift.service.TaskService;
import io.rackshift.service.WorkflowService;
import io.rackshift.utils.ExceptionUtils;
import io.rackshift.utils.LogUtil;
import io.rackshift.utils.Translator;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component("abstractHandler")
public class AbstractHandler implements IStateHandler {
    @Resource
    private BareMetalManager bareMetalManager;
    @Resource
    protected ExecutionLogService executionLogService;
    @Autowired
    private SimpMessagingTemplate template;
    @Resource
    private TaskService taskService;
    @Resource
    private WorkflowService workflowService;
    @Resource
    private PluginConfig pluginConfig;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StateMachine stateMachine;

    protected BareMetal getBareMetalById(String id) {
        return bareMetalManager.getBareMetalById(id);
    }

    protected List<LifeStatus> preStatus = new ArrayList<LifeStatus>() {{
        add(LifeStatus.ready);
        add(LifeStatus.allocated);
        add(LifeStatus.deployed);
    }};

    protected List<String> preProcessRaidWf = new ArrayList<String>() {{
        add("Graph.Raid.Create.HpssaRAID");
        add("Graph.Raid.Create.AdaptecRAID");
        add("Graph.Raid.Create.PercRAID");
    }};

    public void handleYourself(LifeEvent event) throws Exception {

    }

    @Override
    public void handleNoSession(LifeEvent event) {
        String taskId = event.getWorkflowRequestDTO().getTaskId();
        Task task = taskService.getById(taskId);
        try {
            handleYourself(event);
        } catch (Exception e) {
            executionLogService.saveLogDetail(taskId, task.getUserId(), ExecutionLogConstants.OperationEnum.ERROR.name(), event.getBareMetalId(), String.format("错误：%s", ExceptionUtils.getExceptionDetail(e)));
        }
    }

    @Override
    public void handle(LifeEvent event) {
        Task task = taskService.getById(event.getWorkflowRequestDTO().getTaskId());

        try {
            paramPreProcess(event);
            handleYourself(event);
        } catch (Exception e) {
            executionLogService.saveLogDetail(task.getId(), task.getUserId(), ExecutionLogConstants.OperationEnum.ERROR.name(), event.getBareMetalId(), String.format("错误：%s", ExceptionUtils.getExceptionDetail(e)));
            revert(event);
            throw new RuntimeException(e);
        }
    }

    public void paramPreProcess(LifeEvent event) {
        if (preProcessRaidWf.contains(event.getWorkflowRequestDTO().getWorkflowName())) {
            if (Optional.of(event.getWorkflowRequestDTO()).isPresent()) {
                WorkflowRequestDTO workflowRequestDTO = event.getWorkflowRequestDTO();
                JSONObject params = workflowRequestDTO.getParams();

                IMetalProvider iMetalProvider = pluginConfig.getPlugin(getBareMetalById(event.getBareMetalId()));
                if (params != null) {
                    JSONObject param = iMetalProvider.getRaidPayLoad(params.toJSONString());
                    workflowRequestDTO.setParams(param);
                }
            }
        }
    }

    @Override
    public void revert(LifeEvent event) {
        Task task = taskService.getById(event.getWorkflowRequestDTO().getTaskId());
        task.setStatus(ServiceConstants.TaskStatusEnum.failed.name());
        taskService.update(task);
        changeStatus(event, LifeStatus.ready, false);
        executionLogService.saveLogDetail(task.getId(), task.getUserId(), ExecutionLogConstants.OperationEnum.ERROR.name(), event.getBareMetalId(), String.format("错误：event:%s:worflow:%ss,参数:%s,回滚状态至%s", event.getEventType().getDesc(), Optional.ofNullable(event.getWorkflowRequestDTO().getWorkflowName()).orElse("无"), (Optional.ofNullable(event.getWorkflowRequestDTO().getParams()).orElse(new JSONObject())).toJSONString(), LifeStatus.ready));
    }

    protected void beforeChange(LifeStatus curStatus) {
        if (!preStatus.contains(curStatus)) RSException.throwExceptions(Translator.get("status_not_valid"));
    }

    protected void changeStatus(LifeEvent event, LifeStatus status, boolean needCheckStatus) {
        WorkflowRequestDTO requestDTO = event.getWorkflowRequestDTO();
        BareMetal bareMetal = getBareMetalById(requestDTO.getBareMetalId());
        if (needCheckStatus) {
            beforeChange(LifeStatus.valueOf(bareMetal.getStatus()));
        }
        bareMetal.setStatus(status.name());
        bareMetalManager.update(bareMetal, true);
        notifyWebSocket(bareMetal, taskService.getById(event.getWorkflowRequestDTO().getTaskId()));
    }

    protected void notifyWebSocket(BareMetal bareMetal, Task task) {
        String status = bareMetal.getStatus();
        String taskStatus = task.getStatus();
        try {
            status = Translator.get(status);
            taskStatus = Translator.get(taskStatus);
        } catch (Exception e) {
        }
        template.convertAndSend("/topic/lifecycle", String.format("裸金属：%s,状态变更为：%s", bareMetal.getMachineModel() + " " + bareMetal.getManagementIp(), status));
        String msg = String.format("裸金属：%s,任务终止：%s,状态变更为：%s", bareMetal.getMachineModel() + " " + bareMetal.getManagementIp(), workflowService.getFriendlyName(task.getWorkFlowId()), taskStatus);
        if (ServiceConstants.TaskStatusEnum.running.name().equalsIgnoreCase(task.getStatus())) {
            msg = String.format("裸金属：%s,任务开始：%s,状态变更为：%s", bareMetal.getMachineModel() + " " + bareMetal.getManagementIp(), workflowService.getFriendlyName(task.getWorkFlowId()), taskStatus);
        }
        template.convertAndSend("/topic/taskLifecycle", msg);
    }

    protected void startTask(TaskWithBLOBs task) {
        JSONObject taskObj = JSONObject.parseObject(task.getGraphObjects());
        List<JSONObject> tasksReadyToStart = new ArrayList<>();
        for (String t : taskObj.keySet()) {
            if (taskObj.getJSONObject(t).getJSONObject("waitingOn") == null) {
                tasksReadyToStart.add(taskObj.getJSONObject(t));
            }
        }

        if (tasksReadyToStart.size() == 1) {
            //假如有多个启动任务只启动一个
            JSONObject body = new JSONObject();
            body.put("taskId", task.getId());
            body.put("instanceId", tasksReadyToStart.get(0).getString("instanceId"));
//            Message message = new Message(body.toJSONString().getBytes(StandardCharsets.UTF_8));
//            rabbitTemplate.send(MqConstants.RUN_TASK_QUEUE_NAME, message);
            stateMachine.runTask(body);
        } else {
            for (JSONObject jsonObject : tasksReadyToStart) {
                boolean solo = true;
                for (String s : taskObj.keySet()) {
                    JSONObject t = taskObj.getJSONObject(s);
                    if (t.getJSONObject("waitingOn") != null && t.getJSONObject("waitingOn").containsKey(jsonObject.getString("instanceId"))) {
                        solo = false;
                        break;
                    }
                }
                if (solo) {
                    JSONObject body = new JSONObject();
                    body.put("taskId", task.getId());
                    body.put("instanceId", jsonObject.getString("instanceId"));
//                    Message message = new Message(body.toJSONString().getBytes(StandardCharsets.UTF_8));
//                    rabbitTemplate.send(MqConstants.RUN_TASK_QUEUE_NAME, message);
                    stateMachine.runTask(body);
                    break;
                }
            }
        }
    }
}
