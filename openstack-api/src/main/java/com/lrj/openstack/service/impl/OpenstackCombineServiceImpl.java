package com.lrj.openstack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrj.openstack.domain.entity.*;
import com.lrj.openstack.domain.mapper.RouterOsMapper;
import com.lrj.openstack.domain.mapper.TaskFailLogMapper;
import com.lrj.openstack.domain.mapper.TaskMapper;
import com.lrj.openstack.domain.mapper.VirtualMapper;
import com.lrj.openstack.dto.InvokeInstanceDto;
import com.lrj.openstack.dto.InvokeUserDto;
import com.lrj.openstack.dto.ReqUserDto;
import com.lrj.openstack.enums.DirectionEnum;
import com.lrj.openstack.enums.EtherTypeEnum;
import com.lrj.openstack.enums.ResponseEnum;
import com.lrj.openstack.enums.VirtualStatusEnum;
import com.lrj.openstack.exception.ManageException;
import com.lrj.openstack.response.ManageResponse;
import com.lrj.openstack.service.*;
import com.lrj.openstack.utils.RsUtils;
import com.lrj.openstack.utils.StrUtils;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.VolumeAttachment;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.compute.ext.Hypervisor;
import org.openstack4j.model.identity.v3.Project;
import org.openstack4j.model.identity.v3.User;
import org.openstack4j.model.network.*;
import org.openstack4j.model.network.builder.SubnetBuilder;
import org.openstack4j.model.network.ext.NetQosPolicy;
import org.openstack4j.model.network.options.PortListOptions;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.openstack.compute.domain.NovaServer;
import org.openstack4j.openstack.networking.domain.ext.NeutronNetQosPolicy;
import org.openstack4j.openstack.storage.block.domain.VolumeBackendPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ClassName: OpenstackCombineServiceImpl
 * Description:
 * Date: 2021/8/18 14:36
 *
 * @author luorenjie
 * @version 1.0
 * @since JDK 1.8
 */
@Service
@Slf4j
public class OpenstackCombineServiceImpl implements OpenstackCombineService {
    @Autowired
    private OpenstackService openstackService;
    @Autowired
    private OpenstackAuthService openstackAuthService;
    @Autowired
    private RegionService regionService;
    @Autowired
    private OpenstackAuthNetService openstackAuthNetService;
    @Autowired
    private VirtualMapper virtualMapper;
//    @Autowired
//    private StringRedisTemplate handler;
    @Autowired
    private RouterOsMapper routerOsMapper;
    @Autowired
    private RouterOsService routerOsService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskFailLogMapper taskFailLogMapper;

    @Value("${mq.routing-key.instance}")
    private String instanceKey;
    @Value("${desktop.rdp-port}")
    private Integer rdpPort;
    @Value("${openstack.keystone.roleId}")
    private String roleId;
    @Value("${openstack.network.ip.start}")
    private String ipStart;
    @Value("${openstack.network.ip.end}")
    private String ipEnd;
    @Value("${openstack.network.ip.cidr}")
    private String cidr;

    private static final int OVER_NUMBER = 8;

    private static String DESKTOP_DES_PREV = "????????????????????????:";
    private static String FLOATING_IP_DES_LAST = "??????floatingIp";
    private static String POLICY_NAME_PREV = "QJCPolicy";
    private static String QOS_POLICY_DES_LAST = "??????qosPolicy";
    private static String VOLUME_NAME_PREV = "QJCVolume";
    private static String VOLUME_DES_LAST = "??????volume";
    private static String PROJECT_NAME_PREV = "QJCProject";
    private static String PROJECT_DES_LAST = "?????????project";
    private static String USER_DES_PREV = "??????????????????:";
    private static String USER_NAME_PREV = "QJCUser";
    private static String USER_DES_LAST = "?????????user";
    private static String NET_NAME_PREV = "QJCNetwork";
    private static String SUBNET_NAME_PREV = "QJCSubNetwork";
    private static String ROUTER_NAME_PREV = "QJCRouter";

    private static String SECURITY_GROUP_NAME_PREV = "QJCSecurityGroup";
    private static String SECURITY_GROUP_DES_LAST = "??????securityGroup";
    private static String INSTANCE_NAME_PREV = "QJCInstance";


    private final ReqUserDto reqUserDto = new ReqUserDto(0, 1, 3, null);
    private List<String> nameServers = new ArrayList<>(Arrays.asList("8.8.8.8", "114.114.114.114"));
    ExecutorService instanceCreatePool = new ThreadPoolExecutor(20, 200, 1000l, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(2000), new NamedThreadFactory("Create instance on openstack platform"), new ThreadPoolExecutor.DiscardPolicy());
    ExecutorService otherCreatePool = new ThreadPoolExecutor(20, 200, 1000l, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(2000), new NamedThreadFactory("Create params for instance on openstack platform"), new ThreadPoolExecutor.DiscardPolicy());
    ScheduledThreadPoolExecutor instanceStateCheckPool = new ScheduledThreadPoolExecutor(20);
    ScheduledThreadPoolExecutor volumeStateCheckPool = new ScheduledThreadPoolExecutor(20);

