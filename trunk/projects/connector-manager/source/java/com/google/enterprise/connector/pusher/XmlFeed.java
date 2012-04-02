// Copyright 2009 Google Inc.
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

package com.google.enterprise.connector.pusher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.enterprise.connector.servlet.ServletUtil;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.Property;
import com.google.enterprise.connector.spi.RepositoryDocumentException;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SimpleProperty;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.SpiConstants.AclAccess;
import com.google.enterprise.connector.spi.SpiConstants.AclScope;
import com.google.enterprise.connector.spi.SpiConstants.FeedType;
import com.google.enterprise.connector.spi.Value;
import com.google.enterprise.connector.spi.XmlUtils;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;
import com.google.enterprise.connector.spiimpl.ValueImpl;
import com.google.enterprise.connector.util.UniqueIdGenerator;
import com.google.enterprise.connector.util.UuidGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to generate XML Feed for a document from the Document and send it
 * to GSA.
 */
public class XmlFeed extends ByteArrayOutputStream implements FeedData {
  private static final Logger LOGGER =
      Logger.getLogger(XmlFeed.class.getName());

  private final String dataSource;
  private final FeedType feedType;
  private final int maxFeedSize;
  private final Appendable feedLogBuilder;
  private final String feedId;

  /**
   * The prefix that will be used for contentUrl generation.
   * The prefix should include protocol, host and port, web app,
   * and servlet to point back at this Connector Manager instance.
   * For example:
   * {@code http://localhost:8080/connector-manager/getDocumentContent}
   */
  private final String contentUrlPrefix;

  private static UniqueIdGenerator uniqueIdGenerator = new UuidGenerator();

  private boolean isClosed;
  private int recordCount;

  private static Set<String> propertySkipSet;

  static {
    propertySkipSet = new HashSet<String>();
    propertySkipSet.add(SpiConstants.PROPNAME_CONTENT);
    propertySkipSet.add(SpiConstants.PROPNAME_DOCID);
    propertySkipSet.add(SpiConstants.PROPNAME_LOCK);
    propertySkipSet.add(SpiConstants.PROPNAME_PAGERANK);
    propertySkipSet.add(SpiConstants.PROPNAME_ACLINHERITFROM_DOCID);
    propertySkipSet.add(SpiConstants.PROPNAME_ACLINHERITFROM_FEEDTYPE);
  }

  // Strings for XML tags.
  public static final String XML_DEFAULT_ENCODING = "UTF-8";
  private static final String XML_START = "<?xml version='1.0' encoding='"
      + XML_DEFAULT_ENCODING + "'?><!DOCTYPE gsafeed PUBLIC"
      + " \"-//Google//DTD GSA Feeds//EN\" \"gsafeed.dtd\">";
  private static final String XML_GSAFEED = "gsafeed";
  private static final String XML_HEADER = "header";
  private static final String XML_DATASOURCE = "datasource";
  private static final String XML_FEEDTYPE = "feedtype";
  private static final String XML_GROUP = "group";
  private static final String XML_RECORD = "record";
  private static final String XML_METADATA = "metadata";
  private static final String XML_META = "meta";
  private static final String XML_CONTENT = "content";
  private static final String XML_ACTION = "action";
  private static final String XML_URL = "url";
  private static final String XML_DISPLAY_URL = "displayurl";
  private static final String XML_MIMETYPE = "mimetype";
  private static final String XML_LAST_MODIFIED = "last-modified";
  private static final String XML_LOCK = "lock";
  private static final String XML_AUTHMETHOD = "authmethod";
  private static final String XML_PAGERANK = "pagerank";
  private static final String XML_NAME = "name";
  private static final String XML_ENCODING = "encoding";

  public static final String XML_BASE64BINARY = "base64binary";
  public static final String XML_BASE64COMPRESSED = "base64compressed";

  private static final String CONNECTOR_AUTHMETHOD = "httpbasic";

  private static final String XML_ACL = "acl";
  private static final String XML_TYPE = "inheritance-type";
  private static final String XML_INHERIT_FROM = "inherit-from";
  private static final String XML_PRINCIPAL = "principal";
  private static final String XML_SCOPE = "scope";
  private static final String XML_ACCESS = "access";

