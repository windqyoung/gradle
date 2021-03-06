/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.execution.taskgraph;

import org.gradle.api.Action;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.internal.time.Timer;
import org.gradle.internal.time.Timers;
import org.gradle.internal.work.WorkerLeaseRegistry.WorkerLease;
import org.gradle.internal.work.WorkerLeaseService;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.gradle.internal.time.Clock.prettyTime;

class DefaultTaskPlanExecutor implements TaskPlanExecutor {
    private static final Logger LOGGER = Logging.getLogger(DefaultTaskPlanExecutor.class);
    private final int executorCount;
    private final ExecutorFactory executorFactory;
    private final WorkerLeaseService workerLeaseService;

    public DefaultTaskPlanExecutor(int numberOfParallelExecutors, ExecutorFactory executorFactory, WorkerLeaseService workerLeaseService) {
        this.executorFactory = executorFactory;
        if (numberOfParallelExecutors < 1) {
            throw new IllegalArgumentException("Not a valid number of parallel executors: " + numberOfParallelExecutors);
        }

        this.executorCount = numberOfParallelExecutors;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void process(TaskExecutionPlan taskExecutionPlan, Action<? super TaskInternal> taskWorker) {
        StoppableExecutor executor = executorFactory.create("Task worker");
        try {
            WorkerLease parentWorkerLease = workerLeaseService.getCurrentWorkerLease();
            startAdditionalWorkers(taskExecutionPlan, taskWorker, executor, parentWorkerLease);
            taskWorker(taskExecutionPlan, taskWorker, parentWorkerLease).run();
            taskExecutionPlan.awaitCompletion();
        } finally {
            executor.stop();
        }
    }

    private void startAdditionalWorkers(TaskExecutionPlan taskExecutionPlan, Action<? super TaskInternal> taskWorker, Executor executor, WorkerLease parentWorkerLease) {
        LOGGER.debug("Using {} parallel executor threads", executorCount);

        for (int i = 1; i < executorCount; i++) {
            Runnable worker = taskWorker(taskExecutionPlan, taskWorker, parentWorkerLease);
            executor.execute(worker);
        }
    }

    private Runnable taskWorker(TaskExecutionPlan taskExecutionPlan, Action<? super TaskInternal> taskWorker, WorkerLease parentWorkerLease) {
        return new TaskExecutorWorker(taskExecutionPlan, taskWorker, parentWorkerLease);
    }

    private static class TaskExecutorWorker implements Runnable {
        private final TaskExecutionPlan taskExecutionPlan;
        private final Action<? super TaskInternal> taskWorker;
        private final WorkerLease parentWorkerLease;

        private TaskExecutorWorker(TaskExecutionPlan taskExecutionPlan, Action<? super TaskInternal> taskWorker, WorkerLease parentWorkerLease) {
            this.taskExecutionPlan = taskExecutionPlan;
            this.taskWorker = taskWorker;
            this.parentWorkerLease = parentWorkerLease;
        }

        public void run() {
            final AtomicLong busy = new AtomicLong(0);
            Timer totalTimer = Timers.startTimer();
            final Timer taskTimer = Timers.startTimer();

            boolean moreTasksToExecute = true;
            while (moreTasksToExecute) {
                moreTasksToExecute = taskExecutionPlan.executeWithTask(parentWorkerLease, new Action<TaskInfo>() {
                    @Override
                    public void execute(TaskInfo task) {
                        final String taskPath = task.getTask().getPath();
                        LOGGER.info("{} ({}) started.", taskPath, Thread.currentThread());
                        taskTimer.reset();
                        processTask(task);
                        long taskDuration = taskTimer.getElapsedMillis();
                        busy.addAndGet(taskDuration);
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info("{} ({}) completed. Took {}.", taskPath, Thread.currentThread(), prettyTime(taskDuration));
                        }
                    }
                });
            }

            long total = totalTimer.getElapsedMillis();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Task worker [{}] finished, busy: {}, idle: {}", Thread.currentThread(), prettyTime(busy.get()), prettyTime(total - busy.get()));
            }
        }

        private void processTask(TaskInfo taskInfo) {
            try {
                taskWorker.execute(taskInfo.getTask());
            } catch (Throwable e) {
                taskInfo.setExecutionFailure(e);
            } finally {
                taskExecutionPlan.taskComplete(taskInfo);
            }
        }
    }
}