    /**
     * ????????????????????????????????????????????????
     * ???????????????
     * 1.????????????????????????
     * 2.????????????????????????????????????
     *
     * @param invokeUserDto
     * @return
     */
    @Override
    public ManageResponse invokeUser(InvokeUserDto invokeUserDto) {

        /*
         * step1.????????????????????????,??????project???user
         */
        synchronized ("invokeUser" + invokeUserDto.getUserId().toString().intern()) {
            if (ObjectUtils.isEmpty(invokeUserDto.getUserId())) {
                return ManageResponse.returnFail(ResponseEnum.PARAMETER_ERROR);
            }
            OpenstackAuth openstackAuth = checkAuthUser(invokeUserDto.getUserId());
            boolean have = false;
            boolean modify = false;
            //??????????????????
            try {
                log.info(">>>>>>>>>>?????????????????????????????????{}???openstack?????????????????????", invokeUserDto.getUserId());
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                if (!ObjectUtils.isEmpty(openstackAuth)) {
                    have = true;
                    if (ObjectUtils.isEmpty(openstackAuth.getProjectId())) {
                        modify = true;
                        log.info(">>>>>>>>>>????????????project");
                        createProject(invokeUserDto.getUserId(), openstackAuth, os);
                    }
                    if (ObjectUtils.isEmpty(openstackAuth.getUserId())) {
                        modify = true;
                        log.info(">>>>>>>>>>????????????user");
                        createUser(invokeUserDto.getUserId(), openstackAuth.getProjectId(), openstackAuth, os);
                    }
                    boolean isAttach = checkUserIsAttachToProject(openstackAuth.getUserId(), os);
                    if (!isAttach) {
                        modify = true;
                        log.info(">>>>>>>>>>???user??????role????????????project");
                        ActionResponse actionResponse = attachUserToProject(openstackAuth.getUserId(), openstackAuth.getProjectId(), os);
                        if (actionResponse.getCode() != 204) {
                            log.error("???????????????role???project?????????{}", actionResponse.getFault());
                            return ManageResponse.returnFail(actionResponse.getCode(), actionResponse.getFault());
                        }
                    }
                } else {
                    openstackAuth = new OpenstackAuth();
                    openstackAuth.setCustomerId(invokeUserDto.getUserId());
                    openstackAuth.setType(2);

                    log.info(">>>>>>>>>>????????????project");
                    Project project = createProject(invokeUserDto.getUserId(), openstackAuth, os);

                    log.info(">>>>>>>>>>????????????user");
                    User user = createUser(invokeUserDto.getUserId(), project.getId(), openstackAuth, os);

                    log.info(">>>>>>>>>>???user??????role????????????project");
                    ActionResponse actionResponse = attachUserToProject(user.getId(), project.getId(), os);
                    if (actionResponse.getCode() != 204) {
                        log.error("???????????????role???project?????????{}", actionResponse.getFault());
                        return ManageResponse.returnFail(actionResponse.getCode(), actionResponse.getFault());
                    }
                }
            } catch (Exception e) {
                log.error("?????????????????????????????????{}", e.getMessage());
                String msg = "?????????????????????????????????";
//                sentMsg(invokeUserDto.getUserId(),
//                        VirtualStatusEnum.FAIL.getCode(),
//                        msg,
//                        invokeUserDto.getUserId().toString(),
//                        SocketMessageType.SOCKET_COMMAND_TYPE_CREATE_OPENSTACK_AUTH.getType(),
//                        RedisEnum.REDIS_CREATE_OPENSTACK_AUTH_TOPIC.getValue());
                if (e instanceof ClientResponseException) {
                    if (e instanceof ClientResponseException) {
                        return ManageResponse.returnFail(((ClientResponseException) e).getStatusCode().getCode(), e.getMessage());
                    }
                }
                return ManageResponse.returnFail(ResponseEnum.FAIL);
            } finally {
                if (ObjectUtils.isEmpty(openstackAuth)) {
                    return ManageResponse.returnFail(ResponseEnum.FAIL);
                }
                if (have && modify) {
                    openstackAuthService.update(openstackAuth);
                }
                if (!have) {
                    openstackAuthService.create(openstackAuth);
                }
            }
            /*
             * step2.??????????????????????????????????????????ip
             */
            invokeNetInfo(invokeUserDto.getUserId(), openstackAuth);
            return ManageResponse.returnSuccess("???????????????????????????????????????");
        }

    }

