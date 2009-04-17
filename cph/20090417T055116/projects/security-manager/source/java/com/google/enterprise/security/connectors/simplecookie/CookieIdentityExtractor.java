// Copyright 2008 Google Inc. All Rights Reserved.
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

package com.google.enterprise.security.connectors.simplecookie;

/**
 * Interface for use by connectors that extract an identity from a cookie, such
 * as {@link SimpleCookieIdentityConnector}. Objects that implement this
 * interface do the core job of taking a cookie and extracting an identity from
 * it.
 */
public interface CookieIdentityExtractor {

  /**
   * Gets an identity from a cookie.
   * @param s the cookie value
   * @return the extracted identity
   */
  public String extract(String s);

}
