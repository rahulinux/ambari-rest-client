/**
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
package com.sequenceiq.ambari.client.services

import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseException

@Slf4j
trait ClusterService extends CommonService {

  /**
   * Returns the name of the cluster.
   *
   * @return the name of the cluster of null if no cluster yet
   */
  def String getClusterName() {
    if (!clusterNameCache) {
      def clusters = utils.getClusters();
      if (clusters) {
        clusterNameCache = clusters.items[0]?.Clusters?.cluster_name
      }
    }
    return clusterNameCache
  }

  /**
   * Returns a pre-formatted String of the clusters.
   *
   * @return pre-formatted cluster list
   */
  def String showClusterList() {
    utils.getClusters().items.collect {
      "[$it.Clusters.cluster_id] $it.Clusters.cluster_name:$it.Clusters.version"
    }.join('\n')
  }

  /**
   * Returns all clusters as json
   *
   * @return json String
   * @throws HttpResponseException in case of error
   */
  def getClustersAsJson() throws HttpResponseException {
    Map resourceRequestMap = utils.getResourceRequestMap('clusters', null)
    return utils.getRawResource(resourceRequestMap)
  }

  /**
   * Returns the active cluster as json
   *
   * @return cluster as json String
   * @throws HttpResponseException in case of error
   */
  def String getClusterAsJson() throws HttpResponseException {
    String path = 'clusters/' + getClusterName();
    Map resourceRequestMap = utils.getResourceRequestMap(path, null)
    return utils.getRawResource(resourceRequestMap)
  }

  def String createClusterJson(String name, Map hostGroups, String defaultPassword, String strategy,
    String principal, String key, String type, boolean hideQuickLinks) {
    def builder = new JsonBuilder()
    def ambariVersion = ambariServerVersion()
    def isAmbariVersionGreaterThan221 = isAmbariGreaterThan([2, 2, 1], ambariVersion)
    def groups = hostGroups.findResults {
      def hostList = it.value.collect { instanceMeta ->
        def tempMap = ['fqdn': instanceMeta.fqdn]
        if (isAmbariVersionGreaterThan221 && instanceMeta.rack != null) {
          tempMap.rack_info = instanceMeta.rack
        }
        tempMap
      }
      hostList.size() != 0 ? [name: it.key, hosts: hostList] : null
    }
    def isAmbariVersionGreaterThan250 = isAmbariGreaterThan([2, 5, 0], ambariVersion)
    builder {
      blueprint name
      default_password defaultPassword
      host_groups groups
      config_recommendation_strategy strategy
      if (principal) {
        def credential = [["alias": "kdc.admin.credential", "principal": principal, "key": key, type: type]]
        credentials credential
      }
      if (hideQuickLinks && isAmbariVersionGreaterThan250) {
        def filters = ["filters": [["visible": false]]]
        quicklinks_profile filters
      }
    }
    return builder.toPrettyString()
  }

  /**
   * Creates a cluster with the given blueprint and host group - host association.
   *
   * @param clusterName name of the cluster
   * @param blueprintName blueprint id used to create this cluster
   * @param hostGroups Map<String, List<String> key - host group, value - host list
   * @param recommendationStrategy 'NEVER_APPLY', 'ONLY_STACK_DEFAULTS_APPLY', 'ALWAYS_APPLY'
   * @param defaultPassword default password used for the services
   * @param hideQuickLinks whether to hide the quick links on the Ambari UI
   * @return true if the creation was successful false otherwise
   * @throws HttpResponseException in case of error
   *
   * @return cluster creation template
   */
  def String createCluster(String clusterName, String blueprintName, Map<String, List<Map<String, String>>> hostGroups,
    String recommendationStrategy, String defaultPassword, boolean hideQuickLinks) throws HttpResponseException {
    if (debugEnabled) {
      println "[DEBUG] POST ${ambari.getUri()}clusters/$clusterName"
    }
    def template = createClusterJson(blueprintName, hostGroups, defaultPassword,
      recommendationStrategy, null, null, null, hideQuickLinks)
    ambari.post(path: "clusters/$clusterName", body: template, { it })
    return template
  }

  /**
   * Creates a cluster with the given blueprint and host group - host association.
   *
   * @param clusterName name of the cluster
   * @param blueprintName blueprint id used to create this cluster
   * @param hostGroups Map<String, List<String> key - host group, value - host list
   * @param recommendationStrategy 'NEVER_APPLY', 'ONLY_STACK_DEFAULTS_APPLY', 'ALWAYS_APPLY'
   * @param defaultPassword default password used for the services
   * @param principal KDC principal (like: admin/admin)
   * @param key key for KDC principal (like: admin)
   * @param type type of the principal can be either 'TEMPORARY' or 'PERSISTED'
   * @param hideQuickLinks whether to hide the quick links on the Ambari UI
   * @return true if the creation was successful false otherwise
   * @throws HttpResponseException in case of error
   *
   * @return cluster creation template
   */
  def String createSecureCluster(String clusterName, String blueprintName, Map<String, List<Map<String, String>>> hostGroups,
    String recommendationStrategy, String defaultPassword,
    String principal, String key, String type, boolean hideQuickLinks) throws HttpResponseException {
    if (debugEnabled) {
      println "[DEBUG] POST ${ambari.getUri()}clusters/$clusterName"
    }
    def template = createClusterJson(blueprintName, hostGroups, defaultPassword,
      recommendationStrategy, principal, key, type, hideQuickLinks)
    ambari.post(path: "clusters/$clusterName", body: template, { it })
    return template
  }

  /**
   * Deletes the cluster.
   *
   * @param clusterName name of the cluster
   * @throws HttpResponseException in case of error
   */
  def void deleteCluster(String clusterName) throws HttpResponseException {
    if (debugEnabled) {
      println "[DEBUG] DELETE ${ambari.getUri()}clusters/$clusterName"
    }
    ambari.delete(path: "clusters/$clusterName")
  }

  /**
   * Decommission a host component on a given host.
   * @param host hostName where the component is installed to
   * @param slaveName slave to be decommissioned
   * @param serviceName where the slave belongs to
   * @param componentName where the slave belongs to
   */
  def int decommission(List<String> hosts, String slaveName, String serviceName, String componentName) {
    def requestInfo = [
      command   : 'DECOMMISSION',
      context   : "Decommission $slaveName",
      parameters: ['slave_type': slaveName, 'excluded_hosts': hosts.join(',')]
    ]
    def filter = [
      ['service_name': serviceName, 'component_name': componentName]
    ]
    Map bodyMap = [
      'RequestInfo'              : requestInfo,
      'Requests/resource_filters': filter
    ]
    ambari.post(path: "clusters/${getClusterName()}/requests", body: new JsonBuilder(bodyMap).toPrettyString(), {
      utils.getRequestId(it)
    })
  }

  /**
   * Does not return until all the requests are finished.
   * @param requestIds ids of the requests
   */
  def waitForRequestsToFinish(List<Integer> requestIds) {
    def stopped = false
    while (!stopped) {
      def state = true
      for (int id : requestIds) {
        if (getRequestProgress(id) != 100.0) {
          state = false;
          break;
        }
      }
      stopped = state
      Thread.sleep(2000)
    }
  }

  def BigDecimal getRequestProgress() {
    return getRequestProgress(1)
  }

  /**
   * Returns the install progress state. If the install failed -1 returned.
   *
   * @param request request id; default is 1
   * @return progress in percentage
   */
  def BigDecimal getRequestProgress(request) {
    def response = utils.getAllResources("requests/$request", 'Requests')
    def String status = response?.Requests?.request_status
    if (status && status.equals('FAILED')) {
      return new BigDecimal(-1)
    }
    return response?.Requests?.progress_percent
  }

  /**
   * Returns a map with <status, list of request ids> based on statuses parameter.
   * @param statuses the relevant statuses
   * @return the status map
   */
  def Map<String, List<Integer>> getRequests(String... statuses) {
    def reqs = utils.getAllResources('requests', 'Requests/request_status,Requests/id')?.items?.Requests
    def resp = [:]
    reqs.each {
      def reqStatus = it?.request_status
      if (!statuses || reqStatus in statuses) {
        def reqlist = resp[reqStatus]
        if (reqlist == null) {
          reqlist = []
          resp[reqStatus] = reqlist
        }
        reqlist << it?.id
      }
    }
    return resp
  }

  /**
   * Returns the ID of a request which matches the desired status and request context
   *
   * @param requestContext context to search for
   * @param status request's current status
   * @return id of the request or -1
   */
  def int getRequestIdWithContext(String requestContext, String status) {
    def response = utils.getAllResources('requests', 'Requests/request_status,Requests/id,Requests/request_context')
    def requests = response?.items?.Requests
    def result = requests.find { it.request_context.startsWith(requestContext) && it.request_status.equals(status) }
    result ? result.id : -1
  }

  /**
   * Returns the version of the Ambari server.
   *
   * @return version
   */
  def String ambariServerVersion() {
    def json = utils.getSlurpedResource([path: 'services/AMBARI/components/AMBARI_SERVER', headers: ['Accept': ContentType.TEXT], query: [fields: 'RootServiceComponents/component_version']])
    json.RootServiceComponents.component_version
  }

  private boolean isAmbariGreaterThan(List baseVersion, version) {
    def act = version.split('\\.')
    for (int i = 0; i < baseVersion.size(); i++) {
      if (act[i].toInteger() > baseVersion[i].toInteger()) {
        return true
      } else if (act[i].toInteger() < baseVersion[i].toInteger()) {
        return false
      }
    }
    return true
  }
}
