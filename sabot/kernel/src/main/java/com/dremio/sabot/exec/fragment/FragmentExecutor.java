/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.exec.fragment;

import static com.dremio.sabot.exec.fragment.FragmentExecutorBuilder.PIPELINE_RES_GRP;
import static com.dremio.sabot.exec.fragment.FragmentExecutorBuilder.WORK_QUEUE_RES_GRP;

import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;

import com.dremio.common.DeferredException;
import com.dremio.common.ProcessExit;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.ErrorHelper;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.memory.MemoryDebugInfo;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.fragment.CachedFragmentReader;
import com.dremio.exec.planner.fragment.PlanFragmentFull;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.proto.CoordExecRPC.FragmentStatus;
import com.dremio.exec.proto.CoordExecRPC.PlanFragmentMajor;
import com.dremio.exec.proto.CoordExecRPC.PlanFragmentMinor;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.proto.ExecRPC.FragmentStreamComplete;
import com.dremio.exec.proto.UserBitShared.FragmentState;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.options.OptionManager;
import com.dremio.sabot.driver.OperatorCreatorRegistry;
import com.dremio.sabot.driver.Pipeline;
import com.dremio.sabot.driver.PipelineCreator;
import com.dremio.sabot.exec.EventProvider;
import com.dremio.sabot.exec.FragmentTicket;
import com.dremio.sabot.exec.StateTransitionException;
import com.dremio.sabot.exec.context.ContextInformation;
import com.dremio.sabot.exec.context.FragmentStats;
import com.dremio.sabot.exec.cursors.FileCursorManagerFactory;
import com.dremio.sabot.exec.rpc.IncomingDataBatch;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import com.dremio.sabot.memory.MemoryArbiter;
import com.dremio.sabot.memory.MemoryArbiterTask;
import com.dremio.sabot.memory.MemoryTaskAndShrinkableOperator;
import com.dremio.sabot.op.receiver.IncomingBuffers;
import com.dremio.sabot.op.spi.Operator;
import com.dremio.sabot.task.AsyncTask;
import com.dremio.sabot.task.AsyncTaskWrapper;
import com.dremio.sabot.task.SchedulingGroup;
import com.dremio.sabot.task.Task.State;
import com.dremio.sabot.task.TaskDescriptor;
import com.dremio.sabot.threads.AvailabilityCallback;
import com.dremio.sabot.threads.sharedres.ActivableResource;
import com.dremio.sabot.threads.sharedres.SharedResource;
import com.dremio.sabot.threads.sharedres.SharedResourceManager;
import com.dremio.sabot.threads.sharedres.SharedResourceType;
import com.dremio.sabot.threads.sharedres.SharedResourcesContextImpl;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.NodeStatusListener;
import com.dremio.service.spill.SpillService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SettableFuture;

/**
 * A reusable Runnable and Task that executes a fragment's pipeline. This
 * runnable is designed to stop regularly such that it can be rescheduled as
 * necessary. It needs to be run repeatedly until getState() returns State.DONE.
 *
 * Virtually all work is done in a single thread to avoid any concurrency
 * complexities. Any incoming messages are staged until the next time this is
 * scheduled and then the execution thread is responsible for handling those
 * messages.
 */
