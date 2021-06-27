/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine;

import com.google.api.services.compute.model.Instance;
import hudson.Extension;
import hudson.model.PeriodicWork;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

/** Periodically checks if there are no lost nodes in GCP. If it finds any they are deleted. */
@Extension
@Symbol("cleanLostNodesWork")
public class CleanLostNodesWork extends PeriodicWork {
  protected final Logger logger = Logger.getLogger(getClass().getName());

  /** {@inheritDoc} */
  @Override
  public long getRecurrencePeriod() {
    return HOUR;
  }

  /** {@inheritDoc} */
  @Override
  protected void doRun() {
    logger.log(Level.FINEST, "Starting clean lost nodes worker");
    getClouds().forEach(this::cleanCloud);
  }

  private void cleanCloud(ComputeEngineCloud cloud) {
    logger.log(Level.FINEST, "Cleaning cloud " + cloud.getCloudName());
    Stream<Instance> allInstances = cloud.getAllInstances();
    Set<String> allNodesSet = cloud.getAllNodes().collect(Collectors.toSet());
    allInstances
        .filter(instance -> shouldTerminateStatus(instance.getStatus()))
        .filter(instance -> isOrphaned(instance, allNodesSet))
        .forEach(instance -> terminateInstance(instance, cloud));
  }

  private boolean isOrphaned(Instance instance, Set<String> nodes) {
    String instanceName = instance.getName();
    logger.log(Level.FINEST, "Checking instance " + instanceName);
    return !nodes.contains(instanceName);
  }

  private void terminateInstance(Instance instance, ComputeEngineCloud cloud) {
    String instanceName = instance.getName();
    logger.log(Level.INFO, "Instance " + instanceName + " not found locally, removing it");
    try {
      cloud
          .getClient()
          .terminateInstanceAsync(cloud.getProjectId(), instance.getZone(), instanceName);
    } catch (IOException ex) {
      logger.log(Level.WARNING, "Error terminating instance " + instanceName, ex);
    }
  }

  private List<ComputeEngineCloud> getClouds() {
    return Jenkins.get().clouds.stream()
        .filter(cloud -> cloud instanceof ComputeEngineCloud)
        .map(cloud -> (ComputeEngineCloud) cloud)
        .collect(Collectors.toList());
  }

  private boolean shouldTerminateStatus(String status) {
    return !status.equals("STOPPING");
  }
}
