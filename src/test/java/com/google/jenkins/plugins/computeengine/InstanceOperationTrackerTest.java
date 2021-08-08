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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Operation;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class InstanceOperationTrackerTest {

  @Rule public JenkinsRule r = new JenkinsRule();

  @Test
  public void acceptsNewOperations() {
    InstanceOperationTracker instanceOperationTracker = new InstanceOperationTracker(null);

    InstanceOperationTracker.InstanceOperation instanceOperation1 =
        new InstanceOperationTracker.InstanceOperation("instance1", "zone1", "prefix1", "1234");
    InstanceOperationTracker.InstanceOperation instanceOperation2 =
        new InstanceOperationTracker.InstanceOperation("instance2", "zone2", "prefix2", "5678");
    InstanceOperationTracker.InstanceOperation instanceOperation3 =
        new InstanceOperationTracker.InstanceOperation("instance3", "zone3", "prefix3", "abcd");

    instanceOperationTracker.add(instanceOperation1);
    instanceOperationTracker.add(instanceOperation2);

    Set<InstanceOperationTracker.InstanceOperation> instanceOperations =
        instanceOperationTracker.get();

    assertEquals(2, instanceOperations.size());
    assertTrue(instanceOperations.contains(instanceOperation1));
    assertTrue(instanceOperations.contains(instanceOperation2));
    assertFalse(instanceOperations.contains(instanceOperation3));
  }

  @Test
  public void removesCompletedOperations() throws Exception {

    ComputeEngineCloud cloud = Mockito.mock(ComputeEngineCloud.class);
    Compute compute = Mockito.mock(Compute.class);
    Mockito.when(cloud.getCompute()).thenReturn(compute);

    InstanceOperationTracker instanceOperationTracker = new InstanceOperationTracker(cloud);

    InstanceOperationTracker.InstanceOperation instanceOperation1 =
        new InstanceOperationTracker.InstanceOperation("instance1", "zone1", "prefix1", "1234");
    InstanceOperationTracker.InstanceOperation instanceOperation2 =
        new InstanceOperationTracker.InstanceOperation("instance2", "zone2", "prefix2", "5678");
    InstanceOperationTracker.InstanceOperation instanceOperation3 =
        new InstanceOperationTracker.InstanceOperation("instance3", "zone3", "prefix3", "abcd");

    Compute.ZoneOperations zoneOperations = Mockito.mock(Compute.ZoneOperations.class);
    Mockito.when(compute.zoneOperations()).thenReturn(zoneOperations);

    Compute.ZoneOperations.Get zoneOperationsGet = Mockito.mock(Compute.ZoneOperations.Get.class);
    // Mockito.when(zoneOperations.get(anyString(), anyString(), anyString()))
    //     .thenReturn(zoneOperationsGet);
    Mockito.when(zoneOperations.get(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("uhoh2"));
    // TODO: make the above exception trigger

    Operation zoneOperationsGetOperation = Mockito.mock(Operation.class);
    Mockito.when(zoneOperationsGet.execute()).thenReturn(zoneOperationsGetOperation);

    Mockito.when(zoneOperationsGetOperation.getStatus()).thenReturn("DONE");

    instanceOperationTracker.add(instanceOperation1);
    instanceOperationTracker.add(instanceOperation2);

    // Initially, there are two operations in the tracker

    {
      Set<InstanceOperationTracker.InstanceOperation> instanceOperations =
          instanceOperationTracker.get();

      assertEquals(2, instanceOperations.size());
      assertTrue(instanceOperations.contains(instanceOperation1));
      assertTrue(instanceOperations.contains(instanceOperation2));
      assertFalse(instanceOperations.contains(instanceOperation3));
    }

    // One of the operations is complete: removes operation

    instanceOperationTracker.removeCompleted();

    {
      Set<InstanceOperationTracker.InstanceOperation> instanceOperations =
          instanceOperationTracker.get();

      assertEquals(2, instanceOperations.size());
      assertTrue(instanceOperations.contains(instanceOperation1));
      assertTrue(instanceOperations.contains(instanceOperation2));
      assertFalse(instanceOperations.contains(instanceOperation3));
    }
  }
}
