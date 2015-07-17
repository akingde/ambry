package com.github.ambry.replication;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.config.ReplicationConfig;
import com.github.ambry.messageformat.DeleteMessageFormatInputStream;
import com.github.ambry.messageformat.MessageFormatException;
import com.github.ambry.messageformat.MessageFormatFlags;
import com.github.ambry.messageformat.MessageFormatInputStream;
import com.github.ambry.messageformat.MessageFormatWriteSet;
import com.github.ambry.messageformat.MessageSievingInputStream;
import com.github.ambry.network.Port;
import com.github.ambry.notification.BlobReplicaSourceType;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.network.ChannelOutput;
import com.github.ambry.network.ConnectedChannel;
import com.github.ambry.network.ConnectionPool;
import com.github.ambry.network.PortType;
import com.github.ambry.protocol.GetOptions;
import com.github.ambry.protocol.GetRequest;
import com.github.ambry.protocol.GetResponse;
import com.github.ambry.protocol.PartitionRequestInfo;
import com.github.ambry.protocol.PartitionResponseInfo;
import com.github.ambry.protocol.ReplicaMetadataRequest;
import com.github.ambry.protocol.ReplicaMetadataRequestInfo;
import com.github.ambry.protocol.ReplicaMetadataResponse;
import com.github.ambry.protocol.ReplicaMetadataResponseInfo;
import com.github.ambry.store.FindToken;
import com.github.ambry.store.FindTokenFactory;
import com.github.ambry.store.MessageInfo;
import com.github.ambry.store.StoreErrorCodes;
import com.github.ambry.store.StoreException;
import com.github.ambry.store.StoreKey;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.SystemTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * A replica thread is responsible for handling replication for a set of partitions assigned to it
 */
class ReplicaThread implements Runnable {

  private final Map<DataNodeId, List<RemoteReplicaInfo>> replicasToReplicateGroupedByNode;
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private volatile boolean running;
  private boolean needToWaitForReplicaLag;
  private final FindTokenFactory findTokenFactory;
  private final ClusterMap clusterMap;
  private final AtomicInteger correlationIdGenerator;
  private final DataNodeId dataNodeId;
  private final ConnectionPool connectionPool;
  private final ReplicationConfig replicationConfig;
  private final ReplicationMetrics replicationMetrics;
  private final String threadName;
  private final NotificationSystem notification;
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final StoreKeyFactory storeKeyFactory;
  private final boolean validateMessageStream;
  private final MetricRegistry metricRegistry;
  private final ArrayList<String> sslEnabledColos;

  public ReplicaThread(String threadName, Map<DataNodeId, List<RemoteReplicaInfo>> replicasToReplicateGroupedByNode,
      FindTokenFactory findTokenFactory, ClusterMap clusterMap, AtomicInteger correlationIdGenerator,
      DataNodeId dataNodeId, ConnectionPool connectionPool, ReplicationConfig replicationConfig,
      ReplicationMetrics replicationMetrics, NotificationSystem notification, StoreKeyFactory storeKeyFactory,
      boolean validateMessageStream, MetricRegistry metricRegistry, ArrayList<String> sslEnabledColos) {
    this.threadName = threadName;
    this.replicasToReplicateGroupedByNode = replicasToReplicateGroupedByNode;
    this.running = true;
    this.findTokenFactory = findTokenFactory;
    this.clusterMap = clusterMap;
    this.correlationIdGenerator = correlationIdGenerator;
    this.dataNodeId = dataNodeId;
    this.connectionPool = connectionPool;
    this.replicationConfig = replicationConfig;
    this.replicationMetrics = replicationMetrics;
    this.notification = notification;
    this.needToWaitForReplicaLag = true;
    this.storeKeyFactory = storeKeyFactory;
    this.validateMessageStream = validateMessageStream;
    this.metricRegistry = metricRegistry;
    this.sslEnabledColos = sslEnabledColos;
  }

  public String getName() {
    return threadName;
  }

