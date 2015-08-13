/*
 * Copyright 2015 BlackBerry Limited.
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
package com.blackberry.bdp.kaboom;

import com.blackberry.bdp.kaboom.api.KaBoomClient;
import com.blackberry.bdp.kaboom.api.KaBoomTopic;
import com.blackberry.bdp.kaboom.api.KafkaBroker;
import com.blackberry.bdp.kaboom.api.KafkaPartition;
import com.blackberry.bdp.kaboom.api.KafkaTopic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Partitions are assigned based off a weighted workload.  The default 
 * weighting is based on how many cores each client has. There will 
 * be a preference for local work while the client is under-loaded.
 * 
 * However, as soon as the client is over-loaded, work is arbitrarily assigned.
 * 
 */
public class EvenLoadBalancer extends Leader {

	private static final Logger LOG = LoggerFactory.getLogger(EvenLoadBalancer.class);

	public EvenLoadBalancer(StartupConfig config) {
		super(config);
		LOG.info("The even load balancer has been instantiated");
	}

	@Override
	protected void run_balancer(ArrayList<KafkaBroker> kafkaBrokers, 
		 ArrayList<KaBoomClient> kaboomClients,
		 ArrayList<KaBoomTopic> kaboomTopics, 
		 ArrayList<KafkaTopic> kafkaTopics) throws Exception {

		// First we need a mapping of Kafka broker ID to KafkaBroker
		HashMap<Integer, KafkaBroker> kafkaBrokerMap = new HashMap<>();
		for (KafkaBroker broker : kafkaBrokers) {
			kafkaBrokerMap.put(broker.getId(), broker);
		}
		
		// Iterate through all clients and build up a remote and local partition list
		for (KaBoomClient kaboomClient : kaboomClients) {
			List<String> localPartitions = new ArrayList<>();
			List<String> remotePartitions = new ArrayList<>();
			for (KafkaTopic kafkatopic : kafkaTopics) {
				for (KafkaPartition kafkaPartition : kafkatopic.getPartitions()) {
					
				}
			}

		}

		for (Map.Entry<String, KaBoomNodeInfo> e : clientIdToNodeInfo.entrySet()) {
			String client = e.getKey();
			KaBoomNodeInfo info = e.getValue();


			if (!clientToPartitions.containsKey(client)) {
				LOG.info("Skipping checking client {} for being overloaded because it has no work assigned", client);
				continue;
			}

			for (String partition : clientToPartitions.get(client)) {
				if (partitionToHost.get(partition).equals(info.getHostname())) {
					localPartitions.add(partition);
				} else {
					remotePartitions.add(partition);
				}
			}

			LOG.info("Client {} has {} local partitions and {} remote partitions assigned, load={} and target load={}",
				 client, localPartitions.size(), remotePartitions.size(), info.getLoad(), info.getTargetLoad());

			if (info.getLoad() >= info.getTargetLoad() + 1) {
				while (info.getLoad() > info.getTargetLoad()) {
					String partitionToDelete;
					if (remotePartitions.size() > 0) {
						partitionToDelete = remotePartitions.remove(rand.nextInt(remotePartitions.size()));
					} else {
						partitionToDelete = localPartitions.remove(rand.nextInt(localPartitions.size()));
					}

					LOG.info("Unassgning {} from overloaded client {}", partitionToDelete, client);
					partitionToClient.remove(partitionToDelete);
					clientToPartitions.get(client).remove(partitionToDelete);
					info.setLoad(info.getLoad() - 1);

					try {
						curator.delete().forPath("/kaboom/assignments/" + partitionToDelete);
						LOG.info("Deleted assignment {}:", "/kaboom/assignments/" + partitionToDelete);
					} catch (Exception ex) {
						LOG.error("Failed to delete assignment {}:", "/kaboom/assignments/" + partitionToDelete, ex);
					}

				}
			}
		}

		// Sort the clientIdToNodeInfo by percent load, then add unassigned clientIdToNodeInfo to the lowest loaded client			
		{
			List<String> sortedClients = new ArrayList<>();
			Comparator<String> comparator = new Comparator<String>() {
				@Override
				public int compare(String a, String b) {
					KaBoomNodeInfo infoA = clientIdToNodeInfo.get(a);
					double valA = infoA.getLoad() / infoA.getTargetLoad();

					KaBoomNodeInfo infoB = clientIdToNodeInfo.get(b);
					double valB = infoB.getLoad() / infoB.getTargetLoad();

					if (valA == valB) {
						return 0;
					} else {
						if (valA > valB) {
							return 1;
						} else {
							return -1;
						}
					}
				}

			};

			sortedClients.addAll(clientIdToNodeInfo.keySet());

			for (String partition : partitionToHost.keySet()) {
				// If it's already assigned or if it's not supported, skip it

				if (partitionToClient.containsKey(partition)) {
					LOG.debug("[{}] is already assigned", partition);
					continue;
				}

				Pattern topicPartitionPattern = Pattern.compile("^(.*)-(\\d+)$");
				Matcher m = topicPartitionPattern.matcher(partition);
				String topic;

				if (m.matches()) {
					topic = m.group(1);
				} else {
					LOG.error("[{}] can't parse topic from partitionId");
					continue;
				}

				if (false == config.getTopicToSupportedStatus().containsKey(topic)
					 || false == config.getTopicToSupportedStatus().get(topic)) {
					continue;
				}

				Collections.sort(sortedClients, comparator);

				/**
				 * Iterate through the list until we find either a local client below capacity, or we reach the ones that are 
				 * above capacity.  If we reach clients above capacity, then just assign it to the first node.
				 */
				LOG.info("Going to assign {}", partition);
				String chosenClient = null;

				for (String client : sortedClients) {
					LOG.info("- Checking {}", client);
					KaBoomNodeInfo info = clientIdToNodeInfo.get(client);
					LOG.info("- Current load = {}, Target load =  {}", info.getLoad(), info.getTargetLoad());

					if (info.getLoad() >= info.getTargetLoad()) {
						chosenClient = sortedClients.get(0);
						break;
					} else {
						if (clientIdToNodeInfo.get(client).getHostname().equals(partitionToHost.get(partition))) {
							chosenClient = client;
							break;
						}
					}
				}

				if (chosenClient == null) {
					chosenClient = sortedClients.get(0);
				}

				LOG.info("Assigning partition {} to client {}", partition, chosenClient);

				try {
					curator
						 .create()
						 .withMode(CreateMode.PERSISTENT)
						 .forPath("/kaboom/assignments/" + partition, chosenClient.getBytes(UTF8));
				} catch (Exception e) {
					LOG.error("Failed to create assignment {}", "/kaboom/assignments/" + partition, chosenClient, e);
				}

				List<String> parts = clientToPartitions.get(chosenClient);

				if (parts == null) {
					parts = new ArrayList<>();
					clientToPartitions.put(chosenClient, parts);
				}

				parts.add(partition);

				partitionToClient.put(partition, chosenClient);

				clientIdToNodeInfo.get(chosenClient).setLoad(clientIdToNodeInfo.get(chosenClient).getLoad() + 1);
			}
		}
	}

}
