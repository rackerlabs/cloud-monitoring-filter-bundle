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
package org.openrepose.filters.custom.extractdeviceid

import org.apache.http.Header
import org.apache.http.message.BasicHeader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.test.appender.ListAppender
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.mockito.ArgumentCaptor
import org.openrepose.commons.config.manager.UpdateListener
import org.openrepose.commons.config.resource.ConfigurationResource
import org.openrepose.commons.config.resource.ConfigurationResourceResolver
import org.openrepose.commons.utils.http.ServiceClientResponse
import org.openrepose.commons.utils.servlet.http.HttpServletResponseWrapper
import org.openrepose.commons.utils.servlet.http.ResponseMode
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.datastore.Datastore
import org.openrepose.core.services.datastore.DatastoreService
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClientException
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClientFactory
import org.openrepose.filters.custom.extractdeviceid.config.DelegatingType
import org.openrepose.filters.custom.extractdeviceid.config.ExtractDeviceIdConfig
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockFilterConfig
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Unroll

import javax.servlet.http.HttpServletRequest
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import static javax.servlet.http.HttpServletResponse.*
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE
import static javax.ws.rs.core.HttpHeaders.RETRY_AFTER
import static javax.ws.rs.core.MediaType.APPLICATION_JSON
import static org.junit.Assert.*
import static org.mockito.Matchers.*
import static org.mockito.Mockito.*
import static org.openrepose.commons.utils.http.normal.ExtendedStatusCodes.SC_TOO_MANY_REQUESTS

public class ExtractDeviceIdFilterTest extends Specification {
    def LOG = LoggerFactory.getLogger(this.class)
    def COMPONENT = "Extract Device ID"
    def ORIG_ENDPOINT = "http://www.example.com"
    def ExtractDeviceIdFilter filter
    def ExtractDeviceIdConfig config
    def DelegatingType delegatingType
    def MockHttpServletRequest httpServletRequest
    def MockHttpServletResponse httpServletResponse
    def MockFilterChain filterChain
    def Datastore mockDatastore
    def DatastoreService mockDatastoreService
    def AkkaServiceClient mockAkkaServiceClient
    def AkkaServiceClientFactory mockAkkaServiceClientFactory
    def ConfigurationService mockConfigService
    def ListAppender listAppender

    def setup() {
        httpServletRequest = new MockHttpServletRequest()
        httpServletRequest.method = "GET"
        httpServletResponse = new MockHttpServletResponse()
        filterChain = new MockFilterChain()
        mockConfigService = mock(ConfigurationService.class)
        mockDatastore = mock(Datastore.class)
        mockDatastoreService = mock(DatastoreService.class)
        when(mockDatastoreService.defaultDatastore).thenReturn mockDatastore
        mockAkkaServiceClient = mock(AkkaServiceClient.class)
        mockAkkaServiceClientFactory = mock(AkkaServiceClientFactory.class)
        when(mockAkkaServiceClientFactory.newAkkaServiceClient()).thenReturn mockAkkaServiceClient
        filter = new ExtractDeviceIdFilter(mockConfigService, mockAkkaServiceClientFactory, mockDatastoreService)
        config = new ExtractDeviceIdConfig()
        delegatingType = new DelegatingType()
        delegatingType.quality = 0.9
        config.maasServiceUri = "http://www.maas.com"
        LoggerContext ctx = LogManager.getContext(false) as LoggerContext
        listAppender = ((ctx.configuration.getAppender("List0")) as ListAppender).clear()
    }

    def cleanup() {
        if (filter.initialized) filter.destroy()
    }

    def 'The cache is disabled by default'() {
        given:
        def extractDeviceIdConfig = new ExtractDeviceIdConfig()
        LOG.debug extractDeviceIdConfig.toString()

        expect:
        assertEquals 0, extractDeviceIdConfig.cacheTimeoutMillis
    }

    def 'The default delegating quality is 0.5'() {
        given:
        def delegatingType = new DelegatingType()
        LOG.debug delegatingType.toString()

        expect:
        assertEquals 0.5, delegatingType.quality, 0.0
    }

    def 'an un-configured filter should return an Internal Server Error (500)'() {
        when:
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        assertEquals SC_INTERNAL_SERVER_ERROR, httpServletResponse.status // (500)
        listAppender.events.find {
            it.message.formattedMessage.contains "not yet initialized"
        }
    }

