// Copyright 2010 Google Inc.
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
package com.google.enterprise.connector.util.diffing;

import com.google.enterprise.connector.spi.TraversalContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holder for the {@link TraversalContext}.
 */
public class TraversalContextManager {
  private final AtomicReference<TraversalContext> traversalContext
    = new AtomicReference<TraversalContext>();

  public void setTraversalContext(TraversalContext traversalContext) {
    if (traversalContext == null) {
      throw new NullPointerException("traversalContext must not be null");
    }
    this.traversalContext.set(traversalContext);
  }

  public TraversalContext getTraversalContext() {
    TraversalContext result = traversalContext.get();
    if (result == null) {
      throw new IllegalStateException(
          "setTraversalContext has not been called.");
    }
    return result;
  }
}