  public XmlFeed(String dataSource, FeedType feedType, int maxFeedSize,
      Appendable feedLogBuilder, String contentUrlPrefix) throws IOException {
    super(maxFeedSize);
    this.maxFeedSize = maxFeedSize;
    this.dataSource = dataSource;
    this.feedType = feedType;
    this.feedLogBuilder = feedLogBuilder;
    this.recordCount = 0;
    this.isClosed = false;
    this.feedId = uniqueIdGenerator.uniqueId();
    this.contentUrlPrefix = contentUrlPrefix;
    String prefix = xmlFeedPrefix(dataSource, feedType);
    write(prefix.getBytes(XML_DEFAULT_ENCODING));
  }

  @VisibleForTesting
  static void setUniqueIdGenerator(UniqueIdGenerator idGenerator) {
    uniqueIdGenerator = idGenerator;
  }

  /*
   * XmlFeed Public Interface.
   */

  /**
   * Returns the unique ID assigned to this Feed file and all
   * records within the Feed.
   */
  public String getFeedId() {
    return feedId;
  }

  /**
   * Returns {@code true} if the feed is sufficiently full to submit
   * to the GSA.
   */
  public boolean isFull() {
    int bytesLeft = maxFeedSize - size();
    int avgRecordSize = size()/recordCount;
    // If less then 3 average size docs would fit, then consider it full.
    if (bytesLeft < (3 * avgRecordSize)) {
      return true;
    } else if (bytesLeft > (10 * avgRecordSize)) {
      return false;
    } else {
      // If its more 90% full, then its consider it full.
      return (bytesLeft < (maxFeedSize / 10));
    }
  }

  /**
   * Bumps the count of records stored in this feed.
   */
  public synchronized void incrementRecordCount() {
    recordCount++;
  }

  /**
   * Return the count of records in this feed.
   */
  public synchronized int getRecordCount() {
    return recordCount;
  }

  /**
   * Add the XML record for a given document to the Feed.
   */
  public synchronized void addRecord(Document document,
      InputStream contentStream, String contentEncoding)
      throws RepositoryException, IOException {
    // Build an XML feed record for the document.
    xmlWrapRecord(document, contentStream, contentEncoding);
    recordCount++;
  }

  /*
   * FeedData Interface.
   */

  /**
   * Return the {@link FeedType} for all records in this Feed.
   */
  public FeedType getFeedType() {
    return feedType;
  }

  /**
   * Return the data source for all records in this Feed.
   */
  public String getDataSource() {
    return dataSource;
  }

  /*
   * ByteArrayOutputStream (and related) Interface.
   */

  /**
   * Resets the size of this ByteArrayOutputStream to the
   * specified {@code size}, effectively discarding any
   * data that may have been written passed that point.
   * Like {@code reset()}, this method retains the previously
   * allocated buffer.
   * <p>
   * This method may be used to reduce the size of the data stored,
   * but not to increase it.  In other words, the specified {@code size}
   * cannot be greater than the current size.
   *
   * @param size new data size.
   */
  public synchronized void reset(int size) {
    if (size < 0 || size > count) {
      throw new IllegalArgumentException(
          "New size must not be negative or greater than the current size.");
    }
    count = size;
  }

  /**
   * Reads the complete contents of the supplied InputStream
   * directly into buffer of this ByteArrayOutputStream.
   * This avoids the data copy that would occur if using
   * {@code InputStream.read(byte[], int, int)}, followed by
   * {@code ByteArrayOutputStream.write(byte[], int, int)}.
   *
   * @param in the InputStream from which to read the data.
   * @throws IOException if an I/O error occurs.
   */
  public synchronized void readFrom(InputStream in) throws IOException {
    int bytes = 0;
    do {
      count += bytes;
      if (count >= buf.length) {
        // Need to grow buffer.
        int incr = Math.min(buf.length, 8 * 1024 * 1024);
        byte[] newbuf = new byte[buf.length + incr];
        System.arraycopy(buf, 0, newbuf, 0, buf.length);
        buf = newbuf;
      }
      bytes = in.read(buf, count, buf.length - count);
    } while (bytes != -1);
  }

  @Override
  public synchronized void close() throws IOException {
    if (!isClosed) {
      isClosed = true;
      String suffix = xmlFeedSuffix();
      write(suffix.getBytes(XML_DEFAULT_ENCODING));
    }
  }

  /*
   * Private Methods to XML encode the feed data.
   */

