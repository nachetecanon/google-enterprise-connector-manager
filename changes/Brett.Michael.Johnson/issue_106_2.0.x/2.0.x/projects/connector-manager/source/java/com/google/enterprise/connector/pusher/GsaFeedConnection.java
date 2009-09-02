// Copyright 2006-2008 Google Inc.  All Rights Reserved.
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

package com.google.enterprise.connector.pusher;

import com.google.enterprise.connector.servlet.ServletUtil;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens a connection to a url and sends data to it.
 */
public class GsaFeedConnection implements FeedConnection {

  /**
   * The GSA's response when it successfully receives a feed.
   */
  public static final String SUCCESS_RESPONSE = "Success";

  /**
   * The GSA's response when the client is not authorized to send feeds.
   */
  public static final String UNAUTHORIZED_RESPONSE =
      "Error - Unauthorized Request";

  /**
   * The GSA's response when there was an internal error.
   */
  public static final String INTERNAL_ERROR_RESPONSE = "Internal Error";

  // multipart/form-data uploads require a boundary to delimit controls.
  // Since we XML-escape or base64-encode all data provided by the connector,
  // the feed XML will never contain "<<".
  private static final String BOUNDARY = "<<";

  private static final String CRLF = "\r\n";

  private URL url = null;

  private static final Logger LOGGER =
      Logger.getLogger(GsaFeedConnection.class.getName());

  public GsaFeedConnection(String host, int port) throws MalformedURLException {
    this.setFeedHostAndPort(host, port);
  }

  public synchronized void setFeedHostAndPort(String host, int port)
      throws MalformedURLException {
    url = new URL("http", host, port, "/xmlfeed");
  }

  private static final void controlHeader(StringBuilder builder,
                                          String name, String mimetype) {
    builder.append("--").append(BOUNDARY).append(CRLF);
    builder.append("Content-Disposition: form-data;");
    builder.append(" name=\"").append(name).append("\"").append(CRLF);
    builder.append("Content-Type: ").append(mimetype).append(CRLF);
    builder.append(CRLF);
  }

  public String sendData(String dataSource, FeedData feedData)
      throws FeedException {
    String feedType = ((GsaFeedData) feedData).getFeedType();
    ByteArrayOutputStream data = ((GsaFeedData) feedData).getData();
    OutputStream outputStream;
    HttpURLConnection uc;
    StringBuilder buf = new StringBuilder();
    byte[] prefix;
    byte[] suffix;
    try {
      // Build prefix.
      controlHeader(buf, "datasource", ServletUtil.MIMETYPE_TEXT_PLAIN);
      buf.append(dataSource).append(CRLF);
      controlHeader(buf, "feedtype", ServletUtil.MIMETYPE_TEXT_PLAIN);
      buf.append(feedType).append(CRLF);
      controlHeader(buf, "data", ServletUtil.MIMETYPE_XML);
      prefix = buf.toString().getBytes("UTF-8");

      // Build suffix.
      buf.setLength(0);
      buf.append(CRLF).append("--").append(BOUNDARY).append("--").append(CRLF);
      suffix = buf.toString().getBytes("UTF-8");

      LOGGER.finest("Opening feed connection.");
      synchronized (this) {
        uc = (HttpURLConnection)url.openConnection();
      }
      uc.setDoInput(true);
      uc.setDoOutput(true);
      uc.setFixedLengthStreamingMode(prefix.length + data.size()
          + suffix.length);
      uc.setRequestProperty("Content-Type", "multipart/form-data; boundary="
          + BOUNDARY);
      outputStream = uc.getOutputStream();
    } catch (IOException ioe) {
      throw new FeedException(ioe);
    }

    boolean isThrowing = false;
    buf.setLength(0);
    try {
      LOGGER.finest("Writing feed data to feed connection.");
      // If there is an exception during this read/write, we do our
      // best to close the url connection and read the result.
      try {
        outputStream.write(prefix);
        data.writeTo(outputStream);
        outputStream.write(suffix);
        outputStream.flush();
      } catch (IOException e) {
        LOGGER.log(Level.SEVERE,
            "IOException while posting: will retry later", e);
        isThrowing = true;
        throw new FeedException(e);
      } catch (RuntimeException e) {
        isThrowing = true;
        throw e;
      } catch (Error e) {
        isThrowing = true;
        throw e;
      } finally {
        try {
          outputStream.close();
        } catch (IOException e) {
          LOGGER.log(Level.SEVERE,
              "IOException while closing after post: will retry later", e);
          if (!isThrowing) {
            isThrowing = true;
            throw new FeedException(e);
          }
        }
      }
    } finally {
      BufferedReader br = null;
      try {
        LOGGER.finest("Waiting for response from feed connection.");
        InputStream inputStream = uc.getInputStream();
        br = new BufferedReader(new InputStreamReader(inputStream, "UTF8"));
        String line;
        while ((line = br.readLine()) != null) {
          buf.append(line);
        }
      } catch (IOException ioe) {
        if (!isThrowing) {
          throw new FeedException(ioe);
        }
      } finally {
        try {
          if (br != null) {
            br.close();
          }
        } catch (IOException e) {
          LOGGER.log(Level.SEVERE,
                     "IOException while closing after post: continuing", e);
        }
        if (LOGGER.isLoggable(Level.FINEST)) {
          LOGGER.finest("Received response from feed connection: "
                        + buf.toString());
        }
      }
    }
    return buf.toString();
  }
}
