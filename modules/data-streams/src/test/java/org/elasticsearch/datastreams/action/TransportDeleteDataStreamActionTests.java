/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.datastreams.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.datastreams.DeleteDataStreamAction;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.SnapshotsInProgress;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.EmptySystemIndices;
import org.elasticsearch.indices.TestIndexNameExpressionResolver;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInProgressException;
import org.elasticsearch.test.ESTestCase;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class TransportDeleteDataStreamActionTests extends ESTestCase {

    private final IndexNameExpressionResolver iner = TestIndexNameExpressionResolver.newInstance();
    private final ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
    private final Consumer<String> validator = s -> EmptySystemIndices.INSTANCE.validateDataStreamAccess(s, threadContext);

    public void testDeleteDataStream() {
        final String dataStreamName = "my-data-stream";
        final List<String> otherIndices = randomSubsetOf(List.of("foo", "bar", "baz"));

        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(List.of(new Tuple<>(dataStreamName, 2)), otherIndices);
        DeleteDataStreamAction.Request req = new DeleteDataStreamAction.Request(TEST_REQUEST_TIMEOUT, new String[] { dataStreamName });
        ClusterState newState = TransportDeleteDataStreamAction.removeDataStream(
            iner,
            projectStateFromProject(project),
            req,
            validator,
            Settings.EMPTY
        );
        final var newProject = newState.metadata().getProject(project.id());
        assertThat(newProject.dataStreams().size(), equalTo(0));
        assertThat(newProject.indices().size(), equalTo(otherIndices.size()));
        for (String indexName : otherIndices) {
            assertThat(newProject.indices().get(indexName).getIndex().getName(), equalTo(indexName));
        }
    }

    public void testDeleteDataStreamWithFailureStore() {
        final String dataStreamName = "my-data-stream";
        final List<String> otherIndices = randomSubsetOf(List.of("foo", "bar", "baz"));

        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(new Tuple<>(dataStreamName, 2)),
            otherIndices,
            System.currentTimeMillis(),
            Settings.EMPTY,
            1,
            false,
            true
        );
        DeleteDataStreamAction.Request req = new DeleteDataStreamAction.Request(TEST_REQUEST_TIMEOUT, new String[] { dataStreamName });
        ClusterState newState = TransportDeleteDataStreamAction.removeDataStream(
            iner,
            projectStateFromProject(project),
            req,
            validator,
            Settings.EMPTY
        );
        final var newProject = newState.metadata().getProject(project.id());
        assertThat(newProject.dataStreams().size(), equalTo(0));
        assertThat(newProject.indices().size(), equalTo(otherIndices.size()));
        for (String indexName : otherIndices) {
            assertThat(newProject.indices().get(indexName).getIndex().getName(), equalTo(indexName));
        }
    }

    public void testDeleteMultipleDataStreams() {
        String[] dataStreamNames = { "foo", "bar", "baz", "eggplant" };
        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(
                new Tuple<>(dataStreamNames[0], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[1], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[2], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[3], randomIntBetween(1, 3))
            ),
            List.of()
        );

        DeleteDataStreamAction.Request req = new DeleteDataStreamAction.Request(TEST_REQUEST_TIMEOUT, new String[] { "ba*", "eggplant" });
        ClusterState newState = TransportDeleteDataStreamAction.removeDataStream(
            iner,
            projectStateFromProject(project),
            req,
            validator,
            Settings.EMPTY
        );
        final var newProject = newState.metadata().getProject(project.id());
        assertThat(newProject.dataStreams().size(), equalTo(1));
        DataStream remainingDataStream = newProject.dataStreams().get(dataStreamNames[0]);
        assertNotNull(remainingDataStream);
        assertThat(newProject.indices().size(), equalTo(remainingDataStream.getIndices().size()));
        for (Index i : remainingDataStream.getIndices()) {
            assertThat(newProject.indices().get(i.getName()).getIndex(), equalTo(i));
        }
    }

    public void testDeleteSnapshottingDataStream() {
        final String dataStreamName = "my-data-stream1";
        final String dataStreamName2 = "my-data-stream2";
        final List<String> otherIndices = randomSubsetOf(List.of("foo", "bar", "baz"));

        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(new Tuple<>(dataStreamName, 2), new Tuple<>(dataStreamName2, 2)),
            otherIndices
        );
        final var projectId = project.id();
        final ClusterState cs = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(project).build();
        SnapshotsInProgress snapshotsInProgress = SnapshotsInProgress.EMPTY.withAddedEntry(
            createEntry(dataStreamName, projectId, "repo1", false)
        ).withAddedEntry(createEntry(dataStreamName2, projectId, "repo2", true));
        ClusterState snapshotCs = ClusterState.builder(cs).putCustom(SnapshotsInProgress.TYPE, snapshotsInProgress).build();

        DeleteDataStreamAction.Request req = new DeleteDataStreamAction.Request(TEST_REQUEST_TIMEOUT, new String[] { dataStreamName });
        SnapshotInProgressException e = expectThrows(
            SnapshotInProgressException.class,
            () -> TransportDeleteDataStreamAction.removeDataStream(iner, snapshotCs.projectState(projectId), req, validator, Settings.EMPTY)
        );

        assertThat(
            e.getMessage(),
            equalTo(
                "Cannot delete data streams that are being snapshotted: [my-data-stream1]. Try again after "
                    + "snapshot finishes or cancel the currently running snapshot."
            )
        );
    }

    private SnapshotsInProgress.Entry createEntry(String dataStreamName, ProjectId projectId, String repo, boolean partial) {
        return SnapshotsInProgress.Entry.snapshot(
            new Snapshot(projectId, repo, new SnapshotId("", "")),
            false,
            partial,
            SnapshotsInProgress.State.SUCCESS,
            Collections.emptyMap(),
            List.of(dataStreamName),
            Collections.emptyList(),
            0,
            1,
            ImmutableOpenMap.of(),
            null,
            null,
            null
        );
    }

    public void testDeleteNonexistentDataStream() {
        final String dataStreamName = "my-data-stream";
        String[] dataStreamNames = { "foo", "bar", "baz", "eggplant" };
        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(
                new Tuple<>(dataStreamNames[0], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[1], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[2], randomIntBetween(1, 3)),
                new Tuple<>(dataStreamNames[3], randomIntBetween(1, 3))
            ),
            List.of()
        );
        final ClusterState cs = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(project).build();

        expectThrows(
            ResourceNotFoundException.class,
            () -> TransportDeleteDataStreamAction.removeDataStream(
                iner,
                cs.projectState(project.id()),
                new DeleteDataStreamAction.Request(TEST_REQUEST_TIMEOUT, new String[] { dataStreamName }),
                validator,
                Settings.EMPTY
            )
        );

        DeleteDataStreamAction.Request req = new DeleteDataStreamAction.Request(
            TEST_REQUEST_TIMEOUT,
            new String[] { dataStreamName + "*" }
        );
        ClusterState newState = TransportDeleteDataStreamAction.removeDataStream(
            iner,
            cs.projectState(project.id()),
            req,
            validator,
            Settings.EMPTY
        );
        assertThat(newState, sameInstance(cs));
        final var newProject = newState.metadata().getProject(project.id());
        assertThat(newProject.dataStreams().size(), equalTo(project.dataStreams().size()));
        assertThat(newProject.dataStreams().keySet(), containsInAnyOrder(project.dataStreams().keySet().toArray(Strings.EMPTY_ARRAY)));
    }

}
