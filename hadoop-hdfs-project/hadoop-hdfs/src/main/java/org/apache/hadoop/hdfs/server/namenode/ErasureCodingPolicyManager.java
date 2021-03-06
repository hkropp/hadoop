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
package org.apache.hadoop.hdfs.server.namenode;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicyState;
import org.apache.hadoop.hdfs.protocol.IllegalECPolicyException;
import org.apache.hadoop.hdfs.protocol.SystemErasureCodingPolicies;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;

import org.apache.hadoop.io.erasurecode.CodecUtil;
import org.apache.hadoop.io.erasurecode.ErasureCodeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * This manages erasure coding policies predefined and activated in the system.
 * It loads customized policies and syncs with persisted ones in
 * NameNode image.
 *
 * This class is instantiated by the FSNamesystem.
 */
@InterfaceAudience.LimitedPrivate({"HDFS"})
public final class ErasureCodingPolicyManager {

  public static Logger LOG = LoggerFactory.getLogger(
      ErasureCodingPolicyManager.class);
  private int maxCellSize =
      DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_MAX_CELLSIZE_DEFAULT;

  // Supported storage policies for striped EC files
  private static final byte[] SUITABLE_STORAGE_POLICIES_FOR_EC_STRIPED_MODE =
      new byte[]{
          HdfsConstants.HOT_STORAGE_POLICY_ID,
          HdfsConstants.COLD_STORAGE_POLICY_ID,
          HdfsConstants.ALLSSD_STORAGE_POLICY_ID};

  /**
   * All policies sorted by name for fast querying, include built-in policy,
   * user defined policy, removed policy.
   */
  private Map<String, ErasureCodingPolicy> policiesByName;

  /**
   * All policies sorted by ID for fast querying, including built-in policy,
   * user defined policy, removed policy.
   */
  private Map<Byte, ErasureCodingPolicy> policiesByID;

  /**
   * For better performance when query all Policies.
   */
  private ErasureCodingPolicy[] allPolicies;

  /**
   * All enabled policies sorted by name for fast querying, including built-in
   * policy, user defined policy.
   */
  private Map<String, ErasureCodingPolicy> enabledPoliciesByName;
  /**
   * For better performance when query all enabled Policies.
   */
  private ErasureCodingPolicy[] enabledPolicies;


  private volatile static ErasureCodingPolicyManager instance = null;

  public static ErasureCodingPolicyManager getInstance() {
    if (instance == null) {
      instance = new ErasureCodingPolicyManager();
    }
    return instance;
  }

  private ErasureCodingPolicyManager() {}

