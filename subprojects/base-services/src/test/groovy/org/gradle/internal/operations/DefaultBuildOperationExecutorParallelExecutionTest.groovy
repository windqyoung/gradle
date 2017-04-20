/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.internal.operations

import org.gradle.api.GradleException
import org.gradle.internal.concurrent.DefaultExecutorFactory
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.internal.exceptions.DefaultMultiCauseException
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.progress.BuildOperationDescriptor
import org.gradle.internal.progress.BuildOperationListener
import org.gradle.internal.progress.DefaultBuildOperationExecutor
import org.gradle.internal.resources.DefaultResourceLockCoordinationService
import org.gradle.internal.time.TimeProvider
import org.gradle.internal.work.DefaultWorkerLeaseService
import org.gradle.internal.work.WorkerLeaseRegistry
import org.gradle.test.fixtures.concurrent.ConcurrentSpec
import spock.lang.Unroll

class DefaultBuildOperationExecutorParallelExecutionTest extends ConcurrentSpec {

    WorkerLeaseRegistry workerRegistry
    BuildOperationExecutor buildOperationExecutor
    WorkerLeaseRegistry.WorkerLeaseCompletion outerOperationCompletion
    WorkerLeaseRegistry.WorkerLease outerOperation

    def setupBuildOperationExecutor(int maxThreads) {
        workerRegistry = new DefaultWorkerLeaseService(new DefaultResourceLockCoordinationService(), true, maxThreads)
        buildOperationExecutor = new DefaultBuildOperationExecutor(
            Mock(BuildOperationListener), Mock(TimeProvider), Mock(ProgressLoggerFactory),
            new DefaultBuildOperationQueueFactory(workerRegistry), new DefaultExecutorFactory(), maxThreads)
        outerOperationCompletion = workerRegistry.getWorkerLease().start()
        outerOperation = workerRegistry.getCurrentWorkerLease()
    }

    def "cleanup"() {
        if (outerOperationCompletion) {
            outerOperationCompletion.leaseFinish()
            workerRegistry.stop()
        }
    }

    @Unroll
    def "all #operations operations run to completion when using #maxThreads threads"() {
        given:
        setupBuildOperationExecutor(maxThreads)
        def operation = Mock(DefaultBuildOperationQueueTest.TestBuildOperation)
        def worker = new DefaultBuildOperationQueueTest.SimpleWorker()

        when:
        buildOperationExecutor.runAll(worker, { queue ->
            operations.times { queue.add(operation) }
        })

        then:
        operations * operation.run(_)

        where:
        // Where operations < maxThreads
        // operations = maxThreads
        // operations >> maxThreads
        operations | maxThreads
        0          | 1
        1          | 1
        20         | 1
        1          | 4
        4          | 4
        20         | 4
    }

    @Unroll
    def "all work run to completion for multiple queues when using multiple threads #maxThreads"() {
        given:
        def amountOfWork = 10
        setupBuildOperationExecutor(maxThreads)
        def worker = new DefaultBuildOperationQueueTest.SimpleWorker()
        def numberOfQueues = 5
        def operations = [
            Mock(DefaultBuildOperationQueueTest.TestBuildOperation),
            Mock(DefaultBuildOperationQueueTest.TestBuildOperation),
            Mock(DefaultBuildOperationQueueTest.TestBuildOperation),
            Mock(DefaultBuildOperationQueueTest.TestBuildOperation),
            Mock(DefaultBuildOperationQueueTest.TestBuildOperation),
        ]

        when:
        async {
            numberOfQueues.times { i ->
                start {
                    def cl = outerOperation.startChild()
                    buildOperationExecutor.runAll(worker, { queue ->
                        amountOfWork.times {
                            queue.add(operations[i])
                        }
                    })
                    cl.leaseFinish()
                }
            }
        }

        then:
        operations.each { operation ->
            amountOfWork * operation.run(_)
        }

        where:
        maxThreads << [1, 4, 10]
    }

