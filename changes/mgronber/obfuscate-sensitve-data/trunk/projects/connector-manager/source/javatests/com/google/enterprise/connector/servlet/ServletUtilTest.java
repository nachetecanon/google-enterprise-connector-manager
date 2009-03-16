// Copyright (C) 2006 Google Inc.
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

package com.google.enterprise.connector.servlet;

import com.google.enterprise.connector.spi.ConfigureResponse;
import com.google.enterprise.connector.spi.SimpleConnectorType;

import junit.framework.Assert;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ServletUtilTest extends TestCase {
  private static final String HIDE_KEY_ONE = "PasswordOne";
  private static final String HIDE_KEY_TWO = "a_password_two";
  private static final String HIDE_KEY_THREE = "imapasswordtoo";
  private static final String CLEAR_KEY_ONE = "NotAPwd";    


  public void testPrependCmPrefix() {
    onePrependTest("foo name=\"bar\" bar", "foo name=\"CM_bar\" bar");
    onePrependTest("name=\"bar\"", "name=\"CM_bar\"");
    onePrependTest("name=\"bar\" name=\"baz\"", "name=\"CM_bar\" name=\"CM_baz\"");
    onePrependTest("name='bar' name=\"baz\"", "name='CM_bar' name=\"CM_baz\"");
    onePrependTest("name = 'bar'   name   =  \"baz\"",
        "name = 'CM_bar'   name   =  \"CM_baz\"");
  }

  private void onePrependTest(String original, String expected) {
    String result = ServletUtil.prependCmPrefix(original);
    Assert.assertEquals(expected, result);
    Assert.assertEquals(original, ServletUtil.stripCmPrefix(result));
  }

  public void testObfuscateForm() {
    // Create simple form.
    String protectedValue = "protected";
    String clearValue = "clear";
    Map configMap = new HashMap();
    configMap.put(HIDE_KEY_ONE, protectedValue);
    configMap.put(HIDE_KEY_TWO, protectedValue);
    configMap.put(HIDE_KEY_THREE, protectedValue);
    configMap.put(CLEAR_KEY_ONE, clearValue);
    List keys = Arrays.asList(new String[] {HIDE_KEY_ONE, HIDE_KEY_TWO,
        HIDE_KEY_THREE, CLEAR_KEY_ONE});
    SimpleConnectorType connType = new SimpleConnectorType();
    connType.setConfigKeys(keys);
    ConfigureResponse configResponse =
        connType.getPopulatedConfigForm(configMap, Locale.ENGLISH);
    String configForm = configResponse.getFormSnippet();

    // Filter out sensitive data.
    String obfuscatedForm = ServletUtil.filterSensitiveData(configForm);
    assertNotNull("Form returned", obfuscatedForm);
    assertFalse("Form does not contain protected values",
        obfuscatedForm.contains(protectedValue));
    assertTrue("Form still contains clear values",
        obfuscatedForm.contains(clearValue));
    
    
    // Test exception cases.
    configForm = configForm.substring(1);
    obfuscatedForm = ServletUtil.filterSensitiveData(configForm);
    assertNull("Error - null form returned", obfuscatedForm);
  }

  public void testObfuscateTools() {
    String baseClearValue = "this is open";
    String baseObfuscatedValue = "************";
    String clear;
    String obfuscated;

    // Simple cycle.
    clear = baseClearValue;
    obfuscated = ServletUtil.obfuscateValue(clear);
    assertEquals("clear was not changed", baseClearValue, clear);
    assertEquals("string was obfuscated", baseObfuscatedValue, obfuscated);
    assertTrue("obfuscated string recognized",
        ServletUtil.isObfuscated(obfuscated));

    // isObfuscated corner cases.
    assertFalse(ServletUtil.isObfuscated("***n***"));
    assertFalse(ServletUtil.isObfuscated("***n"));
    assertFalse(ServletUtil.isObfuscated("n***"));

    // obfuscateValue corner cases.
    assertEquals(baseObfuscatedValue,
        ServletUtil.obfuscateValue("1234 56 7890"));
    assertEquals(baseObfuscatedValue,
        ServletUtil.obfuscateValue("-+=< >^ ()[]"));
    assertEquals(baseObfuscatedValue,
        ServletUtil.obfuscateValue("ABCD EF GHIJ"));
    assertEquals(baseObfuscatedValue,
        ServletUtil.obfuscateValue("**** &@ !#$%"));

    // isKeySensitive cases.
    assertTrue(ServletUtil.isKeySensitive("Password"));
    assertTrue(ServletUtil.isKeySensitive("PasswordOne"));
    assertTrue(ServletUtil.isKeySensitive("OnePassword"));
    assertTrue(ServletUtil.isKeySensitive("password"));
    assertTrue(ServletUtil.isKeySensitive("pAsSwOrD"));
    assertTrue(ServletUtil.isKeySensitive("imapasswordtoo"));
    assertFalse(ServletUtil.isKeySensitive("pwd"));
  }

  public void testReplaceSensitiveData() {
    String clearValue = "clear value";
    Map clearConfig = new HashMap();
    clearConfig.put(HIDE_KEY_ONE, clearValue);
    clearConfig.put(HIDE_KEY_TWO, clearValue);
    clearConfig.put(HIDE_KEY_THREE, clearValue);
    clearConfig.put(CLEAR_KEY_ONE, clearValue);

    // Let's just obfuscate all the ones that should be and then revert them.
    String obfuscatedValue = "***********";
    Map obfuscatedConfig = new HashMap();
    obfuscateValues(clearConfig, obfuscatedConfig);
    assertEquals(obfuscatedValue, obfuscatedConfig.get(HIDE_KEY_ONE));
    assertEquals(obfuscatedValue, obfuscatedConfig.get(HIDE_KEY_TWO));
    assertEquals(obfuscatedValue, obfuscatedConfig.get(HIDE_KEY_THREE));
    assertEquals(clearValue, obfuscatedConfig.get(CLEAR_KEY_ONE));
    ServletUtil.replaceSensitiveData(obfuscatedConfig, clearConfig);
    assertEquals(clearValue, obfuscatedConfig.get(HIDE_KEY_ONE));
    assertEquals(clearValue, obfuscatedConfig.get(HIDE_KEY_TWO));
    assertEquals(clearValue, obfuscatedConfig.get(HIDE_KEY_THREE));
    assertEquals(clearValue, obfuscatedConfig.get(CLEAR_KEY_ONE));

    // Now let's obfuscate and change some of the values to make sure the
    // new values are preserved.
    String newValueOne = "new nice value";
    String newValueTwo = "******n******";
    String evilValue = "***";
    obfuscatedConfig.clear();
    obfuscateValues(clearConfig, obfuscatedConfig);
    obfuscatedConfig.put(HIDE_KEY_ONE, newValueOne);
    obfuscatedConfig.put(HIDE_KEY_TWO, newValueTwo);
    obfuscatedConfig.put(HIDE_KEY_THREE, evilValue);
    ServletUtil.replaceSensitiveData(obfuscatedConfig, clearConfig);
    assertEquals(newValueOne, obfuscatedConfig.get(HIDE_KEY_ONE));
    assertEquals(newValueTwo, obfuscatedConfig.get(HIDE_KEY_TWO));
    assertEquals(evilValue, obfuscatedConfig.get(HIDE_KEY_THREE));
    assertEquals(clearValue, obfuscatedConfig.get(CLEAR_KEY_ONE));
  }
  
  private void obfuscateValues(Map clearConfig, Map obfuscatedConfig) {
    for (Iterator iter = clearConfig.keySet().iterator(); iter.hasNext(); ) {
      String key = (String) iter.next();
      if (ServletUtil.isKeySensitive(key)) {
        obfuscatedConfig.put(key,
            ServletUtil.obfuscateValue((String) clearConfig.get(key)));
      } else {
        obfuscatedConfig.put(key, clearConfig.get(key));
      }
    }
  }
}