  @Override
  public void run() {
    try {
      logger.trace("Starting replica thread on Local node: " + dataNodeId + " Thread name: " + threadName);
      List<List<RemoteReplicaInfo>> replicasToReplicate =
          new ArrayList<List<RemoteReplicaInfo>>(replicasToReplicateGroupedByNode.size());
      for (Map.Entry<DataNodeId, List<RemoteReplicaInfo>> replicasToReplicateEntry : replicasToReplicateGroupedByNode
          .entrySet()) {
        logger.info("Remote node: " + replicasToReplicateEntry.getKey() +
            " Thread name: " + threadName +
            " ReplicasToReplicate: " + replicasToReplicateEntry.getValue());
        replicasToReplicate.add(replicasToReplicateEntry.getValue());
      }
      logger.info("Begin iteration for thread " + threadName);
      while (running) {
        // shuffle the nodes
        Collections.shuffle(replicasToReplicate);
        for (List<RemoteReplicaInfo> replicasToReplicatePerNode : replicasToReplicate) {
          if (!running) {
            break;
          }
          DataNodeId remoteNode = replicasToReplicatePerNode.get(0).getReplicaId().getDataNodeId();
          logger.trace("Remote node: {} Thread name: {} Remote replicas: {}", remoteNode, threadName,
              replicasToReplicatePerNode);
          boolean remoteColo = true;
          if (dataNodeId.getDatacenterName().equals(remoteNode.getDatacenterName())) {
            remoteColo = false;
          }
          Timer.Context context = null;
          if (remoteColo) {
            context = replicationMetrics.interColoReplicationLatency.time();
          } else {
            context = replicationMetrics.intraColoReplicationLatency.time();
          }
          ConnectedChannel connectedChannel = null;
          long checkoutConnectionTimeInMs = -1;
          long exchangeMetadataTimeInMs = -1;
          long fixMissingStoreKeysTimeInMs = -1;
          long replicationStartTimeInMs = SystemTime.getInstance().milliseconds();
          long startTimeInMs = replicationStartTimeInMs;
          try {
            if (sslEnabledColos.contains(remoteNode.getDatacenterName())) {
              replicationMetrics.sslConnectionsRequestRate.mark();
              logger.error("No SSL Connections should be established for replica " + remoteNode);
              connectedChannel = connectionPool
                  .checkOutConnection(remoteNode.getHostname(), new Port(remoteNode.getPort(), PortType.SSL),
                      replicationConfig.replicationConnectionPoolCheckoutTimeoutMs);
            } else {
              replicationMetrics.plainTextConnectionsRequestRate.mark();
              connectedChannel = connectionPool
                  .checkOutConnection(remoteNode.getHostname(), new Port(remoteNode.getPort(), PortType.PLAINTEXT),
                      replicationConfig.replicationConnectionPoolCheckoutTimeoutMs);
            }
            checkoutConnectionTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;

            startTimeInMs = SystemTime.getInstance().milliseconds();
            List<ExchangeMetadataResponse> exchangeMetadataResponseList =
                exchangeMetadata(connectedChannel, replicasToReplicatePerNode, remoteColo);
            exchangeMetadataTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;

            startTimeInMs = SystemTime.getInstance().milliseconds();
            fixMissingStoreKeys(connectedChannel, replicasToReplicatePerNode, remoteColo, exchangeMetadataResponseList);
            fixMissingStoreKeysTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;
          } catch (Exception e) {
            if (checkoutConnectionTimeInMs == -1) {
              // exception happened in checkout connection phase
              checkoutConnectionTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;
            } else if (exchangeMetadataTimeInMs == -1) {
              // exception happened in exchange metadata phase
              exchangeMetadataTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;
            } else if (fixMissingStoreKeysTimeInMs == -1) {
              // exception happened in fix missing store phase
              fixMissingStoreKeysTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;
            }
            StringBuilder strBuilder = new StringBuilder();
            strBuilder.append("Remote node: ").append(remoteNode);
            strBuilder.append(" Thread name: ").append(threadName);
            strBuilder.append(" Remote replicas: ").append(replicasToReplicatePerNode);
            strBuilder.append(" Error while replicating with remote replica ");
            strBuilder.append(" Checkout connection time: ").append(checkoutConnectionTimeInMs);
            strBuilder.append(" Exchange metadata time: ").append(exchangeMetadataTimeInMs);
            strBuilder.append(" Fix missing store key time: ").append(fixMissingStoreKeysTimeInMs);

            if (logger.isTraceEnabled()) {
              logger.trace(strBuilder.toString(), e);
            } else {
              logger.error(strBuilder.toString() + e);
            }
            replicationMetrics.replicationErrors.inc();
            if (connectedChannel != null) {
              connectionPool.destroyConnection(connectedChannel);
              connectedChannel = null;
            }
          } catch (Throwable e) {
            logger.error("Remote node: " + remoteNode +
                " Thread name: " + threadName +
                " Remote replicas: " + replicasToReplicatePerNode +
                " Throwable exception while replicating with remote replica ", e);
            replicationMetrics.replicationErrors.inc();
            if (connectedChannel != null) {
              connectionPool.destroyConnection(connectedChannel);
              connectedChannel = null;
            }
          } finally {
            if (remoteColo) {
              replicationMetrics.interColoTotalReplicationTime
                  .update(SystemTime.getInstance().milliseconds() - replicationStartTimeInMs);
            } else {
              replicationMetrics.intraColoTotalReplicationTime
                  .update(SystemTime.getInstance().milliseconds() - replicationStartTimeInMs);
            }

            if (connectedChannel != null) {
              connectionPool.checkInConnection(connectedChannel);
            }
            context.stop();
          }
        }
      }
    } finally {
      running = false;
      shutdownLatch.countDown();
    }
  }