  /**
   * Construct the XML header for a feed file.
   *
   * @param dataSource The dataSource for the feed.
   * @param feedType The type of feed.
   * @return XML feed header string.
   */
  private static String xmlFeedPrefix(String dataSource, FeedType feedType) {
    // Build prefix.
    StringBuffer prefix = new StringBuffer();
    prefix.append(XML_START).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_GSAFEED)).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_HEADER)).append('\n');
    prefix.append(XmlUtils.xmlWrapStart(XML_DATASOURCE));
    prefix.append(dataSource);
    prefix.append(XmlUtils.xmlWrapEnd(XML_DATASOURCE));
    prefix.append(XmlUtils.xmlWrapStart(XML_FEEDTYPE));
    prefix.append(feedType.toLegacyString());
    prefix.append(XmlUtils.xmlWrapEnd(XML_FEEDTYPE));
    prefix.append(XmlUtils.xmlWrapEnd(XML_HEADER));
    prefix.append(XmlUtils.xmlWrapStart(XML_GROUP)).append('\n');
    return prefix.toString();
  }

  /**
   * Construct the XML footer for a feed file.
   *
   * @return XML feed suffix string.
   */
  private static String xmlFeedSuffix() {
    // Build suffix.
    StringBuffer suffix = new StringBuffer();
    suffix.append(XmlUtils.xmlWrapEnd(XML_GROUP));
    suffix.append(XmlUtils.xmlWrapEnd(XML_GSAFEED));
    return suffix.toString();
  }

  /*
   * Generate the record tag for the xml data.
   *
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapRecord(Document document, InputStream contentStream,
      String contentEncoding) throws RepositoryException, IOException {
    if (feedType == FeedType.ACL) {
      xmlWrapAclRecord(document);
    } else {
      xmlWrapDocumentRecord(document, contentStream, contentEncoding);
    }
  }

  /*
   * Generate the record tag for the xml data.
   *
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapDocumentRecord(Document document, InputStream contentStream,
      String contentEncoding) throws RepositoryException, IOException {

    boolean metadataAllowed = true;
    boolean contentAllowed = (feedType == FeedType.CONTENT &&
                              contentStream != null);

    StringBuilder prefix = new StringBuilder();
    prefix.append("<").append(XML_RECORD);

    String searchUrl = getRecordUrl(document);
    XmlUtils.xmlAppendAttr(XML_URL, searchUrl, prefix);

    String displayUrl = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_DISPLAYURL);
    XmlUtils.xmlAppendAttr(XML_DISPLAY_URL, displayUrl, prefix);

    ActionType actionType = null;
    String action = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_ACTION);
    if (action != null) {
      // Compare to legal action types.
      actionType = ActionType.findActionType(action);
      if (actionType == ActionType.ADD) {
        XmlUtils.xmlAppendAttr(XML_ACTION, actionType.toString(), prefix);
      } else if (actionType == ActionType.DELETE) {
        XmlUtils.xmlAppendAttr(XML_ACTION, actionType.toString(), prefix);
        metadataAllowed = false;
        contentAllowed = false;
      } else if (actionType == ActionType.ERROR) {
        LOGGER.log(Level.WARNING, "Illegal tag used for ActionType: " + action);
        actionType = null;
      }
    }

    boolean lock = DocUtils.getOptionalBoolean(document, SpiConstants.PROPNAME_LOCK, false);
    if (lock) {
      XmlUtils.xmlAppendAttr(XML_LOCK, Value.getBooleanValue(true).toString(), prefix);
    }

    // Do not validate the values, just send them in the feed.
    String pagerank =
        DocUtils.getOptionalString(document, SpiConstants.PROPNAME_PAGERANK);
    XmlUtils.xmlAppendAttr(XML_PAGERANK, pagerank, prefix);

    String mimetype =
        DocUtils.getOptionalString(document, SpiConstants.PROPNAME_MIMETYPE);
    if (mimetype == null) {
      mimetype = SpiConstants.DEFAULT_MIMETYPE;
    }
    XmlUtils.xmlAppendAttr(XML_MIMETYPE, mimetype, prefix);

    try {
      String lastModified = DocUtils.getCalendarAndThrow(document,
          SpiConstants.PROPNAME_LASTMODIFIED);
      if (lastModified == null) {
        LOGGER.log(Level.FINEST, "Document does not contain "
            + SpiConstants.PROPNAME_LASTMODIFIED);
      } else {
        XmlUtils.xmlAppendAttr(XML_LAST_MODIFIED, lastModified, prefix);
      }
    } catch (IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Swallowing exception while getting "
          + SpiConstants.PROPNAME_LASTMODIFIED, e);
    }

    try {
      ValueImpl v = (ValueImpl) Value.getSingleValue(document,
          SpiConstants.PROPNAME_ISPUBLIC);
      if (v != null) {
        boolean isPublic = v.toBoolean();
        if (!isPublic) {
          XmlUtils.xmlAppendAttr(XML_AUTHMETHOD, CONNECTOR_AUTHMETHOD, prefix);
        }
      }
    } catch (IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Illegal value for ispublic property."
          + " Treat as a public doc", e);
    }
    prefix.append(">\n");
    if (metadataAllowed) {
      xmlWrapMetadata(prefix, document);
    }

    StringBuilder suffix = new StringBuilder();

    // If including document content, wrap it with <content> tags.
    if (contentAllowed) {
      prefix.append("<");
      prefix.append(XML_CONTENT);
      XmlUtils.xmlAppendAttr(XML_ENCODING, contentEncoding, prefix);
      prefix.append(">\n");

      suffix.append('\n');
      XmlUtils.xmlAppendEndTag(XML_CONTENT, suffix);
    }

    XmlUtils.xmlAppendEndTag(XML_RECORD, suffix);

    write(prefix.toString().getBytes(XML_DEFAULT_ENCODING));
    if (contentAllowed) {
      readFrom(contentStream);
    }
    write(suffix.toString().getBytes(XML_DEFAULT_ENCODING));

    if (feedLogBuilder != null) {
      try {
        feedLogBuilder.append(prefix);
        if (contentAllowed) {
          feedLogBuilder.append("...content...");
        }
        feedLogBuilder.append(suffix);
      } catch (IOException e) {
        // This won't happen with StringBuffer or StringBuilder.
        LOGGER.log(Level.WARNING, "Exception while constructing feed log:", e);
      }
    }
  }

  /**
   * Constructs the record URL for the given doc id, feed type and search URL.
   *
   * @throws RepositoryDocumentException if searchUrl is invalid.
   */
  private String getRecordUrl(Document document)
      throws RepositoryException, RepositoryDocumentException {
    String docId = DocUtils.getRequiredString(document,
        SpiConstants.PROPNAME_DOCID);
    String recordUrl = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_SEARCHURL);
    if (recordUrl != null) {
      validateUrl(recordUrl, "search");
    } else {
      // Fabricate a URL from the docid and feedType.
      recordUrl = constructUrl(docId, feedType);
    }

    return recordUrl;
  }

  /**
   * Constructs the record URL for the inherited ACL document.
   *
   * @return inheritFrom URL, or null if there is none.
   */
  private String getInheritFromUrl(Document document)
      throws RepositoryException, RepositoryDocumentException {
    String recordUrl = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_ACLINHERITFROM);
    if (recordUrl != null) {
      validateUrl(recordUrl, "inherit-from");
    } else {
      String docId = DocUtils.getOptionalString(document,
         SpiConstants.PROPNAME_ACLINHERITFROM_DOCID);
      if (docId != null) {
        // Fabricate a URL from the docid and feedType
        recordUrl = constructUrl(docId, getInheritFromFeedType(document));
      }
    }
    return recordUrl;
  }

  /**
   * Determines the FeedType for the inherited ACL document.
   * The FeedType comes from (in order):
   *   1. SpiConstants.PROPNAME_ACLINHERITFROM_FEEDTYPE
   *   2. SpiConstants.PROPNAME_FEEDTYPE
   *   3. FeedType of this XmlFeed
   *
   * @return the FeedType for the inherited ACL document
   */
  private FeedType getInheritFromFeedType(Document document)
      throws RepositoryException, RepositoryDocumentException {
    String feedType = DocUtils.getOptionalString(document,
        SpiConstants.PROPNAME_ACLINHERITFROM_FEEDTYPE);
    if (feedType == null) {
      feedType = DocUtils.getOptionalString(document,
          SpiConstants.PROPNAME_FEEDTYPE);
    }
    return (feedType == null) ? this.feedType : FeedType.findFeedType(feedType);
  }

  /*
   * Generate the record tag for the ACL xml data.
   */
  private void xmlWrapAclRecord(Document acl)
      throws IOException, RepositoryException {
    StringBuffer aclBuff = new StringBuffer();

    String docId = DocUtils.getRequiredString(acl,
        SpiConstants.PROPNAME_DOCID);
    String searchUrl = DocUtils.getOptionalString(acl,
        SpiConstants.PROPNAME_SEARCHURL);
    String inheritFrom = getInheritFromUrl(acl);
    String inheritanceType = DocUtils.getOptionalString(acl,
        SpiConstants.PROPNAME_ACLINHERITANCETYPE);

    aclBuff.append("<").append(XML_ACL);
    XmlUtils.xmlAppendAttr(XML_URL, getRecordUrl(acl), aclBuff);
    if (!Strings.isNullOrEmpty(inheritanceType)) {
      XmlUtils.xmlAppendAttr(XML_TYPE, inheritanceType, aclBuff);
    }

    if (!Strings.isNullOrEmpty(inheritFrom)) {
      XmlUtils.xmlAppendAttr(XML_INHERIT_FROM, inheritFrom, aclBuff);
    }
    aclBuff.append(">\n");

    // add principal info
    getPrincipalXml(acl, aclBuff);
    XmlUtils.xmlAppendEndTag(XML_ACL, aclBuff);

    write(aclBuff.toString().getBytes(XML_DEFAULT_ENCODING));

    if (feedLogBuilder != null) {
      try {
        feedLogBuilder.append(aclBuff);
      } catch (IOException e) {
        // This won't happen with StringBuffer or StringBuilder.
        LOGGER.log(Level.WARNING, "Exception while constructing feed log:", e);
      }
    }
  }

  /*
   * Generate the ACL principal XML data.
   */
  private void getPrincipalXml(Document acl, StringBuffer buff)
      throws IOException, RepositoryException {
    Property property;

    property = acl.findProperty(SpiConstants.PROPNAME_ACLUSERS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.USER, AclAccess.PERMIT);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLGROUPS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.GROUP, AclAccess.PERMIT);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLDENYUSERS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.USER, AclAccess.DENY);
    }

    property = acl.findProperty(SpiConstants.PROPNAME_ACLDENYGROUPS);
    if (property != null) {
      wrapAclPrincipal(buff, property, AclScope.GROUP, AclAccess.DENY);
    }
  }

  /*
   * Wrap the ACL principal info as XML data.
   */
  private static void wrapAclPrincipal(StringBuffer buff, Property property,
      AclScope scope, AclAccess access)
      throws RepositoryException, IOException {
    ValueImpl value;
    while ((value = (ValueImpl) property.nextValue()) != null) {
      String valString = value.toFeedXml();
      if (!Strings.isNullOrEmpty(valString)) {
        buff.append("<").append(XML_PRINCIPAL);
        XmlUtils.xmlAppendAttr(XML_SCOPE, scope.toString(), buff);
        XmlUtils.xmlAppendAttr(XML_ACCESS, access.toString(), buff);
        buff.append(">");
        XmlUtils.xmlAppendAttrValue(value.toFeedXml(), buff);
        XmlUtils.xmlAppendEndTag(XML_PRINCIPAL, buff);
      }
    }
  }

  /**
   * Wrap the metadata and append it to the string buffer. Empty metadata
   * properties are not appended.
   *
   * @param buf string buffer
   * @param document Document
   * @throws RepositoryException if error reading Property from Document
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private void xmlWrapMetadata(StringBuilder buf, Document document)
      throws RepositoryException, IOException {
    XmlUtils.xmlAppendStartTag(XML_METADATA, buf);
    buf.append("\n");

    // Add all the metadata supplied by the Connector.
    Set<String> propertyNames = document.getPropertyNames();
    if ((propertyNames == null) || propertyNames.isEmpty()) {
      LOGGER.log(Level.WARNING, "Property names set is empty");
    } else {
      for (String name : propertyNames) {
        Property property = null;
        // Possibly manufacture an ACLINHERITFROM property from
        // ACLINHERITFROM_DOCID and ACLINHERITFROM_FEEDTYPE.
        // TODO: All this ACL property munging should really be
        // done in a DocumentFilter.
        if (SpiConstants.PROPNAME_ACLINHERITFROM_DOCID.equals(name) &&
            (DocUtils.getOptionalString(document,
                SpiConstants.PROPNAME_ACLINHERITFROM) == null)) {
          property = new SimpleProperty(
              Value.getStringValue(getInheritFromUrl(document)));
          wrapOneProperty(buf, SpiConstants.PROPNAME_ACLINHERITFROM, property);
          continue;
        }
        if (propertySkipSet.contains(name) ||
            name.startsWith(SpiConstants.USER_ROLES_PROPNAME_PREFIX) ||
            name.startsWith(SpiConstants.GROUP_ROLES_PROPNAME_PREFIX)) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            logOneProperty(document, name);
          }
          continue;
        }
        if (SpiConstants.PROPNAME_ACLGROUPS.equals(name) ||
            SpiConstants.PROPNAME_ACLUSERS.equals(name)) {
          property = DocUtils.processAclProperty(document, name);
        } else {
          property = document.findProperty(name);
        }
        if (property != null) {
          wrapOneProperty(buf, name, property);
        }
      }
    }
    XmlUtils.xmlAppendEndTag(XML_METADATA, buf);
  }

  /**
   * Wrap a single Property and append to string buffer. Does nothing if the
   * Property's value is null or zero-length.
   *
   * @param buf string builder
   * @param name the property's name
   * @param property Property
   * @throws RepositoryException if error reading Property from Document
   * @throws IOException only from Appendable, and that can't really
   *         happen when using StringBuilder.
   */
  private static void wrapOneProperty(StringBuilder buf, String name,
      Property property) throws RepositoryException, IOException {
    ValueImpl value = null;
    while ((value = (ValueImpl) property.nextValue()) != null) {
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.finest("PROPERTY: " + name + " = \"" + value.toString() + "\"");
      }
      String valString = value.toFeedXml();
      if (valString != null && valString.length() > 0) {
        buf.append("<").append(XML_META);
        XmlUtils.xmlAppendAttr(XML_NAME, name, buf);
        XmlUtils.xmlAppendAttr(XML_CONTENT, valString, buf);
        buf.append("/>\n");
      }
    }
  }

  /**
   * Log the given Property's values.  This is called for
   * the small set of document properties that are not simply
   * added to the feed via wrapOneProperty().
   */
  private void logOneProperty(Document document, String name)
      throws RepositoryException, IOException {
    if (SpiConstants.PROPNAME_CONTENT.equals(name)) {
      LOGGER.finest("PROPERTY: " + name + " = \"...content...\"");
    } else {
      Property property = document.findProperty(name);
      if (property != null) {
        ValueImpl value = null;
        while ((value = (ValueImpl) property.nextValue()) != null) {
          LOGGER.finest("PROPERTY: " + name + " = \"" + value.toString()
                        + "\"");
        }
      }
    }
  }

  /**
   * Form either a Google connector URL or a Content URL, based on
   * feed type.
   */
  private String constructUrl(String docid, FeedType feedType) {
    if (feedType == FeedType.CONTENTURL) {
      return constructContentUrl(docid);
    } else {  // FeedType.CONTENT or FeedType.ACL.
      return constructGoogleConnectorUrl(docid);
    }
  }

  /**
   * Form a Google connector URL.
   *
   * @param docid
   * @return the connector url
   */
  private String constructGoogleConnectorUrl(String docid) {
    StringBuilder buf = new StringBuilder(ServletUtil.PROTOCOL);
    buf.append(dataSource);
    buf.append(".localhost").append(ServletUtil.DOCID);
    buf.append(docid);
    return buf.toString();
  }

  /**
   * Form a Content URL.
   *
   * @param docid
   * @return the contentUrl
   */
  private String constructContentUrl(String docid) {
    Preconditions.checkState(!Strings.isNullOrEmpty(contentUrlPrefix),
                             "contentUrlPrefix must not be null or empty");
    StringBuilder buf = new StringBuilder(contentUrlPrefix);
    ServletUtil.appendQueryParam(buf, ServletUtil.XMLTAG_CONNECTOR_NAME,
                                 dataSource);
    ServletUtil.appendQueryParam(buf, ServletUtil.QUERY_PARAM_DOCID, docid);
    return buf.toString();
  }

  /**
   * Verify that a supplied URL is at least syntactically valid.
   *
   * @param url the URL to validate
   * @param description description of the URL
   * @throws RepositoryDocumentException if the URL is invalid
   */
  private static void validateUrl(String url, String description)
      throws RepositoryDocumentException {
    // Check that this looks like a URL.
    try {
      // The GSA supports SMB URLs, but Java does not.
      if (url != null && url.startsWith("smb:")) {
        new URL(null, url, SmbURLStreamHandler.getInstance());
      } else {
        new URL(url);
      }
    } catch (MalformedURLException e) {
      throw new RepositoryDocumentException(
          "Supplied " + description + " URL " + url + " is malformed.", e);
    }
  }
}