    @Override
    public synchronized ManageResponse createInstance(InvokeInstanceDto invokeInstanceDto) {
        log.info(">>>>>>>>>>???????????????????????????{}???openstack??????????????????", invokeInstanceDto.getUserId());
        if (CollectionUtils.isEmpty(invokeInstanceDto.getDesktopIds())) {
            return ManageResponse.returnFail(ResponseEnum.PARAMETER_ERROR);
        }
        if ((!ObjectUtils.isEmpty(invokeInstanceDto.getBandwidthSize()) && invokeInstanceDto.getBandwidthSize() <= 0)
                || (!ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize()) && invokeInstanceDto.getVolumeSize() <= 0)) {
            return ManageResponse.returnFail(ResponseEnum.PARAMETER_ILLEGAL);
        }
        OpenstackAuth openstackAuth = checkAuthUser(invokeInstanceDto.getUserId());
        Region region = regionService.getRegionInfo(invokeInstanceDto.getRegionId());
        OpenstackAuthNet openstackAuthNet = openstackAuthNetService
                .getOpenstackAuthNet(invokeInstanceDto.getRegionId(), invokeInstanceDto.getUserId());
        for (Integer desktopId : invokeInstanceDto.getDesktopIds()) {
            Virtual virtual = virtualMapper.selectById(desktopId);
        }
        //????????????????????????
        Integer taskRecordId = createTask(invokeInstanceDto.getDesktopIds());
        for (Integer desktopId : invokeInstanceDto.getDesktopIds()) {
            Virtual virtual = virtualMapper.selectById(desktopId);
            if (ObjectUtils.isEmpty(virtual)) {
                return ManageResponse.returnFail(100, "???????????????????????????");
            }
            if (!virtual.getCustomerId().equals(invokeInstanceDto.getUserId())) {
                log.warn("??????????????????????????????????????????");
                return ManageResponse.returnFail(ResponseEnum.FORBIDDEN);
            }
            if (checkCreateSuccess(virtual.getProgress(), invokeInstanceDto)) {
                log.warn("??????????????????{}???????????????", desktopId);
                continue;
            }
            instanceCreatePool.execute(() -> {
                if (ObjectUtils.isEmpty(virtual.getProgress())) {
                    createInstance(openstackAuth, region, invokeInstanceDto, openstackAuthNet, virtual, taskRecordId);
                } else {
                    ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(),
                            openstackAuth.getType(), 3, null);
                    createAndBindOtherSource(reqUserDto1, region, openstackAuth, invokeInstanceDto, virtual, taskRecordId);
//                //??????????????????????????????????????????
//                Map<String, Object> map = new HashMap<>();
//                map.put("reqUserDto", reqUserDto1);
//                map.put("regionVo", regionVo);
//                map.put("openstackAuth", openstackAuth);
//                map.put("gpCustomerVirtual", gpCustomerVirtual);
//                map.put("invokeInstanceDto", invokeInstanceDto);
//                msgPublisher.send(instanceKey, map);
//
                }
            });
            virtual.setStatus(VirtualStatusEnum.CREATING.getCode());
            virtualMapper.updateById(virtual);
        }
        return ManageResponse.returnSuccess("?????????????????????????????????");
    }

    @Override
    public ManageResponse destroyInstance(InvokeInstanceDto invokeInstanceDto) {
        List<Integer> vids = invokeInstanceDto.getDesktopIds();
        for (Integer vid : vids) {
            Virtual virtual = virtualMapper.selectById(vid);
            OpenstackAuth openstackAuth = checkAuthUser(virtual.getCustomerId());
            if (ObjectUtils.isEmpty(openstackAuth)) {
                return ManageResponse.returnFail(100, "?????????????????????");
            }
            Region region = regionService.getRegionInfo(virtual.getRegionId());
            if (ObjectUtils.isEmpty(region)) {
                return ManageResponse.returnFail(100, "???????????????");
            }
            ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(),
                    openstackAuth.getType(), 3, null);
            OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto1).getData());
            os.useRegion(region.getRealName());

            //??????instance
            if (!ObjectUtils.isEmpty(virtual.getInstanceId())) {
                Server server = os.compute().servers().get(virtual.getInstanceId());
                if (!ObjectUtils.isEmpty(server)) {
                    ActionResponse actionResponse = os.compute().servers().delete(virtual.getInstanceId());
                    if (actionResponse.getCode() != 200) {
                        log.error("??????openstack?????????{}??????", virtual.getInstanceId());
                        return ManageResponse.returnFail(actionResponse.getCode(), actionResponse.getFault());
                    }
                }
            }

            //??????floating ip
            if (!ObjectUtils.isEmpty(virtual.getFloatingIpId())) {
                NetFloatingIP floatingIP = os.networking().floatingip().get(virtual.getFloatingIpId());
                if (!ObjectUtils.isEmpty(floatingIP)) {
                    ActionResponse actionResponse = os.networking().floatingip().delete(virtual.getFloatingIpId());
                    if (actionResponse.getCode() != 204) {
                        log.error("??????openstack??????ip???{}????????????????????????{}", virtual.getFloatingIp(), actionResponse.getFault());
                    }
                }
            }

            //??????qos
            if (!ObjectUtils.isEmpty(virtual.getPolicyId())) {
                NeutronNetQosPolicy policy = (NeutronNetQosPolicy) os.networking().netQosPolicy().get(virtual.getPolicyId());
                if (!ObjectUtils.isEmpty(policy)) {
                    List<Map<String, String>> policyRules = policy.getRules();
                    if (!CollectionUtils.isEmpty(policyRules)) {
                        for (Map<String, String> policyRule : policyRules) {
                            String ruleId = policyRule.get("id");
                            ActionResponse actionResponse = os.networking().netQosPolicyBLRule().delete(policy.getId(), ruleId);
                            if (actionResponse.getCode() != 204) {
                                log.error("??????openstack???????????????{}????????????????????????{}", ruleId, actionResponse.getFault());
                            }
                        }
                    }
                    ActionResponse actionResponse = os.networking().netQosPolicy().delete(policy.getId());
                    if (actionResponse.getCode() != 204) {
                        log.error("??????openstack qos?????????{}????????????????????????{}", policy.getId(), actionResponse.getFault());
                    }
                }
            }

            //??????volume
            if (!ObjectUtils.isEmpty(virtual.getVolumeId())) {
                //??????volume
                Volume volume = os.blockStorage().volumes().get(virtual.getVolumeId());
                if (!ObjectUtils.isEmpty(volume)) {
                    ActionResponse actionResponse = os.compute().servers()
                            .detachVolume(virtual.getInstanceId(), virtual.getVolumeId());
                    if (actionResponse.getCode() != 200) {
                        log.error("??????openstack????????????{}????????????????????????{}", virtual.getVolumeId(), actionResponse.getFault());
                    }
                    //??????volume
                    actionResponse = os.blockStorage().volumes().forceDelete(virtual.getVolumeId());
                    if (actionResponse.getCode() != 202) {
                        log.error("??????openstack????????????{}????????????????????????{}", virtual.getVolumeId(), actionResponse.getFault());
                    }
                }
            }

            virtual.setIsDelete(1);
            virtual.setDeleteTime(new Date());
            virtualMapper.updateById(virtual);
        }

        return ManageResponse.returnSuccess();
    }

    @Override
    public ManageResponse actionInstance(InvokeInstanceDto invokeInstanceDto, boolean start) {
        Integer vid = invokeInstanceDto.getDesktopIds().get(0);
        Virtual virtual = virtualMapper.selectById(vid);
        OpenstackAuth openstackAuth = checkAuthUser(virtual.getCustomerId());
        if (ObjectUtils.isEmpty(openstackAuth)) {
            return ManageResponse.returnFail(100, "?????????????????????");
        }
        Region region = regionService.getRegionInfo(virtual.getRegionId());
        if (ObjectUtils.isEmpty(region)) {
            return ManageResponse.returnFail(100, "???????????????");
        }
        ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(),
                openstackAuth.getType(), 3, null);
        OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto1).getData());
        os.useRegion(region.getRealName());
        Action action;
        if (start){
            action = Action.START;
        }else {
            action = Action.STOP;
        }
        ActionResponse actionResponse = os.compute().servers().action(virtual.getInstanceId(), action);
        //todo 1.???/????????????????????????2.??????????????????
        if (actionResponse.getCode() == 200) {
            return ManageResponse.returnSuccess();
        }
        return ManageResponse.returnFail(actionResponse.getCode(), actionResponse.getFault());
    }

    @Override
    public String checkResource(InvokeInstanceDto invokeInstanceDto) {
        log.info(">>>>>>>>>>??????????????????{}???????????????????????????", invokeInstanceDto.getUserId());
        if (ObjectUtils.isEmpty(invokeInstanceDto.getNum())
                || ObjectUtils.isEmpty(invokeInstanceDto.getUserId())
                || ObjectUtils.isEmpty(invokeInstanceDto.getFlavorId())
                || ObjectUtils.isEmpty(invokeInstanceDto.getRegionId())) {
            return ResponseEnum.PARAMETER_ERROR.getDesZh();
        }
        if ((!ObjectUtils.isEmpty(invokeInstanceDto.getBandwidthSize()) && invokeInstanceDto.getBandwidthSize() <= 0)
                || (!ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize()) && invokeInstanceDto.getVolumeSize() <= 0)) {
            return ResponseEnum.PARAMETER_ILLEGAL.getDesZh();
        }
        OpenstackAuth openstackAuth = checkAuthUser(invokeInstanceDto.getUserId());
        if (ObjectUtils.isEmpty(openstackAuth)) {
            return "?????????????????????";
        }
        Region region = regionService.getRegionInfo(invokeInstanceDto.getRegionId());
        if (ObjectUtils.isEmpty(region)) {
            return "???????????????";
        }
        OpenstackAuthNet openstackAuthNet = openstackAuthNetService
                .getOpenstackAuthNet(invokeInstanceDto.getRegionId(), invokeInstanceDto.getUserId());
        if (ObjectUtils.isEmpty(openstackAuthNet)) {
            return "???????????????????????????";
        }

        //todo ???????????????
        ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(),
                openstackAuth.getType(), 3, null);
        OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto1).getData());
        os.useRegion(region.getRealName());
        List<? extends Hypervisor> hypervisors = os.compute().hypervisors().list();
        Flavor flavor = os.compute().flavors().get(invokeInstanceDto.getFlavorId());
        String msg = null;
