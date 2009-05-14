// Copyright 2008 Google Inc.
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

package com.google.enterprise.connector.saml.server;

import com.google.enterprise.connector.common.PostableHttpServlet;
import com.google.enterprise.connector.common.ServletBase;
import com.google.enterprise.connector.saml.common.SamlLogUtil;

import org.opensaml.common.binding.SAMLMessageContext;
import org.opensaml.common.binding.artifact.SAMLArtifactMap;
import org.opensaml.common.binding.artifact.SAMLArtifactMap.SAMLArtifactMapEntry;
import org.opensaml.saml2.binding.decoding.HTTPSOAP11Decoder;
import org.opensaml.saml2.binding.encoding.HTTPSOAP11Encoder;
import org.opensaml.saml2.core.ArtifactResolve;
import org.opensaml.saml2.core.ArtifactResponse;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.saml2.metadata.ArtifactResolutionService;
import org.opensaml.saml2.metadata.Endpoint;
import org.opensaml.saml2.metadata.EntityDescriptor;
import org.opensaml.ws.transport.http.HttpServletRequestAdapter;
import org.opensaml.ws.transport.http.HttpServletResponseAdapter;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.google.enterprise.connector.saml.common.OpenSamlUtil.initializeLocalEntity;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.initializePeerEntity;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.makeArtifactResponse;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.makeIssuer;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.makeSamlMessageContext;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.makeStatus;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.runDecoder;
import static com.google.enterprise.connector.saml.common.OpenSamlUtil.runEncoder;

import static org.opensaml.common.xml.SAMLConstants.SAML20P_NS;
import static org.opensaml.common.xml.SAMLConstants.SAML2_SOAP11_BINDING_URI;

/**
 * Servlet to handle SAML artifact-resolution requests.  This is one part of the security manager's
 * identity provider, and will handle only those artifacts generated by the identity provider.
 */
public class SamlArtifactResolve extends ServletBase implements PostableHttpServlet {

  /** Required for serializable classes. */
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(SamlArtifactResolve.class.getName());

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    BackEnd backend = getBackEnd();

    // Establish the SAML message context
    SAMLMessageContext<ArtifactResolve, ArtifactResponse, NameID> context =
        makeSamlMessageContext();
    EntityDescriptor localEntity = getSmEntity();
    initializeLocalEntity(context, localEntity, localEntity.getIDPSSODescriptor(SAML20P_NS),
                          ArtifactResolutionService.DEFAULT_ELEMENT_NAME);

    // Decode the request
    context.setInboundMessageTransport(new HttpServletRequestAdapter(req));
    runDecoder(new HTTPSOAP11Decoder(), context);
    ArtifactResolve artifactResolve = context.getInboundSAMLMessage();

    // Select entity for response
    EntityDescriptor peerEntity = getEntity(context.getInboundMessageIssuer());
    initializePeerEntity(context, peerEntity, peerEntity.getSPSSODescriptor(SAML20P_NS),
                         Endpoint.DEFAULT_ELEMENT_NAME,
                         SAML2_SOAP11_BINDING_URI);

    // Create response
    ArtifactResponse artifactResponse =
        makeArtifactResponse(artifactResolve, makeStatus(StatusCode.SUCCESS_URI));
    artifactResponse.setIssuer(makeIssuer(localEntity.getEntityID()));

    // Look up artifact and add any resulting object to response
    String encodedArtifact = artifactResolve.getArtifact().getArtifact();
    SAMLArtifactMap artifactMap = backend.getArtifactMap();
    if (artifactMap.contains(encodedArtifact)) {
      LOGGER.info("local Entity ID: " + localEntity.getEntityID());
      LOGGER.info("peer entity ID: " + peerEntity.getEntityID());
      SAMLArtifactMapEntry entry = artifactMap.get(encodedArtifact);
      LOGGER.info("artifact issuer: " + entry.getIssuerId());
      LOGGER.info("artifact relying party: " + entry.getRelyingPartyId());
      if ((!entry.isExpired())
          && localEntity.getEntityID().equals(entry.getIssuerId())
          && peerEntity.getEntityID().equals(entry.getRelyingPartyId())) {
        artifactResponse.setMessage(entry.getSamlMessage());
        LOGGER.info("Artifact resolved");
      }
      // Always remove the artifact after use
      artifactMap.remove(encodedArtifact);
    }

    // Encode response
    context.setOutboundSAMLMessage(artifactResponse);
    String message = "Artifact Response as XML:";
    // todo: change this level to FINEST once we're comfortable
    // with adjusting log levels in tomcat
    SamlLogUtil.logXml(LOGGER, Level.INFO, message, artifactResponse);
    initResponse(resp);
    context.setOutboundMessageTransport(new HttpServletResponseAdapter(resp, true));
    runEncoder(new HTTPSOAP11Encoder(), context);
  }
}
