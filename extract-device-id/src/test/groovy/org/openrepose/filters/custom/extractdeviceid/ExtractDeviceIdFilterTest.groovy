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
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.test.appender.ListAppender
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.datastore.Datastore
import org.openrepose.core.services.datastore.DatastoreService
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient
import org.openrepose.filters.custom.extractdeviceid.config.DelegatingType
import org.openrepose.filters.custom.extractdeviceid.config.ExtractDeviceIdConfig
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockFilterConfig
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Unroll

import javax.ws.rs.core.HttpHeaders
import java.time.ZonedDateTime

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import static javax.servlet.http.HttpServletResponse.SC_CONTINUE
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.mockito.Mockito.mock

public class ExtractDeviceIdFilterTest extends Specification {
    private ExtractDeviceIdFilter filter
    private ExtractDeviceIdConfig config
    private MockHttpServletRequest servletRequest
    private MockHttpServletResponse servletResponse
    private MockFilterChain filterChain
    private Datastore mockDatastore
    private DatastoreService mockDatastoreService
    private AkkaServiceClient mockAkkaServiceClient
    private ConfigurationService mockConfigService
    private MockFilterConfig mockFilterConfig
    private ListAppender listAppender

    def setupSpec() {
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory",
                "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl")
    }

    def setup() {
        servletRequest = new MockHttpServletRequest()
        servletResponse = new MockHttpServletResponse()
        filterChain = new MockFilterChain()
        mockConfigService = mock(ConfigurationService.class)
        mockDatastore = mock(Datastore.class)
        mockDatastoreService = mock(DatastoreService.class)
        mockAkkaServiceClient = mock(AkkaServiceClient.class)
        mockFilterConfig = new MockFilterConfig("ExtractDeviceIdConfig")
        filter = new ExtractDeviceIdFilter(mockConfigService, mockAkkaServiceClient, mockDatastoreService)
        config = new ExtractDeviceIdConfig()
        DelegatingType delegatingType = new DelegatingType()
        delegatingType.setQuality(0.0)
        config.setDelegating(delegatingType)
        config.setCacheTimeoutMillis(0)
        config.setMaasServiceUri("/this/is/an/entity/test")
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false)
        listAppender = ((ListAppender) (ctx.getConfiguration().getAppender("List0"))).clear()
    }

    def cleanup() {
        if (filter.isInitialized()) filter.destroy()
    }

    @Unroll
    def 'extracts from #uri URI the #extracting #extracted'() {
        assertEquals(extracted, ExtractDeviceIdFilter.extractPrefixedElement(uri, extracting))

        where:
        uri                                       | extracting | extracted
        "maas.com/entity/foo"                     | "entity"   | "foo"
        "maas.com/tenantid/entity/foo/alarm/bar"  | "entity"   | "foo"
        "maas.com/tenantid/entity/foo/check/baz"  | "entity"   | "foo"
        "maas.com/this/is/an/entity/"             | "entity"   | null
        "maas.com/this/is/an/"                    | "entity"   | null
        "maas.com/accounts/tenantid/devices/dees" | "devices"  | "dees"
        "maas.com/accounts/tenantid/devices/"     | "devices"  | null
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
                new BasicHeader(HttpHeaders.RETRY_AFTER, retryString),
                new BasicHeader("X-Header-5", "Value-5"),
                new BasicHeader("X-Header-6", "Value-6"),
                new BasicHeader("X-Header-7", "Value-7"),
                new BasicHeader("X-Header-8", "Value-8"),
                new BasicHeader("X-Header-9", "Value-9")
        ]

        when:
        def returned = ExtractDeviceIdFilter.getRetryString(headers, SC_CONTINUE)

        then:
        assertEquals(retryString, returned)
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
        def returned = ExtractDeviceIdFilter.getRetryString(headers, SC_CONTINUE)

        then:
        assertNotNull(returned)
        listAppender.getEvents().find {
            it.getMessage().getFormattedMessage().contains("Missing Retry-After header on")
        }
    }

    private int count(final List<LogEvent> events, final String msg) {
        int rtn = 0
        for (LogEvent event : events) {
            if (event.getMessage().getFormattedMessage().contains(msg)) rtn++
        }
        return rtn
    }
}
