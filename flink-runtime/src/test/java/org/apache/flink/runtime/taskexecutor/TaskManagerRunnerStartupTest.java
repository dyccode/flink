/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.taskexecutor;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.NettyShuffleEnvironmentOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.testutils.AllCallbackWrapper;
import org.apache.flink.runtime.blob.NoOpTaskExecutorBlobService;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.entrypoint.WorkingDirectory;
import org.apache.flink.runtime.externalresource.ExternalResourceInfoProvider;
import org.apache.flink.runtime.heartbeat.TestingHeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.TestingHighAvailabilityServices;
import org.apache.flink.runtime.leaderretrieval.SettableLeaderRetrievalService;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.metrics.NoOpMetricRegistry;
import org.apache.flink.runtime.metrics.scope.ScopeFormats;
import org.apache.flink.runtime.metrics.util.TestingMetricRegistry;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.TestingRpcServiceExtension;
import org.apache.flink.runtime.security.token.DelegationTokenReceiverRepository;
import org.apache.flink.runtime.testutils.WorkingDirectoryExtension;
import org.apache.flink.testutils.junit.utils.TempDirUtils;
import org.apache.flink.util.IOUtils;

import org.apache.flink.shaded.guava33.com.google.common.collect.Sets;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that check how the {@link TaskManagerRunner} behaves when encountering startup problems.
 */
class TaskManagerRunnerStartupTest {

    private static final String LOCAL_HOST = "localhost";

    @RegisterExtension
    public static final AllCallbackWrapper<TestingRpcServiceExtension>
            RPC_SERVICE_EXTENSION_WRAPPER =
                    new AllCallbackWrapper<>(new TestingRpcServiceExtension());

    @TempDir public Path tempFolder;

    @TempDir public static File workingDirectoryFolder;

    @RegisterExtension
    private static final AllCallbackWrapper<WorkingDirectoryExtension>
            WORKING_DIRECTORY_EXTENSION_WRAPPER =
                    new AllCallbackWrapper<>(
                            new WorkingDirectoryExtension(() -> workingDirectoryFolder));

    private final RpcService rpcService =
            RPC_SERVICE_EXTENSION_WRAPPER.getCustomExtension().getTestingRpcService();

    private TestingHighAvailabilityServices highAvailabilityServices;

    @BeforeEach
    void setupTest() {
        highAvailabilityServices = new TestingHighAvailabilityServices();
        highAvailabilityServices.setResourceManagerLeaderRetriever(
                new SettableLeaderRetrievalService());
    }

    @AfterEach
    void tearDownTest() throws Exception {
        highAvailabilityServices.closeWithOptionalClean(true);
        highAvailabilityServices = null;
    }

    /**
     * Tests that the TaskManagerRunner startup fails synchronously when the I/O directories are not
     * writable.
     */
    @Tag("org.apache.flink.testutils.junit.FailsInGHAContainerWithRootUser")
    @Test
    void testIODirectoryNotWritable() throws Exception {
        File nonWritable = TempDirUtils.newFolder(tempFolder);
        Assumptions.assumeTrue(
                nonWritable.setWritable(false, false),
                "Cannot create non-writable temporary file directory. Skipping test.");

        try {
            Configuration cfg = createFlinkConfiguration();
            cfg.set(CoreOptions.TMP_DIRS, nonWritable.getAbsolutePath());

            assertThatThrownBy(
                            () ->
                                    startTaskManager(
                                            cfg,
                                            rpcService,
                                            highAvailabilityServices,
                                            WORKING_DIRECTORY_EXTENSION_WRAPPER
                                                    .getCustomExtension()
                                                    .createNewWorkingDirectory()),
                            "Should fail synchronously with an IOException")
                    .isInstanceOf(IOException.class);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            nonWritable.setWritable(true, false);
            try {
                FileUtils.deleteDirectory(nonWritable);
            } catch (IOException e) {
                // best effort
            }
        }
    }