//        for (Hypervisor hypervisor : hypervisors) {
//            if (hypervisor.getFreeRam() < flavor.getRam() * invokeInstanceDto.getNum()) {
//                log.error("????????????????????????????????????{}????????????????????????????????????{}", hypervisor.getFreeRam(), flavor.getRam());
//                msg = "????????????";
//                continue;
//            }
//            if (hypervisor.getFreeDisk() < flavor.getDisk() * invokeInstanceDto.getNum()) {
//                log.error("???????????????????????????????????????{}???????????????????????????????????????{}", hypervisor.getFreeDisk(), flavor.getDisk());
//                msg = "???????????????";
//                continue;
//            }
//            int freeCpu = hypervisor.getVirtualCPU() - hypervisor.getVirtualUsedCPU();
//            if (freeCpu < flavor.getVcpus() * invokeInstanceDto.getNum()) {
//                log.error("??????????????????CPU????????????{}?????????????????????CPU?????????{}", freeCpu, flavor.getVcpus());
//                msg = "CPU????????????";
//                continue;
//            }
//        }
//        if (msg != null) {
//            return msg;
//        }

        List<? extends Hypervisor> cpuList = hypervisors.stream().filter(x -> (x.getVirtualCPU() * OVER_NUMBER - x.getVirtualUsedCPU()) >= flavor.getVcpus() * invokeInstanceDto.getNum()).collect(Collectors.toList());
        List<? extends Hypervisor> ramList = hypervisors.stream().filter(x -> x.getFreeRam() >= flavor.getRam() * invokeInstanceDto.getNum()).collect(Collectors.toList());
        List<? extends Hypervisor> diskList = hypervisors.stream().filter(x -> x.getFreeDisk() >= flavor.getDisk() * invokeInstanceDto.getNum()).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(cpuList)) {
            log.error("??????????????????CPU????????????????????????CPU?????????{}", flavor.getVcpus() * invokeInstanceDto.getNum());
            return "CPU????????????";
        }
        if (CollectionUtils.isEmpty(ramList)) {
            log.error("???????????????????????????????????????????????????????????????{}", flavor.getRam() * invokeInstanceDto.getNum());
            return "????????????";
        }
        if (CollectionUtils.isEmpty(diskList)) {
            log.error("?????????????????????????????????????????????????????????????????????{}", flavor.getDisk() * invokeInstanceDto.getNum());
            return "???????????????";
        }

        if (!ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize())) {
            long totalSize = 0;
            long attachSize = 0;
            List<? extends VolumeBackendPool> volumeBackendPools = os.blockStorage().schedulerStatsPools().poolsDetail();
            for (VolumeBackendPool volumeBackendPool : volumeBackendPools) {
                totalSize += volumeBackendPool.getCapabilities().getTotalCapacityGb();
                attachSize += volumeBackendPool.getCapabilities().getAllocatedcapacitygb();
            }
            long freeSize = totalSize - attachSize;
            if (freeSize < invokeInstanceDto.getVolumeSize() * invokeInstanceDto.getNum()) {
                log.error("???????????????????????????{}???????????????????????????????????????{}", freeSize, invokeInstanceDto.getVolumeSize());
                msg = "???????????????";
                return msg;
            }
        }
        return null;
    }

    private void createInstance(OpenstackAuth openstackAuth, Region region, InvokeInstanceDto invokeInstanceDto, OpenstackAuthNet openstackAuthNet, Virtual virtual, Integer taskRecordId) {
        try {
            ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(),
                    openstackAuth.getType(), 3, null);
            OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto1).getData());
            os.useRegion(region.getRealName());
            //1.??????instance
            Map<String, String> passMap = new HashMap<>();
            String winPass = StrUtils.generateWinPass();
            passMap.put("admin_pass", winPass);
            String instanceName = INSTANCE_NAME_PREV + virtual.getId();
            //???????????????
            SecurityGroup securityGroup = os.networking().securitygroup().get(openstackAuthNet.getSgId());
            log.info(">>>>>>>>>>????????????instance");
            ServerCreateBuilder sb = Builders.server()
                    .name(instanceName)
                    .flavor(invokeInstanceDto.getFlavorId())
                    .networks(Arrays.asList(openstackAuthNet.getNetworkId()))
                    .image(invokeInstanceDto.getImageId())
                    .addMetadata(passMap)
                    .addSecurityGroup(securityGroup.getName());
            Server server = os.compute().servers().boot(sb.build());
            //TODO ??????????????????
            if (!checkInstanceState(reqUserDto1, region, server.getId(), 1, taskRecordId, virtual.getId())) {
                throw new ManageException("??????????????????");
            }
            virtual.setInstanceId(server.getId());
            virtual.setProgress("1");
            virtual.setPassword(winPass);
            virtual.setStatus(VirtualStatusEnum.CREATING.getCode());
            virtualMapper.updateById(virtual);
            createAndBindOtherSource(reqUserDto1, region, openstackAuth, invokeInstanceDto, virtual, taskRecordId);
