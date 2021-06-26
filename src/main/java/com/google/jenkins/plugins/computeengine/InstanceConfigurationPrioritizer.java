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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InstanceConfigurationPrioritizer {

  private String CLOUD_PREFIX;
  private String CONFIG_LABEL_KEY;
  private String CLOUD_ID_LABEL_KEY;

  private static int configsNext;
  private static int instancesNext;

  public InstanceConfigurationPrioritizer(
      String CLOUD_PREFIX, String CONFIG_LABEL_KEY, String CLOUD_ID_LABEL_KEY) {
    this.CLOUD_PREFIX = CLOUD_PREFIX;
    this.CONFIG_LABEL_KEY = CONFIG_LABEL_KEY;
    this.CLOUD_ID_LABEL_KEY = CLOUD_ID_LABEL_KEY;
  }

  /**
   * Choose config from list of available configs. Current implementation use round robin strategy
   * starting at semi random element of list. Because most of times arriving requests asks for only
   * 1 new node, we don't want to start every time from 1 element.
   *
   * @param configs List of configs to choose from.
   * @return Chosen config from list.
   */
  private InstanceConfiguration chooseConfigFromList(List<InstanceConfiguration> configs) {
    return configs.get(Math.abs(configsNext++) % configs.size());
  }

  /**
   * Choose instance from list of available instances. Current implementation use round robin
   * strategy starting at semi random element of list.
   *
   * @param instances List of instances to choose from.
   * @return Chosen instance from list.
   */
  private Instance chooseInstanceFromList(List<Instance> instances) {
    return instances.get(Math.abs(instancesNext++) % instances.size());
  }

  // Given a config, and a stream of instances,
  //  return those instances that are associated with that config

  Stream<Instance> filterInstancesForConfig(
      InstanceConfiguration config, Stream<Instance> instances) {
    return instances.filter(
        instance ->
            instance
                .getLabels()
                .getOrDefault(CONFIG_LABEL_KEY, "<no config label key found>")
                .equals(config.getNamePrefix()));
  }

  // Given a list of configs, and a list of provisionable instances,
  //  return those configs that have at least one provisionable instance associated with it

  List<InstanceConfiguration> getConfigsWithProvisionableInstances(
      List<InstanceConfiguration> configs, List<Instance> provisionableInstances) {

    List<InstanceConfiguration> configsWithProvisionableInstances =
        configs.stream()
            .filter(
                config ->
                    filterInstancesForConfig(config, provisionableInstances.stream())
                        .findAny()
                        .isPresent())
            .collect(Collectors.toList());
    return configsWithProvisionableInstances;
  }

  // Given a config, and a list of provisionable instances,
  //  return those provisionable instances that are associated with that particular config

  List<Instance> getProvisionableInstancesForConfig(
      InstanceConfiguration config, List<Instance> provisionableInstances) {
    List<Instance> provisionableInstancesForConfig =
        filterInstancesForConfig(config, provisionableInstances.stream())
            .collect(Collectors.toList());
    return provisionableInstancesForConfig;
  }

  // Given a list of configs, and a list of remote instances
  //  return those configs that have capacity for at least one extra instance

  List<InstanceConfiguration> getConfigsWithSpareCapacity(
      List<InstanceConfiguration> configs, List<Instance> instances) {

    List<InstanceConfiguration> configsWithSpareCapacity =
        configs.stream()
            .filter(
                config ->
                    config.getMaxNumInstancesToCreate()
                        > filterInstancesForConfig(config, instances.stream()).count())
            .collect(Collectors.toList());
    return configsWithSpareCapacity;
  }

  public static class ConfigAndInstance {
    InstanceConfiguration config;
    Instance instance;

    public ConfigAndInstance(InstanceConfiguration config, Instance instance) {
      this.config = config;
      this.instance = instance;
    }
  }

  // Given a list of relevant configs, a list of all instances related to these configs,
  // and a list of all instances not currnently used by these configs,
  // choose a suitable config (and possible also an instance) to provision
  //
  // The result is either:
  // - a ConfigAndInstance identifying a config & an instance - use this config, re-use this
  // instance
  // - a ConfigAndInstance identifying a config, but no instance - use this config, provision a new
  // instance
  // - null - there is no config suitable for provisioning

  public ConfigAndInstance getConfigAndInstance(
      List<InstanceConfiguration> configs,
      List<Instance> allInstances,
      List<Instance> provisionableInstances) {

    // First, look for a config that has provisionable instances
    // If we find one, choose that config, plus a corresponding instance

    if (!provisionableInstances.isEmpty()) {
      List<InstanceConfiguration> configsWithProvisionableInstances =
          getConfigsWithProvisionableInstances(configs, provisionableInstances);
      if (!configsWithProvisionableInstances.isEmpty()) {
        InstanceConfiguration tentativeConfig =
            chooseConfigFromList(configsWithProvisionableInstances);
        List<Instance> provisionableInstancesForConfig =
            getProvisionableInstancesForConfig(tentativeConfig, provisionableInstances);
        if (!provisionableInstancesForConfig.isEmpty()) {
          return new ConfigAndInstance(
              tentativeConfig, chooseInstanceFromList(provisionableInstancesForConfig));
        }
      }
    }

    // Second, look for a config that has no provisionable instances but available capacity
    // If we find one, choose that config, but leave the instance blank

    List<InstanceConfiguration> configsWithSpareCapacity =
        getConfigsWithSpareCapacity(configs, allInstances);
    if (!configsWithSpareCapacity.isEmpty()) {
      return new ConfigAndInstance(chooseConfigFromList(configsWithSpareCapacity), null);
    }

    // We did not find any suitable config

    return null;
  }
}