  /**
   * Gets all the metadata about messages from the remote replicas since last token. Checks the messages with the local
   * store and finds all the messages that are missing. For the messages that are not missing, updates the delete
   * and ttl state.
   * @param connectedChannel The connected channel that represents a connection to the remote replica
   * @param replicasToReplicatePerNode The information about the replicas that is being replicated
   * @param remoteColo True, if the replicas are from remote DC. False, if the replicas are from local DC
   * @return - List of ExchangeMetadataResponse that contains the set of store keys that are missing from the local
   *           store and are present in the remote replicas and also the new token from the remote replicas
   * @throws IOException
   * @throws StoreException
   * @throws MessageFormatException
   * @throws ReplicationException
   * @throws InterruptedException
   */
  protected List<ExchangeMetadataResponse> exchangeMetadata(ConnectedChannel connectedChannel,
      List<RemoteReplicaInfo> replicasToReplicatePerNode, boolean remoteColo)
      throws IOException, ReplicationException, InterruptedException {

    long exchangeMetadataStartTimeInMs = SystemTime.getInstance().milliseconds();
    List<ExchangeMetadataResponse> exchangeMetadataResponseList = new ArrayList<ExchangeMetadataResponse>();
    if (replicasToReplicatePerNode.size() > 0) {
      try {
        DataNodeId remoteNode = replicasToReplicatePerNode.get(0).getReplicaId().getDataNodeId();
        ReplicaMetadataResponse response =
            getReplicaMetadataResponse(replicasToReplicatePerNode, connectedChannel, remoteNode, remoteColo);
        long startTimeInMs = SystemTime.getInstance().milliseconds();
        needToWaitForReplicaLag = true;
        for (int i = 0; i < response.getReplicaMetadataResponseInfoList().size(); i++) {
          RemoteReplicaInfo remoteReplicaInfo = replicasToReplicatePerNode.get(i);
          ReplicaMetadataResponseInfo replicaMetadataResponseInfo =
              response.getReplicaMetadataResponseInfoList().get(i);
          if (replicaMetadataResponseInfo.getError() == ServerErrorCode.No_Error) {
            try {
              logger.trace("Remote node: {} Thread name: {} Remote replica: {} Token from remote: {} Replica lag: {} ",
                  remoteNode, threadName, remoteReplicaInfo.getReplicaId(), replicaMetadataResponseInfo.getFindToken(),
                  replicaMetadataResponseInfo.getRemoteReplicaLagInBytes());
              checkNeedWaitTime(replicaMetadataResponseInfo, remoteNode, remoteReplicaInfo, remoteColo);
              Set<StoreKey> missingStoreKeys =
                  getMissingStoreKeys(replicaMetadataResponseInfo, remoteNode, remoteReplicaInfo, remoteColo);
              processReplicaMetadataResponse(missingStoreKeys, replicaMetadataResponseInfo, remoteReplicaInfo,
                  remoteNode, remoteColo);
              ExchangeMetadataResponse exchangeMetadataResponse =
                  new ExchangeMetadataResponse(missingStoreKeys, replicaMetadataResponseInfo.getFindToken());
              exchangeMetadataResponseList.add(exchangeMetadataResponse);
            } catch (Exception e) {
              replicationMetrics.updateLocalStoreError(remoteReplicaInfo);
              logger.error("Remote node: " + remoteNode + " Thread name: " + threadName +
                  " Remote replica: " + remoteReplicaInfo.getReplicaId(), e);
              ExchangeMetadataResponse exchangeMetadataResponse =
                  new ExchangeMetadataResponse(ServerErrorCode.Unknown_Error);
              exchangeMetadataResponseList.add(exchangeMetadataResponse);
            }
          } else {
            replicationMetrics.updateMetadataRequestError(remoteReplicaInfo);
            logger.error("Remote node: {} Thread name: {} Remote replica: {} Server error: {}", remoteNode, threadName,
                remoteReplicaInfo.getReplicaId(), replicaMetadataResponseInfo.getError());
            ExchangeMetadataResponse exchangeMetadataResponse =
                new ExchangeMetadataResponse(replicaMetadataResponseInfo.getError());
            exchangeMetadataResponseList.add(exchangeMetadataResponse);
          }
        }
        long processMetadataResponseTimeInMs = SystemTime.getInstance().milliseconds() - startTimeInMs;
        logger.trace("Remote node: {} Thread name: {} processMetadataResponseTime: {}", remoteNode, threadName,
            processMetadataResponseTimeInMs);
      } finally {
        if (remoteColo) {
          replicationMetrics.interColoMetadataExchangeCount.inc();
          replicationMetrics.interColoExchangeMetadataTime
              .update(SystemTime.getInstance().milliseconds() - exchangeMetadataStartTimeInMs);
        } else {
          replicationMetrics.intraColoMetadataExchangeCount.inc();
          replicationMetrics.intraColoExchangeMetadataTime
              .update(SystemTime.getInstance().milliseconds() - exchangeMetadataStartTimeInMs);
        }
      }
    }
    return exchangeMetadataResponseList;
  }

