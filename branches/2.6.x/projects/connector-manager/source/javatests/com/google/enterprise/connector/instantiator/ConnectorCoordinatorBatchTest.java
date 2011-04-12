// Copyright (C) 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.instantiator;

import com.google.enterprise.connector.common.I18NUtil;
import com.google.enterprise.connector.persist.ConnectorNotFoundException;
import com.google.enterprise.connector.pusher.Pusher;
import com.google.enterprise.connector.pusher.PusherFactory;
import com.google.enterprise.connector.scheduler.LoadManager;
import com.google.enterprise.connector.scheduler.LoadManagerFactory;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SimpleDocument;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.Value;
import com.google.enterprise.connector.test.ConnectorTestUtils;
import com.google.enterprise.connector.traversal.BatchResult;
import com.google.enterprise.connector.traversal.BatchSize;
import com.google.enterprise.connector.traversal.TraversalDelayPolicy;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tests batch support in {@link ConnectorCoordinatorImpl}.
 */
public class ConnectorCoordinatorBatchTest extends TestCase {
  private static final Logger LOGGER =
      Logger.getLogger(ConnectorCoordinatorBatchTest.class.getName());
  private static final String EN = "en";
  private static final int TRAVERSAL_TIME_LIMIT_SECS = 20;
  private static final int SHORT_TRAVERSAL_TIME_LIMIT_SECS = 1;

  ConnectorCoordinatorImpl coordinator;
  TypeInfo typeInfo;
  RecordingPusher recordingPusher;
  MockLoadManager recordingLoadManager;
  File connectorDir;
  Locale locale;

  private static final String CONNECTOR_TYPE_RESOURCE_NAME =
      "testdata/connectorCoordinatorBatchTest/config/connectorType.xml";

  private static final String TEST_DIR_NAME =
      "testdata/tmp/ConnectorCoordinatorBatchTests";

  @Override
  protected void setUp() throws Exception {
    SyncingConnector.reset();
    File baseDirectory = new File(TEST_DIR_NAME);
    if (!baseDirectory.exists()) {
      assertTrue(baseDirectory.mkdirs());
    }
    connectorDir = File.createTempFile("zzCm", "_cmType", baseDirectory);
    connectorDir.delete();
    assertTrue(connectorDir.mkdirs());
    locale = I18NUtil.getLocaleFromStandardLocaleString(EN);

    Resource r = new FileSystemResource(CONNECTOR_TYPE_RESOURCE_NAME);
    TypeInfo ti = TypeInfo.fromSpringResourceAndThrow(r);
    Assert.assertNotNull(ti);

    File connectorTypeDir =
        new File(connectorDir, ti.getConnectorTypeName());

    if (!ConnectorTestUtils.deleteAllFiles(connectorTypeDir) ||
        !connectorTypeDir.mkdirs()) {
      throw new Exception("Failed to create type dir " + connectorTypeDir);
    }

    LOGGER.info("connector type dir = " + connectorTypeDir);

    ti.setConnectorTypeDir(connectorTypeDir);
    typeInfo = ti;
  }

  @Override
  protected void tearDown() {
    try {
      if (coordinator != null) {
        coordinator.removeConnector();
      }
    } finally {
      coordinator = null;
      recordingPusher = null;
      if (connectorDir != null) {
        assertTrue(ConnectorTestUtils.deleteAllFiles(connectorDir));
      }
    }
  }

  private void createPusherAndCoordinator(int batchTimeout) throws Exception {
    ThreadPool threadPool = new ThreadPool(batchTimeout);
    recordingPusher = new RecordingPusher("c1");
    recordingLoadManager = new MockLoadManager();
    coordinator = new ConnectorCoordinatorImpl("c1",
        new MockPusherFactory(recordingPusher),
        new MockLoadManagerFactory(recordingLoadManager), threadPool);
    Map<String, String> config = new HashMap<String, String>();
    coordinator.setConnectorConfig(typeInfo, config, locale, false);
    coordinator.setConnectorSchedule("c1:1000:0:0-0");
  }

  public void testCreateRunRemoveLoop() throws Exception {
    for (int ix = 0; ix < 100; ix++) {
      createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
      runBatch(1 + ix, 1 + ix, 0);
      coordinator.removeConnector();
      coordinator = null;
      recordingPusher = null;
    }
  }

  public void testStartThenResumeTraversal() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    runBatch(1, 1, 0);

