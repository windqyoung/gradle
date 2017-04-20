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

package org.gradle.internal.work;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.specs.Spec;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.util.CollectionUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultAsyncWorkTracker implements AsyncWorkTracker {
    private final ListMultimap<Object, AsyncWorkCompletion> items = ArrayListMultimap.create();
    private final Set<Object> waiting = Sets.newHashSet();
    private final ReentrantLock lock = new ReentrantLock();
    private final ProjectLeaseRegistry projectLeaseRegistry;

    public DefaultAsyncWorkTracker(ProjectLeaseRegistry projectLeaseRegistry) {
        this.projectLeaseRegistry = projectLeaseRegistry;
    }

    @Override
    public void registerWork(Object operationId, AsyncWorkCompletion workCompletion) {
        lock.lock();
        try {
            if (waiting.contains(operationId)) {
                throw new IllegalStateException("Another thread is currently waiting on the completion of work for the provided operation");
            }
            items.put(operationId, workCompletion);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void waitForCompletion(Object operationId) {
        final List<Throwable> failures = Lists.newArrayList();
        final List<AsyncWorkCompletion> workItems;
        lock.lock();
        try {
            workItems = ImmutableList.copyOf(items.get(operationId));
            items.removeAll(operationId);
            startWaiting(operationId);
        } finally {
            lock.unlock();
        }

        try {
            if (workItems.size() > 0) {
                boolean workInProgress = CollectionUtils.any(workItems, new Spec<AsyncWorkCompletion>() {
                    @Override
                    public boolean isSatisfiedBy(AsyncWorkCompletion workCompletion) {
                        return !workCompletion.isComplete();
                    }
                });
                if (workInProgress) {
                    projectLeaseRegistry.withoutProjectLock(new Runnable() {
                        @Override
                        public void run() {
                            for (AsyncWorkCompletion item : workItems) {
                                try {
                                    item.waitForCompletion();
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            }

                            if (failures.size() > 0) {
                                throw new DefaultMultiCauseException("There were failures while executing asynchronous work:", failures);
                            }
                        }
                    });
                }
            }
        } finally {
            stopWaiting(operationId);
        }
    }

    private void startWaiting(Object operationId) {
        lock.lock();
        try {
            waiting.add(operationId);
        } finally {
            lock.unlock();
        }
    }

    private void stopWaiting(Object operationId) {
        lock.lock();
        try {
            waiting.remove(operationId);
        } finally {
            lock.unlock();
        }
    }
}