  /**
   * Gets all the messages from the remote node for the missing keys and writes them to the local store
   * @param connectedChannel The connected channel that represents a connection to the remote replica
   * @param replicasToReplicatePerNode The information about the replicas that is being replicated
   * @param remoteColo True, if the replicas are from remote DC. False, if the replicas are from local DC
   * @param exchangeMetadataResponseList The missing keys in the local stores whose message needs to be retrieved
   *                                     from the remote stores
   * @throws IOException
   * @throws StoreException
   * @throws MessageFormatException
   * @throws ReplicationException
   */
  protected void fixMissingStoreKeys(ConnectedChannel connectedChannel,
      List<RemoteReplicaInfo> replicasToReplicatePerNode, boolean remoteColo,
      List<ExchangeMetadataResponse> exchangeMetadataResponseList)
      throws IOException, StoreException, MessageFormatException, ReplicationException {
    long fixMissingStoreKeysStartTimeInMs = SystemTime.getInstance().milliseconds();
    try {
      if (exchangeMetadataResponseList.size() != replicasToReplicatePerNode.size()
          || replicasToReplicatePerNode.size() == 0) {
        throw new IllegalArgumentException("ExchangeMetadataResponseList size " + exchangeMetadataResponseList.size() +
            " and replicasToReplicatePerNode size " + replicasToReplicatePerNode.size() +
            " should be the same and greater than zero");
      }
      DataNodeId remoteNode = replicasToReplicatePerNode.get(0).getReplicaId().getDataNodeId();
      GetResponse getResponse =
          getMessagesForMissingKeys(connectedChannel, exchangeMetadataResponseList, replicasToReplicatePerNode,
              remoteNode, remoteColo);
      writeMessagesToLocalStore(exchangeMetadataResponseList, getResponse, replicasToReplicatePerNode, remoteNode,
          remoteColo);
    } finally {
      if (remoteColo) {
        replicationMetrics.interColoFixMissingKeysTime
            .update(SystemTime.getInstance().milliseconds() - fixMissingStoreKeysStartTimeInMs);
      } else {
        replicationMetrics.intraColoFixMissingKeysTime
            .update(SystemTime.getInstance().milliseconds() - fixMissingStoreKeysStartTimeInMs);
      }
    }
  }

  /**
   * Gets the replica metadata response for a list of remote replicas on a given remote data node
   * @param replicasToReplicatePerNode The list of remote replicas for a node
   * @param connectedChannel The connection channel to the node
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteColo True, if the remote node is in a remote colo, False otherwise
   * @return ReplicaMetadataResponse, the response from replica metadata request to remote node
   * @throws ReplicationException
   * @throws IOException
   */
  private ReplicaMetadataResponse getReplicaMetadataResponse(List<RemoteReplicaInfo> replicasToReplicatePerNode,
      ConnectedChannel connectedChannel, DataNodeId remoteNode, boolean remoteColo)
      throws ReplicationException, IOException {
    long replicaMetadataRequestStartTime = SystemTime.getInstance().milliseconds();
    List<ReplicaMetadataRequestInfo> replicaMetadataRequestInfoList = new ArrayList<ReplicaMetadataRequestInfo>();
    for (RemoteReplicaInfo remoteReplicaInfo : replicasToReplicatePerNode) {
      ReplicaMetadataRequestInfo replicaMetadataRequestInfo =
          new ReplicaMetadataRequestInfo(remoteReplicaInfo.getReplicaId().getPartitionId(),
              remoteReplicaInfo.getToken(), dataNodeId.getHostname(),
              remoteReplicaInfo.getLocalReplicaId().getReplicaPath());
      replicaMetadataRequestInfoList.add(replicaMetadataRequestInfo);
      logger
          .trace("Remote node: {} Thread name: {} Remote replica: {} Token going to be sent to remote: {} ", remoteNode,
              threadName, remoteReplicaInfo.getReplicaId(), remoteReplicaInfo.getToken());
    }
    ReplicaMetadataRequest request = new ReplicaMetadataRequest(correlationIdGenerator.incrementAndGet(),
        "replication-metadata-" + dataNodeId.getHostname(), replicaMetadataRequestInfoList,
        replicationConfig.replicationFetchSizeInBytes);
    connectedChannel.send(request);
    ChannelOutput channelOutput = connectedChannel.receive();
    ByteBufferInputStream byteBufferInputStream =
        new ByteBufferInputStream(channelOutput.getInputStream(), (int) channelOutput.getStreamSize());
    logger.trace("Remote node: {} Thread name: {} Remote replicas: {} ByteBuffer size after deserialization: {} ",
        remoteNode, threadName, replicasToReplicatePerNode, byteBufferInputStream.available());
    ReplicaMetadataResponse response =
        ReplicaMetadataResponse.readFrom(new DataInputStream(byteBufferInputStream), findTokenFactory, clusterMap);

    if (remoteColo) {
      replicationMetrics.interColoReplicationMetadataRequestTime
          .update(SystemTime.getInstance().milliseconds() - replicaMetadataRequestStartTime);
    } else {
      replicationMetrics.intraColoReplicationMetadataRequestTime
          .update(SystemTime.getInstance().milliseconds() - replicaMetadataRequestStartTime);
    }
    if (response.getError() != ServerErrorCode.No_Error
        || response.getReplicaMetadataResponseInfoList().size() != replicasToReplicatePerNode.size()) {
      logger.error("Remote node: " + remoteNode +
          " Thread name: " + threadName +
          " Remote replicas: " + replicasToReplicatePerNode +
          " Replica metadata response error: " + response.getError() +
          " ReplicaMetadataResponseInfoListSize: " + response.getReplicaMetadataResponseInfoList().size() +
          " ReplicasToReplicatePerNodeSize: " + replicasToReplicatePerNode.size());
      throw new ReplicationException("Replica Metadata Response Error " + response.getError());
    }
    return response;
  }

