/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.progress;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.internal.Cast;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.GradleThread;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.operations.BuildOperation;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.BuildOperationQueueFactory;
import org.gradle.internal.operations.BuildOperationQueueFailure;
import org.gradle.internal.operations.BuildOperationWorker;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.operations.MultipleBuildOperationFailures;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.time.TimeProvider;
import org.gradle.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

//TODO move to base-services once the ProgressLogger dependency is removed
public class DefaultBuildOperationExecutor implements BuildOperationExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildOperationExecutor.class);
    private static final String LINE_SEPARATOR = SystemProperties.getInstance().getLineSeparator();

    private final BuildOperationListener listener;
    private final TimeProvider timeProvider;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final BuildOperationQueueFactory buildOperationQueueFactory;
    private final StoppableExecutor fixedSizePool;

    private final AtomicLong nextId = new AtomicLong();
    private final ThreadLocal<BuildOperationState> currentOperation = new ThreadLocal<BuildOperationState>();
    private final Set<Object> runningOperationIds = Collections.synchronizedSet(new HashSet<Object>());

    public DefaultBuildOperationExecutor(BuildOperationListener listener, TimeProvider timeProvider, ProgressLoggerFactory progressLoggerFactory,
                                         BuildOperationQueueFactory buildOperationQueueFactory, ExecutorFactory executorFactory, int maxWorkerCount) {
        this.listener = listener;
        this.timeProvider = timeProvider;
        this.progressLoggerFactory = progressLoggerFactory;
        this.buildOperationQueueFactory = buildOperationQueueFactory;
        this.fixedSizePool = executorFactory.create("build operations", maxWorkerCount);
    }


    @Override
    public Object getCurrentOperationId() {
        BuildOperationState current = currentOperation.get();
        if (current == null) {
            throw new IllegalStateException("No operation is currently running.");
        }
        return current.getDescription().getId();
    }

    @Override
    public Object getCurrentParentOperationId() {
        BuildOperationState current = currentOperation.get();
        if (current == null) {
            throw new IllegalStateException("No operation is currently running.");
        }
        return current.getDescription().getParentId();
    }

    @Override
    public void run(RunnableBuildOperation buildOperation) {
        execute(buildOperation);
        maybeStopUnmanagedThreadOperation();
    }

    @Override
    public <T> T call(CallableBuildOperation<T> buildOperation) {
        T result = execute(buildOperation);
        maybeStopUnmanagedThreadOperation();
        return result;
    }

    @Override
    public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction) {
        executeInParallel(new ParentBuildOperationAwareWorker<O>(), schedulingAction);
        maybeStopUnmanagedThreadOperation();
    }

    @Override
    public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction) {
        executeInParallel(worker, schedulingAction);
        maybeStopUnmanagedThreadOperation();
    }

    private <O extends BuildOperation> void executeInParallel(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> queueAction) {
        BuildOperationQueue<O> queue = buildOperationQueueFactory.create(fixedSizePool, worker);

        List<GradleException> failures = Lists.newArrayList();
        try {
            queueAction.execute(queue);
        } catch (Exception e) {
            failures.add(new BuildOperationQueueFailure("There was a failure while populating the build operation queue: " + e.getMessage(), e));
            queue.cancel();
        }

        try {
            queue.waitForCompletion();
        } catch (MultipleBuildOperationFailures e) {
            failures.add(e);
        }

        if (failures.size() == 1) {
            throw failures.get(0);
        } else if (failures.size() > 1) {
            throw new DefaultMultiCauseException(formatMultipleFailureMessage(failures), failures);
        }
    }

    private <T> T execute(BuildOperation buildOperation) {
        BuildOperationDescriptor descriptor = createDescriptor(buildOperation);
        BuildOperationState currentOperation = new BuildOperationState(descriptor, timeProvider.getCurrentTime());
        BuildOperationDescriptor description = currentOperation.getDescription();

        BuildOperationState operationBefore = this.currentOperation.get();

        assertParentRunning("Cannot start operation (%s) as parent operation (%s) has already completed.", description, operationBefore);

        runningOperationIds.add(currentOperation.getDescription().getId());

        this.currentOperation.set(currentOperation);

        try {
            listener.started((BuildOperationInternal) description, new OperationStartEvent(currentOperation.getStartTime()));

            T result = null;
            Throwable failure = null;
            DefaultBuildOperationContext context = new DefaultBuildOperationContext();
            try {
                ProgressLogger progressLogger = maybeStartProgressLogging(currentOperation);

                LOGGER.debug("Build operation '{}' started", description.getDisplayName());
                try {
                    if (buildOperation instanceof RunnableBuildOperation) {
                        ((RunnableBuildOperation) buildOperation).run(context);
                    }
                    if (buildOperation instanceof CallableBuildOperation) {
                        result = Cast.uncheckedCast(((CallableBuildOperation) buildOperation).call(context));
                    }
                } finally {
                    if (progressLogger != null) {
                        progressLogger.completed();
                    }
                }
                assertParentRunning("Parent operation (%2$s) completed before this operation (%1$s).", description, operationBefore);
            } catch (Throwable t) {
                context.failed(t);
                failure = t;
            }

            long endTime = timeProvider.getCurrentTime();
            listener.finished((BuildOperationInternal) description, new OperationResult(currentOperation.getStartTime(), endTime, context.failure, context.result));

            if (failure != null) {
                throw UncheckedException.throwAsUncheckedException(failure, true);
            }

            LOGGER.debug("Build operation '{}' completed", description.getDisplayName());
            return result;
        } finally {
            this.currentOperation.set(operationBefore);
            runningOperationIds.remove(currentOperation.getDescription().getId());
        }
    }

    private BuildOperationDescriptor createDescriptor(BuildOperation buildOperation) {
        OperationIdentifier id = new OperationIdentifier(nextId.getAndIncrement());
        BuildOperationDescriptor.Builder descriptorBuilder = buildOperation.description();
        BuildOperationState current = maybeStartUnmanagedThreadOperation(descriptorBuilder);
        return descriptorBuilder.build(id, current == null ? null : current.getDescription().getId());
    }

    private void assertParentRunning(String message, BuildOperationDescriptor child, BuildOperationState parent) {
        Object parentId = child.getParentId();
        if (parentId != null && !runningOperationIds.contains(parentId)) {
            String parentName = parent != null && parent.getDescription().getId() == parentId ? parent.getDescription().getDisplayName() : parentId.toString();
            throw new IllegalStateException(String.format(message, child.getDisplayName(), parentName));
        }
    }

    @Nullable
    private ProgressLogger maybeStartProgressLogging(BuildOperationState currentOperation) {
        if (providesProgressLogging(currentOperation)) {
            ProgressLogger progressLogger = progressLoggerFactory.newOperation(DefaultBuildOperationExecutor.class);
            progressLogger.setDescription(currentOperation.getDescription().getDisplayName());
            progressLogger.setShortDescription(currentOperation.getDescription().getProgressDisplayName());
            progressLogger.started();
            return progressLogger;
        } else {
            return null;
        }
    }

    private boolean providesProgressLogging(BuildOperationState currentOperation) {
        return currentOperation.getDescription().getProgressDisplayName() != null;
    }

    private BuildOperationState maybeStartUnmanagedThreadOperation(BuildOperationDescriptor.Builder buildOperationDescriptorBuilder) {
        if (!GradleThread.isManaged() && currentOperation.get() == null && (buildOperationDescriptorBuilder == null || !buildOperationDescriptorBuilder.hasParentId())) {
            UnmanagedThreadOperation operation = UnmanagedThreadOperation.create(timeProvider);
            runningOperationIds.add(operation.getDescription().getId());
            currentOperation.set(operation);
            listener.started((BuildOperationInternal) operation.getDescription(), new OperationStartEvent(operation.getStartTime()));
        }
        return currentOperation.get();
    }

    private void maybeStopUnmanagedThreadOperation() {
        BuildOperationState current = currentOperation.get();
        if (current instanceof UnmanagedThreadOperation) {
            try {
                listener.finished((BuildOperationInternal) current.getDescription(), new OperationResult(current.getStartTime(), timeProvider.getCurrentTime(), null, null));
            } finally {
                currentOperation.set(null);
                runningOperationIds.remove(current.getDescription().getId());
            }
        }
    }

    /**
     * Artificially create a running root operation.
     * Main use case is ProjectBuilder, useful for some of our test fixtures too.
     */
    protected void createRunningRootOperation(String displayName) {
        assert currentOperation.get() == null;
        BuildOperationState operation = new BuildOperationState(BuildOperationDescriptor.displayName(displayName).build(new OperationIdentifier(0), null), timeProvider.getCurrentTime());
        runningOperationIds.add(operation.getDescription().getId());
        currentOperation.set(operation);
    }

    private static String formatMultipleFailureMessage(List<GradleException> failures) {
        return StringUtils.join(CollectionUtils.collect(failures, new Transformer<String, GradleException>() {
            @Override
            public String transform(GradleException e) {
                return e.getMessage();
            }
        }), LINE_SEPARATOR + "AND" + LINE_SEPARATOR);
    }

    public void stop() {
        fixedSizePool.stop();
    }

    private static class DefaultBuildOperationContext implements BuildOperationContext {
        Throwable failure;
        Object result;

        @Override
        public void failed(Throwable t) {
            failure = t;
        }

        @Override
        public void setResult(Object result) {
            this.result = result;
        }
    }

    private class ParentBuildOperationAwareWorker<O extends RunnableBuildOperation> implements BuildOperationWorker<O> {

        private Object parentId;

        private ParentBuildOperationAwareWorker() {
            this.parentId = maybeStartUnmanagedThreadOperation(null).getDescription().getId();
        }

        @Override
        public String getDisplayName() {
            return "runnable worker";
        }

        @Override
        public void execute(O buildOperation) {
            DefaultBuildOperationExecutor.this.execute(new ParentAwareBuildOperation<O>(buildOperation, parentId));
        }
    }

    private class ParentAwareBuildOperation<O extends RunnableBuildOperation> implements RunnableBuildOperation {
        private O delegate;
        private Object parentId;

        private ParentAwareBuildOperation(O delegate, Object parentId) {
            this.delegate = delegate;
            this.parentId = parentId;
        }

        @Override
        public void run(BuildOperationContext context) {
            delegate.run(context);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return delegate.description().parentId(parentId);
        }
    }

    private static class BuildOperationState {
        private final BuildOperationDescriptor description;
        private final long startTime;

        private BuildOperationState(BuildOperationDescriptor descriptor, long startTime) {
            this.startTime = startTime;
            this.description = descriptor;
        }

        protected BuildOperationDescriptor getDescription() {
            return description;
        }

        protected long getStartTime() {
            return startTime;
        }
    }

    private static class UnmanagedThreadOperation extends BuildOperationState {

        private static final AtomicLong UNMANAGED_THREAD_OPERATION_COUNTER = new AtomicLong(-1);

        private static UnmanagedThreadOperation create(TimeProvider timeProvider) {
            // TODO:pm Move this to WARN level once we fixed maven-publish, see gradle/gradle#1662
            LOGGER.debug("WARNING No operation is currently running in unmanaged thread: {}", Thread.currentThread().getName());
            OperationIdentifier id = new OperationIdentifier(UNMANAGED_THREAD_OPERATION_COUNTER.getAndDecrement());
            String displayName = "Unmanaged thread operation #" + id + " (" + Thread.currentThread().getName() + ')';
            return new UnmanagedThreadOperation(BuildOperationDescriptor.displayName(displayName).build(id, null), null, timeProvider.getCurrentTime());
        }

        private UnmanagedThreadOperation(BuildOperationDescriptor descriptor, BuildOperationState parent, long startTime) {
            super(descriptor, startTime);
        }
    }
}
