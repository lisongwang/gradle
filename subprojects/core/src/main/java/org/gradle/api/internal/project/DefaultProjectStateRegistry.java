/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.api.internal.project;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.internal.Factories;
import org.gradle.internal.Factory;
import org.gradle.internal.Pair;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.model.CalculatedModelValue;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public class DefaultProjectStateRegistry implements ProjectStateRegistry {
    private final WorkerLeaseService workerLeaseService;
    private final Object lock = new Object();
    private final Map<Path, ProjectStateImpl> projectsByPath = Maps.newLinkedHashMap();
    private final Map<ProjectComponentIdentifier, ProjectStateImpl> projectsById = Maps.newLinkedHashMap();
    private final Map<Pair<BuildIdentifier, Path>, ProjectStateImpl> projectsByCompId = Maps.newLinkedHashMap();
    private final AtomicReference<Thread> ownerOfAllProjects = new AtomicReference<>();

    public DefaultProjectStateRegistry(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void registerProjects(BuildState owner) {
        Set<DefaultProjectDescriptor> allProjects = owner.getLoadedSettings().getProjectRegistry().getAllProjects();
        synchronized (lock) {
            for (DefaultProjectDescriptor descriptor : allProjects) {
                addProject(owner, descriptor);
            }
        }
    }

    @Override
    public void registerProject(BuildState owner, DefaultProjectDescriptor projectDescriptor) {
        synchronized (lock) {
            addProject(owner, projectDescriptor);
        }
    }

    private void addProject(BuildState owner, DefaultProjectDescriptor descriptor) {
        Path projectPath = descriptor.path();
        Path identityPath = owner.getIdentityPathForProject(projectPath);
        ProjectComponentIdentifier projectIdentifier = owner.getIdentifierForProject(projectPath);
        ProjectStateImpl projectState = new ProjectStateImpl(owner, identityPath, projectPath, descriptor.getName(), projectIdentifier);
        projectsByPath.put(identityPath, projectState);
        projectsById.put(projectIdentifier, projectState);
        projectsByCompId.put(Pair.of(owner.getBuildIdentifier(), projectPath), projectState);
    }

    @Override
    public Collection<ProjectStateImpl> getAllProjects() {
        synchronized (lock) {
            return projectsByPath.values();
        }
    }

    // TODO - can kill this method, as the caller can use ProjectInternal.getMutationState() instead
    @Override
    public ProjectState stateFor(Project project) {
        synchronized (lock) {
            ProjectStateImpl projectState = projectsByPath.get(((ProjectInternal) project).getIdentityPath());
            if (projectState == null) {
                throw new IllegalArgumentException("Could not find state for " + project);
            }
            return projectState;
        }
    }

    @Override
    public ProjectState stateFor(ProjectComponentIdentifier identifier) {
        synchronized (lock) {
            ProjectStateImpl projectState = projectsById.get(identifier);
            if (projectState == null) {
                throw new IllegalArgumentException(identifier.getDisplayName() + " not found.");
            }
            return projectState;
        }
    }

    @Override
    public ProjectState stateFor(BuildIdentifier buildIdentifier, Path projectPath) {
        synchronized (lock) {
            ProjectStateImpl projectState = projectsByCompId.get(Pair.of(buildIdentifier, projectPath));
            if (projectState == null) {
                throw new IllegalArgumentException(buildIdentifier + " project " + projectPath + " not found.");
            }
            return projectState;
        }
    }

    @Override
    public void withMutableStateOfAllProjects(Runnable runnable) {
        withMutableStateOfAllProjects(Factories.toFactory(runnable));
    }

    @Override
    public <T> T withMutableStateOfAllProjects(Factory<T> factory) {
        if (!ownerOfAllProjects.compareAndSet(null, Thread.currentThread())) {
            throw new IllegalStateException(String.format("Another thread (%s) currently holds the state lock for all projects.", ownerOfAllProjects));
        }
        try {
            return factory.create();
        } finally {
            ownerOfAllProjects.set(null);
        }
    }

    private class ProjectStateImpl implements ProjectState {
        private final Path projectPath;
        private final String projectName;
        private final ProjectComponentIdentifier identifier;
        private final BuildState owner;
        private final Path identityPath;
        private final ResourceLock projectLock;
        private ProjectInternal project;

        ProjectStateImpl(BuildState owner, Path identityPath, Path projectPath, String projectName, ProjectComponentIdentifier identifier) {
            this.owner = owner;
            this.identityPath = identityPath;
            this.projectPath = projectPath;
            this.projectName = projectName;
            this.identifier = identifier;
            this.projectLock = workerLeaseService.getProjectLock(owner.getIdentityPath(), identityPath);
        }

        @Override
        public String toString() {
            return identifier.getDisplayName();
        }

        @Override
        public BuildState getOwner() {
            return owner;
        }

        @Nullable
        @Override
        public ProjectState getParent() {
            return identityPath.getParent() == null ? null : projectsByPath.get(identityPath.getParent());
        }

        @Override
        public String getName() {
            return projectName;
        }

        @Override
        public Path getIdentityPath() {
            return identityPath;
        }

        @Override
        public Path getProjectPath() {
            return projectPath;
        }

        @Override
        public void attachMutableModel(ProjectInternal project) {
            synchronized (this) {
                if (this.project != null) {
                    throw new IllegalStateException(String.format("The project object for project %s has already been attached.", getIdentityPath()));
                }
                this.project = project;
            }
        }

        @Override
        public ProjectInternal getMutableModel() {
            synchronized (this) {
                if (project == null) {
                    throw new IllegalStateException(String.format("The project object for project %s has not been attached yet.", getIdentityPath()));
                }
                return project;
            }
        }

        @Override
        public ProjectComponentIdentifier getComponentIdentifier() {
            return identifier;
        }

        @Override
        public ResourceLock getAccessLock() {
            return projectLock;
        }

        @Override
        public void withMutableState(Runnable action) {
            withMutableState(Factories.toFactory(action));
        }

        @Override
        public <T> T withMutableState(final Factory<? extends T> factory) {
            Thread currentOwner = ownerOfAllProjects.get();
            if (currentOwner != null) {
                if (currentOwner == Thread.currentThread()) {
                    return factory.create();
                }
                throw new IllegalStateException(String.format("Cannot acquire state lock for %s as another thread (%s) currently holds the state lock for all projects.", project, currentOwner));
            }

            Collection<? extends ResourceLock> currentLocks = workerLeaseService.getCurrentProjectLocks();
            if (currentLocks.contains(projectLock)) {
                // if we already hold the project lock for this project
                if (currentLocks.size() == 1) {
                    // the lock for this project is the only lock we hold
                    return factory.create();
                } else {
                    currentLocks = Lists.newArrayList(currentLocks);
                    currentLocks.remove(projectLock);
                    // release any other project locks we might happen to hold
                    return workerLeaseService.withoutLocks(currentLocks, factory);
                }
            } else {
                // we don't currently hold the project lock
                if (!currentLocks.isEmpty()) {
                    // we hold other project locks that we should release first
                    return workerLeaseService.withoutLocks(currentLocks, new Factory<T>() {
                        @Override
                        public T create() {
                            return withProjectLock(projectLock, factory);
                        }
                    });
                } else {
                    // we just need to get the lock for this project
                    return withProjectLock(projectLock, factory);
                }
            }
        }

        private <T> T withProjectLock(ResourceLock projectLock, final Factory<? extends T> factory) {
            return workerLeaseService.withLocks(Collections.singleton(projectLock), factory);
        }

        @Override
        public boolean hasMutableState() {
            return ownerOfAllProjects.get() == Thread.currentThread() || workerLeaseService.getCurrentProjectLocks().contains(projectLock);
        }

        @Override
        public <T> CalculatedModelValue<T> newCalculatedValue(@Nullable T initialValue) {
            return new CalculatedModelValueImpl<>(this, workerLeaseService, initialValue);
        }
    }

    private static class CalculatedModelValueImpl<T> implements CalculatedModelValue<T> {
        private final WorkerLeaseService workerLeaseService;
        private final ProjectStateImpl owner;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile T value;

        public CalculatedModelValueImpl(ProjectStateImpl owner, WorkerLeaseService workerLeaseService, @Nullable T initialValue) {
            this.workerLeaseService = workerLeaseService;
            this.value = initialValue;
            this.owner = owner;
        }

        @Override
        public T get() throws IllegalStateException {
            T currentValue = getOrNull();
            if (currentValue == null) {
                throw new IllegalStateException("No value is available for " + owner);
            }
            return currentValue;
        }

        @Override
        public T getOrNull() {
            // Grab the current value, ignore updates that may be happening
            return value;
        }

        @Override
        public void set(T newValue) {
            assertCanMutate();
            value = newValue;
        }

        @Override
        public T update(Function<T, T> updateFunction) {
            acquireUpdateLock();
            try {
                // Do not hold any locks while applying the update
                T newValue = updateFunction.apply(value);
                value = newValue;
                return newValue;
            } finally {
                releaseUpdateLock();
            }
        }

        private void acquireUpdateLock() {
            // It's important that we do not block waiting for the lock while holding the project mutation lock.
            // Doing so can lead to deadlocks.

            assertCanMutate();

            if (lock.tryLock()) {
                // Update lock was not contended, can keep holding the project locks
                return;
            }

            // Another thread holds the update lock, release the project locks and wait for the other thread to finish the update
            workerLeaseService.withoutProjectLock(lock::lock);
        }

        private void assertCanMutate() {
            if (!owner.hasMutableState()) {
                throw new IllegalStateException("Current thread does not hold the state lock for " + owner);
            }
        }

        private void releaseUpdateLock() {
            lock.unlock();
        }
    }
}