    // Run a second batch for the same connector coordinator to confirm
    // resumeTraversal rather than startTraversal is called.
    runBatch(1, 1, 1);
  }

  private void runBatch(int expectLoginCount, int expectStartTraversalCount,
      int expectResumeTraversalCount) throws RepositoryException,
      ConnectorNotFoundException, InterruptedException {
    List<SimpleDocument> expectList =
        SyncingConnector.createaAndQueueDocumentList();
    String expectId =
        Value.getSingleValueString(expectList.get(0),
            SpiConstants.PROPNAME_DOCID);
    SyncingConnector.Tracker tracker =
        SyncingConnector.getTracker();
    startBatch();
    PushedDocument got = recordingPusher.poll();
    assertNotNull(tracker.toString(), got);
    String gotId =
        Value.getSingleValueString(got.getDocument(),
            SpiConstants.PROPNAME_DOCID);
    assertEquals(expectId, gotId);
    assertEquals(tracker.toString(), expectLoginCount, tracker.getLoginCount());
    assertEquals(tracker.toString(), expectStartTraversalCount, tracker
        .getStartTraversalCount());
    assertEquals(tracker.toString(), expectResumeTraversalCount, tracker
        .getResumeTraversalCount());
    BatchResult batchResult = recordingLoadManager.getBatchResult();
    assertEquals(tracker.toString(), 1, batchResult.getCountProcessed());
    assertEquals(tracker.toString(), TraversalDelayPolicy.IMMEDIATE,
        batchResult.getDelayPolicy());
  }

  public void testManyBatches() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    for (int ix = 0; ix < 10; ix++) {
      runBatch(1, 1, ix);
    }
  }

  /**
   * Starts a batch.
   * <p>
   * These tests wait for completion of activity that occurs in a batch such as
   * the {@link SyncingConnector} registering an interrupt. There is a
   * little interval between the time such activity occurs and the time the
   * batch fully completes. Since {@link ConnectorCoordinatorImpl#startBatch()}
   * will not start a batch while one is running this function includes a retry
   * loop.
   *
   * @throws ConnectorNotFoundException
   * @throws InterruptedException
   */
  private void startBatch()
      throws ConnectorNotFoundException, InterruptedException {
    for (int iy = 0; iy < 100; iy++) {
      if (coordinator.startBatch()) {
        return;
      } else {
        Thread.sleep(20);
      }
    }
    fail("Failed to start batch - probably a batch is not ending properly.");
  }

  public void testDisabledTraversal() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    // Disable traversal schedule.  No batch should run.
    coordinator.setConnectorSchedule("#c1:1000:0:0-0");
    assertFalse(coordinator.startBatch());
    assertNull(recordingLoadManager.getBatchResult());
  }

  public void testScheduledTraversal() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    // With no traversal intervals, no batch should run.
    coordinator.setConnectorSchedule("c1:1000:0:");
    assertFalse(coordinator.startBatch());
    assertNull(recordingLoadManager.getBatchResult());
  }

  public void testTraversalDelayPolicy1() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    // Force a POLLING wait.
    coordinator.setConnectorSchedule("c1:1000:250:0-0");
    coordinator.delayTraversal(TraversalDelayPolicy.POLL);
    assertFalse(coordinator.startBatch());
    assertNull(recordingLoadManager.getBatchResult());

    // Wait until delay interval is up.  Should be able to run.
    try {
      Thread.sleep(300);
    } catch (InterruptedException ie) {}
    assertTrue(coordinator.startBatch());
  }

  public void testTraversalDelayPolicy2() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    // Force a POLLING wait.
    coordinator.setConnectorSchedule("c1:1000:500:0-0");
    coordinator.delayTraversal(TraversalDelayPolicy.POLL);
    assertFalse(coordinator.startBatch());
    assertNull(recordingLoadManager.getBatchResult());
    // IMMEDIATE should cancel out any current delay policy.
    coordinator.delayTraversal(TraversalDelayPolicy.IMMEDIATE);
    assertTrue(coordinator.startBatch());
  }

  public void testTraversalDelayPolicy3() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    // Force a ERROR wait.
    coordinator.delayTraversal(TraversalDelayPolicy.ERROR);
    assertFalse(coordinator.startBatch());
    assertNull(recordingLoadManager.getBatchResult());
    // IMMEDIATE should cancel out any current delay policy.
    coordinator.delayTraversal(TraversalDelayPolicy.IMMEDIATE);
    assertTrue(coordinator.startBatch());
  }

  public void testCancelBatch() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    coordinator.startBatch();
    SyncingConnector.Tracker tracker =
        SyncingConnector.getTracker();
    tracker.blockUntilTraversing();
    assertEquals(1, tracker.getStartTraversalCount());
    coordinator.restartConnectorTraversal();
    tracker.blockUntilTraversingInterrupted();
    assertEquals(1, tracker.getLoginCount());
    assertEquals(1, tracker.getInterruptedCount());
    assertEquals(1, tracker.getStartTraversalCount());

    // Run a second batch to confirm we create a new connector
    // and call start traversal after the cancel.
    runBatch(2, 2, 0);
  }

  public void testSetConnectorConfig() throws Exception {
    createPusherAndCoordinator(TRAVERSAL_TIME_LIMIT_SECS);
    coordinator.startBatch();
    SyncingConnector.Tracker tracker =
        SyncingConnector.getTracker();
    tracker.blockUntilTraversing();
    assertEquals(1, tracker.getStartTraversalCount());

    Map<String, String> configMap = coordinator.getConnectorConfig();
    configMap.put("hi", "mom");
    coordinator.setConnectorConfig(typeInfo, configMap, locale, true);
    tracker.blockUntilTraversingInterrupted();
    assertEquals(1, tracker.getLoginCount());
    assertEquals(1, tracker.getInterruptedCount());
    assertEquals(1, tracker.getStartTraversalCount());

    // Run a second batch to confirm we create a new connector
    // and call start traversal after the cancel.
    runBatch(2, 2, 0);
  }

  public void testTimeoutBatch() throws Exception {
    createPusherAndCoordinator(SHORT_TRAVERSAL_TIME_LIMIT_SECS);
    coordinator.startBatch();
    SyncingConnector.Tracker tracker =
        SyncingConnector.getTracker();
    tracker.blockUntilTraversingInterrupted();

    assertEquals(tracker.toString(), 1, tracker.getLoginCount());
    assertEquals(tracker.toString(), 1, tracker.getStartTraversalCount());
    assertEquals(tracker.toString(), 1, tracker.getInterruptedCount());

    // Run a second batch to confirm we create a new connector
    // and call start traversal after the cancel.
    runBatch(2, 2, 0);
  }

  private static class PushedDocument {
    private final Document document;
    private final String connectorName;

    PushedDocument(Document document, String connectorName) {
      this.document = document;
      this.connectorName = connectorName;
    }

    public Document getDocument() {
      return document;
    }

    public String getConnectorName() {
      return connectorName;
    }

    @Override
    public String toString() {
      return "PushedDocument connectorName = " + connectorName + " document = "
          + document;
    }
  }

  private static class MockPusherFactory implements PusherFactory {
    private final Pusher pusher;

    MockPusherFactory(Pusher pusher) {
      this.pusher = pusher;
    }

    public Pusher newPusher(String ignored) {
      return pusher;
    }
  }

  private static class RecordingPusher implements Pusher {
    private final BlockingQueue<PushedDocument> pushedDocuments =
        new ArrayBlockingQueue<PushedDocument>(100);
    private final String connectorName;

    RecordingPusher(String connectorName) {
      this.connectorName = connectorName;
    }

    public boolean take(Document document) {
      pushedDocuments.add(new PushedDocument(document, connectorName));
      return true;
    }

    public void flush() {
    }

    public void cancel() {
    }

    int getCountPending() {
      return pushedDocuments.size();
    }

    PushedDocument poll() throws InterruptedException {
      return pushedDocuments.poll(
          SyncingConnector.POLL_TIME_LIMIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public String toString() {
      return "Pusher pushed = " + pushedDocuments;
    }
  }

  private static class MockLoadManagerFactory implements LoadManagerFactory {
    private final LoadManager loadManager;

    MockLoadManagerFactory(LoadManager loadManager) {
      this.loadManager = loadManager;
    }

    public LoadManager newLoadManager(String ignored) {
      return loadManager;
    }
  }

  private static class MockLoadManager implements LoadManager {
    int load = 200;
    int batchSize = 3;

    private final BlockingQueue<BatchResult> resultQueue =
        new ArrayBlockingQueue<BatchResult>(10);

    public void recordResult(BatchResult batchResult) {
      resultQueue.add(batchResult);
    }

    BatchResult getBatchResult() throws InterruptedException {
      return resultQueue.poll(SyncingConnector.POLL_TIME_LIMIT_MILLIS,
          TimeUnit.MILLISECONDS);
    }

    public void setLoad(int load) {
      this.load = load;
    }

    public void setPeriod(int period) {
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public BatchSize determineBatchSize() {
      int size = Math.min(load, batchSize);
      return new BatchSize(size, size);
    }

    public boolean shouldDelay() {
      return false;
    }
  }
}