/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.contrib.streaming.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.core.testutils.CommonTestUtils;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.operators.testutils.DummyEnvironment;
import org.apache.flink.runtime.state.KvState;
import org.apache.flink.runtime.state.KvStateSnapshot;
import org.apache.flink.runtime.state.StateHandle;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.shaded.com.google.common.collect.Lists;
import org.apache.flink.util.InstantiationUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Optional;

public class DbStateBackendTest {

	private static NetworkServerControl server;
	private static File tempDir;
	private static DbBackendConfig conf;
	private static String url1;
	private static String url2;

	@BeforeClass
	public static void startDerbyServer() throws UnknownHostException, Exception {
		server = new NetworkServerControl(InetAddress.getByName("localhost"), 1527, "flink", "flink");
		server.start(null);
		tempDir = new File(ConfigConstants.DEFAULT_TASK_MANAGER_TMP_PATH, UUID.randomUUID().toString());
		conf = new DbBackendConfig("flink", "flink",
				"jdbc:derby://localhost:1527/" + tempDir.getAbsolutePath() + "/flinkDB1;create=true");
		conf.setDbAdapter(new DerbyAdapter());
		conf.setKvStateCompactionFrequency(1);

		url1 = "jdbc:derby://localhost:1527/" + tempDir.getAbsolutePath() + "/flinkDB1;create=true";
		url2 = "jdbc:derby://localhost:1527/" + tempDir.getAbsolutePath() + "/flinkDB2;create=true";
	}

	@AfterClass
	public static void stopDerbyServer() throws Exception {
		try {
			server.shutdown();
			FileUtils.deleteDirectory(new File(tempDir.getAbsolutePath() + "/flinkDB1"));
			FileUtils.deleteDirectory(new File(tempDir.getAbsolutePath() + "/flinkDB2"));
			FileUtils.forceDelete(new File("derby.log"));
		} catch (Exception ignore) {
		}
	}

	@Test
	public void testSetupAndSerialization() throws Exception {
		DbStateBackend dbBackend = new DbStateBackend(conf);

		assertFalse(dbBackend.isInitialized());

		// serialize / copy the backend
		DbStateBackend backend = CommonTestUtils.createCopySerializable(dbBackend);
		assertFalse(backend.isInitialized());
		assertEquals(dbBackend.getConfiguration(), backend.getConfiguration());

		Environment env = new DummyEnvironment("test", 1, 0);
		backend.initializeForJob(env);

		assertNotNull(backend.getConnections());
		assertTrue(isTableCreated(backend.getConnections().getFirst(), "checkpoints_" + env.getJobID().toString()));

		backend.disposeAllStateForCurrentJob();
		assertFalse(isTableCreated(backend.getConnections().getFirst(), "checkpoints_" + env.getJobID().toString()));
		backend.close();

		assertTrue(backend.getConnections().getFirst().isClosed());
	}

	@Test
	public void testSerializableState() throws Exception {
		Environment env = new DummyEnvironment("test", 1, 0);
		DbStateBackend backend = new DbStateBackend(conf);

		backend.initializeForJob(env);

		String state1 = "dummy state";
		String state2 = "row row row your boat";
		Integer state3 = 42;

		StateHandle<String> handle1 = backend.checkpointStateSerializable(state1, 439568923746L,
				System.currentTimeMillis());
		StateHandle<String> handle2 = backend.checkpointStateSerializable(state2, 439568923746L,
				System.currentTimeMillis());
		StateHandle<Integer> handle3 = backend.checkpointStateSerializable(state3, 439568923746L,
				System.currentTimeMillis());

		assertEquals(state1, handle1.getState(getClass().getClassLoader()));
		handle1.discardState();

		assertEquals(state2, handle2.getState(getClass().getClassLoader()));
		handle2.discardState();

		assertFalse(isTableEmpty(backend.getConnections().getFirst(), "checkpoints_" + env.getJobID().toString()));

		assertEquals(state3, handle3.getState(getClass().getClassLoader()));
		handle3.discardState();

		assertTrue(isTableEmpty(backend.getConnections().getFirst(), "checkpoints_" + env.getJobID().toString()));

		backend.close();

	}