  /**
   * Gets the missing store keys by comparing the messages from the remote node
   * @param replicaMetadataResponseInfo The response that contains the messages from the remote node
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteReplicaInfo The remote replica that contains information about the remote replica id
   * @param remoteColo True, if the remote node is in a remote colo, False otherwise
   * @return List of store keys that are missing from the local store
   * @throws StoreException
   */
  private Set<StoreKey> getMissingStoreKeys(ReplicaMetadataResponseInfo replicaMetadataResponseInfo,
      DataNodeId remoteNode, RemoteReplicaInfo remoteReplicaInfo, boolean remoteColo)
      throws StoreException {
    long startTime = SystemTime.getInstance().milliseconds();
    List<MessageInfo> messageInfoList = replicaMetadataResponseInfo.getMessageInfoList();
    List<StoreKey> storeKeysToCheck = new ArrayList<StoreKey>(messageInfoList.size());
    for (MessageInfo messageInfo : messageInfoList) {
      storeKeysToCheck.add(messageInfo.getStoreKey());
      logger.trace("Remote node: {} Thread name: {} Remote replica: {} Key from remote: {}", remoteNode, threadName,
          remoteReplicaInfo.getReplicaId(), messageInfo.getStoreKey());
    }

    Set<StoreKey> missingStoreKeys = remoteReplicaInfo.getLocalStore().findMissingKeys(storeKeysToCheck);
    for (StoreKey storeKey : missingStoreKeys) {
      logger.trace("Remote node: {} Thread name: {} Remote replica: {} Key missing id: {}", remoteNode, threadName,
          remoteReplicaInfo.getReplicaId(), storeKey);
    }
    if (remoteColo) {
      replicationMetrics.interColoCheckMissingKeysTime.update(SystemTime.getInstance().milliseconds() - startTime);
    } else {
      replicationMetrics.intraColoCheckMissingKeysTime.update(SystemTime.getInstance().milliseconds() - startTime);
    }
    return missingStoreKeys;
  }

  /**
   * Takes the missing keys and the message list from the remote store and identifies messages that are deleted
   * on the remote store and updates them locally. Also, if the message that is missing is deleted in the remote
   * store, we remove the message from the list of missing keys
   * @param missingStoreKeys The list of keys missing from the local store
   * @param replicaMetadataResponseInfo The replica metadata response from the remote store
   * @param remoteReplicaInfo The remote replica that is being replicated from
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteColo True, if the remote node is in remote colo, False otherwise
   * @throws IOException
   * @throws StoreException
   * @throws MessageFormatException
   */
  private void processReplicaMetadataResponse(Set<StoreKey> missingStoreKeys,
      ReplicaMetadataResponseInfo replicaMetadataResponseInfo, RemoteReplicaInfo remoteReplicaInfo,
      DataNodeId remoteNode, boolean remoteColo)
      throws IOException, StoreException, MessageFormatException {
    long startTime = SystemTime.getInstance().milliseconds();
    List<MessageInfo> messageInfoList = replicaMetadataResponseInfo.getMessageInfoList();
    for (MessageInfo messageInfo : messageInfoList) {
      BlobId blobId = (BlobId) messageInfo.getStoreKey();
      if (remoteReplicaInfo.getLocalReplicaId().getPartitionId().compareTo(blobId.getPartition()) != 0) {
        throw new IllegalStateException("Blob id is not in the expected partition Actual partition " +
            blobId.getPartition() + " Expected partition " + remoteReplicaInfo.getLocalReplicaId().getPartitionId());
      }
      if (!missingStoreKeys.contains(messageInfo.getStoreKey())) {
        // the key is present in the local store. Mark it for deletion if it is deleted in the remote store
        if (messageInfo.isDeleted() && !remoteReplicaInfo.getLocalStore().isKeyDeleted(messageInfo.getStoreKey())) {
          MessageFormatInputStream deleteStream = new DeleteMessageFormatInputStream(messageInfo.getStoreKey());
          MessageInfo info = new MessageInfo(messageInfo.getStoreKey(), deleteStream.getSize(), true);
          ArrayList<MessageInfo> infoList = new ArrayList<MessageInfo>();
          infoList.add(info);
          MessageFormatWriteSet writeset = new MessageFormatWriteSet(deleteStream, infoList, false);
          remoteReplicaInfo.getLocalStore().delete(writeset);
          logger.trace("Remote node: {} Thread name: {} Remote replica: {} Key deleted. mark for deletion id: {}",
              remoteNode, threadName, remoteReplicaInfo.getReplicaId(), messageInfo.getStoreKey());
          if (notification != null) {
            notification
                .onBlobReplicaDeleted(dataNodeId.getHostname(), dataNodeId.getPort(), messageInfo.getStoreKey().getID(),
                    BlobReplicaSourceType.REPAIRED);
          }
        }
      } else {
        if (messageInfo.isDeleted()) {
          // if the remote replica has the message in deleted state, it is not considered missing locally
          missingStoreKeys.remove(messageInfo.getStoreKey());
          logger
              .trace("Remote node: {} Thread name: {} Remote replica: {} Key in deleted state remotely: {}", remoteNode,
                  threadName, remoteReplicaInfo.getReplicaId(), messageInfo.getStoreKey());
          if (notification != null) {
            notification
                .onBlobReplicaDeleted(dataNodeId.getHostname(), dataNodeId.getPort(), messageInfo.getStoreKey().getID(),
                    BlobReplicaSourceType.REPAIRED);
          }
        } else if (messageInfo.isExpired()) {
          // if the remote replica has an object that is expired, it is not considered missing locally
          missingStoreKeys.remove(messageInfo.getStoreKey());
          logger
              .trace("Remote node: {} Thread name: {} Remote replica: {} Key in expired state remotely {}", remoteNode,
                  threadName, remoteReplicaInfo.getReplicaId(), messageInfo.getStoreKey());
        }
      }
    }
    if (remoteColo) {
      replicationMetrics.interColoProcessMetadataResponseTime
          .update(SystemTime.getInstance().milliseconds() - startTime);
    } else {
      replicationMetrics.intraColoProcessMetadataResponseTime
          .update(SystemTime.getInstance().milliseconds() - startTime);
    }
  }

