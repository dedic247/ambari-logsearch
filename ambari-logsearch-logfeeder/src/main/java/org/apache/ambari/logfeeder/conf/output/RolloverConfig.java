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
package org.apache.ambari.logfeeder.conf.output;

import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RolloverConfig {

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_MIN,
    description = "Rollover cloud log files after an interval (minutes)",
    examples = {"1"},
    defaultValue = "60",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_MIN + ":60}")
  private int rolloverThresholdTimeMins;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE,
    description = "Rollover cloud log files after the log file size reach this limit",
    examples = {"1024KB"},
    defaultValue = "80MB",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE + ":80MB}")
  private String rolloverSize;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_USE_GZIP,
    description = "Use GZip on archived logs.",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_USE_GZIP + ":true}")
  private boolean useGzip;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_IMMEDIATE_FLUSH,
    description = "Immediately flush cloud logs (to active location).",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_IMMEDIATE_FLUSH + ":false}")
  private boolean immediateFlush;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_ON_SHUTDOWN,
    description = "Rollover log files on shutdown",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_ON_SHUTDOWN + ":false}")
  private boolean rolloverOnShutdown;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_ON_STARTUP,
    description = "Rollover log files on startup",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_ON_STARTUP + ":false}")
  private boolean rolloverOnStartup;

  public int getRolloverThresholdTimeMins() {
    return rolloverThresholdTimeMins;
  }

  public void setRolloverThresholdTimeMins(int rolloverThresholdTimeMins) {
    this.rolloverThresholdTimeMins = rolloverThresholdTimeMins;
  }

  public String getRolloverSize() {
    return rolloverSize;
  }

  public void setRolloverSize(String rolloverSize) {
    this.rolloverSize = rolloverSize;
  }

  public boolean isUseGzip() {
    return useGzip;
  }

  public void setUseGzip(boolean useGzip) {
    this.useGzip = useGzip;
  }

  public boolean isImmediateFlush() {
    return immediateFlush;
  }

  public void setImmediateFlush(boolean immediateFlush) {
    this.immediateFlush = immediateFlush;
  }

  public boolean isRolloverOnShutdown() {
    return rolloverOnShutdown;
  }

  public void setRolloverOnShutdown(boolean rolloverOnShutdown) {
    this.rolloverOnShutdown = rolloverOnShutdown;
  }

  public boolean isRolloverOnStartup() {
    return rolloverOnStartup;
  }

  public void setRolloverOnStartup(boolean rolloverOnStartup) {
    this.rolloverOnStartup = rolloverOnStartup;
  }
}