  public void init(Configuration conf) {
    // Populate the list of enabled policies from configuration
    final String[] enablePolicyNames = conf.getTrimmedStrings(
            DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_ENABLED_KEY,
            DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_ENABLED_DEFAULT);
    final String defaultPolicyName = conf.getTrimmed(
            DFSConfigKeys.DFS_NAMENODE_EC_SYSTEM_DEFAULT_POLICY,
            DFSConfigKeys.DFS_NAMENODE_EC_SYSTEM_DEFAULT_POLICY_DEFAULT);
    final String[] policyNames =
            (String[]) ArrayUtils.add(enablePolicyNames, defaultPolicyName);
    this.policiesByName = new TreeMap<>();
    this.policiesByID = new TreeMap<>();
    this.enabledPoliciesByName = new TreeMap<>();

    /**
     * TODO: load user defined EC policy from fsImage HDFS-7859
     * load persistent policies from image and editlog, which is done only once
     * during NameNode startup. This can be done here or in a separate method.
     */

    /*
     * Add all System built-in policies into policy map
     */
    for (ErasureCodingPolicy policy :
        SystemErasureCodingPolicies.getPolicies()) {
      policiesByName.put(policy.getName(), policy);
      policiesByID.put(policy.getId(), policy);
    }

    for (String policyName : policyNames) {
      if (policyName.trim().isEmpty()) {
        continue;
      }
      ErasureCodingPolicy ecPolicy = policiesByName.get(policyName);
      if (ecPolicy == null) {
        String names = policiesByName.values()
            .stream().map(ErasureCodingPolicy::getName)
            .collect(Collectors.joining(", "));
        String msg = String.format("EC policy '%s' specified at %s is not a "
                + "valid policy. Please choose from list of available "
                + "policies: [%s]",
            policyName,
            DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_ENABLED_KEY,
            names);
        throw new IllegalArgumentException(msg);
      }
      enabledPoliciesByName.put(ecPolicy.getName(), ecPolicy);
    }
    enabledPolicies =
        enabledPoliciesByName.values().toArray(new ErasureCodingPolicy[0]);
    allPolicies = policiesByName.values().toArray(new ErasureCodingPolicy[0]);

    maxCellSize = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_MAX_CELLSIZE_KEY,
        DFSConfigKeys.DFS_NAMENODE_EC_POLICIES_MAX_CELLSIZE_DEFAULT);
  }

  /**
   * Get the set of enabled policies.
   * @return all policies
   */
  public ErasureCodingPolicy[] getEnabledPolicies() {
    return enabledPolicies;
  }

  /**
   * Get enabled policy by policy name.
   */
  public ErasureCodingPolicy getEnabledPolicyByName(String name) {
    ErasureCodingPolicy ecPolicy = enabledPoliciesByName.get(name);
    if (ecPolicy == null) {
      if (name.equalsIgnoreCase(ErasureCodeConstants.REPLICATION_POLICY_NAME)) {
        ecPolicy = SystemErasureCodingPolicies.getReplicationPolicy();
      }
    }
    return ecPolicy;
  }

  /**
   * @return if the specified storage policy ID is suitable for striped EC
   * files.
   */
  public static boolean checkStoragePolicySuitableForECStripedMode(
      byte storagePolicyID) {
    boolean isPolicySuitable = false;
    for (byte suitablePolicy : SUITABLE_STORAGE_POLICIES_FOR_EC_STRIPED_MODE) {
      if (storagePolicyID == suitablePolicy) {
        isPolicySuitable = true;
        break;
      }
    }
    return isPolicySuitable;
  }

  /**
   * Get all system defined policies and user defined policies.
   * @return all policies
   */
  public ErasureCodingPolicy[] getPolicies() {
    return allPolicies;
  }

  /**
   * Get a policy by policy ID, including system policy and user defined policy.
   * @return ecPolicy, or null if not found
   */
  public ErasureCodingPolicy getByID(byte id) {
    return this.policiesByID.get(id);
  }

  /**
   * Get a policy by policy ID, including system policy and user defined policy.
   * @return ecPolicy, or null if not found
   */
  public ErasureCodingPolicy getByName(String name) {
    return this.policiesByName.get(name);
  }

  /**
   * Clear and clean up.
   */
  public void clear() {
    // TODO: we should only clear policies loaded from NN metadata.
    // This is a placeholder for HDFS-7337.
  }

  /**
   * Add an erasure coding policy.
   * @return the added policy
   */
  public synchronized ErasureCodingPolicy addPolicy(ErasureCodingPolicy policy)
      throws IllegalECPolicyException {
    // Set policy state into DISABLED when adding into Hadoop.
    policy.setState(ErasureCodingPolicyState.DISABLED);

    if (!CodecUtil.hasCodec(policy.getCodecName())) {
      throw new IllegalECPolicyException("Codec name "
          + policy.getCodecName() + " is not supported");
    }

    if (policy.getCellSize() > maxCellSize) {
      throw new IllegalECPolicyException("Cell size " + policy.getCellSize()
          + " should not exceed maximum " + maxCellSize + " byte");
    }

    String assignedNewName = ErasureCodingPolicy.composePolicyName(
        policy.getSchema(), policy.getCellSize());
    for (ErasureCodingPolicy p : getPolicies()) {
      if (p.getName().equals(assignedNewName)) {
        throw new IllegalECPolicyException("The policy name " + assignedNewName
            + " already exists");
      }
      if (p.getSchema().equals(policy.getSchema()) &&
          p.getCellSize() == policy.getCellSize()) {
        throw new IllegalECPolicyException("A policy with same schema "
            + policy.getSchema().toString() + " and cell size "
            + p.getCellSize() + " is already exists");
      }
    }
    policy.setName(assignedNewName);
    policy.setId(getNextAvailablePolicyID());
    this.policiesByName.put(policy.getName(), policy);
    this.policiesByID.put(policy.getId(), policy);
    allPolicies = policiesByName.values().toArray(new ErasureCodingPolicy[0]);
    return policy;
  }

  private byte getNextAvailablePolicyID() {
    byte currentId = this.policiesByID.keySet().stream()
        .max(Byte::compareTo)
        .filter(id -> id >= ErasureCodeConstants.USER_DEFINED_POLICY_START_ID)
        .orElse(ErasureCodeConstants.USER_DEFINED_POLICY_START_ID);
    return (byte) (currentId + 1);
  }

  /**
   * Remove an User erasure coding policy by policyName.
   */
  public synchronized void removePolicy(String name) {
    ErasureCodingPolicy ecPolicy = policiesByName.get(name);
    if (ecPolicy == null) {
      throw new IllegalArgumentException("The policy name " +
          name + " does not exists");
    }

    if (ecPolicy.isSystemPolicy()) {
      throw new IllegalArgumentException("System erasure coding policy " +
          name + " cannot be removed");
    }

    if (enabledPoliciesByName.containsKey(name)) {
      enabledPoliciesByName.remove(name);
      enabledPolicies =
          enabledPoliciesByName.values().toArray(new ErasureCodingPolicy[0]);
    }
    ecPolicy.setState(ErasureCodingPolicyState.REMOVED);
    LOG.info("Remove erasure coding policy " + name);
  }

  @VisibleForTesting
  public List<ErasureCodingPolicy> getRemovedPolicies() {
    ArrayList<ErasureCodingPolicy> removedPolicies =
        new ArrayList<ErasureCodingPolicy>();
    for (ErasureCodingPolicy ecPolicy : policiesByName.values()) {
      if (ecPolicy.isRemoved()) {
        removedPolicies.add(ecPolicy);
      }
    }
    return removedPolicies;
  }

  /**
   * Disable an erasure coding policy by policyName.
   */
  public synchronized void disablePolicy(String name) {
    ErasureCodingPolicy ecPolicy = policiesByName.get(name);
    if (ecPolicy == null) {
      throw new IllegalArgumentException("The policy name " +
          name + " does not exists");
    }

    if (enabledPoliciesByName.containsKey(name)) {
      enabledPoliciesByName.remove(name);
      enabledPolicies =
          enabledPoliciesByName.values().toArray(new ErasureCodingPolicy[0]);
    }
    ecPolicy.setState(ErasureCodingPolicyState.DISABLED);
    LOG.info("Disable the erasure coding policy " + name);
  }

  /**
   * Enable an erasure coding policy by policyName.
   */
  public synchronized void enablePolicy(String name) {
    ErasureCodingPolicy ecPolicy = policiesByName.get(name);
    if (ecPolicy == null) {
      throw new IllegalArgumentException("The policy name " +
          name + " does not exists");
    }

    enabledPoliciesByName.put(name, ecPolicy);
    ecPolicy.setState(ErasureCodingPolicyState.ENABLED);
    enabledPolicies =
        enabledPoliciesByName.values().toArray(new ErasureCodingPolicy[0]);
    LOG.info("Enable the erasure coding policy " + name);
  }
}
