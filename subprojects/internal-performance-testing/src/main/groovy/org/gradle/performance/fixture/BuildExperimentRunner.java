/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.performance.fixture;

import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.performance.measure.MeasuredOperation;
import org.gradle.performance.results.MeasuredOperationList;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static org.gradle.performance.fixture.DurationMeasurementImpl.executeProcess;
import static org.gradle.performance.fixture.DurationMeasurementImpl.printProcess;

public class BuildExperimentRunner {

    private final GradleSessionProvider executerProvider;
    private final Profiler profiler;

    public enum Phase {
        WARMUP,
        MEASUREMENT
    }

    public BuildExperimentRunner(GradleSessionProvider executerProvider) {
        this.executerProvider = executerProvider;
        profiler = Profiler.create();
    }

    public Profiler getProfiler() {
        return profiler;
    }

    public void run(BuildExperimentSpec experiment, MeasuredOperationList results) {
        System.out.println();
        System.out.println(String.format("%s ...", experiment.getDisplayName()));
        System.out.println();

        InvocationSpec invocationSpec = experiment.getInvocation();
        File workingDirectory = invocationSpec.getWorkingDirectory();
        workingDirectory.mkdirs();
        copyTemplateTo(experiment, workingDirectory);

        if (experiment.getListener() != null) {
            experiment.getListener().beforeExperiment(experiment, workingDirectory);
        }

        if (invocationSpec instanceof GradleInvocationSpec) {
            GradleInvocationSpec invocation = (GradleInvocationSpec) invocationSpec;
            final List<String> additionalJvmOpts = profiler.getAdditionalJvmOpts(experiment);
            final List<String> additionalArgs = new ArrayList<String>(profiler.getAdditionalGradleArgs(experiment));
            additionalArgs.add("-PbuildExperimentDisplayName=" + experiment.getDisplayName());

            GradleInvocationSpec buildSpec = invocation.withAdditionalJvmOpts(additionalJvmOpts).withAdditionalArgs(additionalArgs);
            GradleSession session = executerProvider.session(buildSpec);
            session.prepare();
            try {
                performMeasurements(session, experiment, results, workingDirectory);
            } finally {
                CompositeStoppable.stoppable(profiler).stop();
                session.cleanup();
            }
        }
    }

    private void copyTemplateTo(BuildExperimentSpec experiment, File workingDir) {
        File templateDir = new TestProjectLocator().findProjectDir(experiment.getProjectName());
        GFileUtils.cleanDirectory(workingDir);
        GFileUtils.copyDirectory(templateDir, workingDir);
    }

    protected void performMeasurements(InvocationExecutorProvider session, BuildExperimentSpec experiment, MeasuredOperationList results, File projectDir) {
        doWarmup(experiment, projectDir, session);
        profiler.start(experiment);
        doMeasure(experiment, results, projectDir, session);
        profiler.stop(experiment);
    }

    // TODO move to string utils
    private static final Pattern NEW_LINE_PATTERN = Pattern.compile("\n");

    private static Stream<String> splitLines(String values) {
        return NEW_LINE_PATTERN.splitAsStream(values);
    }

    private void doMeasure(BuildExperimentSpec experiment, MeasuredOperationList results, File projectDir, InvocationExecutorProvider session) {
        printProcess("pstree", "pstree");
        Set<String> previousProcessIds = gradleProcessesIds();
        printProcess("previousProcesses", "ps -eu --pid " + StringUtils.join(previousProcessIds, " "));

        int invocationCount = invocationsForExperiment(experiment);
        for (int i = 0; i < invocationCount; i++) {
            System.out.println();
            System.out.println(String.format("Test run #%s", i + 1));

            killLeftoverGradleProcesses(previousProcessIds);

            displayInfo();

            BuildExperimentInvocationInfo info = new DefaultBuildExperimentInvocationInfo(experiment, projectDir, Phase.MEASUREMENT, i + 1, invocationCount);
            runOnce(session, results, info);
        }
    }

    private void killLeftoverGradleProcesses(Set<String> previousProcessIds) {
        Set<String> leftoverProcessIds = Sets.difference(gradleProcessesIds(), previousProcessIds);
        if (!leftoverProcessIds.isEmpty()) {
            String leftoverPidList = StringUtils.join(leftoverProcessIds, " ");
            printProcess("Killing leftover Gradle processes: " + leftoverPidList, "ps -eu --pid " + leftoverPidList);
            executeProcess("kill -9 " + leftoverPidList);
        }
    }

    private Set<String> gradleProcessesIds() {
        return splitLines(executeProcess("ps aux | egrep '[Gg]radle' | awk '{print $2}'"))
            .collect(toSet());
    }

    @SuppressWarnings("unchecked")
    protected InvocationCustomizer createInvocationCustomizer(final BuildExperimentInvocationInfo info) {
        if (info.getBuildExperimentSpec() instanceof GradleBuildExperimentSpec) {
            return new InvocationCustomizer() {
                @Override
                public <T extends InvocationSpec> T customize(BuildExperimentInvocationInfo info, T invocationSpec) {
                    final List<String> iterationInfoArguments = createIterationInfoArguments(info.getPhase(), info.getIterationNumber(), info.getIterationMax());
                    GradleInvocationSpec gradleInvocationSpec = ((GradleInvocationSpec) invocationSpec).withAdditionalArgs(iterationInfoArguments);
                    System.out.println("Run Gradle using JVM opts: " + gradleInvocationSpec.getJvmOpts());
                    if (info.getBuildExperimentSpec().getInvocationCustomizer() != null) {
                        gradleInvocationSpec = info.getBuildExperimentSpec().getInvocationCustomizer().customize(info, gradleInvocationSpec);
                    }
                    return (T) gradleInvocationSpec;
                }
            };
        }
        return null;
    }