public class FragmentExecutor implements MemoryArbiterTask {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FragmentExecutor.class);
  private static final ControlsInjector injector = ControlsInjectorFactory.getInjector(FragmentExecutor.class);
  public static final long MB = 1024 * 1024;

  @VisibleForTesting
  public static final String INJECTOR_DO_WORK = "injectOOMOnRun";

  /** threadsafe fields, influenced by external events. **/
  private final FragmentExecutorListener listener = new FragmentExecutorListener();
  private final ForemanDeathListener crashListener = new ForemanDeathListener();

  /** start of private execution thread only fields. **/
  private final String name;
  private final DoAsPumper pumper = new DoAsPumper();
  private final FragmentStatusReporter statusReporter;
  private final DeferredException deferredException;

  private final PlanFragmentFull fragment;
  private final ClusterCoordinator clusterCoordinator;
  private final CachedFragmentReader reader;
  private final SharedResourceManager sharedResources;
  private final OperatorCreatorRegistry opCreator;
  private final BufferAllocator allocator;
  private final OperatorContextCreator contextCreator;
  private final FunctionLookupContext functionLookupContext;
  private final FunctionLookupContext decimalFunctionLookupContext;
  private final TunnelProvider tunnelProvider;
  private final FlushableSendingAccountor flushable;
  private final OptionManager fragmentOptions;
  private final FragmentStats stats;
  private final FragmentTicket ticket;
  private final CatalogService sources;

  private boolean retired = false;
  private boolean isSetup = false;
  private volatile boolean foremanDead = false;
  private volatile UserException foremanDeadException = null;

  // All tasks start as runnable. Only the execution thread will be allowed to change this value so no locking is needed.
  private volatile State taskState = State.RUNNABLE;

  // All Fragments starts as awaiting allocation. Changed by only execution thread. Modified externally thus volatile setting.
  private volatile FragmentState state = FragmentState.AWAITING_ALLOCATION;

  private BufferAllocator outputAllocator;
  private Pipeline pipeline;
  private final IncomingBuffers buffers;

  private volatile TaskDescriptor taskDescriptor;

  private final EventProvider eventProvider;

  private final FragmentWorkQueue workQueue;

  private final SettableFuture<Boolean> cancelled;

  private final ExecutionControls executionControls;

  // The fragment will not be activated until it gets :
  // a. a activate/cancel from the foreman (or)
  // b. an incoming data/oob/finished msg from any upstream fragment.
  private final ActivableResource activateResource;

  private final SharedResource allocatorLock;

  // This is the weight assigned by the planner
  private final long fragmentWeight;
  // This is the weight assigned by the executor
  private final int schedulingWeight;
  private final boolean leafFragment;

  // This is used to keep track of fragments that use the memory arbiter
  private final MemoryArbiter memoryArbiter;
  private long memoryGrantInBytes = 0;
  private long maxMemoryUsedPerPump = 16 * MB;
  private final List<MemoryTaskAndShrinkableOperator> shrinkableOperators = new ArrayList<>();
  // This is the list of operators that have been asked to spill
  private final Map<Integer, Long> spillingOperators = new HashMap<>();
  // This is a queue of in-progress spilling operators
  private final Queue<Integer> spillingOperatorQueue = new ArrayDeque<>(10);

  // used to block the fragment when the node is short of direct memory and
  // unblock the fragment when the node has direct memory
  private final SharedResource memoryResource;

  public FragmentExecutor(
      FragmentStatusReporter statusReporter,
      SabotConfig config,
      ExecutionControls executionControls,
      PlanFragmentFull fragment,
      int schedulingWeight,
      MemoryArbiter memoryArbiter,
      ClusterCoordinator clusterCoordinator,
      CachedFragmentReader reader,
      SharedResourceManager sharedResources,
      OperatorCreatorRegistry opCreator,
      BufferAllocator allocator,
      ContextInformation contextInfo,
      OperatorContextCreator contextCreator,
      FunctionLookupContext functionLookupContext,
      FunctionLookupContext decimalFunctionLookupContext,
      FileCursorManagerFactory fileCursorManagerFactory,
      TunnelProvider tunnelProvider,
      FlushableSendingAccountor flushable,
      OptionManager fragmentOptions,
      FragmentStats stats,
      final FragmentTicket ticket,
      final CatalogService sources,
      DeferredException exception,
      EventProvider eventProvider,
      SpillService spillService) {
    super();
    this.name = QueryIdHelper.getExecutorThreadName(fragment.getHandle());
    this.statusReporter = statusReporter;
    this.fragment = fragment;
    this.fragmentWeight = fragment.getMajor().getFragmentExecWeight() <= 0 ?
      1 : fragment.getMajor().getFragmentExecWeight();
    this.schedulingWeight = schedulingWeight;
    this.memoryArbiter = memoryArbiter;
    if (memoryArbiter != null) {
      memoryArbiter.startTask(this);
    }
    this.leafFragment = fragment.getMajor().getLeafFragment();
    this.clusterCoordinator = clusterCoordinator;
    this.reader = reader;
    this.sharedResources = sharedResources;
    this.opCreator = opCreator;
    this.functionLookupContext = functionLookupContext;
    this.decimalFunctionLookupContext = decimalFunctionLookupContext;
    this.allocator = allocator;
    this.contextCreator = contextCreator;
    this.tunnelProvider = tunnelProvider;
    this.flushable = flushable;
    this.fragmentOptions = fragmentOptions;
    this.stats = stats;
    this.ticket = ticket;
    this.deferredException = exception;
    this.sources = sources;
    this.activateResource = new ActivableResource(sharedResources.getGroup(PIPELINE_RES_GRP).createResource(
      "activate-signal-" + this.name, SharedResourceType.FRAGMENT_ACTIVATE_SIGNAL));
    this.workQueue = new FragmentWorkQueue(sharedResources.getGroup(WORK_QUEUE_RES_GRP));
    this.buffers = new IncomingBuffers(
      deferredException, sharedResources.getGroup(PIPELINE_RES_GRP), workQueue, tunnelProvider,
      fileCursorManagerFactory,
      fragment, allocator, config, fragmentOptions, executionControls, spillService, reader.getPlanFragmentsIndex());
    this.eventProvider = eventProvider;
    this.cancelled = SettableFuture.create();
    this.executionControls = executionControls;
    this.allocatorLock = sharedResources.getGroup(PIPELINE_RES_GRP).createResource("frag-allocator", SharedResourceType.UNKNOWN);
    this.memoryResource = sharedResources.getGroup(PIPELINE_RES_GRP).createResource("blocked-on-memory", SharedResourceType.WAIT_FOR_MEMORY);
  }

  @Override
  public String getTaskId() {
    return name;
  }

  @Override
  public void blockOnMemory() {
    Preconditions.checkArgument(this.taskState != State.BLOCKED_ON_MEMORY, "Unexpected state, the fragment is already blocked on memory");
    this.taskState = State.BLOCKED_ON_MEMORY;
    logger.debug("Fragment {}:{} blocked on memory", fragment.getHandle().getMajorFragmentId(), fragment.getHandle().getMinorFragmentId());
    this.memoryResource.markBlocked();
  }

  @Override
  public void unblockOnMemory() {
    if (this.taskState == State.BLOCKED_ON_MEMORY) {
      logger.debug("Fragment {}:{} was blocked on memory, unblocked now", fragment.getHandle().getMajorFragmentId(), fragment.getHandle().getMinorFragmentId());
      this.memoryResource.markAvailable();
    }
  }

  @Override
  public long getMemoryGrant() {
    return this.memoryGrantInBytes;
  }

  @Override
  public void setMemoryGrant(long memoryGrantInBytes) {
    this.memoryGrantInBytes = memoryGrantInBytes;
  }

  @Override
  public long getUsedMemory() {
    return allocator.getAllocatedMemory();
  }

  @Override
  public List<MemoryTaskAndShrinkableOperator> getShrinkableOperators() {
    return shrinkableOperators;
  }

  // TODO: Improve this based on actual usage
  private long getMemoryToAcquire() {
    return maxMemoryUsedPerPump;
  }

  private void postRunUpdate() {
    if (memoryArbiter != null) {
      memoryArbiter.releaseMemoryGrant(this);
    }
    assert memoryGrantInBytes == 0 : "Memory grant should be 0";
  }

  private int getPhaseAndOperatorId(int localOperatorId) {
    return (fragment.getMajorFragmentId() << 16) + localOperatorId;
  }

  @Override
  public boolean isOperatorShrinkingMemory(Operator.ShrinkableOperator shrinkableOperator) {
    return spillingOperators.containsKey(getPhaseAndOperatorId(shrinkableOperator.getOperatorId()));
  }

  @Override
  public void shrinkMemory(Operator.ShrinkableOperator shrinkableOperator, long currentMemoryUsed) throws Exception {
    // Need to send an OOB message to self
    // We could call handleShrinkMemoryRequest() directly. However, sending an OOBMessage ensures that the
    // task is moved to Runnable state
    ExecProtos.ShrinkMemoryUsage shrinkMemoryUsage = ExecProtos.ShrinkMemoryUsage.newBuilder().setMemoryInBytes(currentMemoryUsed).build();
    OutOfBandMessage.Payload payload = new OutOfBandMessage.Payload(shrinkMemoryUsage);
    OutOfBandMessage outOfBandMessage = new OutOfBandMessage(
      fragment.getHandle().getQueryId(),
      fragment.getMajorFragmentId(), fragment.getMinorFragmentId(),
      getPhaseAndOperatorId(shrinkableOperator.getOperatorId()), payload);
    logger.debug("Sending shrinkMemory OOB to {} for operator {}:{}:{}", fragment.getAssignment(),
      fragment.getMajorFragmentId(), fragment.getMinorFragmentId(), shrinkableOperator.getOperatorId());
    tunnelProvider.getExecTunnel(fragment.getAssignment()).sendOOBMessage(outOfBandMessage);
  }

  private boolean reduceMemoryUsageForOperator(int operatorId) throws Exception {
    Long memoryUsageBeforeSpilling = spillingOperators.get(operatorId);
    if (memoryUsageBeforeSpilling == null) {
      return true;
    }

    int localOperatorId = operatorId & 0xFFFF;
    logger.trace("Asking operator {} to spill", localOperatorId);
    boolean doneSpilling = pipeline.shrinkMemory(operatorId, memoryUsageBeforeSpilling);
    if (doneSpilling) {
      // Spilled the required amount of memory
      logger.debug("Operator {} done spilling", localOperatorId);
      logger.debug("Memory arbiter state {}", memoryArbiter);
      spillingOperators.remove(operatorId);
    }

    return doneSpilling;
  }

  private void reduceMemoryUsage() throws Exception {
    if (spillingOperatorQueue.isEmpty()) {
      // Make a copy of the spilling operator ids
      spillingOperatorQueue.addAll(spillingOperators.keySet());
    }

    if (spillingOperatorQueue.isEmpty()) {
      return;
    }

    // pick the first spilling operator and ask it to spill
    int operatorId = spillingOperatorQueue.remove();
    reduceMemoryUsageForOperator(operatorId);
  }

  /**
   * Do some work.
   */
  private void run(){
    assert taskState != State.DONE : "Attempted to run a task with state of DONE.";
    assert eventProvider != null : "Attempted to run without an eventProvider";

    if (!activateResource.isActivated()) {
      // All tasks are expected to begin in a runnable state. So, switch to the BLOCKED state on the
      // first call.
      taskState = State.BLOCKED_ON_SHARED_RESOURCE;
      return;
    }
    stats.runStarted();

    // update thread name.
    final Thread currentThread = Thread.currentThread();
    final String originalName = currentThread.getName();
    currentThread.setName(originalName + " - " + name);

    try {

      // if we're already done, we're finishing clean up. No core method
      // execution is necessary, simply exit this block so we finishRun() below.
      // We do this because a failure state will put us in a situation.
      if(state == FragmentState.CANCELLED || state == FragmentState.FAILED || state == FragmentState.FINISHED) {
        return;
      }

      // if there are any deferred exceptions, exit.
      if(deferredException.hasException()) {
        transitionToFailed(null);
        return;
      }

      // if cancellation is requested, that is always the top priority.
      if (cancelled.isDone()) {
        Optional<Throwable> failedReason = eventProvider.getFailedReason();
        if (failedReason.isPresent() || foremanDead) {
          // check if it was failed due to an external reason (eg. by heap monitor).
          // foremanDead is true, foremanDeadException must be non null.
          assert(!foremanDead || (foremanDeadException != null));
          transitionToFailed(failedReason.isPresent() ? failedReason.get() : foremanDeadException);
          return;
        }

        transitionToCancelled();
        taskState = State.DONE;
        return;
      }

      // setup the execution if it isn't setup.
      if(!isSetup){
        stats.setupStarted();
        try {
          if ((memoryArbiter != null) && !memoryArbiter.acquireMemoryGrant(this, getMemoryToAcquire())) {
            return;
          }
          setupExecution();
        } finally {
          stats.setupEnded();
        }
        // exit since we just did setup which could be a non-trivial amount of work. Allow the scheduler to decide whether we should continue.
        return;
      }

      // workQueue might contain OOBMessages, which should be held and processed after the setup.
      // This piece should always execute after the setup is done.
      final Runnable work = workQueue.poll();
      if (work != null) {
        // we don't know how long it will take to process one work unit, we rely on the scheduler to execute
        // this fragment again if it didn't run long enough
        work.run();
        return;
      }

      if (!spillingOperators.isEmpty()) {
        // there are spilling operators
        reduceMemoryUsage();
        return;
      }

      // handle any previously sent fragment finished messages.
      FragmentHandle finishedFragment;
      while ((finishedFragment = eventProvider.pollFinishedReceiver()) != null) {
        pipeline.getTerminalOperator().receivingFragmentFinished(finishedFragment);
      }

      long memoryUsedBeforePump = getUsedMemory();
      if ((memoryArbiter != null) && !memoryArbiter.acquireMemoryGrant(this, getMemoryToAcquire())) {
        return;
      }

      // pump the pipeline
      taskState = pumper.run();
      long memoryUsedAfterPump = getUsedMemory();
      if (memoryUsedAfterPump > memoryUsedBeforePump) {
        long diff = memoryUsedAfterPump - memoryUsedBeforePump;
        if (diff > maxMemoryUsedPerPump) {
          logger.debug("Used {} more memory than granted {}", diff, maxMemoryUsedPerPump);
          maxMemoryUsedPerPump = diff;
        }
      }

      // if we've finished all work, let's wrap up.
      if(taskState == State.DONE){
        transitionToFinished();
      }

      injector.injectChecked(executionControls, INJECTOR_DO_WORK, OutOfMemoryError.class);

    } catch (OutOfMemoryError | OutOfMemoryException e) {
      // handle out of memory errors differently from other error types.
      if (ErrorHelper.isDirectMemoryException(e) || INJECTOR_DO_WORK.equals(e.getMessage())) {
        transitionToFailed(UserException.memoryError(e)
            .addContext(MemoryDebugInfo.getDetailsOnAllocationFailure(new OutOfMemoryException(e), allocator))
            .buildSilently());
      } else {
        // we have a heap out of memory error. The JVM in unstable, exit.
        ProcessExit.exitHeap(e);
      }
    } catch (Throwable e) {
      transitionToFailed(e);
    } finally {

      try {
        finishRun(originalName);
      } finally {
        stats.runEnded();
      }
    }

  }

  /**
   * Informs FragmentExecutor to refresh state with the expectation that a
   * previously blocked state is now moving to an unblocked state.
   */
  private void refreshState() {
    Preconditions.checkArgument(taskState == State.BLOCKED_ON_DOWNSTREAM ||
      taskState == State.BLOCKED_ON_UPSTREAM ||
      taskState == State.BLOCKED_ON_MEMORY ||
      taskState == State.BLOCKED_ON_SHARED_RESOURCE, "Should only called when we were previously blocked.");
    Preconditions.checkArgument(sharedResources.isAvailable(), "Should only be called once at least one shared group is available: " + sharedResources.toString());
    taskState = State.RUNNABLE;
  }

  public long getSchedulingWeight() {
    return (long) schedulingWeight;
  }

  public long getFragmentWeight() {
    return fragmentWeight;
  }

  public boolean isLeafFragment() {
    return leafFragment;
  }

  /**
   * Class used to pump data within the query user's doAs space.
   */
  private class DoAsPumper implements PrivilegedExceptionAction<State> {

    @Override
    public State run() throws Exception {
      return pipeline.pumpOnce();
    }

  }

  /**
   * Returns the current fragment status if the fragment is running. Otherwise, returns no status.
   *
   * @return FragmentStatus or null.
   */
  public FragmentStatus getStatus() {
    /*
     * If the query is not in a running state, the operator tree is still being constructed and
     * there is no reason to poll for intermediate results.
     *
     * Previously the call to get the operator stats with the AbstractStatusReporter was happening
     * before this check. This caused a concurrent modification exception as the list of operator
     * stats is iterated over while collecting info, and added to while building the operator tree.
     */
    if (state != FragmentState.RUNNING) {
      return null;
    }

    return statusReporter.getStatus(FragmentState.RUNNING);
  }

  @VisibleForTesting
  // TO BE USED ONLY BY TEST CLASSES
  void overrideIsSetup(boolean isSetupIn) {
    isSetup = isSetupIn;
  }

  @VisibleForTesting
  void setupExecution() throws Exception{
    final PlanFragmentMajor major = fragment.getMajor();
    final PlanFragmentMinor minor = fragment.getMinor();

    logger.debug("Starting fragment {}:{} on {}:{}", major.getHandle().getMajorFragmentId(), getHandle().getMinorFragmentId(), minor.getAssignment().getAddress(), minor.getAssignment().getUserPort());
    outputAllocator = ticket.newChildAllocator("output-frag:" + QueryIdHelper.getFragmentId(getHandle()),
      fragmentOptions.getOption(ExecConstants.OUTPUT_ALLOCATOR_RESERVATION),
      Long.MAX_VALUE);
    contextCreator.setFragmentOutputAllocator(outputAllocator);

    final PhysicalOperator rootOperator = reader.readFragment(fragment);
    contextCreator.setMinorFragmentEndpointsFromRootSender(rootOperator);
    FunctionLookupContext functionLookupContextToUse = functionLookupContext;
    if (fragmentOptions.getOption(PlannerSettings.ENABLE_DECIMAL_V2)) {
      functionLookupContextToUse = decimalFunctionLookupContext;
    }
    pipeline = PipelineCreator.get(
        new FragmentExecutionContext(major.getForeman(), sources, cancelled, major.getContext()),
        buffers,
        opCreator,
        contextCreator,
        functionLookupContextToUse,
        rootOperator,
        tunnelProvider,
        new SharedResourcesContextImpl(sharedResources)
        );

    pipeline.setup();
    shrinkableOperators.addAll(
      pipeline.getShrinkableOperators()
        .stream()
        .map(shrinkableOperator -> { return new MemoryTaskAndShrinkableOperator(this, shrinkableOperator); })
        .collect(Collectors.toList())
    );
    clusterCoordinator.getServiceSet(ClusterCoordinator.Role.COORDINATOR).addNodeStatusListener(crashListener);

    transitionToRunning();
    isSetup = true;
  }

  // called every time a run is completed.
  private void finishRun(String originalThreadName) {

    // if we're in a terminal state, send final outcome.
    stats.finishStarted();
    try {
      switch(state){
      case FAILED:
      case FINISHED:
      case CANCELLED:
        retire();

      default:
        // noop
      }

    } finally {
      Thread.currentThread().setName(originalThreadName);
      stats.finishEnded();
    }
  }

  /**
   * Entered by something other than the execution thread. Makes this fragment's pipeline runnable.
   */
  private void requestActivate(String trigger) {
    this.activateResource.activate(trigger);
  }

  /**
   * Entered by something other than the execution thread. Ensures this fragment gets rescheduled as soon as possible.
   */
  private void requestCancellation(){
    this.cancelled.set(true);
    this.sharedResources.getGroup(PIPELINE_RES_GRP).markAllAvailable();
  }

  private State getState() {
    return taskState;
  }

  private void retire() {
    Preconditions.checkArgument(!retired, "Fragment executor already retired.");

    if(!flushable.flushMessages()) {
      // rerun retire if we have messages still pending send completion.
      logger.debug("fragment retire blocked on downstream");
      taskState = State.BLOCKED_ON_DOWNSTREAM;
      return;
    }

    deferredException.suppressingClose(pipeline);
    // make sure to close incoming buffers before we call flushMessages() otherwise we may block before
    // we sent ACKs to other fragments and force other fragments to wait on us
    deferredException.suppressingClose(buffers);

    if(!flushable.flushMessages()) {
      // rerun retire if we have messages still pending send completion.
      logger.debug("fragment retire blocked on downstream");
      taskState = State.BLOCKED_ON_DOWNSTREAM;
      return;
    } else {
      taskState = State.DONE;
    }

    clusterCoordinator.getServiceSet(ClusterCoordinator.Role.COORDINATOR).removeNodeStatusListener(crashListener);

    deferredException.suppressingClose(contextCreator);
    deferredException.suppressingClose(outputAllocator);
    synchronized (allocatorLock) {
      workQueue.retire();
      deferredException.suppressingClose(allocator);
    }
    deferredException.suppressingClose(ticket);
    if (tunnelProvider != null && tunnelProvider.getCoordTunnel() != null) {
      deferredException.suppressingClose(tunnelProvider.getCoordTunnel().getTunnel());
    }

    // if defferedexception is set, update state to failed.
    if(deferredException.hasException()){
      transitionToFailed(null);
    }

    // send the final state of the fragment. only the main execution thread can send the final state and it can
    // only be sent once.
    final FragmentHandle handle = fragment.getMajor().getHandle();
    if (state == FragmentState.FAILED) {
      final UserException uex = UserException.systemError(deferredException.getAndClear())
        .addIdentity(fragment.getMinor().getAssignment())
        .addContext("Fragment", handle.getMajorFragmentId() + ":" + handle.getMinorFragmentId())
        .build(logger);
      statusReporter.fail(uex);
    } else {
      statusReporter.stateChanged(state);
    }

    retired = true;
    logger.debug("Fragment finished {}:{} on {}:{}", fragment.getHandle().getMajorFragmentId(), fragment.getHandle().getMinorFragmentId(), fragment.getAssignment().getAddress(), fragment.getAssignment().getUserPort());
  }

  private void transitionToFinished(){
    switch(state){
    case FAILED:
    case CANCELLED:
      // don't override a terminal state.
      dropStateChange(FragmentState.FINISHED);
      return;

    default:
      state = FragmentState.FINISHED;
    }
  }

  private void transitionToCancelled(){
    switch(state){
    case FAILED:
      dropStateChange(FragmentState.CANCELLED);
      return;
    default:
      state = FragmentState.CANCELLED;
    }
  }

  private void transitionToFailed(Throwable t) {
    if(t != null){
      deferredException.addThrowable(t);
    }
    switch(state){
    case FAILED:
      dropStateChange(FragmentState.FAILED);
      return;
    default:
      state = FragmentState.FAILED;
      return;
    }
  }

  @VisibleForTesting
  void transitionToRunning() {
    switch(state){
    case FAILED:
    case CANCELLED:
      // we've already moved to a terminal state.
      dropStateChange(FragmentState.RUNNING);
      return;

    // reasonable initial states.
    case AWAITING_ALLOCATION:
    case SENDING:
      state = FragmentState.RUNNING;
      return;

    case FINISHED:
    case RUNNING:
      errorStateChange(FragmentState.RUNNING);
      return;
    default:
      return;

    }
  }

  /**
   * Responsible for listening to a death to the driving force behind this
   * fragment. If the driving node crashes, all the PipelineExecutors have to
   * shoot themselves.
   */
  private class ForemanDeathListener implements NodeStatusListener {

    @Override
    public void nodesRegistered(final Set<NodeEndpoint> registereds) {
    }

    @Override
    public void nodesUnregistered(final Set<NodeEndpoint> unregistereds) {
      final NodeEndpoint foremanEndpoint = fragment.getMajor().getForeman();
      if (unregistereds.contains(foremanEndpoint)) {
        logger.warn("AttemptManager {} no longer active. Cancelling fragment {}.",
                    foremanEndpoint.getAddress(),
                    QueryIdHelper.getQueryIdentifier(fragment.getHandle()));
        if (!foremanDead) {
          foremanDead = true;
          foremanDeadException =
            UserException.connectionError()
              .message(String.format(
                "AttemptManager %s no longer active. Cancelling fragment %s",
                foremanEndpoint.getAddress(),
                QueryIdHelper.getQueryIdentifier(fragment.getHandle())))
              .buildSilently();
        }
        requestCancellation();
      }
    }

  }

  private void errorStateChange(final FragmentState target) {
    final String msg = "%s: Invalid state transition %s --> %s";
    throw new StateTransitionException(String.format(msg, name, state.name(), target.name()));
  }

  private void dropStateChange(final FragmentState target) {
    logger.debug(name + ": Dropping state transition {} --> {}", state.name(), target.name());
  }

  public FragmentExecutorListener getListener(){
    return listener;
  }

  public FragmentHandle getHandle(){
    return fragment.getHandle();
  }

  public SchedulingGroup<AsyncTaskWrapper> getSchedulingGroup() {
    return ticket.getSchedulingGroup();
  }

  public AsyncTask asAsyncTask(){
    return new AsyncTaskImpl();
  }

  public NodeEndpoint getForeman(){
    return fragment.getMajor().getForeman();
  }

  public String getBlockingStatus(){
    return sharedResources.toString();
  }

  public TaskDescriptor getTaskDescriptor() {
    return taskDescriptor;
  }

  // This fragment got a shrink memory request. Add this to the list of spilling operators
  private void handleShrinkMemoryRequest(OutOfBandMessage message) {
    unblockOnMemory();
    ExecProtos.ShrinkMemoryUsage shrinkMemoryUsage = message.getPayload(ExecProtos.ShrinkMemoryUsage.parser());
    Long prevValue = spillingOperators.put(message.getOperatorId(), shrinkMemoryUsage.getMemoryInBytes());
    if (prevValue != null) {
      logger.debug("Operator {} got duplicate OOM message, previous request {}, current request {}",
        message.getOperatorId(), prevValue, shrinkMemoryUsage.getMemoryInBytes());
    }
  }

  private void handleOOBMessage(OutOfBandMessage outOfBandMessage) throws Exception {
    if (!isSetup) {
      if (outOfBandMessage.getIsOptional()) {
        logger.warn("Fragment {} received optional OOB message in state {} for operatorId {}. Fragment is not yet set up. Ignoring message.",
          this.getHandle().toString(), state.toString(), outOfBandMessage.getOperatorId());
      } else {
        logger.error("Fragment {} received OOB message in state {} for operatorId {}. Fragment is not yet set up.",
          this.getHandle().toString(), state.toString(), outOfBandMessage.getOperatorId());
        throw new IllegalStateException("Unable to handle OOB message");
      }
    } else {
      pipeline.workOnOOB(outOfBandMessage);
    }
  }

  @Override
  public String toString() {
    StringBuffer buffer = new StringBuffer();
    buffer.append(name)
      .append(" : ")
      .append("state=" + taskState);
    return buffer.toString();
  }

  /**
   * Facade for external events.
   */
  public class FragmentExecutorListener {

    public void handle(final IncomingDataBatch batch) {
      requestActivate("incoming data batch");
      buffers.batchArrived(batch);
    }

    public void handle(FragmentStreamComplete completion) {
      requestActivate("stream completion");
      buffers.completionArrived(completion);
    }

    public void handle(OutOfBandMessage message) {
      requestActivate("out of band message");
      synchronized (allocatorLock) {
        Optional<ArrowBuf> msgBuffer = message.getIfSingleBuffer();
        if (msgBuffer.isPresent()) {
          try {
            allocator.assertOpen(); // throws exception if allocator is closed
            final ArrowBuf transferredBuf = msgBuffer.get().getReferenceManager()
                    .transferOwnership(msgBuffer.get(), allocator).getTransferredBuffer();
            message = new OutOfBandMessage(message.toProtoMessage(), transferredBuf);
            msgBuffer = Optional.of(transferredBuf);
          } catch (Exception e) {
            logger.error("Error while transferring OOBMessage buffer to the fragment allocator", e);
            return; // Fragment will not be able to handle the buffer.
          }
        }
        final AutoCloseable closeable = msgBuffer.map(AutoCloseable.class::cast).orElse(() -> {});

        final OutOfBandMessage finalMessage = message;
        if (message.isShrinkMemoryRequest()) {
          handleShrinkMemoryRequest(message);
        } else {
          workQueue.put(() -> {
            try {
              handleOOBMessage(finalMessage);
            } catch (IllegalStateException e) {
              logger.warn("Failure while handling OOB message. {}", finalMessage, e);
              throw e;
            } catch (Exception e) {
              //propagate the exception
              logger.warn("Failure while handling OOB message. {}", finalMessage, e);
              throw new IllegalStateException(e);
            } finally {
              try {
                closeable.close();
              } catch (Exception e) {
                logger.error("Error while closing OOBMessage ref", e);
              }
            }
          }, closeable);
        }
      }
    }

    public void activate() {
      requestActivate("activate message from foreman");
    }

    public void cancel() {
      logger.info("Cancellation requested for fragment {}.",
        QueryIdHelper.getQueryIdentifier(fragment.getHandle()));
      requestActivate("cancel message from foreman");
      requestCancellation();
    }

    public FragmentHandle getHandle() {
      return fragment.getHandle();
    }

    @VisibleForTesting
    // TO BE USED ONLY FROM TEST CLASSES
    void overrideIsSetup(boolean isSetupIn) {
      isSetup = isSetupIn;
    }

    @VisibleForTesting
    // TO BE USED ONLY FROM TEST CLASSES
    void overridePipeline(Pipeline inPipeline) {
      pipeline = inPipeline;
    }
  }

  /**
   * Facade to expose this as an AsyncTask but protect other uses of APIs.
   */
  private class AsyncTaskImpl implements AsyncTask {

    @Override
    public void run() {
      FragmentExecutor.this.run();
    }

    @Override
    public void postRunUpdate() {
      FragmentExecutor.this.postRunUpdate();
    }

    @Override
    public void refreshState() {
      FragmentExecutor.this.refreshState();
    }

    @Override
    public State getState() {
      return FragmentExecutor.this.getState();
    }

    @Override
    public void updateSleepDuration(long duration) {
      stats.setSleepingDuration(duration);
    }

    @Override
    public void updateBlockedOnDownstreamDuration(long duration) {
      stats.setBlockedOnDownstreamDuration(duration);
    }

    @Override
    public void updateBlockedOnUpstreamDuration(long duration) {
      stats.setBlockedOnUpstreamDuration(duration);
    }

    @Override
    public void updateBlockedOnMemoryDuration(long duration) {
      stats.setBlockedOnMemoryDuration(duration);
      memoryArbiter.removeFromBlocked(FragmentExecutor.this);
    }

    @Override
    public void addBlockedOnSharedResourceDuration(SharedResourceType resource, long duration) {
      stats.addBlockedOnSharedResourceDuration(resource, duration);
    }

    @Override
    public SharedResourceType getFirstBlockedResource() {
      return FragmentExecutor.this.sharedResources.getFirstBlockedResource("pipeline");
    }

    @Override
    public void setWakeupCallback(AvailabilityCallback callback) {
      FragmentExecutor.this.sharedResources.setNextCallback(callback);
    }

    @Override
    public void setTaskDescriptor(TaskDescriptor descriptor) {
      taskDescriptor = descriptor;
    }

    @Override
    public String toString() {
      return QueryIdHelper.getQueryIdentifier(FragmentExecutor.this.fragment.getHandle());
    }
  }
}
