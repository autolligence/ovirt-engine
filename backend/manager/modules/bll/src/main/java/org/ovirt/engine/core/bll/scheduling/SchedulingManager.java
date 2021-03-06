package org.ovirt.engine.core.bll.scheduling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.ovirt.engine.core.bll.HostLocking;
import org.ovirt.engine.core.bll.VmHandler;
import org.ovirt.engine.core.bll.network.host.NetworkDeviceHelper;
import org.ovirt.engine.core.bll.network.host.VfScheduler;
import org.ovirt.engine.core.bll.scheduling.external.BalanceResult;
import org.ovirt.engine.core.bll.scheduling.external.ExternalSchedulerBroker;
import org.ovirt.engine.core.bll.scheduling.external.ExternalSchedulerDiscovery;
import org.ovirt.engine.core.bll.scheduling.external.WeightResultEntry;
import org.ovirt.engine.core.bll.scheduling.pending.PendingCpuCores;
import org.ovirt.engine.core.bll.scheduling.pending.PendingCpuLoad;
import org.ovirt.engine.core.bll.scheduling.pending.PendingHugePages;
import org.ovirt.engine.core.bll.scheduling.pending.PendingMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingNumaMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingOvercommitMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingResourceManager;
import org.ovirt.engine.core.bll.scheduling.pending.PendingVM;
import org.ovirt.engine.core.bll.scheduling.policyunits.RankSelectorPolicyUnit;
import org.ovirt.engine.core.bll.scheduling.selector.SelectorInstance;
import org.ovirt.engine.core.bll.scheduling.utils.CpuPinningHelper;
import org.ovirt.engine.core.bll.scheduling.utils.NumaPinningHelper;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.BackendService;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.NumaTuneMode;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.businessentities.VmNumaNode;
import org.ovirt.engine.core.common.businessentities.VmStatic;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.scheduling.ClusterPolicy;
import org.ovirt.engine.core.common.scheduling.OptimizationType;
import org.ovirt.engine.core.common.scheduling.PerHostMessages;
import org.ovirt.engine.core.common.scheduling.PolicyUnit;
import org.ovirt.engine.core.common.scheduling.PolicyUnitType;
import org.ovirt.engine.core.common.scheduling.VmOverheadCalculator;
import org.ovirt.engine.core.common.utils.HugePageUtils;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogable;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableImpl;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.MessageBundler;
import org.ovirt.engine.core.dao.ClusterDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VdsNumaNodeDao;
import org.ovirt.engine.core.dao.VmNumaNodeDao;
import org.ovirt.engine.core.dao.scheduling.ClusterPolicyDao;
import org.ovirt.engine.core.dao.scheduling.PolicyUnitDao;
import org.ovirt.engine.core.di.Injector;
import org.ovirt.engine.core.utils.threadpool.ThreadPoolUtil;
import org.ovirt.engine.core.utils.threadpool.ThreadPools;
import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SchedulingManager implements BackendService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingManager.class);
    private static final String HIGH_UTILIZATION = "HighUtilization";
    private static final String LOW_UTILIZATION = "LowUtilization";

    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private ResourceManager resourceManager;
    @Inject
    private MigrationHandler migrationHandler;
    @Inject
    private ExternalSchedulerDiscovery exSchedulerDiscovery;
    @Inject
    private VdsDao vdsDao;
    @Inject
    private VmHandler vmHandler;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private PolicyUnitDao policyUnitDao;
    @Inject
    private ClusterPolicyDao clusterPolicyDao;
    @Inject
    private NetworkDeviceHelper networkDeviceHelper;
    @Inject
    private HostLocking hostLocking;
    @Inject
    private VmOverheadCalculator vmOverheadCalculator;
    @Inject
    private ExternalSchedulerBroker externalBroker;
    @Inject
    private VfScheduler vfScheduler;
    @Inject
    private VmNumaNodeDao vmNumaNodeDao;
    @Inject
    private VdsNumaNodeDao vdsNumaNodeDao;
    @Inject
    private RunVmDelayer runVmDelayer;
    @Inject
    @ThreadPools(ThreadPools.ThreadPoolType.EngineScheduledThreadPool)
    private ManagedScheduledExecutorService executor;

    private PendingResourceManager pendingResourceManager;

    /**
     * [policy id, policy] map
     */
    private final ConcurrentHashMap<Guid, ClusterPolicy> policyMap;
    /**
     * [policy unit id, policy unit] map
     */
    private volatile ConcurrentHashMap<Guid, PolicyUnitImpl> policyUnits;

    private final Object policyUnitsLock = new Object();

    private final ConcurrentHashMap<Guid, Semaphore> clusterLockMap = new ConcurrentHashMap<>();

    private final Map<Guid, Boolean> clusterId2isHaReservationSafe = new HashMap<>();

    private final Guid defaultSelectorGuid = InternalPolicyUnits.getGuid(RankSelectorPolicyUnit.class);

    private final int vcpuLoadPerCore = Config.<Integer>getValue(ConfigValues.VcpuConsumptionPercentage);

    private PendingResourceManager getPendingResourceManager() {
        return pendingResourceManager;
    }

    @Inject
    protected SchedulingManager() {
        policyMap = new ConcurrentHashMap<>();
        policyUnits = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Scheduling manager");
        initializePendingResourceManager();
        loadPolicyUnits();
        loadClusterPolicies();
        loadExternalScheduler();
        enableLoadBalancer();
        enableHaReservationCheck();
        log.info("Initialized Scheduling manager");
    }

    private void initializePendingResourceManager() {
        pendingResourceManager = new PendingResourceManager(resourceManager);
    }

    private void loadExternalScheduler() {
        if (Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled)) {
            log.info("Starting external scheduler discovery thread");

            /* Disable all external units, this is needed in case an external scheduler broker
               implementation is missing, because nobody would then disable units that
               were registered by the missing broker */
            exSchedulerDiscovery.markAllExternalPoliciesAsDisabled();

            ThreadPoolUtil.execute(() -> {
                if (exSchedulerDiscovery.discover()) {
                    reloadPolicyUnits();
                }
            });
        } else {
            exSchedulerDiscovery.markAllExternalPoliciesAsDisabled();
            log.info("External scheduler disabled, discovery skipped");
        }
    }

    private void reloadPolicyUnits() {
        synchronized (policyUnitsLock) {
            policyUnits = new ConcurrentHashMap<>();
            loadPolicyUnits();
        }
    }

    public List<ClusterPolicy> getClusterPolicies() {
        return new ArrayList<>(policyMap.values());
    }

    public ClusterPolicy getClusterPolicy(Guid clusterPolicyId) {
        return policyMap.get(clusterPolicyId);
    }

    public Optional<ClusterPolicy> getClusterPolicy(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        for (ClusterPolicy clusterPolicy : policyMap.values()) {
            if (clusterPolicy.getName().toLowerCase().equals(name.toLowerCase())) {
                return Optional.of(clusterPolicy);
            }
        }
        return Optional.empty();
    }

    public ClusterPolicy getDefaultClusterPolicy() {
        for (ClusterPolicy clusterPolicy : policyMap.values()) {
            if (clusterPolicy.isDefaultPolicy()) {
                return clusterPolicy;
            }
        }

        // This should never happen, there must be at least one InternalClusterPolicy
        // that is marked as default. InternalClusterPoliciesTest.testDefaultPolicy()
        // makes sure exactly one is defined
        throw new RuntimeException("There is no system default cluster policy!");
    }

    public Map<Guid, PolicyUnitImpl> getPolicyUnitsMap() {
        synchronized (policyUnitsLock) {
            return policyUnits;
        }
    }

    private void loadClusterPolicies() {
        // Load internal cluster policies
        policyMap.putAll(InternalClusterPolicies.getClusterPolicies());

        Map<Guid, PolicyUnitType> internalTypes = new HashMap<>();
        for (PolicyUnitImpl unit: policyUnits.values()) {
            internalTypes.put(unit.getGuid(), unit.getType());
        }

        // Get all user provided cluster policies
        List<ClusterPolicy> allClusterPolicies = clusterPolicyDao.getAll(
                Collections.unmodifiableMap(internalTypes));

        for (ClusterPolicy clusterPolicy : allClusterPolicies) {
            policyMap.put(clusterPolicy.getId(), clusterPolicy);
        }
    }

    private void loadPolicyUnits() {
        // Load internal policy units
        for (Class<? extends PolicyUnitImpl> unitType: InternalPolicyUnits.getList()) {
            try {
                PolicyUnitImpl unit = InternalPolicyUnits.instantiate(unitType, getPendingResourceManager());
                policyUnits.put(unit.getGuid(), Injector.injectMembers(unit));
            } catch (Exception e){
                log.error("Could not instantiate a policy unit {}.", unitType.getName(), e);
            }
        }

        // Load all external policy units
        List<PolicyUnit> allPolicyUnits = policyUnitDao.getAll();
        for (PolicyUnit policyUnit : allPolicyUnits) {
            policyUnits.put(policyUnit.getId(), new ExternalPolicyUnit(policyUnit, getPendingResourceManager()));
        }
    }

    private static class SchedulingResult {
        Map<Guid, Pair<EngineMessage, String>> filteredOutReasons;
        Map<Guid, String> hostNames;
        PerHostMessages details;

        public SchedulingResult() {
            filteredOutReasons = new HashMap<>();
            hostNames = new HashMap<>();
            details = new PerHostMessages();
        }

        public void addReason(Guid id, String hostName, EngineMessage filterType, String filterName) {
            filteredOutReasons.put(id, new Pair<>(filterType, filterName));
            hostNames.put(id, hostName);
        }

        public Collection<String> getReasonMessages() {
            List<String> lines = new ArrayList<>();

            for (Entry<Guid, Pair<EngineMessage, String>> line: filteredOutReasons.entrySet()) {
                lines.add(line.getValue().getFirst().name());
                lines.add(String.format("$%1$s %2$s", "hostName", hostNames.get(line.getKey())));
                lines.add(String.format("$%1$s %2$s", "filterName", line.getValue().getSecond()));

                final List<String> detailMessages = details.getMessages(line.getKey());
                if (detailMessages.isEmpty()) {
                    lines.add(EngineMessage.SCHEDULING_HOST_FILTERED_REASON.name());
                } else {
                    lines.addAll(detailMessages);
                    lines.add(EngineMessage.SCHEDULING_HOST_FILTERED_REASON_WITH_DETAIL.name());
                }
            }

            return lines;
        }

        private PerHostMessages getDetails() {
            return details;
        }

    }

    public Map<Guid, Guid> schedule(Cluster cluster,
            List<VM> vms,
            List<Guid> hostBlackList,
            List<Guid> hostWhiteList,
            List<Guid> destHostIdList,
            SchedulingParameters schedulingParameters,
            List<String> messages,
            boolean delayWhenNeeded,
            String correlationId) {
        prepareClusterLock(cluster.getId());
        try {
            log.debug("Scheduling started, correlation Id: {}", correlationId);
            checkAllowOverbooking(cluster);
            lockCluster(cluster.getId());
            List<VDS> hosts = fetchHosts(cluster.getId(), hostBlackList, hostWhiteList);
            vms.forEach(vmHandler::updateVmStatistics);
            fetchNumaNodes(vms, hosts);
            ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
            SchedulingContext context = new SchedulingContext(cluster,
                    createClusterPolicyParameters(cluster),
                    schedulingParameters);

            splitFilters(policy.getFilters(), policy.getFilterPositionMap(), context);
            splitFunctions(policy.getFunctions(), context);
            context.setShouldWeighClusterHosts(shouldWeighClusterHosts(cluster));

            Function<VM, Optional<Guid>> findBestHost = vm -> {
                context.getMessages().clear();
                refreshCachedPendingValues(hosts);
                return selectHost(policy, hosts, vm, destHostIdList, context, correlationId);
            };

            Map<Guid, VDS> hostsMap = hosts.stream().collect(Collectors.toMap(VDS::getId, h -> h));

            Set<Guid> hostsToNotifyPending = new HashSet<>();
            List<Runnable> vfsUpdates = new ArrayList<>();
            Map<Guid, Guid> vmToHostAssignment = new HashMap<>();
            for (VM vm : vms) {
                Optional<Guid> bestHost = findBestHost.apply(vm);
                // The delay is executed only once
                if (delayWhenNeeded && context.isShouldDelay()) {
                    log.debug("Delaying scheduling...");
                    runVmDelayer.delay(hosts.stream().map(VDS::getId).collect(Collectors.toList()));
                    context.setCanDelay(false);
                    bestHost = findBestHost.apply(vm);
                }

                if (!bestHost.isPresent()) {
                    continue;
                }

                vmToHostAssignment.put(vm.getId(), bestHost.get());

                if (bestHost.get().equals(vm.getRunOnVds())) {
                    continue;
                }

                Guid bestHostId = bestHost.get();
                VDS host = hostsMap.get(bestHostId);
                Map<Integer, Long> numaConsumption = vmNumaRequirements(vm, host);
                updateHostNumaNodes(host, numaConsumption);

                addPendingResources(vm, bestHostId, numaConsumption);
                hostsToNotifyPending.add(bestHostId);
                vfsUpdates.add(() -> markVfsAsUsedByVm(vm, bestHostId));
            }

            hostsToNotifyPending.forEach(hostId -> getPendingResourceManager().notifyHostManagers(hostId));
            vfsUpdates.forEach(Runnable::run);
            messages.addAll(context.getMessages());
            return vmToHostAssignment;
        } catch (InterruptedException e) {
            log.error("scheduling interrupted, correlation Id: {}: {}", correlationId, e.getMessage());
            log.debug("Exception: ", e);
            return Collections.emptyMap();
        } finally {
            releaseCluster(cluster.getId());

            log.debug("Scheduling ended, correlation Id: {}", correlationId);
        }
    }

    public Optional<Guid> schedule(Cluster cluster,
            VM vm,
            List<Guid> hostBlackList,
            List<Guid> hostWhiteList,
            List<Guid> destHostIdList,
            SchedulingParameters schedulingParameters,
            List<String> messages,
            boolean delayWhenNeeded,
            String correlationId) {
        Map<Guid, Guid> res = schedule(cluster,
                Collections.singletonList(vm),
                hostBlackList,
                hostWhiteList,
                destHostIdList,
                schedulingParameters,
                messages,
                delayWhenNeeded,
                correlationId);

        return Optional.ofNullable(res.get(vm.getId()));
    }

    private Optional<Guid> selectHost(ClusterPolicy policy,
            List<VDS> hosts,
            VM vm,
            List<Guid> destHostIdList,
            SchedulingContext context,
            String correlationId) {
        List<VDS> hostList = runFilters(hosts,
                        vm,
                        context,
                        true,
                        correlationId);

        if (hostList.isEmpty()) {
            return Optional.empty();
        }

        if (context.isCanDelay() && context.isShouldDelay()) {
            return Optional.empty();
        }

        return selectBestHost(vm, destHostIdList, hostList, policy, context);
    }

    private List<VDS> fetchHosts(Guid clusterId, List<Guid> blackList, List<Guid> whiteList) {
        List<VDS> vdsList = vdsDao.getAllForClusterWithStatus(clusterId, VDSStatus.Up);
        vdsList = removeBlacklistedHosts(vdsList, blackList);
        return keepOnlyWhitelistedHosts(vdsList, whiteList);
    }

    private void fetchNumaNodes(List<VM> vms, List<VDS> hosts) {
        // TODO - fetch numa nodes for all VMs in 1 DB call
        for (VM vm : vms) {
            vm.setvNumaNodeList(vmNumaNodeDao.getAllVmNumaNodeByVmId(vm.getId()));
        }

        for (VDS host : hosts) {
            host.setNumaNodeList(vdsNumaNodeDao.getAllVdsNumaNodeByVdsId(host.getId()));

            // Subtracting pending memory, so the scheduling units don't have to consider it
            Map<Integer, Long> pendingNumaMemory = PendingNumaMemory.collectForHost(pendingResourceManager, host.getId());
            for (VdsNumaNode node : host.getNumaNodeList()) {
                long memFree = node.getNumaNodeStatistics().getMemFree();
                long memPending = pendingNumaMemory.getOrDefault(node.getIndex(), 0L);
                node.getNumaNodeStatistics().setMemFree(memFree - memPending);
            }
        }
    }

    private void updateHostNumaNodes(VDS host, Map<Integer, Long> numaConsumption) {
        for (VdsNumaNode node : host.getNumaNodeList()) {
            long memFree = node.getNumaNodeStatistics().getMemFree();
            long memNeeded = numaConsumption.getOrDefault(node.getIndex(), 0L);
            node.getNumaNodeStatistics().setMemFree(memFree - memNeeded);
        }
    }

    private void addPendingResources(VM vm, Guid hostId, Map<Integer, Long> numaConsumption) {
        getPendingResourceManager().addPending(new PendingCpuCores(hostId, vm, vm.getNumOfCpus()));
        getPendingResourceManager().addPending(new PendingMemory(hostId, vm, vmOverheadCalculator.getStaticOverheadInMb(vm)));
        getPendingResourceManager().addPending(new PendingOvercommitMemory(hostId, vm, vmOverheadCalculator.getTotalRequiredMemoryInMb(vm)));
        getPendingResourceManager().addPending(new PendingVM(hostId, vm));

        int cpuLoad = vm.getRunOnVds() != null && vm.getStatisticsData() != null ?
                vm.getUsageCpuPercent() * vm.getNumOfCpus() :
                vcpuLoadPerCore * vm.getNumOfCpus();

        getPendingResourceManager().addPending(new PendingCpuLoad(hostId, vm, cpuLoad));

        /*
         * Adds NUMA node assignment to pending resources.
         *
         * The assignment is only one of the possible assignments.
         * The real one used by libvirt can be different, but the engine does not know it.
         *
         * When starting many VMs with NUMA pinning, it may happen that some of them will
         * not pass scheduling, even if they could fit on the host.
         */
        if (vm.getNumaTuneMode() != NumaTuneMode.PREFERRED) {
            numaConsumption.forEach((nodeIndex, neededMemory) -> {
                getPendingResourceManager().addPending(new PendingNumaMemory(hostId, vm, nodeIndex, neededMemory));
            });
        }

        // Add pending records for all specified hugepage sizes
        for (Map.Entry<Integer, Integer> hugepage: HugePageUtils.getHugePages(vm.getStaticData()).entrySet()) {
            getPendingResourceManager().addPending(new PendingHugePages(hostId, vm,
                    hugepage.getKey(), hugepage.getValue()));
        }
    }

    private Map<Integer, Long> vmNumaRequirements(VM vm, VDS host) {
        if (vm.getNumaTuneMode() == NumaTuneMode.PREFERRED) {
            return Collections.emptyMap();
        }

        if (host.getId().equals(vm.getRunOnVds())) {
            return Collections.emptyMap();
        }

        List<VmNumaNode> vmNodes = vm.getvNumaNodeList();
        List<VdsNumaNode> hostNodes = host.getNumaNodeList();

        Map<Integer, Collection<Integer>> cpuPinning = CpuPinningHelper.parseCpuPinning(vm.getCpuPinning()).stream()
                .collect(Collectors.toMap(p -> p.getvCpu(), p -> p.getpCpus()));

        Optional<Map<Integer, Integer>> nodeAssignment = Optional.empty();
        if (!cpuPinning.isEmpty()) {
            nodeAssignment = NumaPinningHelper.findAssignment(vmNodes, hostNodes, cpuPinning);
        }

        if (!nodeAssignment.isPresent()) {
            nodeAssignment = NumaPinningHelper.findAssignment(vmNodes, hostNodes);
        }

        if (!nodeAssignment.isPresent()) {
            return Collections.emptyMap();
        }

        Map<Integer, Long> result = new HashMap<>(hostNodes.size());
        for (VmNumaNode vmNode: vmNodes) {
            Integer hostNodeIndex = nodeAssignment.get().get(vmNode.getIndex());
            // Ignore unpinned numa nodes
            if (hostNodeIndex == null) {
                continue;
            }

            result.merge(hostNodeIndex, vmNode.getMemTotal(), Long::sum);
        }

        return result;
    }

    private void releaseCluster(Guid cluster) {
        // ensuring setting the semaphore permits to 1
        synchronized (clusterLockMap.get(cluster)) {
            clusterLockMap.get(cluster).drainPermits();
            clusterLockMap.get(cluster).release();
        }
    }

    private void lockCluster(Guid cluster) throws InterruptedException {
        clusterLockMap.get(cluster).acquire();
    }

    private void prepareClusterLock(Guid cluster) {
        clusterLockMap.putIfAbsent(cluster, new Semaphore(1));
    }

    private void markVfsAsUsedByVm(VM vm, Guid bestHostId) {
        Map<Guid, String> passthroughVnicToVfMap = vfScheduler.getVnicToVfMap(vm.getId(), bestHostId);
        if (passthroughVnicToVfMap == null || passthroughVnicToVfMap.isEmpty()) {
            return;
        }

        try {
            hostLocking.acquireHostDevicesLock(bestHostId);
            Collection<String> virtualFunctions = passthroughVnicToVfMap.values();

            log.debug("Marking following VF as used by VM({}) on selected host({}): {}",
                    vm.getId(),
                    bestHostId,
                    virtualFunctions);

            networkDeviceHelper.setVmIdOnVfs(bestHostId, vm.getId(), new HashSet<>(virtualFunctions));
        } finally {
            hostLocking.releaseHostDevicesLock(bestHostId);
        }
    }

    /**
     * Refresh cached VDS pending fields with the current pending
     * values from PendingResourceManager.
     * @param vdsList - list of candidate hosts
     */
    private void refreshCachedPendingValues(List<VDS> vdsList) {
        for (VDS vds: vdsList) {
            int pendingMemory = PendingOvercommitMemory.collectForHost(getPendingResourceManager(), vds.getId());
            int pendingCpuCount = PendingCpuCores.collectForHost(getPendingResourceManager(), vds.getId());

            vds.setPendingVcpusCount(pendingCpuCount);
            vds.setPendingVmemSize(pendingMemory);
        }
    }

    /**
     * @param destHostIdList - used for RunAt preselection, overrides the ordering in vdsList
     * @param availableVdsList - presorted list of hosts (better hosts first) that are available
     */
    private Optional<Guid> selectBestHost(VM vm,
            List<Guid> destHostIdList,
            List<VDS> availableVdsList,
            ClusterPolicy policy,
            SchedulingContext context) {
        // in case a default destination host was specified and
        // it passed filters, return the first found
        List<VDS> runnableHosts = new LinkedList<>();
        if (destHostIdList.size() > 0) {
            // there are dedicated hosts
            // intersect dedicated hosts list with available list
            for (VDS vds : availableVdsList) {
                for (Guid destHostId : destHostIdList) {
                    if (destHostId.equals(vds.getId())) {
                        runnableHosts.add(vds);
                    }
                }
            }
        }
        if (runnableHosts.isEmpty()) { // no dedicated hosts found
            runnableHosts = availableVdsList;
        }

        switch (runnableHosts.size()){
        case 0:
            // no runnable hosts found, nothing found
            return Optional.empty();
        case 1:
            // found single available host, in available list return it
            return Optional.of(runnableHosts.get(0).getId());
        default:
            // select best runnable host with scoring functions (from policy)
            List<Pair<Guid, Integer>> functions = policy.getFunctions();
            Guid selector = Optional.of(policy).map(ClusterPolicy::getSelector).orElse(defaultSelectorGuid);
            PolicyUnitImpl selectorUnit = policyUnits.get(selector);
            SelectorInstance selectorInstance = selectorUnit.selector(context.getPolicyParameters());

            List<Guid> runnableGuids = runnableHosts.stream().map(VDS::getId).collect(Collectors.toList());
            selectorInstance.init(functions, runnableGuids);

            if (!functions.isEmpty() && context.isShouldWeighClusterHosts()) {
                Optional<Guid> bestHostByFunctions = runFunctions(selectorInstance, runnableHosts, vm, context);
                if (bestHostByFunctions.isPresent()) {
                    return bestHostByFunctions;
                }
            }
        }
        // failed select best runnable host using scoring functions, return the first
        return Optional.of(runnableHosts.get(0).getId());
    }

    /**
     * Checks whether scheduler should schedule several requests in parallel:
     * Conditions:
     * * config option SchedulerAllowOverBooking should be enabled.
     * * cluster optimization type flag should allow over-booking.
     * * more than than X (config.SchedulerOverBookingThreshold) pending for scheduling.
     * In case all of the above conditions are met, we release all the pending scheduling
     * requests.
     */
    private void checkAllowOverbooking(Cluster cluster) {
        if (OptimizationType.ALLOW_OVERBOOKING == cluster.getOptimizationType()
                && Config.<Boolean>getValue(ConfigValues.SchedulerAllowOverBooking)
                && clusterLockMap.get(cluster.getId()).getQueueLength() >=
                Config.<Integer>getValue(ConfigValues.SchedulerOverBookingThreshold)) {
            log.info("Scheduler: cluster '{}' lock is skipped (cluster is allowed to overbook)",
                    cluster.getName());
            // release pending threads (requests) and current one (+1)
            clusterLockMap.get(cluster.getId())
                    .release(Config.<Integer>getValue(ConfigValues.SchedulerOverBookingThreshold) + 1);
        }
    }

    /**
     * Checks whether scheduler should weigh hosts/or skip weighing:
     * * optimize for speed is enabled for the cluster, and there are less than
     *   configurable requests pending (skip weighing in a loaded setup).
     */
    private boolean shouldWeighClusterHosts(Cluster cluster) {
        Integer threshold = Config.<Integer>getValue(ConfigValues.SpeedOptimizationSchedulingThreshold);
        // threshold is crossed only when cluster is configured for optimized for speed
        boolean crossedThreshold =
                OptimizationType.OPTIMIZE_FOR_SPEED == cluster.getOptimizationType()
                        && clusterLockMap.get(cluster.getId()).getQueueLength() >
                        threshold;
        if (crossedThreshold) {
            log.info(
                    "Scheduler: skipping whinging hosts in cluster '{}', since there are more than '{}' parallel requests",
                    cluster.getName(),
                    threshold);
        }
        return !crossedThreshold;
    }

    public Map<Guid, List<VDS>> canSchedule(Cluster cluster,
            List<VM> vms,
            List<Guid> vdsBlackList,
            List<Guid> vdsWhiteList,
            SchedulingParameters schedulingParameters,
            List<String> messages) {
        List<VDS> hosts = fetchHosts(cluster.getId(), vdsBlackList, vdsWhiteList);
        refreshCachedPendingValues(hosts);
        vms.forEach(vmHandler::updateVmStatistics);
        fetchNumaNodes(vms, hosts);
        ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
        SchedulingContext context = new SchedulingContext(cluster,
                createClusterPolicyParameters(cluster),
                schedulingParameters);
        splitFilters(policy.getFilters(), policy.getFilterPositionMap(), context);

        Map<Guid, List<VDS>> res = new HashMap<>();
        for (VM vm : vms) {
            List<VDS> filteredHosts = runFilters(hosts,
                    vm,
                    context,
                    false,
                    null);

            res.put(vm.getId(), filteredHosts);
        }
        messages.addAll(context.getMessages());
        return res;
    }

    public List<VDS> canSchedule(Cluster cluster,
            VM vm,
            List<Guid> vdsBlackList,
            List<Guid> vdsWhiteList,
            SchedulingParameters schedulingParameters,
            List<String> messages) {
        Map<Guid, List<VDS>> res = canSchedule(cluster,
                Collections.singletonList(vm),
                vdsBlackList,
                vdsWhiteList,
                schedulingParameters,
                messages);

        return res.getOrDefault(vm.getId(), Collections.emptyList());
    }

    private Map<String, String> createClusterPolicyParameters(Cluster cluster) {
        Map<String, String> parameters = new HashMap<>();
        if (cluster.getClusterPolicyProperties() != null) {
            parameters.putAll(cluster.getClusterPolicyProperties());
        }
        return parameters;
    }

    /**
     * Remove hosts from vdsList that are not present on the whitelist
     *
     * Empty white list signalizes that nothing is to be done.
     *
     * @param vdsList List of hosts to filter
     * @param list Whitelist
     */
    private List<VDS> keepOnlyWhitelistedHosts(List<VDS> vdsList, List<Guid> list) {
        if (!list.isEmpty()) {
            Set<Guid> listSet = new HashSet<>(list);

            return vdsList.stream()
                    .filter(host -> listSet.contains(host.getId()))
                    .collect(Collectors.toList());
        } else {
            return vdsList;
        }
    }

    /**
     * Remove hosts from vdsList that are present on the blacklist
     *
     * Empty black list signalizes that nothing is to be done.
     *
     * @param vdsList List of hosts to filter
     * @param list Blacklist
     */
    private List<VDS> removeBlacklistedHosts(List<VDS> vdsList, List<Guid> list) {
        if (!list.isEmpty()) {
            Set<Guid> listSet = new HashSet<>(list);

            return vdsList.stream()
                    .filter(host -> !listSet.contains(host.getId()))
                    .collect(Collectors.toList());
        } else {
            return vdsList;
        }
    }

    private List<VDS> runFilters(List<VDS> hostList,
            VM vm,
            SchedulingContext context,
            boolean shouldRunExternalFilters,
            String correlationId) {
        SchedulingResult result = new SchedulingResult();

        /* Short circuit filters if there are no hosts at all */
        if (hostList.isEmpty()) {
            context.getMessages().add(EngineMessage.SCHEDULING_NO_HOSTS.name());
            context.getMessages().addAll(result.getReasonMessages());
            return hostList;
        }

        hostList = runInternalFilters(hostList, vm, context, correlationId, result);

        if (shouldRunExternalFilters
                && Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled)
                && !context.getExternalFilters().isEmpty()
                && !hostList.isEmpty()) {
            hostList = runExternalFilters(hostList, vm, context, correlationId, result);
        }

        if (hostList.isEmpty()) {
            context.getMessages().add(EngineMessage.SCHEDULING_ALL_HOSTS_FILTERED_OUT.name());
            context.getMessages().addAll(result.getReasonMessages());
        }
        return hostList;
    }

    private void splitFilters(List<Guid> filters, Map<Guid, Integer> filterPositionMap, SchedulingContext context) {
        // Create a local copy so we can manipulate it
        filters = new ArrayList<>(filters);

        sortFilters(filters, filterPositionMap);
        for (Guid filter : filters) {
            PolicyUnitImpl filterPolicyUnit = policyUnits.get(filter);
            if (filterPolicyUnit.getPolicyUnit().isInternal()) {
                context.getInternalFilters().add(filterPolicyUnit);
            } else {
                if (filterPolicyUnit.getPolicyUnit().isEnabled()) {
                    context.getExternalFilters().add(filterPolicyUnit);
                }
            }
        }
    }

    private void splitFunctions(List<Pair<Guid, Integer>> functions, SchedulingContext context) {
        for (Pair<Guid, Integer> pair : functions) {
            PolicyUnitImpl currentPolicy = policyUnits.get(pair.getFirst());
            if (currentPolicy.getPolicyUnit().isInternal()) {
                context.getInternalScoreFunctions().add(new Pair<>(currentPolicy, pair.getSecond()));
            } else {
                if (currentPolicy.getPolicyUnit().isEnabled()) {
                    context.getExternalScoreFunctions().add(new Pair<>(currentPolicy, pair.getSecond()));
                }
            }
        }
    }

    private List<VDS> runInternalFilters(List<VDS> hostList,
            VM vm,
            SchedulingContext context,
            String correlationId,
            SchedulingResult result) {
        for (PolicyUnitImpl filterPolicyUnit : context.getInternalFilters()) {
            if (hostList.isEmpty()) {
                break;
            }
            List<VDS> currentHostList = new ArrayList<>(hostList);
            hostList = filterPolicyUnit.filter(context, hostList, vm, result.getDetails());
            logFilterActions(currentHostList,
                    toIdSet(hostList),
                    EngineMessage.VAR__FILTERTYPE__INTERNAL,
                    filterPolicyUnit.getPolicyUnit().getName(),
                    result,
                    correlationId);
        }
        return hostList;
    }

    private Set<Guid> toIdSet(List<VDS> hostList) {
        return hostList.stream().map(VDS::getId).collect(Collectors.toSet());
    }

    private void logFilterActions(List<VDS> oldList,
                                  Set<Guid> newSet,
                                  EngineMessage actionName,
                                  String filterName,
                                  SchedulingResult result,
                                  String correlationId) {
        for (VDS host: oldList) {
            if (!newSet.contains(host.getId())) {
                result.addReason(host.getId(), host.getName(), actionName, filterName);
                log.info("Candidate host '{}' ('{}') was filtered out by '{}' filter '{}' (correlation id: {})",
                        host.getName(),
                        host.getId(),
                        actionName.name(),
                        filterName,
                        correlationId);
            }
        }
    }

    private List<VDS> runExternalFilters(List<VDS> hostList,
            VM vm,
            SchedulingContext context,
            String correlationId,
            SchedulingResult result) {

        List<Guid> hostIDs = hostList.stream().map(VDS::getId).collect(Collectors.toList());

        List<String> filterNames = context.getExternalFilters().stream()
                .filter(f -> !f.getPolicyUnit().isInternal())
                .map(f -> f.getPolicyUnit().getName())
                .collect(Collectors.toList());

        List<Guid> filteredIDs =
                externalBroker.runFilters(filterNames, hostIDs, vm.getId(), context.getPolicyParameters());
        logFilterActions(hostList,
                new HashSet<>(filteredIDs),
                EngineMessage.VAR__FILTERTYPE__EXTERNAL,
                Arrays.toString(filterNames.toArray()),
                result,
                correlationId);
        hostList = intersectHosts(hostList, filteredIDs);

        return hostList;
    }

    private List<VDS> intersectHosts(List<VDS> hosts, List<Guid> IDs) {
        Set<Guid> idSet = new HashSet<>(IDs);
        return hosts.stream().filter(host -> idSet.contains(host.getId())).collect(Collectors.toList());
    }

    private void sortFilters(List<Guid> filters, final Map<Guid, Integer> filterPositionMap) {
        filters.sort(Comparator.comparingInt(f -> filterPositionMap.getOrDefault(f, 0)));
    }

    private Optional<Guid> runFunctions(SelectorInstance selector,
            List<VDS> hostList,
            VM vm,
            SchedulingContext context) {
        runInternalFunctions(selector, hostList, vm, context);

        if (Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled) &&
                !context.getExternalFilters().isEmpty()) {
            runExternalFunctions(selector, hostList, vm, context);
        }

        return selector.best();
    }

    private void runInternalFunctions(SelectorInstance selector,
            List<VDS> hostList,
            VM vm,
            SchedulingContext context) {

        for (Pair<PolicyUnitImpl, Integer> pair : context.getInternalScoreFunctions()) {
            List<Pair<Guid, Integer>> scoreResult = pair.getFirst().score(context, hostList, vm);
            for (Pair<Guid, Integer> result : scoreResult) {
                selector.record(pair.getFirst().getGuid(), result.getFirst(), result.getSecond());
            }
        }
    }

    private void runExternalFunctions(SelectorInstance selector,
            List<VDS> hostList,
            VM vm,
            SchedulingContext context) {
        List<Guid> hostIDs = hostList.stream().map(VDS::getId).collect(Collectors.toList());

        List<Pair<String, Integer>> scoreNameAndWeight = context.getExternalScoreFunctions().stream()
                .filter(pair -> !pair.getFirst().getPolicyUnit().isInternal())
                .map(pair -> new Pair<>(pair.getFirst().getName(), pair.getSecond()))
                .collect(Collectors.toList());

        Map<String, Guid> nameToGuidMap = context.getExternalScoreFunctions().stream()
                .filter(pair -> !pair.getFirst().getPolicyUnit().isInternal())
                .collect(Collectors.toMap(pair -> pair.getFirst().getPolicyUnit().getName(),
                        pair -> pair.getFirst().getPolicyUnit().getId()));

        List<WeightResultEntry> externalScores =
                externalBroker.runScores(scoreNameAndWeight,
                        hostIDs,
                        vm.getId(),
                        context.getPolicyParameters());

        sumScoreResults(selector, nameToGuidMap, externalScores);
    }

    private void sumScoreResults(SelectorInstance selector,
            Map<String, Guid> nametoGuidMap,
            List<WeightResultEntry> externalScores) {
        for (WeightResultEntry resultEntry : externalScores) {
            // The old external scheduler returns summed up data without policy unit identification, treat
            // it as a single policy unit with id null
            selector.record(nametoGuidMap.getOrDefault(resultEntry.getWeightUnit(), null),
                    resultEntry.getHost(), resultEntry.getWeight());
        }
    }

    public Map<String, String> getCustomPropertiesRegexMap(ClusterPolicy clusterPolicy) {
        Set<Guid> usedPolicyUnits = new HashSet<>();
        if (clusterPolicy.getFilters() != null) {
            usedPolicyUnits.addAll(clusterPolicy.getFilters());
        }
        if (clusterPolicy.getFunctions() != null) {
            for (Pair<Guid, Integer> pair : clusterPolicy.getFunctions()) {
                usedPolicyUnits.add(pair.getFirst());
            }
        }
        if (clusterPolicy.getBalance() != null) {
            usedPolicyUnits.add(clusterPolicy.getBalance());
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Guid policyUnitId : usedPolicyUnits) {
            map.putAll(policyUnits.get(policyUnitId).getPolicyUnit().getParameterRegExMap());
        }
        return map;
    }

    public void addClusterPolicy(ClusterPolicy clusterPolicy) {
        clusterPolicyDao.save(clusterPolicy);
        policyMap.put(clusterPolicy.getId(), clusterPolicy);
    }

    public void editClusterPolicy(ClusterPolicy clusterPolicy) {
        clusterPolicyDao.update(clusterPolicy);
        policyMap.put(clusterPolicy.getId(), clusterPolicy);
    }

    public void removeClusterPolicy(Guid clusterPolicyId) {
        clusterPolicyDao.remove(clusterPolicyId);
        policyMap.remove(clusterPolicyId);
    }

    private void enableLoadBalancer() {
        if (Config.<Boolean>getValue(ConfigValues.EnableVdsLoadBalancing)) {
            log.info("Start scheduling to enable vds load balancer");
            executor.scheduleWithFixedDelay(this::performLoadBalancing,
                    Config.<Long>getValue(ConfigValues.VdsLoadBalancingIntervalInMinutes),
                    Config.<Long>getValue(ConfigValues.VdsLoadBalancingIntervalInMinutes),
                    TimeUnit.MINUTES);
            log.info("Finished scheduling to enable vds load balancer");
        }
    }

    private void enableHaReservationCheck() {

        if (Config.<Boolean>getValue(ConfigValues.EnableVdsLoadBalancing)) {
            log.info("Start HA Reservation check");
            long interval = Config.<Long> getValue(ConfigValues.VdsHaReservationIntervalInMinutes);
            executor.scheduleWithFixedDelay(this::performHaResevationCheck,
                    interval,
                    interval,
                    TimeUnit.MINUTES);
            log.info("Finished HA Reservation check");
        }

    }

    private void performHaResevationCheck() {
        try {
            performHaResevationCheckImpl();
        } catch (Throwable t) {
            log.error("Exception in performing HA Reservation check: {}", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    public void performHaResevationCheckImpl() {

        log.debug("HA Reservation check timer entered.");
        List<Cluster> clusters = clusterDao.getAll();
        if (clusters != null) {
            HaReservationHandling haReservationHandling = new HaReservationHandling(getPendingResourceManager());
            for (Cluster cluster : clusters) {
                if (cluster.supportsHaReservation()) {
                    List<VDS> returnedFailedHosts = new ArrayList<>();
                    boolean clusterHaStatus =
                            haReservationHandling.checkHaReservationStatusForCluster(cluster, returnedFailedHosts);
                    if (!clusterHaStatus) {
                        // create Alert using returnedFailedHosts
                        AuditLogable logable = createEventForCluster(cluster);
                        String failedHostsStr =
                                returnedFailedHosts.stream().map(VDS::getName).collect(Collectors.joining(", "));

                        logable.addCustomValue("Hosts", failedHostsStr);
                        auditLogDirector.log(logable, AuditLogType.CLUSTER_ALERT_HA_RESERVATION);
                        log.info("Cluster '{}' fail to pass HA reservation check.", cluster.getName());
                    }

                    boolean clusterHaStatusFromPreviousCycle =
                            clusterId2isHaReservationSafe.getOrDefault(cluster.getId(), true);

                    // Update the status map with the new status
                    clusterId2isHaReservationSafe.put(cluster.getId(), clusterHaStatus);

                    // Create Alert if the status was changed from false to true
                    if (!clusterHaStatusFromPreviousCycle && clusterHaStatus) {
                        AuditLogable logable = createEventForCluster(cluster);
                        auditLogDirector.log(logable, AuditLogType.CLUSTER_ALERT_HA_RESERVATION_DOWN);
                    }
                }
            }
        }
        log.debug("HA Reservation check timer finished.");
    }

    private AuditLogable createEventForCluster(Cluster cluster) {
        AuditLogable logable = new AuditLogableImpl();
        logable.setClusterName(cluster.getName());
        logable.setClusterId(cluster.getId());
        return logable;
    }

    private void performLoadBalancing() {
        try {
            performLoadBalancingImpl();
        } catch (Throwable t) {
            log.error("Exception in performing load balancing: {}", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    private void performLoadBalancingImpl() {
        log.debug("Load Balancer timer entered.");
        List<Cluster> clusters = clusterDao.getAll();
        for (Cluster cluster : clusters) {
            ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
            PolicyUnitImpl policyUnit = policyUnits.get(policy.getBalance());
            List<BalanceResult> balanceResults = Collections.emptyList();
            if (policyUnit.getPolicyUnit().isEnabled()) {
                List<VDS> hosts = vdsDao.getAllForClusterWithoutMigrating(cluster.getId());
                if (policyUnit.getPolicyUnit().isInternal()) {
                    balanceResults = internalRunBalance(policyUnit, cluster, hosts);
                } else if (Config.<Boolean> getValue(ConfigValues.ExternalSchedulerEnabled)) {
                    balanceResults = externalRunBalance(policyUnit, cluster, hosts);
                }
            }

            for (BalanceResult balanceResult: balanceResults) {
                if (!balanceResult.isValid()) {
                    continue;
                }

                boolean migrated = migrationHandler.migrateVM(balanceResult.getCandidateHosts(),
                        balanceResult.getVmToMigrate(),
                        MessageBundler.getMessage(AuditLogType.MIGRATION_REASON_LOAD_BALANCING));

                if (migrated) {
                    break;
                }
            }
        }
    }

    private List<BalanceResult> internalRunBalance(PolicyUnitImpl policyUnit,
            Cluster cluster,
            List<VDS> hosts) {
        return policyUnit.balance(cluster,
                hosts,
                cluster.getClusterPolicyProperties());
    }

    private List<BalanceResult> externalRunBalance(PolicyUnitImpl policyUnit,
            Cluster cluster,
            List<VDS> hosts) {
        List<Guid> hostIDs = new ArrayList<>();
        for (VDS vds : hosts) {
            hostIDs.add(vds.getId());
        }

        Optional<BalanceResult> balanceResult = externalBroker.runBalance(policyUnit.getPolicyUnit().getName(),
                hostIDs, cluster.getClusterPolicyProperties());

        if (balanceResult.isPresent()) {
            return Collections.singletonList(balanceResult.get());
        }

        log.warn("All external schedulers returned empty balancing result.");
        return Collections.emptyList();
    }

    /**
     * returns all cluster policies names containing the specific policy unit.
     * @return List of cluster policy names that use the referenced policyUnitId
     *         or null if the policy unit is not available.
     */
    public List<String> getClusterPoliciesNamesByPolicyUnitId(Guid policyUnitId) {
        List<String> list = new ArrayList<>();
        final PolicyUnitImpl policyUnitImpl = policyUnits.get(policyUnitId);
        if (policyUnitImpl == null) {
            log.warn("Trying to find usages of non-existing policy unit '{}'", policyUnitId);
            return null;
        }

        PolicyUnit policyUnit = policyUnitImpl.getPolicyUnit();
        if (policyUnit != null) {
            for (ClusterPolicy clusterPolicy : policyMap.values()) {
                switch (policyUnit.getPolicyUnitType()) {
                case FILTER:
                    Collection<Guid> filters = clusterPolicy.getFilters();
                    if (filters != null && filters.contains(policyUnitId)) {
                        list.add(clusterPolicy.getName());
                    }
                    break;
                case WEIGHT:
                    Collection<Pair<Guid, Integer>> functions = clusterPolicy.getFunctions();
                    if (functions == null) {
                        break;
                    }
                    for (Pair<Guid, Integer> pair : functions) {
                        if (pair.getFirst().equals(policyUnitId)) {
                            list.add(clusterPolicy.getName());
                            break;
                        }
                    }
                    break;
                case LOAD_BALANCING:
                    if (policyUnitId.equals(clusterPolicy.getBalance())) {
                        list.add(clusterPolicy.getName());
                    }
                    break;
                default:
                    break;
                }
            }
        }
        return list;
    }

    public void removeExternalPolicyUnit(Guid policyUnitId) {
        policyUnitDao.remove(policyUnitId);
        policyUnits.remove(policyUnitId);
    }

    /**
     * update host scheduling statistics:
     * * CPU load duration interval over/under policy threshold
     */
    public void updateHostSchedulingStats(VDS vds) {
        if (vds.getUsageCpuPercent() != null) {
            Cluster cluster = clusterDao.get(vds.getClusterId());
            if (vds.getUsageCpuPercent() >= NumberUtils.toInt(cluster.getClusterPolicyProperties()
                    .get(HIGH_UTILIZATION),
                    Config.<Integer> getValue(ConfigValues.HighUtilizationForEvenlyDistribute))
                    || vds.getUsageCpuPercent() <= NumberUtils.toInt(cluster.getClusterPolicyProperties()
                            .get(LOW_UTILIZATION),
                            Config.<Integer> getValue(ConfigValues.LowUtilizationForEvenlyDistribute))) {
                if (vds.getCpuOverCommitTimestamp() == null) {
                    vds.setCpuOverCommitTimestamp(new Date());
                }
            } else {
                vds.setCpuOverCommitTimestamp(null);
            }
        }
    }

    /**
     * Clear pending records for a VM.
     *
     * While scheduling a VM, this function may be called by a different thread
     * when another VM successfully starts. As an effect, policy units can see
     * different states of pending resources.
     * This is OK, because clearing pending resources should only increase the
     * number of possible hosts that can run the VM.
     */
    public void clearPendingVm(VmStatic vm) {
        getPendingResourceManager().clearVm(vm);
    }
}