  /**
   * Checks to see if we need to wait between replication iterations
   * @param replicaMetadataResponseInfo The replica metadata response from the remote node
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteReplicaInfo The remote replica that is being replicated from
   * @param remoteColo True, if the remote node belongs to a remote colo, False otherwise
   * @throws InterruptedException
   */
  private void checkNeedWaitTime(ReplicaMetadataResponseInfo replicaMetadataResponseInfo, DataNodeId remoteNode,
      RemoteReplicaInfo remoteReplicaInfo, boolean remoteColo)
      throws InterruptedException {
    long remoteReplicaLag = replicaMetadataResponseInfo.getRemoteReplicaLagInBytes();

    long startTime = SystemTime.getInstance().milliseconds();
    if (remoteReplicaLag < replicationConfig.replicationMaxLagForWaitTimeInBytes && needToWaitForReplicaLag
        && !remoteColo) {
      logger.trace("Remote node: {} Thread name: {} Remote replica: {} Remote replica lag: {} "
          + "ReplicationMaxLagForWaitTimeInBytes: {} Waiting for {} ms", remoteNode, threadName,
          remoteReplicaInfo.getReplicaId(), replicaMetadataResponseInfo.getRemoteReplicaLagInBytes(),
          replicationConfig.replicationMaxLagForWaitTimeInBytes, replicationConfig.replicaWaitTimeBetweenReplicasMs);
      // We apply the wait time between replication from remote replicas here. Any new objects that get written
      // in the remote replica are given time to be written to the local replica and avoids failing the request
      // from the client. This is done only when the replication lag with that node is less than
      // replicationMaxLagForWaitTimeInBytes
      Thread.sleep(replicationConfig.replicaWaitTimeBetweenReplicasMs);
      needToWaitForReplicaLag = false;
    }
    if (remoteColo) {
      replicationMetrics.interColoReplicationWaitTime.update(SystemTime.getInstance().milliseconds() - startTime);
    } else {
      replicationMetrics.intraColoReplicationWaitTime.update(SystemTime.getInstance().milliseconds() - startTime);
    }
  }

