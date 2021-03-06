/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.custom.extractdeviceid;

import com.google.common.base.Splitter;
import org.apache.http.Header;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.openrepose.commons.config.manager.UpdateListener;
import org.openrepose.commons.utils.servlet.http.HttpServletRequestWrapper;
import org.openrepose.commons.utils.http.ServiceClientResponse;
import org.openrepose.core.filter.FilterConfigHelper;
import org.openrepose.core.services.config.ConfigurationService;
import org.openrepose.core.services.datastore.Datastore;
import org.openrepose.core.services.datastore.DatastoreService;
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient;
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClientException;
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClientFactory;
import org.openrepose.filters.custom.extractdeviceid.config.DelegatingType;
import org.openrepose.filters.custom.extractdeviceid.config.ExtractDeviceIdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static javax.servlet.http.HttpServletResponse.*;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.RETRY_AFTER;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.openrepose.commons.utils.http.normal.ExtendedStatusCodes.SC_TOO_MANY_REQUESTS;

@Named
public class ExtractDeviceIdFilter implements Filter, UpdateListener<ExtractDeviceIdConfig> {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractDeviceIdFilter.class);
    private static final String DEFAULT_CONFIG = "extract-device-id.cfg.xml";
    private static final String X_AUTH_TOKEN = "X-Auth-Token";
    private static final String X_TENANT_ID = "X-Tenant-Id";
    private static final String X_ROLES = "X-Roles";
    private static final String X_DEVICE_ID = "X-Device-Id";
    private static final String X_DELEGATED = "X-Delegated";
    private static final String INTERMEDIARY_ROLE = "monitoring:intermediary";
    private static final String UNREGISTERED_PRODUCT_ROLE = "unregistered_product";
    private static final String DEVICE_ID_KEY_PREFIX = "MaaS:Custom:DeviceId:";
    private final ConfigurationService configurationService;
    private final AkkaServiceClientFactory akkaServiceClientFactory;
    private final Datastore datastore;
    private String configurationFile = DEFAULT_CONFIG;
    private Optional<Double> delegatingQuality;
    private String maasServiceUri;
    private int cacheTimeoutMillis;
    private AkkaServiceClient akkaServiceClient = null;
    private boolean initialized = false;

    @Inject
    public ExtractDeviceIdFilter(ConfigurationService configurationService,
                                 AkkaServiceClientFactory akkaServiceClientFactory,
                                 DatastoreService datastoreService) {
        this.configurationService = configurationService;
        this.akkaServiceClientFactory = akkaServiceClientFactory;
        this.datastore = datastoreService.getDefaultDatastore();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        configurationFile = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG);
        LOG.info("Initializing filter using config {}", configurationFile);
        // Must match the .xsd file created in step 18.
        URL xsdURL = getClass().getResource("/META-INF/schema/config/extract-device-id.xsd");
        configurationService.subscribeTo(
                filterConfig.getFilterName(),
                configurationFile,
                xsdURL,
                this,
                ExtractDeviceIdConfig.class
        );
    }

    @Override
    public void destroy() {
        configurationService.unsubscribeFrom(configurationFile, this);
        Optional.ofNullable(akkaServiceClient).ifPresent(AkkaServiceClient::destroy);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        if (!initialized) {
            LOG.error("Extract Device ID filter has not yet initialized...");
            ((HttpServletResponse) servletResponse).sendError(SC_INTERNAL_SERVER_ERROR); // (500)
        } else {
            HttpServletRequestWrapper wrappedHttpRequest = new HttpServletRequestWrapper((HttpServletRequest) servletRequest);
            HttpServletResponse httpServletResponse = ((HttpServletResponse) servletResponse);

            // This is where this filter's custom logic is invoked.
            LOG.trace("Extract Device ID filter processing request...");
            if (handleRequest(wrappedHttpRequest, httpServletResponse)) {

                LOG.trace("Extract Device ID filter passing on down the Filter Chain...");
                filterChain.doFilter(wrappedHttpRequest, httpServletResponse);

                LOG.trace("Extract Device ID filter processing response...");
                handleResponse(wrappedHttpRequest, httpServletResponse);
            }
        }
        LOG.trace("Extract Device ID filter returning response...");
    }

    @Override
    public void configurationUpdated(ExtractDeviceIdConfig configurationObject) {
        delegatingQuality = Optional.ofNullable(configurationObject.getDelegating()).map(DelegatingType::getQuality);
        maasServiceUri = configurationObject.getMaasServiceUri();
        cacheTimeoutMillis = configurationObject.getCacheTimeoutMillis();
        final AkkaServiceClient akkaServiceClientOld = akkaServiceClient;
        akkaServiceClient = akkaServiceClientFactory.newAkkaServiceClient();
        Optional.ofNullable(akkaServiceClientOld).ifPresent(AkkaServiceClient::destroy);
        initialized = true;
        LOG.trace("Extract Device ID filter was initialized.");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    private boolean handleRequest(HttpServletRequestWrapper httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {
        boolean rtn = true;
        final String entityId = ExtractDeviceIdFilter.extractPrefixedElement(httpServletRequest.getRequestURI(), "entities");
        if (entityId != null) {
            // This filter requires an X-Auth-Token header.
            final String authToken = httpServletRequest.getHeader(X_AUTH_TOKEN);
            if (authToken != null) {
                String deviceId = (String) datastore.get(DEVICE_ID_KEY_PREFIX + entityId);
                // IF the Device ID is cached, THEN put it in the header;
                // ELSE try to get it.
                if (deviceId != null) {
                    httpServletRequest.addHeader(X_DEVICE_ID, deviceId);
                } else {
                    rtn = getDeviceId(
                            httpServletRequest,
                            httpServletResponse,
                            authToken,
                            entityId
                    );
                }
            } else {
                // No X-Auth-Token header
                rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_UNAUTHORIZED, "Not Authenticated."); // (401)
            }
        } else {
            // No Entity ID in the URI
            rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_BAD_REQUEST, "No Entity ID in the URI."); // (400)
        }
        return rtn;
    }

    static String extractPrefixedElement(String uri, String prefix) {
        String rtn = null;
        if (uri != null) {
            List<String> list = Splitter.on('/').omitEmptyStrings().splitToList(uri);
            final int idx = list.indexOf(prefix);
            if (idx >= 0 && idx + 1 < list.size()) {
                rtn = list.get(idx + 1);
            }
        }
        return rtn;
    }

    static String getRetryString(Header[] headers, int statusCode) {
        String rtn;
        Object[] retryHeaders = Arrays.stream(headers).filter(header -> header.getName().equals(RETRY_AFTER)).toArray();
        if (retryHeaders.length < 1) {
            LOG.info("Missing {} header on Auth Response status code: {}", RETRY_AFTER, statusCode);
            final ZonedDateTime zdt = ZonedDateTime.now(Clock.systemUTC());
            zdt.plusSeconds(5);
            rtn = zdt.format(RFC_1123_DATE_TIME);
        } else {
            rtn = ((Header) retryHeaders[0]).getValue();
        }
        return rtn;
    }

    private boolean getDeviceId(
            final HttpServletRequestWrapper httpServletRequest,
            final HttpServletResponse httpServletResponse,
            final String authToken,
            final String entityId
    ) throws IOException {
        boolean rtn = true;
        final Map<String, String> headers = new HashMap<>();
        headers.put(ACCEPT, APPLICATION_JSON);
        headers.put(X_AUTH_TOKEN, authToken);
        headers.put(X_ROLES, INTERMEDIARY_ROLE);
        final String tenantId = httpServletRequest.getHeader(X_TENANT_ID);
        // The X-Tenant-Id header from the original request is used if present;
        // otherwise it is expected to have been in the original request URI.
        if (tenantId != null) {
            headers.put(X_TENANT_ID, tenantId);
        }
        try {
            final ServiceClientResponse serviceClientResponse = akkaServiceClient.get(
                    authToken + "_" + entityId,
                    maasServiceUri + extractMaasPath(httpServletRequest.getRequestURI()),
                    headers
            );
            switch (serviceClientResponse.getStatus()) {
                case SC_OK: // (200)
                    try (InputStream is = serviceClientResponse.getData();
                         Reader reader = new InputStreamReader(is, UTF_8)) {
                        JSONObject jsonObject = (JSONObject) new JSONParser().parse(reader);
                        String entityUri = (String) jsonObject.get("uri");
                        if (entityUri != null && entityUri.length() > 0) {
                            String deviceId = ExtractDeviceIdFilter.extractPrefixedElement(entityUri, "devices");
                            // IF we have a Device ID, THEN cache it and put it in the header.
                            if (deviceId != null) {
                                // Caching is configurable and turned off by default.
                                if (cacheTimeoutMillis > 0) {
                                    datastore.put(
                                            DEVICE_ID_KEY_PREFIX + entityId,
                                            deviceId,
                                            cacheTimeoutMillis,
                                            TimeUnit.MILLISECONDS
                                    );
                                }
                                httpServletRequest.addHeader(X_DEVICE_ID, deviceId);
                            } else {
                                // The monitoring public API server has returned an entity with an unrecogized URI format;
                                // the role assigned here will be treated as pre-authorized (bypassing privilege checks) by the downstream Valkyrie filter
                                LOG.debug("Unrecognized URI received from monitoring service; inserting unregistered_product role.");
                                httpServletRequest.addHeader(X_ROLES, UNREGISTERED_PRODUCT_ROLE);
                            }
                        } else {
                            // The monitoring public API server has returned an entity with no URI;
                            // the role assigned here will be treated as pre-authorized (bypassing privilege checks) by the downstream Valkyrie filter
                            LOG.debug("Empty URI received from monitoring service; inserting unregistered_product role.");
                            httpServletRequest.addHeader(X_ROLES, UNREGISTERED_PRODUCT_ROLE);
                        }
                    } catch (IOException e) {
                        LOG.debug("Failed to open the Entity Resource response stream.", e);
                        rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_INTERNAL_SERVER_ERROR, "Unknown Error"); // (500)
                    } catch (ParseException e) {
                        LOG.debug("Failed to parse the Entity Resource response stream.", e);
                        rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_INTERNAL_SERVER_ERROR, "Unknown Error"); // (500)
                    }
                    break;
                case SC_NOT_FOUND:   // (404)
                    // The monitoring public API server has indicated that the entity does not exist
                    // the role assigned here will be treated as pre-authorized (bypassing privilege checks) by the downstream Valkyrie filter,
                    // allowing the public API server to respond with a 404
                    LOG.debug("Entity not found received from monitoring service; inserting unregistered_product role.");
                    httpServletRequest.addHeader(X_ROLES, UNREGISTERED_PRODUCT_ROLE);
                    break;
                case SC_REQUEST_ENTITY_TOO_LARGE:   // (413)
                    final String retryTooLargeString = ExtractDeviceIdFilter.getRetryString(serviceClientResponse.getHeaders(), SC_SERVICE_UNAVAILABLE);  // (503)
                    rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_SERVICE_UNAVAILABLE, retryTooLargeString); // (503)
                    if (!rtn) {
                        httpServletResponse.addHeader(RETRY_AFTER, retryTooLargeString);
                    }
                    break;
                case SC_TOO_MANY_REQUESTS:          // (429)
                    final String retryTooManyString = ExtractDeviceIdFilter.getRetryString(serviceClientResponse.getHeaders(), SC_TOO_MANY_REQUESTS);  // (429)
                    rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_TOO_MANY_REQUESTS, retryTooManyString); // (429)
                    if (!rtn) {
                        httpServletResponse.addHeader(RETRY_AFTER, retryTooManyString);
                    }
                    break;
                default:
                    rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_INTERNAL_SERVER_ERROR, "Unknown Error"); // (500)
            }
        } catch (URISyntaxException e) {
            LOG.debug("Inbound request URI was malformed.", e);
            rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_INTERNAL_SERVER_ERROR, "Unknown Error"); // (500)
        } catch (AkkaServiceClientException e) {
            LOG.debug("Failed to obtain the Entity Resource response.", e);
            rtn = addDelegatedHeaderOrSendError(httpServletRequest, httpServletResponse, SC_INTERNAL_SERVER_ERROR, "Unknown Error"); // (500)
        }
        return rtn;
    }

    private static final String ENTITIES = "entities";
    private static final int ENTITIES_LEN_MINUS_ONE = ENTITIES.length() - 1;
    private static final int ENTITIES_LEN_PLUS_TWO = ENTITIES.length() + 2;
    private static final String URI_SYNTAX_EXCEPTION_MESSAGE = "Malformed MaaS address.";

    static String extractMaasPath(String origUri) throws URISyntaxException {
        if (origUri == null) {
            throw new URISyntaxException("" + null, URI_SYNTAX_EXCEPTION_MESSAGE, 0);
        }
        String path = new URI(origUri).getPath();
        int idxEntities = path.indexOf(ENTITIES);
        if (idxEntities < 0) {
            throw new URISyntaxException(origUri, URI_SYNTAX_EXCEPTION_MESSAGE, 0);
        } else if (path.length() < idxEntities + ENTITIES_LEN_PLUS_TWO) {
            idxEntities = origUri.indexOf(ENTITIES);
            throw new URISyntaxException(origUri, URI_SYNTAX_EXCEPTION_MESSAGE, idxEntities + ENTITIES_LEN_MINUS_ONE);
        }
        int idxEntityId = path.indexOf('/', idxEntities + ENTITIES_LEN_PLUS_TWO);
        if (idxEntityId < 0) {
            return path;
        } else {
            return path.substring(0, idxEntityId);
        }
    }

    private void handleResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {
    }

    private boolean addDelegatedHeaderOrSendError(
            HttpServletRequestWrapper httpServletRequest,
            HttpServletResponse httpServletResponse,
            int statusCode,
            String message
    ) throws IOException {
        boolean rtn = delegatingQuality.isPresent();
        if (rtn) {
            httpServletRequest.addHeader(
                    X_DELEGATED,
                    "status_code=" + statusCode +
                            "`component=" + "Extract Device ID" +
                            "`message=" + message +
                            ";q=" + delegatingQuality.get()
            );
        } else {
            httpServletResponse.sendError(statusCode, message);
        }
        return rtn;
    }
}
