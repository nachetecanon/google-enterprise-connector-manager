// Copyright 2009 Google Inc. All Rights Reserved.
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

package com.google.enterprise.connector.manager;

import com.google.enterprise.connector.manager.TestService.TestServiceToken;

import junit.framework.TestCase;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tests starting and stopping {@link ContextService} instances when the
 * {@link Context} is started and stopped.
 */
public class TestServiceTest extends TestCase {
  private static final String TEST_DIR =
      "testdata/contextTests/contextService/";
  private static final String APPLICATION_CONTEXT = "applicationContext.xml";

  // IDs from the test applicationContext.xml file.
  private static final String TEST_SERVICE_ONE = "TestServiceOne";
  private static final String TEST_SERVICE_TWO = "TestServiceTwo";
  private static final String TEST_SERVICE_THREE = "TestServiceThree";

  // Lists of expected service names.
  private static final List allServiceNames = Arrays.asList(
      new String[] {TEST_SERVICE_ONE, TEST_SERVICE_TWO, TEST_SERVICE_THREE});
  private static final List orderedServiceIds = Arrays.asList(
      new String[] {"1", "2"});
  private static final List orderedServiceNames = Arrays.asList(
      new String[] {TEST_SERVICE_THREE, TEST_SERVICE_TWO});
  private static final List allOrderedServiceNames = Arrays.asList(
      new String[] {TEST_SERVICE_THREE, TEST_SERVICE_TWO, TEST_SERVICE_ONE});

  private Context context;

  protected void setUp() throws Exception {
    // Setup a Context to point to stand alone XML file with just the needed
    // beans.
    Context.refresh();
    context = Context.getInstance();
    context.setStandaloneContext(TEST_DIR + APPLICATION_CONTEXT, null);
  }

  protected void tearDown() throws Exception {
    context = null;
    Context.refresh();
  }

  /**
   * Tests to make sure the expected beans are in the context.
   */
  public void testServiceBeans() {
    ApplicationContext appContext = context.getApplicationContext();

    // Check to make sure the right services are in the context.
    Map services = getServicesBeans(appContext);
    assertEquals("Correct number of services",
        allServiceNames.size(), services.size());
    assertCorrectKeys("Correct service names", allServiceNames, services);

    // Check to make sure the right services have been registered for load
    // order and that they are in the expected order.
    Map contextServices =
        (Map) getBean(Context.ORDERED_SERVICES_BEAN_NAME, null);
    assertEquals("Correct number of ordered services",
        orderedServiceIds.size(), contextServices.size());
    assertCorrectKeys("Correct ordered service ids",
        orderedServiceIds, contextServices);
    assertServicesInOrder("Ordered services are in correct order",
        orderedServiceNames, contextServices);
  }

  /**
   * Tests the order of starting and stopping of the services.
   */
  public void testServiceOrder() {
    // Get the total list of services and register token queue with them.
    List tokenList = new ArrayList();
    ApplicationContext appContext = context.getApplicationContext();
    Map services = getServicesBeans(appContext);
    for (Iterator valueIter = services.values().iterator();
         valueIter.hasNext(); ) {
      TestService service = (TestService) valueIter.next();
      service.setTokenList(tokenList);
    }
    // Start and shutdown the Context and test the tokens.
    // TODO(mgronber): Remove this when the Scheduler has become a service.
    context.setFeeding(false);
    context.start();
    context.shutdown(false);
    assertEquals("check amount of tokens",
        2 * allOrderedServiceNames.size(), tokenList.size());
    assertTokenOrder("services started/stopped in right order",
        allOrderedServiceNames, false, tokenList);
    tokenList.clear();
    context.start();
    context.shutdown(true);
    assertTokenOrder("services started/stopped in right order",
        allOrderedServiceNames, true, tokenList);
  }

