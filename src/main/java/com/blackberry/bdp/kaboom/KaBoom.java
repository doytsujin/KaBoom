/**
 * Copyright 2014 BlackBerry, Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package com.blackberry.bdp.kaboom;

import com.blackberry.bdp.common.jmx.MetricRegistrySingleton;
import com.blackberry.bdp.common.logger.InstrumentedLoggerSingleton;
import com.blackberry.bdp.common.props.Parser;
import com.blackberry.bdp.kaboom.api.KaBoomTopicConfig;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.hadoop.fs.FileSystem;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.zookeeper.KeeperException.NoNodeException;

public class KaBoom {

	private static final Logger LOG = LoggerFactory.getLogger(KaBoom.class);
	private static final Charset UTF8 = Charset.forName("UTF-8");
	boolean shutdown = false;
	private StartupConfig config;

	public static void main(String[] args) throws Exception {
		InstrumentedLoggerSingleton.getInstance();
		MetricRegistrySingleton.getInstance().enableJmx();

		LOG.info("*******************************************");
		LOG.info("***         KABOOM SERVER START         ***");
		LOG.info("*******************************************");

		new KaBoom().run();
	}

	public KaBoom() throws Exception {
	}

	private void run() throws Exception {
		if (Boolean.parseBoolean(System.getProperty("metrics.to.console", "false").trim())) {
			MetricRegistrySingleton.getInstance().enableConsole();
		}

		try {
			Properties props = StartupConfig.getProperties();
			Parser propsParser = new Parser(props);

			if (propsParser.parseBoolean("configuration.authority.zk", false)) {
				// TODO: ZK
			} else {
				LOG.info("Configuration authority is file based");
				config = new StartupConfig(props);
			}

			config.logConfiguraton();
		} catch (Exception e) {
			LOG.error("an error occured while building configuration object: ", e);
			throw e;
		}

		 // Instantiate the ZK curator and ensure that the required nodes exist
		for (String path : new String[]{"/kaboom/leader", "/kaboom/clients", "/kaboom/assignments", "/kaboom/flag-assignments"}) {
			if (config.getCurator().checkExists().forPath(path) == null) {
				try {
					LOG.warn("the path {} was not found in ZK and needs to be created", path);
					config.getCurator().create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
					LOG.warn("path {} created in ZK", path);
				} catch (Exception e) {
					LOG.error("Error creating ZooKeeper node {} ", path, e);
				}
			} else {
				LOG.info("required path {} already exists in zookeeper", path);
			}
		}

		{
			Yaml yaml = new Yaml();
			ByteArrayOutputStream nodeOutputStream = new ByteArrayOutputStream();
			OutputStreamWriter writer = new OutputStreamWriter(nodeOutputStream);

			KaBoomNodeInfo data = new KaBoomNodeInfo();
			data.setHostname(config.getHostname());
			data.setWeight(config.getWeight());
			yaml.dump(data, writer);
			writer.close();
			byte[] nodeContents = nodeOutputStream.toByteArray();
			long backoff = 1000;
			long retries = 8;

			for (int i = 0; i < retries; i++) {
				try {
					config.getCurator().create().withMode(CreateMode.EPHEMERAL).forPath("/kaboom/clients/" + config.getKaboomId(), nodeContents);
					break;
				} catch (Exception e) {
					if (i <= retries) {
						LOG.warn("Failed attempt {}/{} to register with ZooKeeper.  Retrying in {} seconds", i, retries, (backoff / 1000), e);
						Thread.sleep(backoff);
						backoff *= 2;
					} else {
						throw new Exception("Failed to register with ZooKeeper, no retries left--giving up", e);
					}
				}
			}
		}

		// Start leader election thread.  The leader assigns work to each instance
		Leader loadBalancer = null;

		if (config.getLoadBalancer().equals("even")) {
			loadBalancer = new EvenLoadBalancer(config);
		} else {
			if (config.getLoadBalancer().equals("local")) {
				loadBalancer = new LocalLoadBalancer(config);
			}
		}

		final LeaderSelector leaderSelector = new LeaderSelector(config.getCurator(), "/kaboom/leader", loadBalancer);
		leaderSelector.autoRequeue();
		leaderSelector.start();

		final Map<String, Worker> partitionToWorkerMap = new HashMap<>();
		final Map<String, Thread> partitionToThreadsMap = new HashMap<>();

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				shutdown();
				for (Map.Entry<String, Worker> entry : partitionToWorkerMap.entrySet()) {
					Worker w = entry.getValue();
					w.stop();
				}
				for (Map.Entry<String, Thread> entry : partitionToThreadsMap.entrySet()) {
					Thread t = entry.getValue();

					try {
						t.join();
					} catch (InterruptedException e) {
						LOG.error("Interrupted joining thread.", e);
					}
				}
				try {
					FileSystem.get(config.getHadoopConfiguration()).close();
				} catch (Throwable t) {
					LOG.error("Error closing Hadoop filesystem", t);
				}
				try {
					config.getCurator().delete().forPath("/kaboom/clients/" + config.getKaboomId());
				} catch (Exception e) {
					LOG.error("Error deleting /kaboom/clients/{}", config.getKaboomId(), e);
				}
				leaderSelector.close();
				config.getCurator().close();
			}

		}));

		Pattern topicPartitionPattern = Pattern.compile("^(.*)-(\\d+)$");
		
		long lastFlagPropagationTs = System.currentTimeMillis();
		
		Map<String, Thread> topicToFlagPropThread = new HashMap<>();
		Map<String, ReadyFlagPropagator> topicToFlagPropagator = new HashMap<>();

		while (shutdown == false) {
			if (config.getRunningConfig().isPropagateReadyFlags() && System.currentTimeMillis() 
				 > (lastFlagPropagationTs + config.getRunningConfig().getPropagateReadyFlagFrequency())) {
				List<String> topics = ReadyFlagController.getAssignments(config);
				LOG.info("Found a total of {} topics that we are assigned to propagate ready flags for", topics.size());
				for (String topic : topics) {
					if (topicToFlagPropThread.get(topic) != null && topicToFlagPropThread.get(topic).isAlive()) {
						LOG.warn("[{}] Flag propagator thread is still running", topic);
					} else {
						ReadyFlagPropagator flagPropagator = topicToFlagPropagator.get(topic);
						if (flagPropagator == null) {
							KaBoomTopicConfig topicConfig = KaBoomTopicConfig.get(
								 KaBoomTopicConfig.class, config.getCurator(), String.format("/kaboom/topics/%s", topic));
							flagPropagator = new ReadyFlagPropagator(topicConfig, config);
							topicToFlagPropagator.put(topic, flagPropagator);
						}
						Thread t = topicToFlagPropThread.get(topic);
						if (t == null) {
							t = new Thread(flagPropagator);
							topicToFlagPropThread.put(topic, t);
						} else {
							t = new Thread(flagPropagator);
						}
						t.start();
						LOG.info("Started flag propagator thread for {}", topic);
					}
				}
				lastFlagPropagationTs = System.currentTimeMillis();
			}

			// Get all my assignments and create a worker if there's anything not already being worked
			Map<String, Boolean> validWorkingPartitions = new HashMap<>();
			for (String partitionId : config.getCurator().getChildren().forPath("/kaboom/assignments")) {
				String assignee = null;
				try {
					assignee = new String(config.getCurator().getData().forPath("/kaboom/assignments/" 
						 + partitionId), UTF8);
				} catch (NoNodeException nne) {
					LOG.warn("The weird 'NoNodeException' has been raised, let's just continue and it'll retry");
					continue;
				}

				if (assignee.equals(Integer.toString(config.getKaboomId()))) {
					if (partitionToWorkerMap.containsKey(partitionId)) {
						if (false == partitionToThreadsMap.get(partitionId).isAlive()) {
							LOG.error("[{}] worker thead found dead (removed thread/worker objects)", partitionId);
							config.getDeadWorkerMeter().mark();
							partitionToThreadsMap.remove(partitionId);
							partitionToWorkerMap.remove(partitionId);
						} else {
							LOG.debug("KaBoom clientId {} assigned to partitonId {} and worker is already working", 
								 config.getKaboomId(), partitionId);
							validWorkingPartitions.put(partitionId, true);
						}
					} else {
						LOG.info("KaBoom clientId {} assigned to partitonId {} and a worker doesn't exist", 
							 config.getKaboomId(), partitionId);

						Matcher m = topicPartitionPattern.matcher(partitionId);

						if (m.matches()) {
							String topic = m.group(1);
							int partition = Integer.parseInt(m.group(2));

							Worker worker = new Worker(config, config.getCurator(), topic, partition);

							partitionToWorkerMap.put(partitionId, worker);
							partitionToThreadsMap.put(partitionId, new Thread(worker));
							partitionToThreadsMap.get(partitionId).start();

							LOG.info("KaBoom clientId {} assigned to partitonId {} and a new worker has been started", 
								 config.getKaboomId(), partitionId);

							validWorkingPartitions.put(partitionId, true);
						} else {
							LOG.error("Could not get topic and partition from node name. ({})", partitionId);
						}
					}
				} else {
					LOG.debug("{} is not interested in work assigned to {}", config.getKaboomId(), assignee);
				}
			}

			LOG.debug("There are {} entries in the partitons to workers mapping", partitionToWorkerMap.size());
			Iterator<Map.Entry<String, Worker>> iter = partitionToWorkerMap.entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<String, Worker> entry = iter.next();
				Worker worker = entry.getValue();
				if (!validWorkingPartitions.containsKey(worker.getPartitionId())) {
					worker.stop();
					LOG.info("Worker currently assigned to {} is no longer valid has been instructed to stop working", worker.getPartitionId());
				}
				if (worker.pinged()) {
					if (!worker.getPong()) {
						LOG.error("[{}] has been responded from being  pinged, interupting thread and sending kill request to  the worker",
							 worker.getPartitionId());
						partitionToThreadsMap.get(worker.getPartitionId()).interrupt();
						worker.kill();
						iter.remove();
					} else {
						worker.ping();
					}
				} else {
					LOG.info("[{}] inital ping", worker.getPartitionId());
					worker.ping();
				}
			}
			Thread.sleep(config.getRunningConfig().getKaboomServerSleepDurationMs());
		}
	}

	public void shutdown() {
		shutdown = true;
	}
}