	@Test
	public void testKeyValueState() throws Exception {

		// We will create the DbStateBackend backed with a FsStateBackend for
		// nonPartitioned states
		File tempDir = new File(ConfigConstants.DEFAULT_TASK_MANAGER_TMP_PATH, UUID.randomUUID().toString());
		try {
			FsStateBackend fileBackend = new FsStateBackend(localFileUri(tempDir));

			DbStateBackend backend = new DbStateBackend(conf, fileBackend);

			Environment env = new DummyEnvironment("test", 2, 0);

			backend.initializeForJob(env);

			LazyDbKvState<Integer, String> kv = backend.createKvState(1, "state1", IntSerializer.INSTANCE,
					StringSerializer.INSTANCE, null);

			String tableName = "kvstate_" + env.getJobID() + "_1_state1";
			assertTrue(isTableCreated(backend.getConnections().getFirst(), tableName));

			assertEquals(0, kv.size());

			// some modifications to the state
			kv.setCurrentKey(1);
			assertNull(kv.value());
			kv.update("1");
			assertEquals(1, kv.size());
			kv.setCurrentKey(2);
			assertNull(kv.value());
			kv.update("2");
			assertEquals(2, kv.size());
			kv.setCurrentKey(1);
			assertEquals("1", kv.value());
			assertEquals(2, kv.size());

			kv.snapshot(682375462378L, 100);

			// make some more modifications
			kv.setCurrentKey(1);
			kv.update("u1");
			kv.setCurrentKey(2);
			kv.update("u2");
			kv.setCurrentKey(3);
			kv.update("u3");

			assertTrue(containsKey(backend.getConnections().getFirst(), tableName, 1, 100));

			kv.notifyCheckpointComplete(682375462378L);

			// draw another snapshot
			KvStateSnapshot<Integer, String, DbStateBackend> snapshot2 = kv.snapshot(682375462379L,
					200);
			assertTrue(containsKey(backend.getConnections().getFirst(), tableName, 1, 100));
			assertTrue(containsKey(backend.getConnections().getFirst(), tableName, 1, 200));
			kv.notifyCheckpointComplete(682375462379L);
			// Compaction should be performed
			assertFalse(containsKey(backend.getConnections().getFirst(), tableName, 1, 100));
			assertTrue(containsKey(backend.getConnections().getFirst(), tableName, 1, 200));

			// validate the original state
			assertEquals(3, kv.size());
			kv.setCurrentKey(1);
			assertEquals("u1", kv.value());
			kv.setCurrentKey(2);
			assertEquals("u2", kv.value());
			kv.setCurrentKey(3);
			assertEquals("u3", kv.value());

			// restore the first snapshot and validate it
			KvState<Integer, String, DbStateBackend> restored2 = snapshot2.restoreState(backend, IntSerializer.INSTANCE,
					StringSerializer.INSTANCE, null, getClass().getClassLoader(), 6823754623710L);

			assertEquals(0, restored2.size());
			restored2.setCurrentKey(1);
			assertEquals("u1", restored2.value());
			restored2.setCurrentKey(2);
			assertEquals("u2", restored2.value());
			restored2.setCurrentKey(3);
			assertEquals("u3", restored2.value());

			backend.close();
		} finally {
			deleteDirectorySilently(tempDir);
		}
	}

	@Test
	public void testCleanupTasks() throws Exception {
		DbBackendConfig conf = new DbBackendConfig("flink", "flink", url1);
		conf.setDbAdapter(new DerbyAdapter());

		DbStateBackend backend1 = new DbStateBackend(conf);
		DbStateBackend backend2 = new DbStateBackend(conf);
		DbStateBackend backend3 = new DbStateBackend(conf);

		backend1.initializeForJob(new DummyEnvironment("test", 3, 0));
		backend2.initializeForJob(new DummyEnvironment("test", 3, 1));
		backend3.initializeForJob(new DummyEnvironment("test", 3, 2));

		assertTrue(backend1.createKvState(1, "a", null, null, null).isCompacter());
		assertFalse(backend2.createKvState(1, "a", null, null, null).isCompacter());
		assertFalse(backend3.createKvState(1, "a", null, null, null).isCompacter());
	}

