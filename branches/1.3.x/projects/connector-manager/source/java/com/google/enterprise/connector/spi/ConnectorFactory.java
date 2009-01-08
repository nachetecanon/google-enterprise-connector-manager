// Copyright 2007-2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.spi;

import java.util.Locale;
import java.util.Map;

/**
 * This factory provides a mechanism by which
 * {@link ConnectorType#validateConfig(Map, Locale, ConnectorFactory)}
 * may create instances of the connector for the purposes validation.
 * {@link Connector} instances created by the factory are not added to
 * the Connector Manager's list if running connectors and do not have
 * an on-disk representation.
 */
public interface ConnectorFactory {
  /**
   * Make a {@link Connector} instance of the {@link ConnectorType}
   * that owns this ConnectorFactory instance using the supplied
   * {@link java.util.Map} of configuration properties.
   *
   * @param config a {@link java.util.Map} of configuration properties.
   *         If null, the Map that was passed to validateConfig is used.
   * @return a {@link Connector} instance, instantiated by the Connector
   *         Manager in exactly the same way as it would if this config
   *         were valid and persisted.
   * @throws RepositoryException if the Connector construction
   *         fails for any reason.
   */
  Connector makeConnector(Map config) throws RepositoryException;
}
