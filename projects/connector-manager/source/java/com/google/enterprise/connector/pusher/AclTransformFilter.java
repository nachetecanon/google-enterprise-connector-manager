// Copyright 2013 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.pusher;

import com.google.common.base.Preconditions;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.Property;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SimpleProperty;
import com.google.enterprise.connector.spi.SkippedDocumentException;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.SpiConstants.DocumentType;
import com.google.enterprise.connector.spi.Value;
import com.google.enterprise.connector.util.filter.DocumentFilterFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Dynamically assembles a chain of {@link DocumentFilter}s that transform
 * ACL properties based upon the ACL support in the current GSA.  This also
 * transforms legacy user and group roles into aclusers and aclgroups syntax.
 */
public class AclTransformFilter implements DocumentFilterFactory {
  /**
   * GSA 7.0 introduced the ability to use the acl tag in feeds and introduced
   * ACL inheritance. The meaning of ACLs changes depending on if the GSA
   * supports inheritance, for instance.
   */
  /* TODO(jlacey): This probably needs to change if we support DENY on 6.14. */
  private final FeedConnection feedConnection;

  public AclTransformFilter(FeedConnection feedConnection) {
    this.feedConnection = feedConnection;
  }

  /**
   * Constructs a ACL property procssing pipeline, assembled from filters
   * determined by the GSA ACL support and current document features.
   *
   * @param source the input {@link Document} for the filters
   * @return the head of the chain of filters
   */
  @Override
  public Document newDocumentFilter(Document source)
      throws RepositoryException {
    Preconditions.checkNotNull(source);
    Document filter = source;

    // If GSA doesn't support inherited ACLs, strip them out.
    if (!feedConnection.supportsInheritedAcls()) {
      filter = new AclDocumentFilter().newDocumentFilter(filter);
    }

    // If the connector supplies old style user and group roles, transform them.
    for (String name : source.getPropertyNames()) {
      if (name.startsWith(SpiConstants.GROUP_ROLES_PROPNAME_PREFIX) ||
          name.startsWith(SpiConstants.USER_ROLES_PROPNAME_PREFIX)) {
        filter = new AclUserGroupRolesFilter().newDocumentFilter(filter);
        break;
      }
    }

    return filter;
  }
}