//            //??????????????????????????????????????????
//            Map<String, Object> map = new HashMap<>();
//            map.put("reqUserDto", reqUserDto1);
//            map.put("regionVo", regionVo);
//            map.put("openstackAuth", openstackAuth);
//            map.put("gpCustomerVirtual", gpCustomerVirtual);
//            map.put("invokeInstanceDto", invokeInstanceDto);
//            msgPublisher.send(instanceKey, map);
        } catch (Exception e) {
            log.error("??????????????????{}?????????????????????{}", invokeInstanceDto.getUserId(), e.getMessage());
            updateTaskRecord(taskRecordId, false);
            if (!(e instanceof ManageException)) {
                String msg1 = "???????????????????????????????????????id???" + virtual.getId() + "???????????????" + e.getMessage();
                createTaskLog(taskRecordId, msg1);
            }

            virtual.setStatus(VirtualStatusEnum.FAIL.getCode());
            virtualMapper.updateById(virtual);
            String msg = "??????????????????";
//            sentMsg(virtual.getUserId(),
//                    VirtualStatusEnum.Fail.getValue(),
//                    msg,
//                    virtual.getId().toString(),
//                    SocketMessageType.SOCKET_COMMAND_TYPE_CREATE_OPENSTACK_INSTANCE.getType(),
//                    RedisEnum.REDIS_CREATE_OPENSTACK_INSTANCE_TOPIC.getValue());
            throw new ManageException(e);
        }
    }

    /**
     * ????????????????????????????????????
     *
     * @param reqUserDto
     * @param region
     * @param serverId
     * @param count
     * @return
     * @throws Exception
     */
    private boolean checkInstanceState(ReqUserDto reqUserDto,
                                       Region region,
                                       String serverId,
                                       int count,
                                       Integer taskRecordId,
                                       Integer desktopId) {
        ScheduledFuture<Server> scheduledFuture = instanceStateCheckPool.schedule(() -> {
            OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
            os.useRegion(region.getRealName());
            NovaServer server = (NovaServer) os.compute().servers().get(serverId);
            return server;
        }, 30 * count, TimeUnit.SECONDS);

        try {
            NovaServer server = (NovaServer) scheduledFuture.get();
            if (server.getStatus().equals(Server.Status.ERROR)
                    || server.getStatus().equals(Server.Status.UNKNOWN)
                    || server.getStatus().equals(Server.Status.UNRECOGNIZED)) {
                log.error("???????????????????????????{}", server.getFault());
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                os.compute().servers().delete(serverId);
                String msg1 = "???????????????????????????????????????id???" + desktopId + "???????????????" + server.getFault();
                createTaskLog(taskRecordId, msg1);
                return false;
            }
            if (server.getStatus().equals(Server.Status.ACTIVE)) {
                return true;
            }
            if (count > 6) {
                //todo ????????????
                log.error("??????????????????????????????{}", server.getStatus());
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                os.compute().servers().delete(serverId);
                String msg1 = "???????????????????????????????????????id???" + desktopId + "??????????????????????????????????????????????????????" + server.getStatus();
                createTaskLog(taskRecordId, msg1);
                return false;
            }
            count++;
            checkInstanceState(reqUserDto, region, serverId, count, taskRecordId, desktopId);
            return false;
        } catch (Exception e) {
            log.error("?????????????????????????????????{}??? ????????????", e.getMessage());
            if (count > 6) {
                //todo ????????????
                log.error("??????????????????");
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                NovaServer server = (NovaServer) os.compute().servers().get(serverId);
                os.compute().servers().delete(serverId);
                String msg1 = "???????????????????????????????????????id???" + desktopId + "??????????????????????????????????????????????????????" + server.getStatus();
                createTaskLog(taskRecordId, msg1);
                return false;
            }
            count++;
            checkInstanceState(reqUserDto, region, serverId, count, taskRecordId, desktopId);
            return false;
        }
    }


    /**
     * ????????????????????????????????????????????????
     *
     * @param uid
     * @param openstackAuth
     */
    private void invokeNetInfo(Integer uid, OpenstackAuth openstackAuth) {
        //??????????????????
        ReqUserDto reqUserDto1 = new ReqUserDto(openstackAuth.getCustomerId(), openstackAuth.getType(), 3, null);
        OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto1).getData());
        //????????????
        List<Region> regions = regionService.getRegions();
        Set<OpenstackAuthNet> inList = new HashSet<>();
        Set<OpenstackAuthNet> upList = new HashSet<>();
        for (Region region : regions) {
            OpenstackAuthNet result = openstackAuthNetService.getOpenstackAuthNet(region.getId(), uid);
            if (ObjectUtils.isEmpty(result)) {
                OpenstackAuthNet openstackAuthNet = new OpenstackAuthNet();
                openstackAuthNet.setCustomerId(uid);
                openstackAuthNet.setRegionId(region.getId());
                os = os.useRegion(region.getRealName());
                inList.add(openstackAuthNet);
                try {
                    log.info(">>>>>>>>>>????????????network");
                    createNetwork(uid, openstackAuth.getProjectId(), openstackAuthNet, os);

                    log.info(">>>>>>>>>>????????????subnet");
                    createSubnet(uid, openstackAuth.getProjectId(), openstackAuthNet, os);

                    log.info(">>>>>>>>>>????????????router");
                    createRouter(uid, region.getExtNetId(), openstackAuthNet, os);

                    log.info(">>>>>>>>>>???router?????????subnet");
                    os.networking().router().attachInterface(openstackAuthNet.getRouterId(),
                            AttachInterfaceType.SUBNET, openstackAuthNet.getSubnetId());

                    log.info(">>>>>>>>>>????????????security group");
                    createSecurityGroup(uid, openstackAuth.getProjectId(), openstackAuthNet, os);

                    log.info(">>>>>>>>>>????????????security group rule");
                    createSecurityGroupRule(openstackAuthNet.getSgId(), os);
                } catch (Exception e) {
                    log.error("???????????????????????????????????????{}", e.getMessage());
                    continue;
                }
            } else {
                os = os.useRegion(region.getRealName());
                boolean modify = false;
                try {
                    if (ObjectUtils.isEmpty(result.getNetworkId())) {
                        log.info(">>>>>>>>>>????????????network");
                        createNetwork(result.getCustomerId(),
                                openstackAuth.getProjectId(), result, os);
                        modify = true;
                    }
                    if (ObjectUtils.isEmpty(result.getSubnetId())) {
                        log.info(">>>>>>>>>>????????????subnet");
                        createSubnet(result.getCustomerId(),
                                openstackAuth.getProjectId(), result, os);
                        modify = true;
                    }
                    if (ObjectUtils.isEmpty(result.getRouterId())) {
                        log.info(">>>>>>>>>>????????????router");
                        createRouter(result.getCustomerId(),
                                region.getExtNetId(), result, os);
                        modify = true;
                    }
                    List<? extends Port> ports = os.networking().port().list(PortListOptions.create().deviceId(result.getRouterId()));
                    if (ports.size() == 1) {
                        log.info(">>>>>>>>>>???router?????????subnet");
                        os.networking().router().attachInterface(result.getRouterId(),
                                AttachInterfaceType.SUBNET, result.getSubnetId());
                        modify = true;
                    }
                    if (ObjectUtils.isEmpty(result.getSgId())) {
                        log.info(">>>>>>>>>>????????????security group");
                        createSecurityGroup(uid, openstackAuth.getProjectId(), result, os);
                        modify = true;
                    }
                    boolean isCreate = checkSecurityGroupRule(result.getSgId(), os);
                    if (!isCreate) {
                        log.info(">>>>>>>>>>????????????security group rule");
                        createSecurityGroupRule(result.getSgId(), os);
                        modify = true;
                    }

                    if (modify) {
                        upList.add(result);
                    }
                } catch (Exception e) {
                    log.error("???????????????????????????????????????{}", e.getMessage());
                    if (modify) {
                        upList.add(result);
                    }
                    continue;
                }
            }
        }
        //??????????????????
        if (!CollectionUtils.isEmpty(inList)) {
            openstackAuthNetService.saveBatch(inList);
        }
        //??????????????????
        if (!CollectionUtils.isEmpty(upList)) {
            openstackAuthNetService.updateBatchById(upList);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param progress
     * @param invokeInstanceDto
     * @return
     */
    private boolean checkCreateSuccess(String progress, InvokeInstanceDto invokeInstanceDto) {
        List<Integer> list = StrUtils.greatedIntegerList(progress);
        if (list.size() == 9) {
            return true;
        }
        if (ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize()) && list.size() == 7) {
            return true;
        }
        if (ObjectUtils.isEmpty(invokeInstanceDto.getBandwidthSize())
                && ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize())
                && list.size() == 4) {
            return true;
        }
        return false;
    }


    /**
     * ??????????????????????????????
     *
     * @param uid
     * @return
     */
    public OpenstackAuth checkAuthUser(Integer uid) {
        return openstackAuthService.getOpenStackAuthInfo(uid);
    }

    /**
     * ???????????????user??????role????????????project
     *
     * @param userId
     * @param os
     * @return
     */
    private boolean checkUserIsAttachToProject(String userId, OSClient.OSClientV3 os) {
        List<? extends Project> projects = os.identity().users().listUserProjects(userId);
        if (CollectionUtils.isEmpty(projects)) {
            return false;
        }
        return true;
    }

    /**
     * ???????????????????????????
     *
     * @param sgId
     * @param os
     * @return
     */
    private boolean checkSecurityGroupRule(String sgId, OSClient.OSClientV3 os) {
        SecurityGroup securityGroup = os.networking().securitygroup().get(sgId);
        if (CollectionUtils.isEmpty(securityGroup.getRules()) || securityGroup.getRules().size() < 3) {
            return false;
        }
        return true;
    }

    /**
     * ??????project
     *
     * @param uid
     * @param openstackAuth
     * @param os
     * @return
     */
    private Project createProject(Integer uid, OpenstackAuth openstackAuth, OSClient.OSClientV3 os) {
        try {
            String projectName = PROJECT_NAME_PREV + uid;
            String projectDesc = USER_DES_PREV + uid + PROJECT_DES_LAST;
            Project project = os.identity().projects().create(Builders.project().name(projectName)
                    .description(projectDesc)
                    .enabled(true)
                    .build());
            openstackAuth.setProjectId(project.getId());
            openstackAuth.setProjectName(project.getName());
            return project;
        } catch (Exception e) {
            log.error("??????project??????");
            throw new ManageException(e);
        }
    }

    /**
     * ??????user
     *
     * @param uid
     * @param pid
     * @param openstackAuth
     * @param os
     * @return
     */
    private User createUser(Integer uid, String pid, OpenstackAuth openstackAuth, OSClient.OSClientV3 os) {
        try {
            String userName = USER_NAME_PREV + uid;
            String userDesc = USER_DES_PREV + pid + USER_DES_LAST;
            String passwd = StrUtils.getRandomStr(8);
            User user = os.identity().users().create(Builders.user()
                    .name(userName)
                    .password(passwd)
//                    .domainId()
                    .description(userDesc)
                    .build());
            openstackAuth.setDomainId(user.getDomainId());
            openstackAuth.setUserId(user.getId());
            openstackAuth.setUserName(user.getName());
            openstackAuth.setPassword(passwd);
            return user;
        } catch (Exception e) {
            log.error("??????user??????");
            throw new ManageException(e);
        }
    }

    /**
     * ???user??????role????????????project
     *
     * @param userId
     * @param pid
     * @param os
     * @return
     */
    private ActionResponse attachUserToProject(String userId, String pid, OSClient.OSClientV3 os) {
        try {
            ActionResponse actionResponse = os.identity().roles().grantProjectUserRole(pid, userId, roleId);
            return actionResponse;
        } catch (Exception e) {
            log.error("???user??????role????????????project??????");
            throw new ManageException(e);
        }

    }

    /**
     * ???project??????network
     *
     * @param uid
     * @param pid
     * @param openstackAuthNet
     * @param os
     * @return
     */
    private Network createNetwork(Integer uid, String pid, OpenstackAuthNet openstackAuthNet, OSClient.OSClientV3 os) {
        try {
            String networkName = NET_NAME_PREV + uid;
            Network network = os.networking().network().create(Builders.network()
                    .name(networkName)
                    .adminStateUp(true)
                    .networkType(NetworkType.VXLAN)
                    .tenantId(pid)
                    .build());
            openstackAuthNet.setNetworkId(network.getId());
            return network;
        } catch (Exception e) {
            log.error("??????network??????");
            throw new ManageException(e);
        }
    }

    /**
     * ???project???network??????subnet
     *
     * @param uid
     * @param pid
     * @param openstackAuthNet
     * @param os
     * @return
     */
    private Subnet createSubnet(Integer uid, String pid, OpenstackAuthNet openstackAuthNet, OSClient.OSClientV3 os) {
        try {
            String subNetworkName = SUBNET_NAME_PREV + uid;
            SubnetBuilder sb = Builders.subnet()
                    .name(subNetworkName)
                    .networkId(openstackAuthNet.getNetworkId())
                    .enableDHCP(true)
                    .tenantId(pid)
                    .addPool(ipStart, ipEnd)
                    .ipVersion(IPVersionType.V4)
                    .cidr(cidr);
            for (String nameServer : nameServers) {
                sb.addDNSNameServer(nameServer);
            }
            Subnet subnet = os.networking().subnet().create(sb.build());
            openstackAuthNet.setSubnetId(subnet.getId());
            return subnet;
        } catch (Exception e) {
            log.error("??????subnet??????");
            throw new ManageException(e);
        }
    }

    /**
     * ???network??????router
     *
     * @param uid
     * @param extNetworkId
     * @param openstackAuthNet
     * @param os
     * @return
     */
    private Router createRouter(Integer uid, String extNetworkId, OpenstackAuthNet openstackAuthNet, OSClient.OSClientV3 os) {
        try {
            String routerName = ROUTER_NAME_PREV + uid;
            Router router = os.networking().router().create(Builders.router()
                    .name(routerName)
                    .adminStateUp(true)
                    .externalGateway(extNetworkId)
//                    .route(networkDto.getCidr(), "10.20.20.1")
                    .build());
            openstackAuthNet.setRouterId(router.getId());
            return router;
        } catch (Exception e) {
            log.error("??????router??????");
            throw new ManageException(e);
        }
    }

    /**
     * ??????????????????
     *
     * @param virtualIds
     * @return
     */
    private Integer createTask(List<Integer> virtualIds) {
        String vids = virtualIds.toString().replaceAll("\\[", "").replaceAll("]", "").replaceAll(" ", "");
        Task task = new Task();
        task.setVirtualIds(vids);
        taskMapper.insert(task);
        return task.getId();
    }

    /**
     * ????????????????????????
     *
     * @param taskRecordId
     * @param success
     */
    private void updateTaskRecord(Integer taskRecordId, boolean success) {
        Task task = taskMapper.selectById(taskRecordId);
        if (success) {
            task.setSuccessNum(task.getSuccessNum() + 1);
        } else {
            task.setFailNum(task.getFailNum() + 1);
        }
        taskMapper.updateById(task);
    }

    private void createTaskLog(Integer taskId, String msg) {
        TaskFailLog taskFailLog = new TaskFailLog();
        taskFailLog.setTaskId(taskId);
        taskFailLog.setDetails(msg);
        taskFailLogMapper.insert(taskFailLog);
    }

    /**
     * ???project??????security group
     *
     * @param uid
     * @param pid
     * @param openstackAuthNet
     * @param os
     * @return
     */
    private SecurityGroup createSecurityGroup(Integer uid, String pid, OpenstackAuthNet openstackAuthNet, OSClient.OSClientV3 os) {
        try {
            String sgName = SECURITY_GROUP_NAME_PREV + uid;
            String sgDesc = USER_DES_PREV + uid + SECURITY_GROUP_DES_LAST;
            SecurityGroup securityGroup = os.networking().securitygroup().create(Builders.securityGroup()
                    .name(sgName)
                    .tenantId(pid)
                    .description(sgDesc)
                    .build());
            openstackAuthNet.setSgId(securityGroup.getId());
            return securityGroup;
        } catch (Exception e) {
            log.error("??????security group??????");
            throw new ManageException(e);
        }
    }

    /**
     * ???security group??????rule
     *
     * @param sgId
     * @param os
     * @return
     */
    private SecurityGroupRule createSecurityGroupRule(String sgId, OSClient.OSClientV3 os) {
        try {
            SecurityGroupRule securityGroupRule = os.networking().securityrule().create(Builders.securityGroupRule()
                    .securityGroupId(sgId)
                    .direction(DirectionEnum.INGRESS.getVal())
                    .ethertype(EtherTypeEnum.IPv4.name())
                    .protocol("TCP")
                    .portRangeMin(rdpPort)
                    .portRangeMax(rdpPort)
                    .build());
            return securityGroupRule;
        } catch (Exception e) {
            log.error("??????security group??????");
            throw new ManageException(e);
        }
    }

    private void createAndBindOtherSource(ReqUserDto reqUserDto,
                                          Region region,
                                          OpenstackAuth openstackAuth,
                                          InvokeInstanceDto invokeInstanceDto,
                                          Virtual virtual,
                                          Integer taskRecordId) {
        otherCreatePool.execute(() -> {
            try {
                //2.??????floating ip
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                if (ObjectUtils.isEmpty(virtual.getFloatingIpId())) {
                    log.info(">>>>>>>>>>????????????floatingIp");
                    NetFloatingIP netFloatingIP = createFloatingIp(virtual, openstackAuth.getProjectId(),
                            region.getExtNetId(), os);
                    virtual.setStatus(VirtualStatusEnum.CREATING.getCode());
                    virtual.setFloatingIpId(netFloatingIP.getId());
                    virtual.setFloatingIp(netFloatingIP.getFloatingIpAddress());
                    String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 2);
                    virtual.setProgress(progress);
//                    gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
                }
                //3???4 ??????floating ip ??????
                bindingSource(virtual, os);

                //qos??????
                if (!ObjectUtils.isEmpty(invokeInstanceDto.getBandwidthSize())) {
                    //5.??????qos-policy
                    if (ObjectUtils.isEmpty(virtual.getPolicyId())) {
                        log.info(">>>>>>>>>>????????????qos-policy");
                        createPolicy(virtual.getId(), openstackAuth.getProjectId(), virtual, os);
                    }
                    //6.??????qos policy bandwidth rule
                    List<Integer> list = StrUtils.greatedIntegerList(virtual.getProgress());
                    if (!list.contains(6)) {
                        log.info(">>>>>>>>>>????????????qos policy bandwidth rule");
                        createBandwidthRule(virtual.getPolicyId(), invokeInstanceDto.getBandwidthSize(), os);
                        String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 6);
                        virtual.setProgress(progress);
//                        gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
                        list.add(6);
                    }
                    //7.???qos policy?????????floatingIp
                    if (!list.contains(7)) {
                        log.info(">>>>>>>>>>?????????qos policy?????????floatingIp");
                        os.networking().floatingip().bindQosPolicy(virtual.getFloatingIpId(),
                                Builders.netFloatingIP().qosPolicyId(virtual.getPolicyId()).build());
                        String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 7);
                        virtual.setProgress(progress);
//                        gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
                    }

                }

                //volume??????
                if (!ObjectUtils.isEmpty(invokeInstanceDto.getVolumeSize())) {
                    //8.??????volume
                    if (ObjectUtils.isEmpty(virtual.getVolumeId())) {
                        log.info(">>>>>>>>>>????????????volume");
                        Volume volume = createVolume(virtual.getId(), invokeInstanceDto.getVolumeSize(),
                                invokeInstanceDto.getImageId(), os);
                        if (!checkVolumeState(reqUserDto, region, volume.getId(), 0)) {
                            throw new Exception("?????????????????????");
                        }
                        virtual.setVolumeId(volume.getId());
                        String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 8);
                        virtual.setProgress(progress);
                        virtualMapper.updateById(virtual);
                    }
                    //9.???volume?????????instance
                    List<Integer> list = StrUtils.greatedIntegerList(virtual.getProgress());
                    if (!list.contains(9)) {
                        log.info(">>>>>>>>>>?????????volume?????????instance");
                        VolumeAttachment volumeAttachment = os.compute().servers().attachVolume(virtual.getInstanceId(), virtual.getVolumeId(), null);
                        if (ObjectUtils.isEmpty(volumeAttachment)) {
                            throw new Exception("??????????????????????????????");
                        }
                        String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 9);
                        virtual.setProgress(progress);
//                        gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
                    }
                }
                virtual.setStatus(VirtualStatusEnum.RUNNING.getCode());
                virtualMapper.updateById(virtual);
                updateTaskRecord(taskRecordId, true);
                String msg = "?????????????????????????????????id???" + virtual.getId() +
                        "???uuid???" + virtual.getInstanceId() + "???ip???" + virtual.getFloatingIp();
                createTaskLog(taskRecordId, msg);
            } catch (Exception e) {
                log.error("?????????????????????????????????????????????{}", e.getMessage());
                updateTaskRecord(taskRecordId, false);
                String msg = "??????????????????????????????????????????????????????id???" + virtual.getId() + "???????????????" + e.getMessage();
                createTaskLog(taskRecordId, msg);
                virtual.setStatus(VirtualStatusEnum.FAIL.getCode());
                virtualMapper.updateById(virtual);
                throw new ManageException(e);
            }
        });
    }


    /**
     * ???project??????floating ip
     *
     * @param virtual
     * @param pid
     * @param extNetId
     * @param os
     * @return
     */
    private NetFloatingIP createFloatingIp(Virtual virtual, String pid, String extNetId, OSClient.OSClientV3 os) {
        try {
            String floatingIpDesc = DESKTOP_DES_PREV + virtual.getId() + FLOATING_IP_DES_LAST;
            NetFloatingIP netFloatingIP = os.networking().floatingip().create(Builders.netFloatingIP()
                    .floatingNetworkId(extNetId)
                    .projectId(pid)
                    .description(floatingIpDesc)
                    .build());
            return netFloatingIP;
        } catch (Exception e) {
            log.error("??????floatingIp??????");
            virtual.setStatus(VirtualStatusEnum.FAIL.getCode());
            virtualMapper.updateById(virtual);
            String msg = "?????????????????????floatingIp??????";
//            sentMsg(virtual.getUserId(),
//                    VirtualStatusEnum.Fail.getValue(),
//                    msg,
//                    virtual.getId().toString(),
//                    SocketMessageType.SOCKET_COMMAND_TYPE_CREATE_OPENSTACK_INSTANCE.getType(),
//                    RedisEnum.REDIS_CREATE_OPENSTACK_INSTANCE_TOPIC.getValue());
            throw new ManageException(e);
        }
    }

    /**
     * ?????????ip???????????????routerOS
     *
     * @param virtual
     */
    private void mappingRouterOS(Virtual virtual) {
        LambdaQueryWrapper<RouterOs> queryWrapper = new LambdaQueryWrapper();
        queryWrapper.eq(RouterOs::getRegionId, virtual.getRegionId());
        RouterOs routerOs = routerOsMapper.selectOne(queryWrapper);
        //??????????????????????????????:9:rdpPort
        Integer unbindPort = routerOsService.getUnbindPort(virtual, routerOs, 9);
        log.info("??????????????????{}??????ip??????????????????{}?????????routerOS", virtual.getId(), unbindPort);
        try {
            RsUtils.attachPort(routerOs, unbindPort, virtual.getFloatingIp(),rdpPort, "tcp");
            String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 4);
            virtual.setProgress(progress);
        } catch (Exception e) {
            if (e instanceof ManageException) {
                log.error("????????????????????????????????????", e);
                routerOsService.disable(routerOs, unbindPort);
            }
            throw e;
        }
    }

    /**
     * ???project??????qos policy
     *
     * @param desktopId
     * @param pid
     * @param os
     * @return
     */
    private void createPolicy(Integer desktopId, String pid, Virtual virtual, OSClient.OSClientV3 os) {
        try {
            String policyName = POLICY_NAME_PREV + desktopId;
            String policyDesc = DESKTOP_DES_PREV + desktopId + QOS_POLICY_DES_LAST;
            NetQosPolicy netQosPolicy = os.networking().netQosPolicy().create(Builders.netQosPolicy()
                    .name(policyName)
                    .tenantId(pid)
                    .isDefault(false)
                    .shared(true)
                    .description(policyDesc)
                    .build());
            virtual.setPolicyId(netQosPolicy.getId());
            String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 5);
            virtual.setProgress(progress);
//            gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
        } catch (Exception e) {
            log.error("??????qos policy??????");
            throw new ManageException(e);
        }
    }

    /**
     * ??????qos policy???bandwidth rule
     *
     * @param policyId
     * @param bandwidthSize
     * @param os
     */
    private void createBandwidthRule(String policyId, Integer bandwidthSize, OSClient.OSClientV3 os) {
        try {
            DirectionEnum[] directionEnums = DirectionEnum.values();
            for (DirectionEnum directionEnum : directionEnums) {
                os.networking().netQosPolicyBLRule().create(policyId,
                        Builders.netQosPolicyBandwidthLimitRule()
                                .direction(directionEnum.getVal()).maxKbps(bandwidthSize).build());
            }
        } catch (Exception e) {
            log.error("??????qos policy bandwidth rule??????");
            throw new ManageException(e);
        }
    }

    /**
     * ??????volume
     *
     * @param desktopId
     * @param volumeSize
     * @param imageId
     * @param os
     * @return
     */
    private Volume createVolume(Integer desktopId, int volumeSize, String imageId, OSClient.OSClientV3 os) {
        try {
            String volumeName = VOLUME_NAME_PREV + desktopId;
            String volumeDesc = DESKTOP_DES_PREV + desktopId + VOLUME_DES_LAST;
            Volume volume = os.blockStorage().volumes().create(Builders.volume()
                    .name(volumeName)
                    .description(volumeDesc)
                    .size(volumeSize)
                    .bootable(true)
                    .build());
            return volume;
        } catch (Exception e) {
            log.error("????????????????????????{}", e.getMessage());
            throw new ManageException(e);
        }
    }

    private void bindingSource(Virtual virtual, OSClient.OSClientV3 os) {
        try {
            //3.???floating ip?????????instance
            List<Integer> list = StrUtils.greatedIntegerList(virtual.getProgress());
            if (!list.contains(3)) {
                log.info(">>>>>>>>>>?????????floating ip?????????instance");
                ActionResponse actionResponse = os.compute().floatingIps()
                        .addFloatingIP(virtual.getInstanceId(), virtual.getFloatingIp());
                if (actionResponse.getCode() != 200) {
                    throw new ManageException(actionResponse.getFault());
                }
                String progress = StrUtils.sortStringIntegerList(virtual.getProgress() + "," + 3);
//                gpCustomerVirtual.setDesktopStatus(VirtualStatusEnum.Creating.getValue());
                virtual.setProgress(progress);
//                gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
                list.add(3);
            }
            //4.?????????ip????????????????????????routerOS??????
            if (!list.contains(4)) {
                log.info(">>>>>>>>>>???????????????ip????????????????????????routerOS??????");
                mappingRouterOS(virtual);
//                gpCustomerVirtual.setDesktopStatus(VirtualStatusEnum.Creating.getValue());
//                gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
            }

        } catch (Exception e) {
            log.error("??????floatingIp?????????{}", e.getMessage());
//            gpCustomerVirtual.setDesktopStatus(VirtualStatusEnum.Fail.getValue());
//            gpCustomerVirtualMapper.updateById(gpCustomerVirtual);
            String msg = "?????????????????????floatingIp???????????????";
//            sentMsg(virtual.getUserId(),
//                    VirtualStatusEnum.Fail.getValue(),
//                    msg,
//                    virtual.getId().toString(),
//                    SocketMessageType.SOCKET_COMMAND_TYPE_CREATE_OPENSTACK_INSTANCE.getType(),
//                    RedisEnum.REDIS_CREATE_OPENSTACK_INSTANCE_TOPIC.getValue());
            throw new ManageException(e);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param reqUserDto
     * @param region
     * @param volumeId
     * @param count
     * @return
     * @throws Exception
     */
    private boolean checkVolumeState(ReqUserDto reqUserDto, Region region, String volumeId, int count) throws Exception {
        ScheduledFuture<Volume> scheduledFuture = volumeStateCheckPool.schedule(() -> {
            OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
            os.useRegion(region.getRealName());
            Volume volume = os.blockStorage().volumes().get(volumeId);
            return volume;
        }, 30 * count, TimeUnit.SECONDS);
        try {
            Volume volume = scheduledFuture.get();
            if (volume.getStatus().equals(Volume.Status.ERROR)
                    || volume.getStatus().equals(Volume.Status.UNRECOGNIZED)) {
                log.error("?????????????????????????????????{}", volume.getStatus());
//                String msg = "?????????????????????????????????" + volume.getStatus();
//                createTaskLog(taskRecordId, msg);
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                os.blockStorage().volumes().forceDelete(volumeId);
                return false;
            }
            if (volume.getStatus().equals(Volume.Status.AVAILABLE)
                    || volume.getStatus().equals(Volume.Status.DOWNLOADING)) {
                return true;
            }
            if (count > 3) {
                log.error("?????????????????????????????????{}", volume.getStatus());
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                os.blockStorage().volumes().forceDelete(volumeId);
                return false;
            }
            count++;
            checkVolumeState(reqUserDto, region, volumeId, count);
            return false;
        } catch (Exception e) {
            log.error("????????????????????????????????????{}??? ????????????", e.getMessage());
            if (count > 3) {
                //todo ????????????
                log.error("?????????????????????");
                OSClient.OSClientV3 os = ((OSClient.OSClientV3) openstackService.getAuthToke(reqUserDto).getData());
                os.useRegion(region.getRealName());
                os.blockStorage().volumes().forceDelete(volumeId);
                return false;
            }
            count++;
            checkVolumeState(reqUserDto, region, volumeId, count);
            return false;
        }

    }


    /**
     * ??????socket??????
     *
     * @param userId
     */
    private void sentMsg(Integer userId, Integer value, String msg, String body, String type, String topic) {
//        Command command = new Command();
//        command.setUserId(userId);
//        command.setValue(value);
//        command.setMessage(msg);
//        command.setBody(body);
//        command.setType(type);
//        handler.convertAndSend(topic, JSONObject.toJSONString(command));
    }

}
