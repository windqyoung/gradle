/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.internal.tasks;

import com.google.common.collect.Maps;
import org.gradle.api.Transformer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection;
import org.gradle.api.internal.file.collections.FileCollectionResolveContext;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Callable;

public class DefaultSourceSetOutput extends CompositeFileCollection implements SourceSetOutput {
    private final DefaultConfigurableFileCollection outputDirectories;
    private Object resourcesDir;
    private Object classesDir;
    private final DefaultConfigurableFileCollection dirs;
    private final Map<SourceDirectorySet, Object> classesDirsPerSourceDirectorySet;
    private final DefaultConfigurableFileCollection classesDirs;
    private final FileResolver fileResolver;

    public DefaultSourceSetOutput(String sourceSetDisplayName, final FileResolver fileResolver, TaskResolver taskResolver) {
        this.fileResolver = fileResolver;
        String displayName = sourceSetDisplayName + " classes";
        outputDirectories = new DefaultConfigurableFileCollection(displayName, fileResolver, taskResolver, new Callable() {
            public Object call() throws Exception {
                return getClassesDirs();
            }
        }, new Callable() {
            public Object call() throws Exception {
                return getResourcesDir();
            }
        });

        dirs = new DefaultConfigurableFileCollection("dirs", fileResolver, taskResolver);

        classesDirsPerSourceDirectorySet = Maps.newLinkedHashMap();
        classesDirs = new DefaultConfigurableFileCollection("classesDirs", fileResolver, taskResolver, new Callable() {
            @Override
            public Object call() throws Exception {
                if (classesDir!=null) {
                    return Collections.singleton(fileResolver.resolve(classesDir));
                }
                return CollectionUtils.collect(classesDirsPerSourceDirectorySet.entrySet(), new LinkedHashSet<File>(), new Transformer<File, Map.Entry<SourceDirectorySet, Object>>() {
                    @Override
                    public File transform(Map.Entry<SourceDirectorySet, Object> entry) {
                        return fileResolver.resolve(entry.getValue());
                    }
                });
            }
        });
        // TODO: This should be more specific to just the tasks that create the class files?
        classesDirs.builtBy(this);
    }

    @Override
    public void visitContents(FileCollectionResolveContext context) {
        context.add(outputDirectories);
    }

    @Override
    public String getDisplayName() {
        return outputDirectories.getDisplayName();
    }

    public File getClassesDir() {
        if (classesDir!=null) {
            return fileResolver.resolve(classesDir);
        }

        if (classesDirsPerSourceDirectorySet.isEmpty()) {
            return null;
        }
        // TODO: log deprecation?
        return fileResolver.resolve(CollectionUtils.first(classesDirsPerSourceDirectorySet.entrySet()).getValue());
    }

    public void setClassesDir(Object classesDir) {
        this.classesDir = classesDir;
    }

    @Override
    public void addClassesDir(SourceDirectorySet sourceDirectorySet, Object classesDir) {
        classesDirsPerSourceDirectorySet.put(sourceDirectorySet, classesDir);
    }

    @Override
    public File getClassesDirFor(SourceDirectorySet sourceDirectorySet) {
        if (classesDir!=null) {
            return fileResolver.resolve(classesDir);
        }
        return fileResolver.resolve(classesDirsPerSourceDirectorySet.get(sourceDirectorySet));
    }

    @Override
    public FileCollection getClassesDirs() {
        return classesDirs;
    }

    public File getResourcesDir() {
        if (resourcesDir == null) {
            return null;
        }
        return fileResolver.resolve(resourcesDir);
    }

    public void setResourcesDir(Object resourcesDir) {
       this.resourcesDir = resourcesDir;
    }

    public void builtBy(Object ... taskPaths) {
        outputDirectories.builtBy(taskPaths);
    }

    public void dir(Object dir) {
        this.dir(Collections.<String, Object>emptyMap(), dir);
    }

    public void dir(Map<String, Object> options, Object dir) {
        this.dirs.from(dir);
        this.outputDirectories.from(dir);

        Object builtBy = options.get("builtBy");
        if (builtBy != null) {
            this.builtBy(builtBy);
            this.dirs.builtBy(builtBy);
        }
    }

    public FileCollection getDirs() {
        return dirs;
    }
}