    /**
     * Tests that the TaskManagerRunner startup fails synchronously when the memory configuration is
     * wrong.
     */
    @Test
    void testMemoryConfigWrong() {
        Configuration cfg = createFlinkConfiguration();

        // something invalid
        cfg.set(TaskManagerOptions.NETWORK_MEMORY_MIN, MemorySize.parse("100m"));
        cfg.set(TaskManagerOptions.NETWORK_MEMORY_MAX, MemorySize.parse("10m"));
        assertThatThrownBy(
                        () ->
                                startTaskManager(
                                        cfg,
                                        rpcService,
                                        highAvailabilityServices,
                                        WORKING_DIRECTORY_EXTENSION_WRAPPER
                                                .getCustomExtension()
                                                .createNewWorkingDirectory()))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    /**
     * Tests that the TaskManagerRunner startup fails if the network stack cannot be initialized.
     */
    @Test
    void testStartupWhenNetworkStackFailsToInitialize() throws Exception {
        final ServerSocket blocker = new ServerSocket(0, 50, InetAddress.getByName(LOCAL_HOST));

        try {
            final Configuration cfg = createFlinkConfiguration();
            cfg.set(NettyShuffleEnvironmentOptions.DATA_PORT, blocker.getLocalPort());
            cfg.set(TaskManagerOptions.BIND_HOST, LOCAL_HOST);

            assertThatThrownBy(
                            () ->
                                    startTaskManager(
                                            cfg,
                                            rpcService,
                                            highAvailabilityServices,
                                            WORKING_DIRECTORY_EXTENSION_WRAPPER
                                                    .getCustomExtension()
                                                    .createNewWorkingDirectory()),
                            "Should throw IOException when the network stack cannot be initialized.")
                    .isInstanceOf(IOException.class);
        } finally {
            IOUtils.closeQuietly(blocker);
        }
    }

    /** Checks that all expected metrics are initialized. */
    @Test
    void testMetricInitialization() throws Exception {
        Configuration cfg = createFlinkConfiguration();

        List<String> registeredMetrics = new ArrayList<>();
        startTaskManager(
                cfg,
                rpcService,
                highAvailabilityServices,
                WORKING_DIRECTORY_EXTENSION_WRAPPER
                        .getCustomExtension()
                        .createNewWorkingDirectory(),
                TestingMetricRegistry.builder()
                        .setRegisterConsumer(
                                (metric, metricName, group) ->
                                        registeredMetrics.add(
                                                group.getMetricIdentifier(metricName)))
                        .setScopeFormats(ScopeFormats.fromConfig(cfg))
                        .build());

        // GC-related metrics are not checked since their existence depends on the JVM used
        Set<String> expectedTaskManagerMetricsWithoutTaskManagerId =
                Sets.newHashSet(
                        ".taskmanager..Status.JVM.ClassLoader.ClassesLoaded",
                        ".taskmanager..Status.JVM.ClassLoader.ClassesUnloaded",
                        ".taskmanager..Status.JVM.Memory.Heap.Used",
                        ".taskmanager..Status.JVM.Memory.Heap.Committed",
                        ".taskmanager..Status.JVM.Memory.Heap.Max",
                        ".taskmanager..Status.JVM.Memory.NonHeap.Used",
                        ".taskmanager..Status.JVM.Memory.NonHeap.Committed",
                        ".taskmanager..Status.JVM.Memory.NonHeap.Max",
                        ".taskmanager..Status.JVM.Memory.Direct.Count",
                        ".taskmanager..Status.JVM.Memory.Direct.MemoryUsed",
                        ".taskmanager..Status.JVM.Memory.Direct.TotalCapacity",
                        ".taskmanager..Status.JVM.Memory.Mapped.Count",
                        ".taskmanager..Status.JVM.Memory.Mapped.MemoryUsed",
                        ".taskmanager..Status.JVM.Memory.Mapped.TotalCapacity",
                        ".taskmanager..Status.Flink.Memory.Managed.Used",
                        ".taskmanager..Status.Flink.Memory.Managed.Total",
                        ".taskmanager..Status.JVM.Threads.Count",
                        ".taskmanager..Status.JVM.CPU.Load",
                        ".taskmanager..Status.JVM.CPU.Time",
                        ".taskmanager..Status.Shuffle.Netty.TotalMemorySegments",
                        ".taskmanager..Status.Shuffle.Netty.TotalMemory",
                        ".taskmanager..Status.Shuffle.Netty.AvailableMemorySegments",
                        ".taskmanager..Status.Shuffle.Netty.AvailableMemory",
                        ".taskmanager..Status.Shuffle.Netty.UsedMemorySegments",
                        ".taskmanager..Status.Shuffle.Netty.UsedMemory");

        Pattern pattern = Pattern.compile("\\.taskmanager\\.([^.]+)\\..*");
        Set<String> registeredMetricsWithoutTaskManagerId =
                registeredMetrics.stream()
                        .map(pattern::matcher)
                        .flatMap(
                                matcher ->
                                        matcher.find()
                                                ? Stream.of(
                                                        matcher.group(0)
                                                                .replaceAll(matcher.group(1), ""))
                                                : Stream.empty())
                        .collect(Collectors.toSet());

        assertThat(expectedTaskManagerMetricsWithoutTaskManagerId)
                .allSatisfy(ele -> assertThat(ele).isIn(registeredMetricsWithoutTaskManagerId));
    }

    // -----------------------------------------------------------------------------------------------

    private static Configuration createFlinkConfiguration() {
        return TaskExecutorResourceUtils.adjustForLocalExecution(new Configuration());
    }

    private static void startTaskManager(
            Configuration configuration,
            RpcService rpcService,
            HighAvailabilityServices highAvailabilityServices,
            WorkingDirectory workingDirectory)
            throws Exception {
        startTaskManager(
                configuration,
                rpcService,
                highAvailabilityServices,
                workingDirectory,
                NoOpMetricRegistry.INSTANCE);
    }

    private static void startTaskManager(
            Configuration configuration,
            RpcService rpcService,
            HighAvailabilityServices highAvailabilityServices,
            WorkingDirectory workingDirectory,
            MetricRegistry metricRegistry)
            throws Exception {

        TaskManagerRunner.startTaskManager(
                configuration,
                ResourceID.generate(),
                rpcService,
                highAvailabilityServices,
                new TestingHeartbeatServices(),
                metricRegistry,
                NoOpTaskExecutorBlobService.INSTANCE,
                false,
                ExternalResourceInfoProvider.NO_EXTERNAL_RESOURCES,
                workingDirectory,
                error -> {},
                new DelegationTokenReceiverRepository(configuration, null));
    }
}