    def "failures in one queue do not cause failures in other queues"() {
        given:
        def amountOfWork = 10
        def maxThreads = 4
        setupBuildOperationExecutor(maxThreads)
        def success = Stub(DefaultBuildOperationQueueTest.TestBuildOperation)
        def failure = Stub(DefaultBuildOperationQueueTest.TestBuildOperation) {
            run(_) >> { throw new Exception() }
        }
        def worker = new DefaultBuildOperationQueueTest.SimpleWorker()
        boolean successfulQueueCompleted = false
        boolean exceptionInFailureQueue = false

        when:
        async {
            // Successful queue
            start {
                def cl = outerOperation.startChild()
                buildOperationExecutor.runAll(worker, { queue ->
                    amountOfWork.times {
                        queue.add(success)
                    }
                })
                cl.leaseFinish()
                successfulQueueCompleted = true
            }
            // Failure queue
            start {
                def cl = outerOperation.startChild()
                try {
                    buildOperationExecutor.runAll(worker, { queue ->
                        amountOfWork.times {
                            queue.add(failure)
                        }
                    })
                } catch (MultipleBuildOperationFailures e) {
                    exceptionInFailureQueue = true
                } finally {
                    cl.leaseFinish()
                }
            }
        }

        then:
        exceptionInFailureQueue

        and:
        successfulQueueCompleted
    }

    def "multiple failures get reported"() {
        given:
        def threadCount = 4
        setupBuildOperationExecutor(threadCount)
        def worker = new DefaultBuildOperationQueueTest.SimpleWorker()
        def operation = Stub(DefaultBuildOperationQueueTest.TestBuildOperation) {
            run(_) >> {
                throw new GradleException("always fails")
            }
        }

        when:
        buildOperationExecutor.runAll(worker, { queue ->
            threadCount.times { queue.add(operation) }
        })

        then:
        def e = thrown(MultipleBuildOperationFailures)
        e instanceof MultipleBuildOperationFailures
        ((MultipleBuildOperationFailures) e).getCauses().size() == 4
    }

    def "operations are canceled when the generator fails"() {
        def buildQueue = Mock(BuildOperationQueue)
        def buildOperationQueueFactory = Mock(BuildOperationQueueFactory) {
            create(_, _) >> { buildQueue }
        }

        def buildOperationExecutor = new DefaultBuildOperationExecutor(Mock(BuildOperationListener), Mock(TimeProvider), Mock(ProgressLoggerFactory),
            buildOperationQueueFactory, Stub(ExecutorFactory), 1)
        def worker = Stub(BuildOperationWorker)
        def operation = Mock(DefaultBuildOperationQueueTest.TestBuildOperation)

        when:
        buildOperationExecutor.runAll(worker, { queue ->
            4.times { queue.add(operation) }
            throw new Exception("Failure in generator")
        })

        then:
        thrown(BuildOperationQueueFailure)

        and:
        4 * buildQueue.add(_)
        1 * buildQueue.cancel()
    }

    def "multi-cause error when there are failures both enqueueing and running operations"() {
        def operationFailures = [new Exception("failed operation 1"), new Exception("failed operation 2")]
        def buildQueue = Mock(BuildOperationQueue) {
            waitForCompletion() >> { throw new MultipleBuildOperationFailures("operations failed", operationFailures, null) }
        }
        def buildOperationQueueFactory = Mock(BuildOperationQueueFactory) {
            create(_, _) >> { buildQueue }
        }
        def buildOperationExecutor = new DefaultBuildOperationExecutor(
            Mock(BuildOperationListener), Mock(TimeProvider), Mock(ProgressLoggerFactory),
            buildOperationQueueFactory, Stub(ExecutorFactory), 1)
        def worker = Stub(BuildOperationWorker)
        def operation = Mock(DefaultBuildOperationQueueTest.TestBuildOperation)

        when:
        buildOperationExecutor.runAll(worker, { queue ->
            4.times { queue.add(operation) }
            throw new Exception("Failure in generator")
        })

        then:
        def e = thrown(DefaultMultiCauseException)
        e.message.startsWith("There was a failure while populating the build operation queue:")
        e.message.contains("operations failed")
        e.message.contains("failed operation 1")
        e.message.contains("failed operation 2")
        e.causes.size() == 2
        e.causes.any { it instanceof BuildOperationQueueFailure && it.message.startsWith("There was a failure while populating the build operation queue:") }
        e.causes.any { it instanceof MultipleBuildOperationFailures && it.causes.collect { it.message }.sort() == ["failed operation 1", "failed operation 2"] }

        and:
        4 * buildQueue.add(_)
        1 * buildQueue.cancel()
    }