	@Test
	public void testCaching() throws Exception {
		
		List<String> urls = Lists.newArrayList(url1, url2);
		DbBackendConfig conf = new DbBackendConfig("flink", "flink",
				urls);

		conf.setDbAdapter(new DerbyAdapter());
		conf.setKvCacheSize(3);
		conf.setMaxKvInsertBatchSize(2);

		// We evict 2 elements when the cache is full
		conf.setMaxKvCacheEvictFraction(0.6f);

		DbStateBackend backend = new DbStateBackend(conf);

		Environment env = new DummyEnvironment("test", 2, 0);

		String tableName = "kvstate_" + env.getJobID() + "_1_state1";
		assertFalse(isTableCreated(DriverManager.getConnection(url1, "flink", "flink"), tableName));
		assertFalse(isTableCreated(DriverManager.getConnection(url2, "flink", "flink"), tableName));

		backend.initializeForJob(env);

		LazyDbKvState<Integer, String> kv = backend.createKvState(1, "state1", IntSerializer.INSTANCE,
				StringSerializer.INSTANCE, "a");
		
		assertTrue(isTableCreated(DriverManager.getConnection(url1, "flink", "flink"), tableName));
		assertTrue(isTableCreated(DriverManager.getConnection(url2, "flink", "flink"), tableName));
		
		Map<Integer, Optional<String>> cache = kv.getStateCache();
		Map<Integer, Optional<String>> modified = kv.getModified();

		assertEquals(0, kv.size());

		// some modifications to the state
		kv.setCurrentKey(1);
		assertEquals("a", kv.value());

		kv.update(null);
		assertEquals(1, kv.size());
		kv.setCurrentKey(2);
		assertEquals("a", kv.value());
		kv.update("2");
		assertEquals(2, kv.size());
		assertEquals("2", kv.value());

		kv.setCurrentKey(1);
		assertEquals("a", kv.value());

		kv.setCurrentKey(3);
		kv.update("3");
		assertEquals("3", kv.value());

		assertTrue(modified.containsKey(1));
		assertTrue(modified.containsKey(2));
		assertTrue(modified.containsKey(3));

		// 1,2 should be evicted as the cache filled
		kv.setCurrentKey(4);
		kv.update("4");
		assertEquals("4", kv.value());

		assertFalse(modified.containsKey(1));
		assertFalse(modified.containsKey(2));
		assertTrue(modified.containsKey(3));
		assertTrue(modified.containsKey(4));

		assertEquals(Optional.of("3"), cache.get(3));
		assertEquals(Optional.of("4"), cache.get(4));
		assertFalse(cache.containsKey(1));
		assertFalse(cache.containsKey(2));

		// draw a snapshot
		kv.snapshot(682375462378L, 100);

		assertTrue(modified.isEmpty());

		// make some more modifications
		kv.setCurrentKey(2);
		assertEquals("2", kv.value());
		kv.update(null);
		assertEquals("a", kv.value());

		assertTrue(modified.containsKey(2));
		assertEquals(1, modified.size());

		assertEquals(Optional.of("3"), cache.get(3));
		assertEquals(Optional.of("4"), cache.get(4));
		assertEquals(Optional.absent(), cache.get(2));
		assertFalse(cache.containsKey(1));

		assertTrue(modified.containsKey(2));
		assertFalse(modified.containsKey(3));
		assertFalse(modified.containsKey(4));
		assertTrue(cache.containsKey(3));
		assertTrue(cache.containsKey(4));

		// clear cache from initial keys

		kv.setCurrentKey(5);
		kv.value();
		kv.setCurrentKey(6);
		kv.value();
		kv.setCurrentKey(7);
		kv.value();

		assertFalse(modified.containsKey(5));
		assertTrue(modified.containsKey(6));
		assertTrue(modified.containsKey(7));

		assertFalse(cache.containsKey(1));
		assertFalse(cache.containsKey(2));
		assertFalse(cache.containsKey(3));
		assertFalse(cache.containsKey(4));

		kv.setCurrentKey(2);
		assertEquals("a", kv.value());

		long checkpointTs = System.currentTimeMillis();

		// Draw a snapshot that we will restore later
		KvStateSnapshot<Integer, String, DbStateBackend> snapshot1 = kv.snapshot(682375462379L, checkpointTs);
		assertTrue(modified.isEmpty());

		// Do some updates then draw another snapshot (imitate a partial
		// failure), these updates should not be visible if we restore snapshot1
		kv.setCurrentKey(1);
		kv.update("123");
		kv.setCurrentKey(3);
		kv.update("456");
		kv.setCurrentKey(2);
		kv.notifyCheckpointComplete(682375462379L);
		kv.update("2");
		kv.setCurrentKey(4);
		kv.update("4");
		kv.update("5");

		kv.snapshot(6823754623710L, checkpointTs + 10);

		// restore the second snapshot and validate it (we set a new default
		// value here to make sure that the default wasn't written)
		KvState<Integer, String, DbStateBackend> restored = snapshot1.restoreState(backend, IntSerializer.INSTANCE,
				StringSerializer.INSTANCE, "b", getClass().getClassLoader(), 6823754623711L);

		restored.setCurrentKey(1);
		assertEquals("b", restored.value());
		restored.setCurrentKey(2);
		assertEquals("b", restored.value());
		restored.setCurrentKey(3);
		assertEquals("3", restored.value());
		restored.setCurrentKey(4);
		assertEquals("4", restored.value());

		backend.close();
	}

	private static boolean isTableCreated(Connection con, String tableName) throws SQLException {
		return con.getMetaData().getTables(null, null, tableName.toUpperCase(), null).next();
	}

	private static boolean isTableEmpty(Connection con, String tableName) throws SQLException {
		try (Statement smt = con.createStatement()) {
			ResultSet res = smt.executeQuery("select * from " + tableName);
			return !res.next();
		}
	}

	private static boolean containsKey(Connection con, String tableName, int key, long ts)
			throws SQLException, IOException {
		try (PreparedStatement smt = con
				.prepareStatement("select * from " + tableName + " where k=? and timestamp=?")) {
			smt.setBytes(1, InstantiationUtil.serializeToByteArray(IntSerializer.INSTANCE, key));
			smt.setLong(2, ts);
			return smt.executeQuery().next();
		}
	}
	
	private static String localFileUri(File path) {
		return path.toURI().toString();
	}

	private static void deleteDirectorySilently(File dir) {
		try {
			FileUtils.deleteDirectory(dir);
		} catch (IOException ignored) {
		}
	}

}