  /**
   * Gets the messgaes for the keys that are missing from the local store
   * @param connectedChannel The connection channel to the remote node
   * @param exchangeMetadataResponseList The list of metadata response from the remote node
   * @param replicasToReplicatePerNode The list of remote replicas for the remote node
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteColo True, if the remote node is in remote colo, False otherwise
   * @return The response that contains the missing messages
   * @throws ReplicationException
   * @throws IOException
   */
  private GetResponse getMessagesForMissingKeys(ConnectedChannel connectedChannel,
      List<ExchangeMetadataResponse> exchangeMetadataResponseList, List<RemoteReplicaInfo> replicasToReplicatePerNode,
      DataNodeId remoteNode, boolean remoteColo)
      throws ReplicationException, IOException {
    List<PartitionRequestInfo> partitionRequestInfoList = new ArrayList<PartitionRequestInfo>();
    for (int i = 0; i < exchangeMetadataResponseList.size(); i++) {
      ExchangeMetadataResponse exchangeMetadataResponse = exchangeMetadataResponseList.get(i);
      RemoteReplicaInfo remoteReplicaInfo = replicasToReplicatePerNode.get(i);
      if (exchangeMetadataResponse.serverErrorCode == ServerErrorCode.No_Error) {
        Set<StoreKey> missingStoreKeys = exchangeMetadataResponse.missingStoreKeys;
        if (missingStoreKeys.size() > 0) {
          ArrayList<BlobId> keysToFetch = new ArrayList<BlobId>();
          for (StoreKey storeKey : missingStoreKeys) {
            keysToFetch.add((BlobId) storeKey);
          }
          PartitionRequestInfo partitionRequestInfo =
              new PartitionRequestInfo(remoteReplicaInfo.getReplicaId().getPartitionId(), keysToFetch);
          partitionRequestInfoList.add(partitionRequestInfo);
        }
      }
    }
    GetRequest getRequest =
        new GetRequest(correlationIdGenerator.incrementAndGet(), "replication-fetch-" + dataNodeId.getHostname(),
            MessageFormatFlags.All, partitionRequestInfoList, GetOptions.None);
    long startTime = SystemTime.getInstance().milliseconds();
    connectedChannel.send(getRequest);
    ChannelOutput channelOutput = connectedChannel.receive();
    GetResponse getResponse = GetResponse.readFrom(new DataInputStream(channelOutput.getInputStream()), clusterMap);
    if (remoteColo) {
      replicationMetrics.interColoGetRequestTime.update(SystemTime.getInstance().milliseconds() - startTime);
    } else {
      replicationMetrics.intraColoGetRequestTime.update(SystemTime.getInstance().milliseconds() - startTime);
    }
    if (getResponse.getError() != ServerErrorCode.No_Error) {
      logger.error("Remote node: " + remoteNode +
          " Thread name: " + threadName +
          " Remote replicas: " + replicasToReplicatePerNode +
          " GetResponse from replication: " + getResponse.getError());
      throw new ReplicationException(
          " Get Request returned error when trying to get missing keys " + getResponse.getError());
    }
    return getResponse;
  }

