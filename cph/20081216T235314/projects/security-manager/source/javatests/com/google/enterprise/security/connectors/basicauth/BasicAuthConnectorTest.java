// Copyright 2008 Google Inc.  All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.security.connectors.basicauth;

import com.google.enterprise.common.HttpClientAdapter;
import com.google.enterprise.common.HttpClientInterface;
import com.google.enterprise.connector.spi.AuthenticationResponse;
import com.google.enterprise.saml.common.GsaConstants.AuthNMechanism;
import com.google.enterprise.saml.server.AuthSite;
import com.google.enterprise.saml.server.UserIdentity;

import junit.framework.TestCase;

/* 
 * Tests for the {@link BasicAuthConnector} class.
 * Maybe should use a mock Idp...
 */
public class BasicAuthConnectorTest extends TestCase {

  private final HttpClientInterface httpClient;

  public BasicAuthConnectorTest(String name) {
    super (name);
    httpClient = new HttpClientAdapter();
  }
  
  public void testAuthenticate() {
    BasicAuthConnector conn;
    AuthSite site;
    UserIdentity id;

    // HTTP Basic Auth
    site = new AuthSite("http://leiz.mtv.corp.google.com", "/basic/", AuthNMechanism.BASIC_AUTH, null);
    id = new UserIdentity("basic", "test", site);
    conn = new BasicAuthConnector(httpClient, site.getHostname() + site.getRealm());
    AuthenticationResponse result = conn.authenticate(id);
    assertTrue(result.isValid());
    
    // HTTPS Basic Auth
    site = new AuthSite("https://entconcx100-testbed.corp.google.com",
                        "/sslsecure/test1/", AuthNMechanism.BASIC_AUTH, null);
    id = new UserIdentity("ruth_test1", "test1", site);
    conn = new BasicAuthConnector(httpClient, site.getHostname() + site.getRealm());
    result = conn.authenticate(id);
    assertFalse(result.isValid());  // TODO SSL problem, make this work
  }
}
