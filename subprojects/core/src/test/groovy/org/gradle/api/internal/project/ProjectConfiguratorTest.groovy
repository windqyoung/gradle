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

package org.gradle.api.internal.project

import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.tasks.TaskContainerInternal
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.internal.progress.TestBuildOperationExecutor
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.service.scopes.ServiceRegistryFactory
import org.gradle.model.internal.core.MutableModelNode
import org.gradle.model.internal.registry.ModelRegistry
import org.gradle.util.Path
import spock.lang.Specification

class ProjectConfiguratorTest extends Specification {

    def "forwards project details to configure project operation descriptor"() {
        given:
        def buildOperationExecutor = new TestBuildOperationExecutor()
        def projectRegistry = Mock(ProjectRegistry)

        def root = setupProject(buildOperationExecutor, projectRegistry, "root", null)
        def child = setupProject(buildOperationExecutor, projectRegistry, "child", root)

        projectRegistry.getAllProjects(_) >> [root, child]

        when:
        root.allprojects { }

        then:
        buildOperationExecutor.operations.size() == 3
        ConfigureProjectBuildOperationDescriptor configureParentDescriptor = buildOperationExecutor.operations[1].operationDescriptor
        ConfigureProjectBuildOperationDescriptor configureChildDescriptor = buildOperationExecutor.operations[2].operationDescriptor

        and:
        configureParentDescriptor.name == 'root'
        configureParentDescriptor.projectDir == new File('rootDir')
        configureParentDescriptor.buildFile == new File('root.gradle')
        configureParentDescriptor.path == Path.path(':')

        and:
        configureChildDescriptor.name == 'child'
        configureChildDescriptor.projectDir == new File('childDir')
        configureChildDescriptor.buildFile == new File('child.gradle')
        configureChildDescriptor.path == Path.path(':child')
    }

    def "creates only one project operation descriptor per project"() {
        def buildOperationExecutor = new TestBuildOperationExecutor()
        def projectRegistry = Mock(ProjectRegistry)
        def root = setupProject(buildOperationExecutor, projectRegistry, "root", null)
        projectRegistry.getAllProjects(_) >> [root]

        when:
        def access1 = root.getConfigureProjectBuildOperationDescriptor()
        def access2 =root.getConfigureProjectBuildOperationDescriptor()

        then:
        access1.is(access2)

        when:
        root.allprojects { }

        then:
        buildOperationExecutor.operations[1].operationDescriptor.is(access2)
    }

    private setupProject(TestBuildOperationExecutor executor, ProjectRegistry projectRegistry, String name, ProjectInternal parent) {
        def gradleInternal = Mock(GradleInternal)
        gradleInternal.getIdentityPath() >> Path.ROOT
        def serviceRegistryFactory = Mock(ServiceRegistryFactory)
        def services = Mock(ServiceRegistry)
        def modelRegistry = Mock(ModelRegistry)
        serviceRegistryFactory.createFor(_) >> services
        services.newInstance(TaskContainerInternal) >> Mock(TaskContainerInternal)
        services.get(ModelRegistry) >> modelRegistry
        modelRegistry.getRoot() >> Mock(MutableModelNode)
        def projectConfigurator = new BuildOperationProjectConfigurator(executor)
        def scriptSource = Mock(ScriptSource)
        def scriptHandler = Mock(ScriptHandler)
        scriptHandler.getSourceFile() >> new File("${name}.gradle")
        def project = Spy(DefaultProject, constructorArgs: [name, parent, new File("${name}Dir"), scriptSource, gradleInternal, serviceRegistryFactory, null, null])
        project.getBuildscript() >> scriptHandler
        project.getProjectConfigurator() >> projectConfigurator
        project.getProjectRegistry() >> projectRegistry

        return project
    }

}
