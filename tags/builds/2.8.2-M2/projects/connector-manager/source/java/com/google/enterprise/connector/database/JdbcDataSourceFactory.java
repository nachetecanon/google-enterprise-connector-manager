// Copyright 2011 Google Inc.
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

package com.google.enterprise.connector.database;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Factory used to construct JDBC {@link DataSource} instances based upon
 * property values and reflection.  This class is specifically used to
 * overcome the lack of conditional bean creation in the Spring Framework.
 */
public class JdbcDataSourceFactory {
  private static final Logger LOGGER =
      Logger.getLogger(JdbcDataSourceFactory.class.getName());


  /**
   * This factory method makes an attempt to conditionally create a
   * {@link DataSource} instance based upon a property value and the
   * name of the {@link DataSource} implementation class.  If the property
   * value is non-null and non-empty, and the implementation class can be
   * found on the classpath, then an instance of that {@link DataSource}
   * implementation is created and returned; otherwise a fake (stub)
   * {@link DataSource} implementation is returned.
   *
   * @param description descriptive name of the DataSource driver to be
   *                    used in log messages.
   * @param className the name for {@link DataSource} implementation class
   * @param propertyValue the value of a configured Property
   */
  public static DataSource newJdbcDataSource(String description,
      String className, String propertyValue)
      throws InstantiationException, IllegalAccessException {

    // If the property (typically a datasource URL) is not specified,
    // then consider the DataSource to be unconfigured.  Create a stub
    // in its place.
    if (Strings.isNullOrEmpty(propertyValue)) {
      LOGGER.config(description + " JDBC DataSource has not been configured."
                    + " Creating a disabled stub in its place.");
      return new FakeDataSource(description);
    }

    // If the DataSource implementation class can not be found on the
    // classpath, then return a disabled stub in its place.
    Class clazz;
    try {
      clazz = Class.forName(className);
    } catch (ClassNotFoundException cnfe) {
      LOGGER.config(description + " JDBC DataSource implementation not found."
                    + " Creating a disabled stub in its place.");
      return new FakeDataSource(description);
    } catch (NoClassDefFoundError ncdfe) {
      LOGGER.config(description + " JDBC DataSource implementation not found."
                    + " Creating a disabled stub in its place.");
      return new FakeDataSource(description);
    }

    return (DataSource) clazz.newInstance();
  }

  /**
   * This is a {@link DataSource} implementation, as Spring does not allow
   * factory methods to return {@code null} instances.  This implementation
   * does nothing, but is sufficient to trigger the
   * {@link com.google.enterprise.connector.persist.JdbcStore} built on
   * top of it to consider itself disabled.
   */
  @VisibleForTesting
  static class FakeDataSource implements DataSource {
    private final String description;

    FakeDataSource(String description) {
      this.description = description;
    }

    private String message() {
      return description + " JDBC DataSource has not been configured.";
    }

    public String getDescription() {
      return "Disabled stub for " + description + " JDBC DataSource.";
    }

    /* Setter injectors for the benefit of Spring */
    public void setURL(String ignored) {
      // Do nothing.
    }

    public void setUser(String ignored) {
      // Do nothing.
    }

    public void setPassword(String ignored) {
      // Do nothing.
    }

    /* @Override */
    public Connection getConnection() throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public Connection getConnection(String username, String password)
        throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public void setLoginTimeout(int seconds) throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public int getLoginTimeout() throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public void setLogWriter(PrintWriter out) throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public PrintWriter getLogWriter() throws SQLException {
      throw new SQLException(message());
    }

    /* @Override */
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }

    /* @Override */
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException(message());
    }
  }
}