  /**
   * Writes the messages to the local stores from the remote stores for the missing keys
   * @param exchangeMetadataResponseList The list of metadata response from the remote node
   * @param getResponse The getResponse that contains the messages
   * @param replicasToReplicatePerNode The list of remote replicas for the remote node
   * @param remoteNode The remote node from which replication needs to happen
   * @param remoteColo True, if the remote node is in a remote colo, False otherwise
   */
  private void writeMessagesToLocalStore(List<ExchangeMetadataResponse> exchangeMetadataResponseList,
      GetResponse getResponse, List<RemoteReplicaInfo> replicasToReplicatePerNode, DataNodeId remoteNode,
      boolean remoteColo)
      throws IOException {
    int partitionResponseInfoIndex = 0;
    long totalBytesFixed = 0;
    long totalBlobsFixed = 0;
    long startTime = SystemTime.getInstance().milliseconds();
    for (int i = 0; i < exchangeMetadataResponseList.size(); i++) {
      ExchangeMetadataResponse exchangeMetadataResponse = exchangeMetadataResponseList.get(i);
      RemoteReplicaInfo remoteReplicaInfo = replicasToReplicatePerNode.get(i);
      if (exchangeMetadataResponse.serverErrorCode == ServerErrorCode.No_Error) {
        if (exchangeMetadataResponse.missingStoreKeys.size() > 0) {
          PartitionResponseInfo partitionResponseInfo =
              getResponse.getPartitionResponseInfoList().get(partitionResponseInfoIndex);
          partitionResponseInfoIndex++;
          if (partitionResponseInfo.getPartition().compareTo(remoteReplicaInfo.getReplicaId().getPartitionId()) != 0) {
            throw new IllegalStateException("The partition id from partitionResponseInfo " +
                partitionResponseInfo.getPartition() + " and from remoteReplicaInfo " +
                remoteReplicaInfo.getReplicaId().getPartitionId() + " are not the same");
          }
          if (partitionResponseInfo.getErrorCode() == ServerErrorCode.No_Error) {
            try {
              List<MessageInfo> messageInfoList = partitionResponseInfo.getMessageInfoList();
              logger.trace("Remote node: {} Thread name: {} Remote replica: {} Messages to fix: {} "
                  + "Partition: {} Local mount path: {}", remoteNode, threadName, remoteReplicaInfo.getReplicaId(),
                  exchangeMetadataResponse.missingStoreKeys, remoteReplicaInfo.getReplicaId().getPartitionId(),
                  remoteReplicaInfo.getLocalReplicaId().getMountPath());

              MessageFormatWriteSet writeset = null;
              if (validateMessageStream) {
                MessageSievingInputStream validMessageDetectionInputStream =
                    new MessageSievingInputStream(getResponse.getInputStream(), messageInfoList, storeKeyFactory,
                        metricRegistry);
                if (validMessageDetectionInputStream.hasInvalidMessages()) {
                  replicationMetrics.incrementInvalidMessageError(partitionResponseInfo.getPartition());
                  logger.error("Out of " + (messageInfoList.size()) + " messages, " +
                      (messageInfoList.size() - validMessageDetectionInputStream.getValidMessageInfoList().size())
                      + " invalid messages were found in message stream from " + remoteReplicaInfo.getReplicaId());
                }
                messageInfoList = validMessageDetectionInputStream.getValidMessageInfoList();
                if (messageInfoList.size() == 0) {
                  logger.error("MessageInfoList is of size 0 as all messages are invalidated ");
                } else {
                  writeset = new MessageFormatWriteSet(validMessageDetectionInputStream, messageInfoList, false);
                  remoteReplicaInfo.getLocalStore().put(writeset);
                }
              } else {
                writeset = new MessageFormatWriteSet(getResponse.getInputStream(), messageInfoList, true);
                remoteReplicaInfo.getLocalStore().put(writeset);
              }

              for (MessageInfo messageInfo : messageInfoList) {
                totalBytesFixed += messageInfo.getSize();
                logger.trace("Remote node: {} Thread name: {} Remote replica: {} Message replicated: {} Partition: {} "
                    + "Local mount path: {} Message size: {}", remoteNode, threadName, remoteReplicaInfo.getReplicaId(),
                    messageInfo.getStoreKey(), remoteReplicaInfo.getReplicaId().getPartitionId(),
                    remoteReplicaInfo.getLocalReplicaId().getMountPath(), messageInfo.getSize());
                if (notification != null) {
                  notification.onBlobReplicaCreated(dataNodeId.getHostname(), dataNodeId.getPort(),
                      messageInfo.getStoreKey().getID(), BlobReplicaSourceType.REPAIRED);
                }
              }
              totalBlobsFixed += messageInfoList.size();
              remoteReplicaInfo.setToken(exchangeMetadataResponse.remoteToken);
              logger.trace("Remote node: {} Thread name: {} Remote replica: {} Token after speaking to remote node: {}",
                  remoteNode, threadName, remoteReplicaInfo.getReplicaId(), exchangeMetadataResponse.remoteToken);
            } catch (StoreException e) {
              if (e.getErrorCode() != StoreErrorCodes.Already_Exist) {
                replicationMetrics.updateLocalStoreError(remoteReplicaInfo);
                logger.error("Remote node: " + remoteNode + " Thread name: " + threadName +
                    " Remote replica: " + remoteReplicaInfo.getReplicaId(), e);
              }
            }
          } else {
            replicationMetrics.updateGetRequestError(remoteReplicaInfo);
            logger.error("Remote node: {} Thread name: {} Remote replica: {} Server error: {}", remoteNode, threadName,
                remoteReplicaInfo.getReplicaId(), partitionResponseInfo.getErrorCode());
          }
        } else {
          // There are no missing keys. We just advance the token
          remoteReplicaInfo.setToken(exchangeMetadataResponse.remoteToken);
          logger.trace("Remote node: {} Thread name: {} Remote replica: {} Token after speaking to remote node: {}",
              remoteNode, threadName, remoteReplicaInfo.getReplicaId(), exchangeMetadataResponse.remoteToken);
        }
      }
    }
    if (remoteColo) {
      replicationMetrics.interColoReplicationBytesRate.mark(totalBytesFixed);
      replicationMetrics.interColoBlobsReplicatedCount.inc(totalBlobsFixed);
      replicationMetrics.interColoBatchStoreWriteTime.update(SystemTime.getInstance().milliseconds() - startTime);
    } else {
      replicationMetrics.intraColoReplicationBytesRate.mark(totalBytesFixed);
      replicationMetrics.intraColoBlobsReplicatedCount.inc(totalBlobsFixed);
      replicationMetrics.intraColoBatchStoreWriteTime.update(SystemTime.getInstance().milliseconds() - startTime);
    }
  }

  class ExchangeMetadataResponse {
    public final Set<StoreKey> missingStoreKeys;
    public final FindToken remoteToken;
    public final ServerErrorCode serverErrorCode;

    public ExchangeMetadataResponse(Set<StoreKey> missingStoreKeys, FindToken remoteToken) {
      this.missingStoreKeys = missingStoreKeys;
      this.remoteToken = remoteToken;
      this.serverErrorCode = ServerErrorCode.No_Error;
    }

    public ExchangeMetadataResponse(ServerErrorCode errorCode) {
      missingStoreKeys = null;
      remoteToken = null;
      this.serverErrorCode = errorCode;
    }
  }

  public boolean isThreadUp() {
    return running;
  }

  public void shutdown()
      throws InterruptedException {
    running = false;
    shutdownLatch.await();
  }
}