    private void doWarmup(BuildExperimentSpec experiment, File projectDir, InvocationExecutorProvider session) {
        int warmUpCount = warmupsForExperiment(experiment);
        for (int i = 0; i < warmUpCount; i++) {
            System.out.println();
            System.out.println(String.format("Warm-up #%s", i + 1));
            BuildExperimentInvocationInfo info = new DefaultBuildExperimentInvocationInfo(experiment, projectDir, Phase.WARMUP, i + 1, warmUpCount);
            runOnce(session, new MeasuredOperationList(), info);
        }
    }

    /**
     * Show the currently running services, CPU speeds, temperatures, free space, etc.
     */
    private static void displayInfo() {
        printProcess("CPU temperatures", "sensors | grep 'Core ' | awk '{print $3}' | xargs");
        printProcess("CPU speed", " lscpu | grep 'CPU MHz:' | awk '{print $3 \" Mhz\"}'");
        printProcess("Used memory", "awk '/MemFree/ { printf \"%.3f Gb\\n\", $2/1024/1024 }' /proc/meminfo");
        printProcess("Disk space", "df --human | awk '{print $1 \" \" $4}' | xargs");
        printProcess("Running service count", "systemctl | grep 'running' | awk '{print $1}' | wc --lines");
        printProcess("Process count", "ps aux | wc --lines");
        printProcess("Gradle process count", "ps aux | egrep '[Gg]radle' | wc --lines");
        printProcess("Temp directory gradle file count", "find /tmp -type f -name '*gradle*' 2>/dev/null | wc --lines");
    }

    private static String getExperimentOverride(String key) {
        String value = System.getProperty("org.gradle.performance.execution." + key);
        if (value != null && !"defaults".equals(value)) {
            return value;
        }
        return null;
    }

    protected Integer invocationsForExperiment(BuildExperimentSpec experiment) {
        String overriddenInvocationCount = getExperimentOverride("runs");
        if (overriddenInvocationCount != null) {
            return Integer.valueOf(overriddenInvocationCount);
        }
        if (experiment.getInvocationCount() != null) {
            return experiment.getInvocationCount();
        }
        return 40;
    }

    protected int warmupsForExperiment(BuildExperimentSpec experiment) {
        String overriddenWarmUpCount = getExperimentOverride("warmups");
        if (overriddenWarmUpCount != null) {
            return Integer.valueOf(overriddenWarmUpCount);
        }
        if (experiment.getWarmUpCount() != null) {
            return experiment.getWarmUpCount();
        }
        if (usesDaemon(experiment)) {
            return 10;
        } else {
            return 1;
        }
    }

    private boolean usesDaemon(BuildExperimentSpec experiment) {
        InvocationSpec invocation = experiment.getInvocation();
        if (invocation instanceof GradleInvocationSpec) {
            if (((GradleInvocationSpec) invocation).getBuildWillRunInDaemon()) {
                return true;
            }
        }
        return false;
    }

    protected void runOnce(
        final InvocationExecutorProvider session,
        final MeasuredOperationList results,
        final BuildExperimentInvocationInfo invocationInfo) {
        BuildExperimentSpec experiment = invocationInfo.getBuildExperimentSpec();
        final Action<MeasuredOperation> runner = session.runner(invocationInfo, wrapInvocationCustomizer(invocationInfo, createInvocationCustomizer(invocationInfo)));

        if (experiment.getListener() != null) {
            experiment.getListener().beforeInvocation(invocationInfo);
        }

        MeasuredOperation operation = new MeasuredOperation();
        runner.execute(operation);

        final AtomicBoolean omitMeasurement = new AtomicBoolean();
        if (experiment.getListener() != null) {
            experiment.getListener().afterInvocation(invocationInfo, operation, new BuildExperimentListener.MeasurementCallback() {
                @Override
                public void omitMeasurement() {
                    omitMeasurement.set(true);
                }
            });
        }

        if (!omitMeasurement.get()) {
            results.add(operation);
        }
    }

    private InvocationCustomizer wrapInvocationCustomizer(BuildExperimentInvocationInfo invocationInfo, final InvocationCustomizer invocationCustomizer) {
        final InvocationCustomizer experimentSpecificCustomizer = invocationInfo.getBuildExperimentSpec().getInvocationCustomizer();
        if (experimentSpecificCustomizer != null) {
            if (invocationCustomizer != null) {
                return new InvocationCustomizer() {
                    @Override
                    public <T extends InvocationSpec> T customize(BuildExperimentInvocationInfo info, T invocationSpec) {
                        return experimentSpecificCustomizer.customize(info, invocationCustomizer.customize(info, invocationSpec));
                    }
                };
            } else {
                return experimentSpecificCustomizer;
            }
        } else {
            return invocationCustomizer;
        }
    }

    protected List<String> createIterationInfoArguments(Phase phase, int iterationNumber, int iterationMax) {
        List<String> args = new ArrayList<String>(3);
        args.add("-PbuildExperimentPhase=" + phase.toString().toLowerCase());
        args.add("-PbuildExperimentIterationNumber=" + iterationNumber);
        args.add("-PbuildExperimentIterationMax=" + iterationMax);
        return args;
    }
}