    def 'an initialized filter should register to listen to the configuration file'() {
        given:
        def filterNameCaptor = ArgumentCaptor.forClass(String.class)
        def configurationNameCaptor = ArgumentCaptor.forClass(String.class)
        def xsdStreamSourceCaptor = ArgumentCaptor.forClass(URL.class)
        def listenerCaptor = ArgumentCaptor.forClass(UpdateListener.class)
        def configurationClassCaptor = ArgumentCaptor.forClass(Class.class)
        def mockFilterConfig = new MockFilterConfig("extract-device-id")
        def mockResourceResolver = mock(ConfigurationResourceResolver.class)

        def configurationResource = new ConfigurationResource() {
            @Override
            boolean updated() throws IOException {
                return true
            }

            @Override
            boolean exists() throws IOException {
                return true
            }

            @Override
            String name() {
                return "ExtractDeviceIdConfig"
            }

            @Override
            InputStream newInputStream() throws IOException {
                //Return the string of XML that we built earlier
                return new ByteArrayInputStream(
                        """
                        |<extract-device-id xmlns='http://docs.openrepose.org/repose/extract-device-id/v1.0'
                        |   maas-service-uri="http://www.maas.com"
                        |   cache-timeout-millis="0">
                        |   <delegating quality="0.2"/>
                        |</extract-device-id>
                        """.stripMargin().stripIndent().bytes
                )
            }
        }

        when(mockResourceResolver.resolve("extract-device-id.cfg.xml")).thenReturn configurationResource
        when(mockConfigService.getResourceResolver()).thenReturn mockResourceResolver

        when:
        filter.init(mockFilterConfig)

        then:
        verify(mockConfigService, times(1)).subscribeTo(
                filterNameCaptor.capture(),
                configurationNameCaptor.capture(),
                xsdStreamSourceCaptor.capture(),
                listenerCaptor.capture(),
                configurationClassCaptor.capture()
        )
        assertEquals "extract-device-id", filterNameCaptor.value
        assertEquals "extract-device-id.cfg.xml", configurationNameCaptor.value
        assertTrue xsdStreamSourceCaptor.value.path.endsWith("extract-device-id.xsd")
        ////////////////////////////////////////////////////////////////////////////////
        // @TODO: Why doesn't this work?
        //assertTrue listenerCaptor.value instanceof ExtractDeviceIdConfig
        ////////////////////////////////////////////////////////////////////////////////
        assertEquals ExtractDeviceIdConfig.class, configurationClassCaptor.value
        listAppender.events.find {
            it.message.formattedMessage.contains "Initializing filter"
        }
    }

