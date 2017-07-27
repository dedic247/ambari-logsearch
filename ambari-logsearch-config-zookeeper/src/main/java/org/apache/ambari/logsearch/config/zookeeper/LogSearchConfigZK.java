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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ambari.logsearch.config.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.ambari.logsearch.config.api.LogSearchConfig;
import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.apache.ambari.logsearch.config.api.OutputConfigMonitor;
import org.apache.ambari.logsearch.config.api.model.loglevelfilter.LogLevelFilter;
import org.apache.ambari.logsearch.config.api.model.loglevelfilter.LogLevelFilterMap;
import org.apache.ambari.logsearch.config.api.model.outputconfig.OutputSolrProperties;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputConfig;
import org.apache.ambari.logsearch.config.zookeeper.model.inputconfig.impl.InputAdapter;
import org.apache.ambari.logsearch.config.zookeeper.model.inputconfig.impl.InputConfigGson;
import org.apache.ambari.logsearch.config.zookeeper.model.inputconfig.impl.InputConfigImpl;
import org.apache.ambari.logsearch.config.zookeeper.model.outputconfig.impl.OutputSolrPropertiesImpl;
import org.apache.ambari.logsearch.config.api.InputConfigMonitor;
import org.apache.ambari.logsearch.config.api.LogLevelFilterMonitor;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent.Type;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class LogSearchConfigZK implements LogSearchConfig {
  private static final Logger LOG = LoggerFactory.getLogger(LogSearchConfigZK.class);

  private static final int SESSION_TIMEOUT = 15000;
  private static final int CONNECTION_TIMEOUT = 30000;
  private static final String DEFAULT_ZK_ROOT = "/logsearch";
  private static final long WAIT_FOR_ROOT_SLEEP_SECONDS = 10;
  private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";

  @LogSearchPropertyDescription(
    name = "logsearch.config.zk_connect_string",
    description = "ZooKeeper connection string.",
    examples = {"localhost1:2181,localhost2:2181/znode"},
    sources = {"logsearch.properties", "logfeeder.properties"}
  )
  private static final String ZK_CONNECT_STRING_PROPERTY = "logsearch.config.zk_connect_string";

  @LogSearchPropertyDescription(
    name = "logsearch.config.zk_acls",
    description = "ZooKeeper ACLs for handling configs. (read & write)",
    examples = {"world:anyone:r,sasl:solr:cdrwa,sasl:logsearch:cdrwa"},
    sources = {"logsearch.properties", "logfeeder.properties"},
    defaultValue = "world:anyone:cdrwa"
  )
  private static final String ZK_ACLS_PROPERTY = "logsearch.config.zk_acls";

  @LogSearchPropertyDescription(
    name = "logsearch.config.zk_root",
    description = "ZooKeeper root node where the shippers are stored. (added to the connection string)",
    examples = {"/logsearch"},
    sources = {"logsearch.properties", "logfeeder.properties"}
  )
  private static final String ZK_ROOT_NODE_PROPERTY = "logsearch.config.zk_root";

  private Map<String, String> properties;
  private CuratorFramework client;
  private Gson gson;

  private TreeCache serverCache;
  private TreeCache logFeederClusterCache;
  private TreeCache outputCache;

  @Override
  public void init(Component component, Map<String, String> properties, String clusterName) throws Exception {
    this.properties = properties;
    
    String root = MapUtils.getString(properties, ZK_ROOT_NODE_PROPERTY, DEFAULT_ZK_ROOT);
    LOG.info("Connecting to ZooKeeper at " + properties.get(ZK_CONNECT_STRING_PROPERTY) + root);
    client = CuratorFrameworkFactory.builder()
        .connectString(properties.get(ZK_CONNECT_STRING_PROPERTY) + root)
        .retryPolicy(new ExponentialBackoffRetry(1000, 3))
        .connectionTimeoutMs(CONNECTION_TIMEOUT)
        .sessionTimeoutMs(SESSION_TIMEOUT)
        .build();
    client.start();

    outputCache = new TreeCache(client, "/output");
    outputCache.start();

    if (component == Component.SERVER) {
      if (client.checkExists().forPath("/") == null) {
        client.create().creatingParentContainersIfNeeded().forPath("/");
      }
      if (client.checkExists().forPath("/output") == null) {
        client.create().creatingParentContainersIfNeeded().forPath("/output");
      }
      serverCache = new TreeCache(client, "/");
      serverCache.start();
    } else {
      while (client.checkExists().forPath("/") == null) {
        LOG.info("Root node is not present yet, going to sleep for " + WAIT_FOR_ROOT_SLEEP_SECONDS + " seconds");
        Thread.sleep(WAIT_FOR_ROOT_SLEEP_SECONDS * 1000);
      }
      logFeederClusterCache = new TreeCache(client, String.format("/%s", clusterName));
    }
    
    gson = new GsonBuilder().setDateFormat(DATE_FORMAT).create();
  }

  @Override
  public boolean inputConfigExistsLogFeeder(String serviceName) throws Exception {
    String nodePath = String.format("/input/%s", serviceName);
    return logFeederClusterCache.getCurrentData(nodePath) != null;
  }

  @Override
  public boolean inputConfigExistsServer(String clusterName, String serviceName) throws Exception {
    String nodePath = String.format("/%s/input/%s", clusterName, serviceName);
    return serverCache.getCurrentData(nodePath) != null;
  }

  @Override
  public void createInputConfig(String clusterName, String serviceName, String inputConfig) throws Exception {
    String nodePath = String.format("/%s/input/%s", clusterName, serviceName);
    try {
      client.create().creatingParentContainersIfNeeded().withACL(getAcls()).forPath(nodePath, inputConfig.getBytes());
      LOG.info("Uploaded input config for the service " + serviceName + " for cluster " + clusterName);
    } catch (NodeExistsException e) {
      LOG.debug("Did not upload input config for service " + serviceName + " as it was already uploaded by another Log Feeder");
    }
  }

  @Override
  public void setInputConfig(String clusterName, String serviceName, String inputConfig) throws Exception {
    String nodePath = String.format("/%s/input/%s", clusterName, serviceName);
    client.setData().forPath(nodePath, inputConfig.getBytes());
    LOG.info("Set input config for the service " + serviceName + " for cluster " + clusterName);
  }

  @Override
  public void monitorInputConfigChanges(final InputConfigMonitor inputConfigMonitor,
      final LogLevelFilterMonitor logLevelFilterMonitor, final String clusterName) throws Exception {
    final JsonParser parser = new JsonParser();
    final JsonArray globalConfigNode = new JsonArray();
    for (String globalConfigJsonString : inputConfigMonitor.getGlobalConfigJsons()) {
      JsonElement globalConfigJson = parser.parse(globalConfigJsonString);
      globalConfigNode.add(globalConfigJson.getAsJsonObject().get("global"));
    }
    
    createGlobalConfigNode(globalConfigNode, clusterName);
    
    TreeCacheListener listener = new TreeCacheListener() {
      private final Set<Type> nodeEvents = ImmutableSet.of(Type.NODE_ADDED, Type.NODE_UPDATED, Type.NODE_REMOVED);
      
      public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
        if (!nodeEvents.contains(event.getType())) {
          return;
        }
        
        String nodeName = ZKPaths.getNodeFromPath(event.getData().getPath());
        String nodeData = new String(event.getData().getData());
        Type eventType = event.getType();
        
        String configPathStab = String.format("/%s/", clusterName);
        
        if (event.getData().getPath().startsWith(configPathStab + "input/")) {
          handleInputConfigChange(eventType, nodeName, nodeData);
        } else if (event.getData().getPath().startsWith(configPathStab + "loglevelfilter/")) {
          handleLogLevelFilterChange(eventType, nodeName, nodeData);
        }
      }

      private void handleInputConfigChange(Type eventType, String nodeName, String nodeData) {
        switch (eventType) {
          case NODE_ADDED:
            LOG.info("Node added under input ZK node: " + nodeName);
            addInputs(nodeName, nodeData);
            break;
          case NODE_UPDATED:
            LOG.info("Node updated under input ZK node: " + nodeName);
            removeInputs(nodeName);
            addInputs(nodeName, nodeData);
            break;
          case NODE_REMOVED:
            LOG.info("Node removed from input ZK node: " + nodeName);
            removeInputs(nodeName);
            break;
          default:
            break;
        }
      }

      private void removeInputs(String serviceName) {
        inputConfigMonitor.removeInputs(serviceName);
      }

      private void addInputs(String serviceName, String inputConfig) {
        try {
          JsonElement inputConfigJson = parser.parse(inputConfig);
          for (Map.Entry<String, JsonElement> typeEntry : inputConfigJson.getAsJsonObject().entrySet()) {
            for (JsonElement e : typeEntry.getValue().getAsJsonArray()) {
              for (JsonElement globalConfig : globalConfigNode) {
                merge(globalConfig.getAsJsonObject(), e.getAsJsonObject());
              }
            }
          }
          
          inputConfigMonitor.loadInputConfigs(serviceName, InputConfigGson.gson.fromJson(inputConfigJson, InputConfigImpl.class));
        } catch (Exception e) {
          LOG.error("Could not load input configuration for service " + serviceName + ":\n" + inputConfig, e);
        }
      }

      private void handleLogLevelFilterChange(Type eventType, String nodeName, String nodeData) {
        switch (eventType) {
          case NODE_ADDED:
          case NODE_UPDATED:
            LOG.info("Node added/updated under loglevelfilter ZK node: " + nodeName);
            LogLevelFilter logLevelFilter = gson.fromJson(nodeData, LogLevelFilter.class);
            logLevelFilterMonitor.setLogLevelFilter(nodeName, logLevelFilter);
            break;
          case NODE_REMOVED:
            LOG.info("Node removed loglevelfilter input ZK node: " + nodeName);
            logLevelFilterMonitor.removeLogLevelFilter(nodeName);
            break;
          default:
            break;
        }
      }

      private void merge(JsonObject source, JsonObject target) {
        for (Map.Entry<String, JsonElement> e : source.entrySet()) {
          if (!target.has(e.getKey())) {
            target.add(e.getKey(), e.getValue());
          } else {
            if (e.getValue().isJsonObject()) {
              JsonObject valueJson = (JsonObject)e.getValue();
              merge(valueJson, target.get(e.getKey()).getAsJsonObject());
            }
          }
        }
      }
    };
    logFeederClusterCache.getListenable().addListener(listener);
    logFeederClusterCache.start();
  }

  private void createGlobalConfigNode(JsonArray globalConfigNode, String clusterName) {
    String globalConfigNodePath = String.format("/%s/global", clusterName);
    String data = InputConfigGson.gson.toJson(globalConfigNode);
    
    try {
      if (logFeederClusterCache.getCurrentData(globalConfigNodePath) != null) {
        client.setData().forPath(globalConfigNodePath, data.getBytes());
      } else {
        client.create().creatingParentContainersIfNeeded().withACL(getAcls()).forPath(globalConfigNodePath, data.getBytes());
      }
    } catch (Exception e) {
      LOG.warn("Exception during global config node creation/update", e);
    }
  }

  @Override
  public List<String> getServices(String clusterName) {
    String parentPath = String.format("/%s/input", clusterName);
    Map<String, ChildData> serviceNodes = serverCache.getCurrentChildren(parentPath);
    return new ArrayList<String>(serviceNodes.keySet());
  }

  @Override
  public String getGlobalConfigs(String clusterName) {
    String globalConfigNodePath = String.format("/%s/global", clusterName);
    return new String(serverCache.getCurrentData(globalConfigNodePath).getData());
  }

  @Override
  public InputConfig getInputConfig(String clusterName, String serviceName) {
    String globalConfigData = getGlobalConfigs(clusterName);
    JsonArray globalConfigs = (JsonArray) new JsonParser().parse(globalConfigData);
    InputAdapter.setGlobalConfigs(globalConfigs);
    
    ChildData childData = serverCache.getCurrentData(String.format("/%s/input/%s", clusterName, serviceName));
    return childData == null ? null : InputConfigGson.gson.fromJson(new String(childData.getData()), InputConfigImpl.class);
  }

  @Override
  public void createLogLevelFilter(String clusterName, String logId, LogLevelFilter filter) throws Exception {
    String nodePath = String.format("/%s/loglevelfilter/%s", clusterName, logId);
    String logLevelFilterJson = gson.toJson(filter);
    try {
      client.create().creatingParentContainersIfNeeded().withACL(getAcls()).forPath(nodePath, logLevelFilterJson.getBytes());
      LOG.info("Uploaded log level filter for the log " + logId + " for cluster " + clusterName);
    } catch (NodeExistsException e) {
      LOG.debug("Did not upload log level filters for log " + logId + " as it was already uploaded by another Log Feeder");
    }
  }

  @Override
  public void setLogLevelFilters(String clusterName, LogLevelFilterMap filters) throws Exception {
    for (Map.Entry<String, LogLevelFilter> e : filters.getFilter().entrySet()) {
      String nodePath = String.format("/%s/loglevelfilter/%s", clusterName, e.getKey());
      String logLevelFilterJson = gson.toJson(e.getValue());
      String currentLogLevelFilterJson = new String(serverCache.getCurrentData(nodePath).getData());
      if (!logLevelFilterJson.equals(currentLogLevelFilterJson)) {
        client.setData().forPath(nodePath, logLevelFilterJson.getBytes());
        LOG.info("Set log level filter for the log " + e.getKey() + " for cluster " + clusterName);
      }
    }
  }

  @Override
  public LogLevelFilterMap getLogLevelFilters(String clusterName) {
    String parentPath = String.format("/%s/loglevelfilter", clusterName);
    Map<String, ChildData> logLevelFilterNodes = serverCache.getCurrentChildren(parentPath);
    TreeMap<String, LogLevelFilter> filters = new TreeMap<>();
    for (Map.Entry<String, ChildData> e : logLevelFilterNodes.entrySet()) {
      LogLevelFilter logLevelFilter = gson.fromJson(new String(e.getValue().getData()), LogLevelFilter.class);
      filters.put(e.getKey(), logLevelFilter);
    }
    
    LogLevelFilterMap logLevelFilters = new LogLevelFilterMap();
    logLevelFilters.setFilter(filters);
    return logLevelFilters;
  }

  private List<ACL> getAcls() {
    String aclStr = properties.get(ZK_ACLS_PROPERTY);
    if (StringUtils.isBlank(aclStr)) {
      return ZooDefs.Ids.OPEN_ACL_UNSAFE;
    }

    List<ACL> acls = new ArrayList<>();
    List<String> aclStrList = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(aclStr);
    for (String unparcedAcl : aclStrList) {
      String[] parts = unparcedAcl.split(":");
      if (parts.length == 3) {
        acls.add(new ACL(parsePermission(parts[2]), new Id(parts[0], parts[1])));
      }
    }
    return acls;
  }

  private Integer parsePermission(String permission) {
    int permissionCode = 0;
    for (char each : permission.toLowerCase().toCharArray()) {
      switch (each) {
        case 'r':
          permissionCode |= ZooDefs.Perms.READ;
          break;
        case 'w':
          permissionCode |= ZooDefs.Perms.WRITE;
          break;
        case 'c':
          permissionCode |= ZooDefs.Perms.CREATE;
          break;
        case 'd':
          permissionCode |= ZooDefs.Perms.DELETE;
          break;
        case 'a':
          permissionCode |= ZooDefs.Perms.ADMIN;
          break;
        default:
          throw new IllegalArgumentException("Unsupported permission: " + permission);
      }
    }
    return permissionCode;
  }

  @Override
  public void saveOutputSolrProperties(String type, OutputSolrProperties outputSolrProperties) throws Exception {
    String nodePath = String.format("/output/solr/%s", type);
    String data = gson.toJson(outputSolrProperties);
    if (outputCache.getCurrentData(nodePath) == null) {
      client.create().creatingParentContainersIfNeeded().withACL(getAcls()).forPath(nodePath, data.getBytes());
    } else {
      client.setData().forPath(nodePath, data.getBytes());
    }
  }

  @Override
  public OutputSolrProperties getOutputSolrProperties(String type) throws Exception {
    String nodePath = String.format("/output/solr/%s", type);
    ChildData currentData = outputCache.getCurrentData(nodePath);
    return currentData == null ?
        null :
        gson.fromJson(new String(currentData.getData()), OutputSolrPropertiesImpl.class);
  }

  @Override
  public void monitorOutputProperties(final List<? extends OutputConfigMonitor> outputConfigMonitors) throws Exception {
    TreeCacheListener listener = new TreeCacheListener() {
      public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
        if (event.getType() != Type.NODE_UPDATED) {
          return;
        }
        
        LOG.info("Output config updated: " + event.getData().getPath());
        for (OutputConfigMonitor monitor : outputConfigMonitors) {
          String monitorPath = String.format("/output/%s/%s", monitor.getDestination(), monitor.getOutputType());
          if (monitorPath.equals(event.getData().getPath())) {
            String nodeData = new String(event.getData().getData());
            OutputSolrProperties outputSolrProperties = gson.fromJson(nodeData, OutputSolrPropertiesImpl.class);
            monitor.outputConfigChanged(outputSolrProperties);
          }
        }
      }
    };
    outputCache.getListenable().addListener(listener);
  }

  @Override
  public void close() {
    LOG.info("Closing ZooKeeper Connection");
    client.close();
  }
}
