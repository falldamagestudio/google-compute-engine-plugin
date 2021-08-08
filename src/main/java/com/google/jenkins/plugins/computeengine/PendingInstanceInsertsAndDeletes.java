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

import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Operation;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.java.Log;

@Log
public class PendingInstanceInsertsAndDeletes {

  public static class PendingInstance {
    private String name;
    private String zone;
    private String namePrefix;
    private String operationId;

    public PendingInstance(String name, String zone, String namePrefix, String operationId) {
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

  private Set<PendingInstance> pendingInserts;
  private Set<PendingInstance> pendingDeletes;

  private ComputeEngineCloud cloud;

  public PendingInstanceInsertsAndDeletes(ComputeEngineCloud cloud) {
    this.cloud = cloud;
  }

  public void enqueueInsert(PendingInstance pendingInstance) {
    pendingInserts.add(pendingInstance);
    log.fine("Instance insert operation started: " + pendingInstance.getName());
  }

  public void enqueueDelete(PendingInstance pendingInstance) {
    pendingDeletes.add(pendingInstance);
    log.fine("Instance delete operation started: " + pendingInstance.getName());
  }

  protected Operation getZoneOperation(String zone, String operation) throws IOException {
    Compute compute = cloud.getCompute();

    Compute.ZoneOperations.Get request =
        compute.zoneOperations().get(cloud.getProjectId(), zone, operation);
    Operation response = request.execute();

    return response;
  }

  protected boolean isZoneOperationDone(String zone, String operation) {
    try {
      return getZoneOperation(zone, operation).getStatus().equals("DONE");
    } catch (IOException ioException) {
      log.warning("getZoneOperation exception: " + ioException.toString());
      return false;
    }
  }

  private Set<PendingInstance> refresh(Set<PendingInstance> oldPendingInstances) {

    Set<PendingInstance> newPendingInstances =
        oldPendingInstances.stream()
            .filter(
                pendingInstance ->
                    !isZoneOperationDone(
                        pendingInstance.getZone(), pendingInstance.getOperationId()))
            .collect(Collectors.toSet());

    return newPendingInstances;
  }

  public void refresh() {

    Set<PendingInstance> newPendingInserts = refresh(pendingInserts);
    Set<PendingInstance> newPendingDeletes = refresh(pendingDeletes);

    List<String> completedInsertNames =
        pendingInserts.stream()
            .filter(pendingInstance -> !newPendingInserts.contains(pendingInstance))
            .map(pendingInstance -> pendingInstance.getName())
            .collect(Collectors.toList());
    List<String> completedDeleteNames =
        pendingDeletes.stream()
            .filter(pendingInstance -> !newPendingDeletes.contains(pendingInstance))
            .map(pendingInstance -> pendingInstance.getName())
            .collect(Collectors.toList());
    if (!completedInsertNames.isEmpty())
      log.fine(
          "Instance insert operations completed: ["
              + String.join(", ", completedInsertNames)
              + "]");
    if (!completedDeleteNames.isEmpty())
      log.fine(
          "Instance delete operations completed: ["
              + String.join(", ", completedDeleteNames)
              + "]");

    pendingInserts = newPendingInserts;
    pendingDeletes = newPendingDeletes;
  }

  public Set<PendingInstance> getPendingInserts() {
    return pendingInserts;
  }

  public Set<PendingInstance> getPendingDeletes() {
    return pendingDeletes;
  }
}
