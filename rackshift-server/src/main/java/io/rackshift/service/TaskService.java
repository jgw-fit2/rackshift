package io.rackshift.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import io.rackshift.constants.MqConstants;
import io.rackshift.constants.ServiceConstants;
import io.rackshift.engine.basetask.BaseTask;
import io.rackshift.engine.taskobject.BaseTaskObject;
import io.rackshift.manager.BareMetalManager;
import io.rackshift.model.RSException;
import io.rackshift.model.TaskDTO;
import io.rackshift.model.WorkflowRequestDTO;
import io.rackshift.mybatis.domain.*;
import io.rackshift.mybatis.mapper.ExecutionLogDetailsMapper;
import io.rackshift.mybatis.mapper.TaskMapper;
import io.rackshift.mybatis.mapper.ext.ExtTaskMapper;
import io.rackshift.strategy.statemachine.LifeEvent;
import io.rackshift.strategy.statemachine.LifeEventType;
import io.rackshift.strategy.statemachine.LifeStatus;
import io.rackshift.strategy.statemachine.StateMachine;
import io.rackshift.utils.BeanUtils;
import io.rackshift.utils.JSONUtils;
import io.rackshift.utils.SessionUtil;
import io.rackshift.utils.UUIDUtil;
import net.sf.cglib.core.Local;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TaskService {
    @Resource
    private TaskMapper taskMapper;
    @Resource
    private ExtTaskMapper extTaskMapper;
    @Resource
    private RackHDService rackHDService;
    @Resource
    private BareMetalManager bareMetalManager;
    @Resource
    private StateMachine stateMachine;
    @Resource
    private ExecutionLogDetailsMapper executionLogDetailsMapper;
    @Resource
    private EndpointService endpointService;
    @Resource
    private WorkflowService workflowService;
    @Autowired
    private SimpMessagingTemplate template;
    @Resource
    private Map<String, BaseTaskObject> taskObject;
    @Resource
    private Map<String, BaseTask> baseTask;
    @Resource
    private Map<String, String> renderOptions;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private List<String> runningStatus = new ArrayList<String>() {{
        add(ServiceConstants.TaskStatusEnum.created.name());
        add(ServiceConstants.TaskStatusEnum.running.name());
    }};

    public Object add(TaskDTO queryVO) {
        TaskWithBLOBs task = new TaskWithBLOBs();
        BeanUtils.copyBean(task, queryVO);

        taskMapper.insertSelective(task);
        return true;
    }

    public Object update(Task queryVO) {
        taskMapper.updateByPrimaryKey(queryVO);
        return true;
    }

    public Object del(String id) {
        Task task = taskMapper.selectByPrimaryKey(id);
        if (task == null) return false;
        if (bareMetalManager.getBareMetalById(task.getBareMetalId()) == null || StringUtils.isBlank(task.getInstanceId())) {
            taskMapper.deleteByPrimaryKey(id);
            return true;
        }
        if (StringUtils.isNotBlank(task.getInstanceId())) {
            //rackhd 清除 任务
            Endpoint endpoint = endpointService.getById(bareMetalManager.getBareMetalById(task.getBareMetalId()).getEndpointId());
            if (endpoint == null) {
                taskMapper.deleteByPrimaryKey(id);
                return true;
            }
            String status = rackHDService.getWorkflowStatusById(endpoint, task.getInstanceId());
            if (!ServiceConstants.TaskStatusEnum.running.name().equalsIgnoreCase(status)) {
                taskMapper.deleteByPrimaryKey(id);
                return true;
            }
            boolean r = rackHDService.cancelWorkflow(bareMetalManager.getBareMetalById(task.getBareMetalId()));
            if (r) {
                WorkflowRequestDTO requestDTO = new WorkflowRequestDTO();
                requestDTO.setTaskId(task.getId());
                requestDTO.setParams(JSONObject.parseObject("{ \"result\" : false}"));

                requestDTO.setBareMetalId(task.getBareMetalId());
                Workflow workflow = workflowService.getById(task.getWorkFlowId());
                LifeEventType type = LifeEventType.valueOf(workflow.getEventType().replace("START", "END"));
                LifeEvent event = LifeEvent.builder().withEventType(type).withWorkflowRequestDTO(requestDTO);
                stateMachine.sendEvent(event);

                taskMapper.deleteByPrimaryKey(id);
            } else {
                RSException.throwExceptions("取消任务失败！instanceID：" + task.getInstanceId());
            }
        }
        return true;
    }

    public Object del(String[] ids) {
        for (String id : ids) {
            del(id);
        }
        return null;
    }

    public List<Map> list(TaskDTO queryVO) {
        return extTaskMapper.list(queryVO);
    }

    private TaskExample buildExample(TaskDTO queryVO) {
        return new TaskExample();
    }

    public void createTaskFromEvents(List<LifeEvent> events) {
        Map<String, List<LifeEvent>> eventsMap = groupByBM(events);
        for (Map.Entry<String, List<LifeEvent>> entry : eventsMap.entrySet()) {
            List<TaskWithBLOBs> list = new ArrayList();
            entry.getValue().forEach(e -> {
                TaskWithBLOBs task = new TaskWithBLOBs();
                task.setId(UUIDUtil.newUUID());
                task.setBareMetalId(entry.getKey());
                task.setWorkFlowId(e.getWorkflowRequestDTO().getWorkflowId());
                task.setUserId(SessionUtil.getUser().getId());
                task.setParams(Optional.ofNullable(e.getWorkflowRequestDTO().getParams()).orElse(new JSONObject()).toJSONString());
                task.setExtparams(Optional.ofNullable(e.getWorkflowRequestDTO().getExtraParams()).orElse(new JSONObject()).toJSONString());
                task.setStatus(ServiceConstants.TaskStatusEnum.created.name());
                task.setCreateTime(System.currentTimeMillis());
                //生成真正的 taskgraph 实例对象
                task.setGraphObjects(generateGraphObjects(e));
                list.add(task);
            });

            BareMetal bareMetal = bareMetalManager.getBareMetalById(entry.getKey());
            for (int i = 0; i < list.size(); i++) {
                if (i != 0) {
                    list.get(i).setPreTaskId(list.get(i - 1).getId());
                }
                list.get(i).setBeforeStatus(bareMetal.getStatus());
            }
            //第一个任务默认以之前的任意一个未结束任务为前置任务 如果全部都已经结束，则该任务为当前第一个执行任务
            list.get(0).setPreTaskId(findLastTaskId(list.get(0).getBareMetalId()));
            for (TaskWithBLOBs taskWithBLOBs : list) {
                taskMapper.insertSelective(taskWithBLOBs);
            }
        }
    }

    public String generateGraphObjects(LifeEvent e) {
        String bareMetalId = e.getBareMetalId();
        String workflowName = e.getWorkflowRequestDTO().getWorkflowName();
        WorkflowWithBLOBs w = workflowService.getByInjectableName(workflowName);
        LinkedHashMap taskObjects = new LinkedHashMap();
        if (w != null) {
            JSONArray tasks = JSONArray.parseArray(w.getTasks());
            // 原 rackhd graph 定义默认参数
            JSONObject definitionOptions = JSONObject.parseObject(w.getOptions());
            Map<String, JSONObject> taskOptions = definitionOptions.keySet().stream().collect(Collectors.toMap(k -> k, k -> definitionOptions.getJSONObject(k)));
            Map<String, String> instanceMap = new HashedMap();

            for (int i = 0; i < tasks.size(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                String taskName = task.getString("taskName");
                JSONObject taskObj = null;
                JSONObject baseTaskObj = null;
                if (StringUtils.isNotBlank(taskName)) {
                    taskObj = (JSONObject) JSONObject.toJSON(taskObject.get(taskName));
                    baseTaskObj = (JSONObject) JSONObject.toJSON(baseTask.get(taskObj.getString("implementsTask")));
                } else if (task.getJSONObject("taskDefinition") != null) {
                    taskObj = task.getJSONObject("taskDefinition");
                    baseTaskObj = (JSONObject) JSONObject.toJSON(baseTask.get(taskObj.getString("implementsTask")));
                }
                JSONObject finalTaskObj = taskObj;
                taskObj.keySet().forEach(k -> {
                    task.put(k, finalTaskObj.get(k));
                });

                task.put("runJob", baseTaskObj.get("runJob"));
                task.put("state", ServiceConstants.RackHDTaskStatusEnum.pending.name());
                task.put("taskStartTime", LocalDateTime.now());
                if (task.getJSONObject("options") == null) {
                    task.put("options", extract(e.getWorkflowRequestDTO().getParams()));
                } else {
                    JSONObject userOptions = extract(e.getWorkflowRequestDTO().getParams());
                    JSONObject options = task.getJSONObject("options");
                    if (userOptions != null)
                        userOptions.keySet().forEach(k -> {
                            options.put(k, userOptions.get(k));
                        });
                }

                if (taskOptions.get(task.getString("label")) != null) {
                    taskOptions.get(task.getString("label")).keySet().forEach(k -> {
                        if (!task.getJSONObject("options").containsKey(k)) {
                            task.getJSONObject("options").put(k, taskOptions.get(task.getString("label")).get(k));
                        }
                    });
                }

                task.put("bareMetalId", bareMetalId);
                task.put("instanceId", UUIDUtil.newUUID());

                //将 waiton的 task label 替换成实例的 instancId
                instanceMap.put(task.getString("label"), task.getString("instanceId"));
                if (task.getJSONObject("waitOn") != null) {
                    JSONObject waitOnObj = task.getJSONObject("waitOn");
                    String label = waitOnObj.keySet().stream().findFirst().get();
                    waitOnObj.put(instanceMap.get(label), waitOnObj.getString(label));
                    waitOnObj.remove(label);
                    task.put("waitingOn", waitOnObj);
                    task.remove("waitOn");
                }

                //渲染参数中的 {{}} 形式变量
                renderTaskOptions(task);
                taskObjects.put(task.getString("instanceId"), task);
            }
        }

        return JSONObject.toJSONString(taskObjects);
    }

    private void renderTaskOptions(JSONObject task) {
        try {
            Map<String, String> thisOptions = getThisOptions(task);
            Pattern p = Pattern.compile("\\{\\{([a-zA-Z\\.\\s]+)\\}\\}");
            thisOptions.keySet().forEach(k -> {
                if (thisOptions.get(k) instanceof String && thisOptions.get(k).contains("{{")) {
                    Matcher m = p.matcher(thisOptions.get(k));
                    while (m.find()) {
                        if (m.group(1).contains("options")) {
                            thisOptions.put(k, thisOptions.get(k).replace(m.group(), thisOptions.get(m.group(1).trim().replace("options.", ""))));
                        } else if (m.group(1).contains("task.nodeId")) {
                            thisOptions.put(k, thisOptions.get(k).replace(m.group(), task.getString("bareMetalId")));
                        } else {
                            thisOptions.put(k, thisOptions.get(k).replace(m.group(), renderOptions.get(m.group(1).trim())));
                        }
                    }
                }
                task.getJSONObject("options").put(k, thisOptions.get(k));
            });
        } catch (Exception e) {
            RSException.throwExceptions("参数校验失败！请检查是否有必填参数缺失！");
        }
    }

    private Map getThisOptions(JSONObject task) {
        return task.getJSONObject("options").keySet().stream().collect(Collectors.toMap(k -> k, k -> task.getJSONObject("options").get(k)));
    }

    private JSONObject extract(JSONObject params) {
        if (params != null && params.containsKey("options")) {
            return params.getJSONObject("options").getJSONObject("defaults");
        }
        return params;
    }

    private String findLastTaskId(String bareMetalId) {
        TaskExample e = new TaskExample();
        e.createCriteria().andBareMetalIdEqualTo(bareMetalId).andStatusEqualTo(ServiceConstants.TaskStatusEnum.created.name());
        e.or().andBareMetalIdEqualTo(bareMetalId).andStatusEqualTo(ServiceConstants.TaskStatusEnum.running.name());
        e.setOrderByClause("create_time desc");
        List<Task> tasks = taskMapper.selectByExample(e);
        return tasks.size() > 0 ? tasks.get(0).getId() : null;
    }

    private Map<String, List<LifeEvent>> groupByBM(List<LifeEvent> events) {
        return events.stream().collect(Collectors.groupingBy(LifeEvent::getBareMetalId));
    }

    public TaskWithBLOBs getById(String taskId) {
        return taskMapper.selectByPrimaryKey(taskId);
    }

    public Object logs(String id) {
        ExecutionLogDetailsExample e = new ExecutionLogDetailsExample();
        e.createCriteria().andLogIdEqualTo(id);
        e.setOrderByClause("create_time asc");
        return executionLogDetailsMapper.selectByExampleWithBLOBs(e);
    }

    public int delByBareMetalId(String id) {
        TaskExample e = new TaskExample();
        e.createCriteria().andBareMetalIdEqualTo(id);
        return taskMapper.deleteByExample(e);
    }


    public boolean cancel(String[] ids) {
        if (ids == null || ids.length == 0) {
            return false;
        }
        for (String id : ids) {
            Task task = taskMapper.selectByPrimaryKey(id);
            if (task != null && task.getBareMetalId() != null) {
                if (StringUtils.isNotBlank(task.getInstanceId())) {
                    if (rackHDService.cancelWorkflow(bareMetalManager.getBareMetalById(task.getBareMetalId()))) {
                        task.setStatus(ServiceConstants.TaskStatusEnum.cancelled.name());
                        taskMapper.updateByPrimaryKey(task);
                        template.convertAndSend("/topic/lifecycle", "");
                    } else {
                        return false;
                    }
                } else {
                    task.setStatus(ServiceConstants.TaskStatusEnum.cancelled.name());
                    taskMapper.updateByPrimaryKey(task);
                    template.convertAndSend("/topic/lifecycle", "");
                }
            }
        }
        return true;
    }

    public TaskWithBLOBs createDiscoveryGraph(String bareMetalId) {
        WorkflowWithBLOBs w = workflowService.getByInjectableName("Graph.rancherDiscovery");
        TaskWithBLOBs task = new TaskWithBLOBs();
        task.setId(UUIDUtil.newUUID());
        task.setBareMetalId(bareMetalId);
        task.setWorkFlowId(w.getId());
        task.setStatus(ServiceConstants.TaskStatusEnum.created.name());
        task.setCreateTime(System.currentTimeMillis());
        //生成真正的 taskgraph 实例对象
        WorkflowRequestDTO workflowRequestDTO = new WorkflowRequestDTO();
        workflowRequestDTO.setWorkflowName(w.getInjectableName());
        LifeEvent e = LifeEvent.builder().withEventType(LifeEventType.POST_DISCOVERY_WORKFLOW_START).withWorkflowRequestDTO(workflowRequestDTO).withBareMetalId(bareMetalId);
        task.setGraphObjects(generateGraphObjects(e));
        task.setBeforeStatus(LifeStatus.onrack.name());
        taskMapper.insertSelective(task);
        return task;
    }

    public BareMetal createBMAndDiscoveryGraph(String macs) {
        BareMetal bareMetal = bareMetalManager.createNewFromPXE(macs);
        Task t = createDiscoveryGraph(bareMetal.getId());
        JSONObject param = new JSONObject();
        param.put("taskId", t.getId());
        Message m = new Message(param.toJSONString().getBytes(StandardCharsets.UTF_8));
        rabbitTemplate.send(MqConstants.RUN_TASKGRAPH_QUEUE_NAME, m);
        return bareMetal;
    }

    public Map<String, Object> getTaskProfile(String id) {
        TaskExample e = new TaskExample();
        e.createCriteria().andBareMetalIdEqualTo(id).andStatusIn(runningStatus);
        List<TaskWithBLOBs> tasks = taskMapper.selectByExampleWithBLOBs(e);
        if (tasks.isEmpty()) {
            return null;
        }
        TaskWithBLOBs task = taskMapper.selectByExampleWithBLOBs(e).get(0);
        Map<String, Object> r = new HashMap<>();
        r.put("profile", "redirect.ipxe");
        r.put("options", new JSONObject());
        JSONObject taskObj = JSONObject.parseObject(task.getGraphObjects());
        List<JSONObject> tasksReadyToStart = new ArrayList<>();
        for (String t : taskObj.keySet()) {
            if (taskObj.getJSONObject(t).getJSONObject("waitingOn") == null) {
                tasksReadyToStart.add(taskObj.getJSONObject(t));
            }
        }
        for (JSONObject jsonObject : tasksReadyToStart) {
            if (StringUtils.isNotBlank(jsonObject.getJSONObject("options").getString("profile"))) {
                JSONUtils.merge(renderOptions, jsonObject.getJSONObject("options"));
                jsonObject.getJSONObject("options").put("nodeId", jsonObject.getString("bareMetalId"));
                r.put("profile", jsonObject.getJSONObject("options").getString("profile"));
                r.put("options", jsonObject.getJSONObject("options"));
            }
        }
        return r;
    }
}