    @Unroll
    def 'extracts from #uri URI the #extracting #extracted'() {
        assertEquals extracted, ExtractDeviceIdFilter.extractPrefixedElement(uri, extracting)

        where:
        uri                                                    | extracting | extracted
        "maas.com/entities/someId"                             | "entities" | "someId"
        "maas.com/tenantId/entities/someId"                    | "entities" | "someId"
        "maas.com/entities/someId/checks"                      | "entities" | "someId"
        "maas.com/tenantId/entities/someId/checks"             | "entities" | "someId"
        "maas.com/entities/someId/checks/someotherid"          | "entities" | "someId"
        "maas.com/tenantId/entities/someId/checks/someotherid" | "entities" | "someId"
        "maas.com/entities/someId/alarms"                      | "entities" | "someId"
        "maas.com/tenantId/entities/someId/alarms"             | "entities" | "someId"
        "maas.com/entities/someId/alarms/someotherid"          | "entities" | "someId"
        "maas.com/tenantId/entities/someId/alarms/someotherid" | "entities" | "someId"
        "maas.com/someId"                                      | "entities" | null
        "maas.com/tenantId/someId"                             | "entities" | null
        "maas.com/someId/checks"                               | "entities" | null
        "maas.com/tenantId/someId/checks"                      | "entities" | null
        "maas.com/someId/checks/someotherid"                   | "entities" | null
        "maas.com/tenantId/someId/checks/someotherid"          | "entities" | null
        "maas.com/someId/alarms"                               | "entities" | null
        "maas.com/tenantId/someId/alarms"                      | "entities" | null
        "maas.com/someId/alarms/someotherid"                   | "entities" | null
        "maas.com/tenantId/someId/alarms/someotherid"          | "entities" | null
        "maas.com/devices/someId"                              | "devices"  | "someId"
        "maas.com/tenantId/devices/someId"                     | "devices"  | "someId"
        "maas.com/devices/someId/checks"                       | "devices"  | "someId"
        "maas.com/tenantId/devices/someId/checks"              | "devices"  | "someId"
        "maas.com/devices/someId/checks/someotherid"           | "devices"  | "someId"
        "maas.com/tenantId/devices/someId/checks/someotherid"  | "devices"  | "someId"
        "maas.com/devices/someId/alarms"                       | "devices"  | "someId"
        "maas.com/tenantId/devices/someId/alarms"              | "devices"  | "someId"
        "maas.com/devices/someId/alarms/someotherid"           | "devices"  | "someId"
        "maas.com/tenantId/devices/someId/alarms/someotherid"  | "devices"  | "someId"
        "maas.com/someId"                                      | "devices"  | null
        "maas.com/tenantId/someId"                             | "devices"  | null
        "maas.com/someId/checks"                               | "devices"  | null
        "maas.com/tenantId/someId/checks"                      | "devices"  | null
        "maas.com/someId/checks/someotherid"                   | "devices"  | null
        "maas.com/tenantId/someId/checks/someotherid"          | "devices"  | null
        "maas.com/someId/alarms"                               | "devices"  | null
        "maas.com/tenantId/someId/alarms"                      | "devices"  | null
        "maas.com/someId/alarms/someotherid"                   | "devices"  | null
        "maas.com/tenantId/someId/alarms/someotherid"          | "devices"  | null
        ""                                                     | "entities" | null
        "/"                                                    | "entities" | null
        "maas.com/entities/"                                   | "entities" | null
        "maas.com/entities"                                    | "entities" | null
        "maas.com/tenantId/entities/"                          | "entities" | null
        "maas.com/tenantId/entities"                           | "entities" | null
        null                                                   | "entities" | null
    }

    @Unroll
    def 'extracts from #uri the MaaS path #extracted'() {
        assertEquals extracted, ExtractDeviceIdFilter.extractMaasPath(uri)

        where:
        uri                                                                | extracted
        "https://www.maas.com/entities/someId"                             | "/entities/someId"
        "https://www.maas.com/tenantId/entities/someId"                    | "/tenantId/entities/someId"
        "https://www.maas.com/entities/someId/checks"                      | "/entities/someId"
        "https://www.maas.com/tenantId/entities/someId/checks"             | "/tenantId/entities/someId"
        "https://www.maas.com/entities/someId/checks/someotherid"          | "/entities/someId"
        "https://www.maas.com/tenantId/entities/someId/checks/someotherid" | "/tenantId/entities/someId"
        "https://www.maas.com/entities/someId/alarms"                      | "/entities/someId"
        "https://www.maas.com/tenantId/entities/someId/alarms"             | "/tenantId/entities/someId"
        "https://www.maas.com/entities/someId/alarms/someotherid"          | "/entities/someId"
        "https://www.maas.com/tenantId/entities/someId/alarms/someotherid" | "/tenantId/entities/someId"
    }

    @Unroll
    def 'throws URISyntaxException when extracting path from #uri'() {
        try {
            ExtractDeviceIdFilter.extractMaasPath(uri)
            fail()
        } catch (URISyntaxException ex) {
            if (!ex.message.startsWith("Malformed MaaS address.")) {
                fail()
            }
        }

        where:
        uri << [
                "https://www.maas.com/entities/",
                "https://www.maas.com/entities",
                "https://www.maas.com/tenantId/entities/",
                "https://www.maas.com/tenantId/entities",
                "https://www.maas.com/",
                "https://www.maas.com",
                "/",
                "",
                null
        ]
    }

    def 'extracts the value of the Retry-After header from the middle'() {
        given:
        def retryString = ZonedDateTime.now().format(RFC_1123_DATE_TIME)
        Header[] headers = [
                new BasicHeader("X-Header-0", "Value-0"),
                new BasicHeader("X-Header-1", "Value-1"),
                new BasicHeader("X-Header-2", "Value-2"),
                new BasicHeader("X-Header-3", "Value-3"),
                new BasicHeader("X-Header-4", "Value-4"),
                new BasicHeader(RETRY_AFTER, retryString),
                new BasicHeader("X-Header-5", "Value-5"),
                new BasicHeader("X-Header-6", "Value-6"),
                new BasicHeader("X-Header-7", "Value-7"),
                new BasicHeader("X-Header-8", "Value-8"),
                new BasicHeader("X-Header-9", "Value-9")
        ]

        when:
        def returned = ExtractDeviceIdFilter.getRetryString(headers, SC_CONTINUE) // (100)

        then:
        assertEquals retryString, returned
    }

