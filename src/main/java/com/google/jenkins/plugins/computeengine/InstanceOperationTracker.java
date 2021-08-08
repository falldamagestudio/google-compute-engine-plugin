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

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.java.Log;

// Provides a Jenkins-side cache for GCE's "operations" for instances
//
// These operations are, for example, instance creation / destruction,
//  and the log of such operations can be listed via `gcloud compute instances list --zone=<zone>`
//
// The operations cover the gap between the plugin requesting an instance to be created,
//  and the instance being present via the GCE APIs. The life cycle for an instance is kind of like this:
//
//
//  Plugin calls GCE API                          Plugin calls GCE API
//  v                                             v
//  |--- Insert instance operation ---|           |--- Delete instance operation ---|
//              |----------- Instance is visible via API ----------------|
//
//
// The tracking is designed to work without polling or subscribing to events:
// The plugin code will manually enqueue important operations into one of these trackers
//  by calling add(..) at the time that the API call is performed.
// Later, when the plugin wants to check 'which operations are still in progress?',
//  the plugin first has to call removeCompleted(). That call will talk to the GCE backend,
//  check the status of all operations, and remove those that are already done.
// After this, the plugin can access the cached state via get().

@Log
public class InstanceOperationTracker {

  public static class InstanceOperation {
    private String name;
    private String zone;
    private String namePrefix;
    private String operationId;

    public InstanceOperation(String name, String zone, String namePrefix, String operationId) {
      this.name = name;
      this.zone = zone;
      this.namePrefix = namePrefix;
      this.operationId = operationId;
    }

    public String getName() {
      return name;
    }

    public String getZone() {
      return zone;
    }

    public String getNamePrefix() {
      return namePrefix;
    }

    public String getOperationId() {
      return operationId;
    }
  };

  private Set<InstanceOperation> operations = new HashSet<InstanceOperation>();

  private ComputeEngineCloud cloud;

  public InstanceOperationTracker(ComputeEngineCloud cloud) {
    this.cloud = cloud;
  }

  public void add(InstanceOperation instanceOperation) {
    operations.add(instanceOperation);
    log.fine("Instance operation added: " + instanceOperation.getName());
  }

  protected boolean isZoneOperationDone(String zone, String operation) {
    try {
      return cloud
          .getClient2()
          .getZoneOperation(cloud.getProjectId(), zone, operation)
          .getStatus()
          .equals("DONE");
    } catch (IOException ioException) {
      log.warning("getZoneOperation exception: " + ioException.toString());
      return false;
    }
  }

  private Set<InstanceOperation> removeCompletedOperations(
      Set<InstanceOperation> oldPendingInstances) {

    Set<InstanceOperation> newPendingInstances =
        oldPendingInstances.stream()
            .filter(
                instanceOperation ->
                    !isZoneOperationDone(
                        instanceOperation.getZone(), instanceOperation.getOperationId()))
            .collect(Collectors.toSet());

    return newPendingInstances;
  }

  public void removeCompleted() {

    Set<InstanceOperation> newOperations = removeCompletedOperations(operations);

    List<String> completedNames =
        operations.stream()
            .filter(instanceOperation -> !newOperations.contains(instanceOperation))
            .map(instanceOperation -> instanceOperation.getName())
            .collect(Collectors.toList());
    if (!completedNames.isEmpty())
      log.fine("Instance operations completed: [" + String.join(", ", completedNames) + "]");

    operations = newOperations;
  }

  public Set<InstanceOperation> get() {
    return operations;
  }
}