    def "can provide only runnable build operations to the processor"() {
        given:
        setupBuildOperationExecutor(2)
        def operation = Spy(DefaultBuildOperationQueueTest.Success)

        when:
        buildOperationExecutor.runAll({ queue ->
            5.times { queue.add(operation) }
        })

        then:
        5 * operation.run(_)
    }

    def "can be used on unmanaged threads"() {
        given:
        setupBuildOperationExecutor(2)
        def operation = Spy(DefaultBuildOperationQueueTest.Success)

        when:
        async {
            buildOperationExecutor.runAll({ queue ->
                5.times { queue.add(operation) }
            })
        }

        then:
        5 * operation.run(_)
    }

    def "unmanaged thread operation is started and stopped when created by run"() {
        given:
        setupBuildOperationExecutor(2)
        def running = buildOperationExecutor.runningOperationIds
        def operationState

        when:
        async {
            buildOperationExecutor.run(new DefaultBuildOperationQueueTest.TestBuildOperation() {
                void run(BuildOperationContext context) {
                    operationState = buildOperationExecutor.currentOperation.get()
                    def parentOperationId = operationState.description.parentId
                    assert running.contains(operationState.description.id)
                    assert running.contains(parentOperationId)
                    assert parentOperationId.id < 0
                }
            })
        }

        then:
        operationState != null
        !running.contains(operationState.description.id)
        !running.contains(operationState.description.parentId)
    }

    def "unmanaged thread operation is started and stopped when created by call"() {
        given:
        setupBuildOperationExecutor(2)
        def running = buildOperationExecutor.runningOperationIds
        def operationState

        when:
        async {
            buildOperationExecutor.call(new CallableBuildOperation() {
                Object call(BuildOperationContext context) {
                    operationState = buildOperationExecutor.currentOperation.get()
                    def parentOperationId = operationState.description.parentId
                    assert running.contains(operationState.description.id)
                    assert running.contains(parentOperationId)
                    assert parentOperationId.id < 0
                    return null
                }

                BuildOperationDescriptor.Builder description() {
                    BuildOperationDescriptor.displayName("test operation")
                }
            })
        }

        then:
        operationState != null
        !running.contains(operationState.description.id)
        !running.contains(operationState.description.parentId)
    }

    def "a single unmanaged thread operation is started and stopped when created by runAll"() {
        given:
        setupBuildOperationExecutor(2)
        def running = buildOperationExecutor.runningOperationIds
        def operationState
        def parentOperationId

        when:
        async {
            buildOperationExecutor.runAll({ queue ->
                5.times {
                    queue.add(new DefaultBuildOperationQueueTest.TestBuildOperation() {
                        void run(BuildOperationContext context) {
                            operationState = buildOperationExecutor.currentOperation.get()
                            parentOperationId = operationState.description.parentId
                            assert parentOperationId == null || parentOperationId == operationState.description.parentId
                            parentOperationId = operationState.description.parentId
                            assert running.contains(operationState.description.id)
                            assert running.contains(parentOperationId)
                            assert operationState.description.parentId.id < 0
                        }
                    })
                }
            })
        }

        then:
        operationState != null
        !running.contains(operationState.description.id)
        !running.contains(operationState.description.parentId)
    }
}