    def 'fails to extract the value when the Retry-After header is not present'() {
        given:
        Header[] headers = [
                new BasicHeader("X-Header-0", "Value-0"),
                new BasicHeader("X-Header-1", "Value-1"),
                new BasicHeader("X-Header-2", "Value-2"),
                new BasicHeader("X-Header-3", "Value-3"),
                new BasicHeader("X-Header-4", "Value-4"),
                new BasicHeader("X-Header-5", "Value-5"),
                new BasicHeader("X-Header-6", "Value-6"),
                new BasicHeader("X-Header-7", "Value-7"),
                new BasicHeader("X-Header-8", "Value-8"),
                new BasicHeader("X-Header-9", "Value-9")
        ]

        when:
        def returned = ExtractDeviceIdFilter.getRetryString(headers, SC_CONTINUE) // (100)

        then:
        assertNotNull returned
        listAppender.events.find {
            it.message.formattedMessage.contains "Missing Retry-After header on"
        }
    }

    @Unroll
    def 'a filter call #delegating without #descPre should add/return #descPost (#statusCode)'() {
        given:
        if (!delegating.isEmpty()) {
            config.delegating = delegatingType
        }
        httpServletRequest.requestURI = requestURI
        LOG.debug config.toString()

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        if (!delegating.isEmpty()) {
            assertEquals SC_OK, httpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(statusCode as int, "", delegatingType.quality)
        } else {
            assertEquals statusCode, httpServletResponse.status
        }

        where:
        delegating         | descPre           | descPost       | requestURI                                     | statusCode
        "while delegating" | "an entity ID"    | "Bad Request"  | "http://www.example.com/tenantId/entities"     | SC_BAD_REQUEST     // (400)
        ""                 | "an entity ID"    | "Bad Request"  | "http://www.example.com/tenantId/entities"     | SC_BAD_REQUEST     // (400)
        "while delegating" | "an X-Auth-Token" | "Unauthorized" | "http://www.example.com/tenantId/entities/foo" | SC_UNAUTHORIZED    // (401)
        ""                 | "an X-Auth-Token" | "Unauthorized" | "http://www.example.com/tenantId/entities/foo" | SC_UNAUTHORIZED    // (401)
    }

    def 'the Device ID should be added as a header'() {
        given:
        def entityId = UUID.randomUUID().toString()
        def deviceId = UUID.randomUUID().toString()
        httpServletRequest.requestURI = "http://www.example.com/tenantId/entities/" + entityId
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        when(mockDatastore.get("MaaS:Custom:DeviceId:" + entityId)).thenReturn deviceId
        LOG.debug config.toString()

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        assertEquals SC_OK, httpServletResponse.status  // (200)
        assertEquals deviceId, (filterChain.request as HttpServletRequest).getHeader("X-Device-Id")
    }

    def 'Add the Auth Token and Tenant ID Headers to the MaaS request if the original request had them'() {
        given:
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def deviceId = UUID.randomUUID().toString()
        def authToken = UUID.randomUUID().toString()
        def tenantId = UUID.randomUUID().toString()
        def requestPath = "/$tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", authToken
        httpServletRequest.addHeader "X-Tenant-Id", tenantId
        config.cacheTimeoutMillis = 60000
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK,  // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""{"uri": "http://www.maas.com/accounts/$tenantId/devices/$deviceId"}""".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        assertEquals authToken, (filterChain.request as HttpServletRequest).getHeader("X-Auth-Token")
        assertEquals tenantId, (filterChain.request as HttpServletRequest).getHeader("X-Tenant-Id")
    }

    @Unroll
    def 'Return/Add an expected Internal Server Error (500) if the MaaS request doesn\'t contain a Device ID [delegating = #delegating]'() {
        given:
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def authToken = UUID.randomUUID().toString()
        def tenantId = UUID.randomUUID().toString()
        def requestPath = "/$tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", authToken
        httpServletRequest.addHeader "X-Tenant-Id", tenantId
        config.cacheTimeoutMillis = 60000
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK,  // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""{"uri": "http://www.maas.com/accounts/$tenantId/devices"}""".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, httpServletResponse.status // (200)
        } else {
            assertEquals SC_OK, httpServletResponse.status // (200)
        }