  /**
   * Tests the order of starting and stopping of the services.
   */
  public void testServiceState() {
    // Get the total list of services and register token queue with them.
    List tokenList = new ArrayList();
    ApplicationContext appContext = context.getApplicationContext();
    Map services = getServicesBeans(appContext);
    for (Iterator valueIter = services.values().iterator();
         valueIter.hasNext(); ) {
      TestService service = (TestService) valueIter.next();
      service.setTokenList(tokenList);
    }
    // Start and shutdown the Context and test the status of the service.
    // TODO(mgronber): Remove this when the Scheduler has become a service.
    context.setFeeding(false);
    context.start();
    assertServicesStarted("check services are running",
        true, services.values());
    context.shutdown(false);
    assertServicesStarted("check services are not running",
        false, services.values());
    context.start();
    assertServicesStarted("check services are running",
        true, services.values());
    context.shutdown(true);
    assertServicesStarted("check services are not running",
        false, services.values());
  }
  
  /**
   * Tests that services can be retrieved from the Context.
   */
  public void testFindService() {
    ApplicationContext appContext = context.getApplicationContext();
    Map services = getServicesBeans(appContext);
    for (Iterator valueIter = services.values().iterator();
         valueIter.hasNext(); ) {
      ContextService expectedService = (ContextService) valueIter.next();
      assertEquals("found expected service",
          expectedService, context.findService(expectedService.getName()));
    }
    assertTrue("null if not found", context.findService("bogus") == null);
  }
  
  /**
   * Checks the status of all the given services to make sure it matches the
   * given expected state. 
   */
  private void assertServicesStarted(String message, boolean expectedState,
      Collection services) {
    for (Iterator iter = services.iterator(); iter.hasNext(); ) {
      assertEquals(message, expectedState,
          ((ContextService) iter.next()).isRunning());
    }
  }

  private Map getServicesBeans(ListableBeanFactory factory) {
    return factory.getBeansOfType(ContextService.class);
  }

  private Object getBean(String name, Class requiredType) {
    return context.getRequiredBean(name, requiredType);
  }

  /**
   * Compares the given list of expected keys with the key set from the
   * given map of services to make sure they are all included.  Does not check
   * for additional keys in the key set.
   */
  private void assertCorrectKeys(String message, 
      List expectedKeys, Map services) {
    List missingServiceNames = new ArrayList();

    for (Iterator iter = expectedKeys.iterator(); iter.hasNext(); ) {
      Object serviceName = iter.next();
      if (!services.containsKey(serviceName)) {
        missingServiceNames.add(serviceName);
      }
    }
    assertTrue(message, missingServiceNames.isEmpty());
  }

  /**
   * Compares the order of the given keys with the order of the related values
   * in the given map of services.  Size of list is known to be equal to the
   * size of the map.
   */
  private void assertServicesInOrder(String message, List orderedKeys,
      Map contextServices) {
    Iterator listIter = orderedKeys.iterator();
    Iterator mapIter = contextServices.entrySet().iterator();
    while (listIter.hasNext()) {
      String serviceName = (String) listIter.next();
      TestService service =
          (TestService) ((Map.Entry)mapIter.next()).getValue();
      assertEquals(message, serviceName, service.getName());
    }
  }

  /**
   * Compares the order of the tokens in the given token list with the service
   * names in the given list of service names and force value.  It is expected
   * that the token list would contain tokens showing the services being started
   * in the order given and then stopped in reverse order with the given force
   * value.  
   */
  private void assertTokenOrder(String message,
      List orderedServiceNames, boolean force, List tokenList) {
    Iterator tokenIter = tokenList.iterator();
    TestServiceToken token;
    // First check for start tokens.
    for (int index = 0; index < orderedServiceNames.size(); index++) {
      token = (TestServiceToken) tokenIter.next();
      assertEquals(message,
          orderedServiceNames.get(index), token.getService());
      assertEquals(message, "start", token.getAction());
    }
    // Then check for stop tokens.  Note they should be in reverse order.
    for (int index = orderedServiceNames.size() - 1; index >= 0; index--) {
      token = (TestServiceToken) tokenIter.next();
      assertEquals(message,
          orderedServiceNames.get(index), token.getService());
      assertEquals(message, "stop", token.getAction());
      assertEquals(message, force, token.isActionForced());
    }
  }
}