        where:
        delegating << [true, false]
    }

    def 'Put the entityID/deviceID in the cache if enabled'() {
        given:
        def keyCaptor = ArgumentCaptor.forClass(String.class)
        def valueCaptor = ArgumentCaptor.forClass(Serializable.class)
        def ttlCaptor = ArgumentCaptor.forClass(Integer.class)
        def timeUnitCaptor = ArgumentCaptor.forClass(TimeUnit.class)
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def deviceId = UUID.randomUUID().toString()
        def tenantId = UUID.randomUUID().toString()
        def requestPath = "/$tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        config.cacheTimeoutMillis = 60000
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK, // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""{"uri": "http://www.maas.com/accounts/$tenantId/devices/$deviceId"}""".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        verify(mockDatastore).put keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture(), timeUnitCaptor.capture()
        assertEquals "MaaS:Custom:DeviceId:" + entityId, keyCaptor.value
        assertEquals deviceId, valueCaptor.value
        assertEquals config.cacheTimeoutMillis, ttlCaptor.value
        assertEquals TimeUnit.MILLISECONDS, timeUnitCaptor.value
    }

    def 'Don\'t put the entityID/deviceID in the cache if disabled'() {
        given:
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def deviceId = UUID.randomUUID().toString()
        def tenantId = UUID.randomUUID().toString()
        def requestPath = "/$tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK, // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""{"uri": "http://www.maas.com/accounts/$tenantId/devices/$deviceId"}""".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        verify(mockDatastore, never()).put anyString(), any(Serializable.class), anyInt(), any(TimeUnit.class)
    }

    @Unroll
    def 'Return/Add an Internal Server Error (500) if the AkkaServiceClient throws #desc [delegating = #delegating]'() {
        given:
        if (delegating) {
            config.delegating = delegatingType
        }
        httpServletRequest.requestURI = "http://www.example.com/tenantId/entities/" + UUID.randomUUID()
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                anyString(),
                anyMapOf(String.class, String.class)
        )).thenThrow exception

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, httpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(SC_INTERNAL_SERVER_ERROR, "", delegatingType.quality) // (500)
        } else {
            assertEquals SC_INTERNAL_SERVER_ERROR, httpServletResponse.status // (500)
        }
        listAppender.events.find {
            it.message.formattedMessage.contains logMessage
        }

        where:
        delegating | desc                            | logMessage         | exception
        true       | "a URISyntaxException"          | "malformed"        | URISyntaxException.class
        false      | "an URISyntaxException"         | "malformed"        | URISyntaxException.class
        true       | "an AkkaServiceClientException" | "Failed to obtain" | AkkaServiceClientException.class
        false      | "an AkkaServiceClientException" | "Failed to obtain" | AkkaServiceClientException.class
    }

    @Unroll
    def 'Add a Device ID Header to the request if one is returned in the MaaS response [delegating = #delegating]'() {
        given:
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def deviceId = UUID.randomUUID().toString()
        def tenantId = UUID.randomUUID().toString()
        def requestPath = "/$tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK, // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""{"uri": "http://www.maas.com/accounts/$tenantId/devices/$deviceId"}""".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        assertEquals SC_OK, httpServletResponse.status // (200)
        assertEquals deviceId, (filterChain.request as HttpServletRequest).getHeader("X-Device-Id")

        where:
        delegating << [true, false]
    }

    @Unroll
    def 'Return/Add an Internal Server Error (500) if the JSON returned from MaaS is not parsable [delegating = #delegating]'() {
        given:
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def requestPath = "/tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK, // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("Invalid JSON".stripMargin().stripIndent().bytes)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, httpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(SC_INTERNAL_SERVER_ERROR, "", delegatingType.quality) // (500)
        } else {
            assertEquals SC_INTERNAL_SERVER_ERROR, httpServletResponse.status // (500)
        }
        listAppender.events.find {
            it.message.formattedMessage.contains "Failed to parse"
        }

        where:
        delegating << [true, false]
    }

    @Unroll
    def 'Return/Add an Internal Server Error (500) if the Stream from the ServiceClientResponse throws an exception [delegating = #delegating]'() {
        given:
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def requestPath = "/tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_OK, // (200)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                mock(InputStream.class)
        )

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, httpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, httpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(SC_INTERNAL_SERVER_ERROR, "", delegatingType.quality) // (500)
        } else {
            assertEquals SC_INTERNAL_SERVER_ERROR, httpServletResponse.status // (500)
        }
        listAppender.events.find {
            it.message.formattedMessage.contains "Failed to open"
        }

        where:
        delegating << [true, false]
    }

    @Unroll
    def 'Return/Add an Service Unavailable (503) if the MaaS request was rate limited [delegating = #delegating][MaaS Code = #statusCode]'() {
        given:
        def mutableHttpServletResponse = new HttpServletResponseWrapper(httpServletResponse, ResponseMode.PASSTHROUGH, ResponseMode.PASSTHROUGH)
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def requestPath = "/tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        def retryString = ZonedDateTime.now().format(RFC_1123_DATE_TIME)
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                statusCode as int,
                [
                        new BasicHeader(CONTENT_TYPE, APPLICATION_JSON),
                        new BasicHeader(RETRY_AFTER, retryString)
                ] as Header[],
                new ByteArrayInputStream("""
                        |Invalid JSON
                        """.stripMargin().stripIndent().bytes
                ))

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, mutableHttpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, mutableHttpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(SC_SERVICE_UNAVAILABLE, retryString, delegatingType.quality) // (503)
        } else {
            assertEquals SC_SERVICE_UNAVAILABLE, mutableHttpServletResponse.status // (503)
            assertEquals retryString, mutableHttpServletResponse.getHeader(RETRY_AFTER)
        }

        where:
        delegating | statusCode
        true       | SC_REQUEST_ENTITY_TOO_LARGE    // (413)
        true       | SC_TOO_MANY_REQUESTS           // (429)
        false      | SC_REQUEST_ENTITY_TOO_LARGE    // (413)
        false      | SC_TOO_MANY_REQUESTS           // (429)
    }

    @Unroll
    def 'Return/Add an Internal Server Error (500) if the MaaS request returns an unexpected code [delegating = #delegating]'() {
        given:
        def mutableHttpServletResponse = new HttpServletResponseWrapper(httpServletResponse, ResponseMode.PASSTHROUGH, ResponseMode.PASSTHROUGH)
        if (delegating) {
            config.delegating = delegatingType
        }
        def entityId = UUID.randomUUID().toString()
        def alarmsId = UUID.randomUUID().toString()
        def requestPath = "/tenantId/entities/" + entityId
        def requestExtra = "/alarms/" + alarmsId
        httpServletRequest.requestURI = ORIG_ENDPOINT + requestPath + requestExtra
        httpServletRequest.addHeader "X-Auth-Token", UUID.randomUUID()
        LOG.debug config.toString()

        when(mockAkkaServiceClient.get(
                anyString(),
                eq(config.maasServiceUri + requestPath),
                anyMapOf(String.class, String.class)
        )).thenReturn new ServiceClientResponse(
                SC_EXPECTATION_FAILED, // (417)
                [new BasicHeader(CONTENT_TYPE, APPLICATION_JSON)] as Header[],
                new ByteArrayInputStream("""
                        |Invalid JSON
                        """.stripMargin().stripIndent().bytes
                ))

        when:
        filter.configurationUpdated config
        filter.doFilter httpServletRequest, mutableHttpServletResponse, filterChain

        then:
        if (delegating) {
            assertEquals SC_OK, mutableHttpServletResponse.status // (200)
            assertThat "Should add proper delegation header",
                    (filterChain.request as HttpServletRequest).getHeader("X-Delegated"),
                    isFormatted(SC_INTERNAL_SERVER_ERROR, "", delegatingType.quality) // (500)
        } else {
            assertEquals SC_INTERNAL_SERVER_ERROR, mutableHttpServletResponse.status // (500)
        }

        where:
        delegating << [true, false]
    }

    private Matcher<String> isFormatted(final int status_code, final String message, final double quality) {
        return new TypeSafeMatcher<String>() {
            @Override
            protected boolean matchesSafely(final String headerValue) {
                return headerValue.contains("status_code=" + status_code) &&
                        headerValue.contains("`component=" + COMPONENT) &&
                        headerValue.contains("`message=" + message) &&
                        headerValue.contains(";q=" + quality)
            }

            @Override
            public void describeTo(Description description) {
                description.appendText "The string was a properly formatted delegation header."
            }
        }
    }
}
