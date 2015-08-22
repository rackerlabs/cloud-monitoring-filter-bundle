package features.monitoring

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Endpoint
import org.rackspace.deproxy.Response
import spock.lang.Unroll

/**
 * Created by dimi5963 on 8/11/15.
 */
class MonitoringWithDelegableTest  extends ReposeValveTest {
    static Endpoint monitoringEndpoint

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)
        monitoringEndpoint = deproxy.addEndpoint(properties.monitoringPort, 'monitoring service', null, { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") })

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/monitoring", params)
        repose.configurationProvider.applyConfigs("features/monitoring/delegable", params)
        repose.start()
        repose.waitForNon500FromUrl(reposeEndpoint)
    }

    def cleanupSpec() {
        deproxy.shutdown()
        repose.stop()
    }

    def cleanup() {
        deproxy._removeEndpoint(monitoringEndpoint)
    }

    @Unroll("request: #requestMethod #requestURI -d #requestBody will return #responseCode with #responseMessage")
    def "When doing the test"() {
        given: "set up monitoring response"
        monitoringEndpoint.defaultHandler = monitoringResponse

        when: "Do awesome request"
        def mc = deproxy.makeRequest([
                url: reposeEndpoint + requestURI,
                method: requestMethod,
                requestBody: requestBody,
                headers: headers
        ])

        then: "Pass all the things"
        mc.receivedResponse.code == responseCode
        mc.receivedResponse.message == responseMessage
        mc.handlings[0].request.headers.contains("X-Delegated") == delegatedHeaderExists
        if(delegatedHeaderExists)
          assert mc.handlings[0].request.headers["X-Delegated"] == delegatedHeaderValue

        where:

        requestBody | requestMethod | requestURI       | responseCode | responseMessage            | headers                  | delegatedHeaderExists | delegatedHeaderValue                                                                                 | monitoringResponse
        ""          | "GET"         | "/"              | "200"        | "OK"                       | []                       | true                  | "status_code=400`component=Extract Device ID`message=No Entity ID in the URI.;q=0.2"                 | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        "BLAH"      | "POST"        | "/"              | "200"        | "OK"                       | []                       | true                  | "status_code=400`component=Extract Device ID`message=No Entity ID in the URI.;q=0.2"                 | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities"      | "200"        | "OK"                       | []                       | true                  | "status_code=400`component=Extract Device ID`message=No Entity ID in the URI.;q=0.2"                 | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/123"  | "200"        | "OK"                       | []                       | true                  | "status_code=401`component=Extract Device ID`message=Not Authenticated.;q=0.2"                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/234"  | "200"        | "OK"                       | ['x-auth-token': '123']  | false                 | null                                                                                                 | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/345"  | "200"        | "OK"                       | ['X-Auth-Token': '234']  | false                 | null                                                                                                 | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/456"  | "200"        | "OK"                       | ['X-Auth-Token': '345']  | true                  | "status_code=500`component=Extract Device ID`message=Invalid response from monitoring service;q=0.2" | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/567"  | "200"        | "OK"                       | ['x-auth-token': '456']  | true                  | "status_code=500`component=Extract Device ID`message=Unknown Error;q=0.2"                            | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entities/678"  | "200"        | "OK"                       | ['X-Auth-Token': '567']  | true                  | "status_code=500`component=Extract Device ID`message=Unknown Error;q=0.2"                            | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entities/789"  | "200"        | "OK"                       | ['x-auth-token': '678']  | true                  | "status_code=500`component=Extract Device ID`message=Unknown Error;q=0.2"                            | { return new Response(500, "Internal Server Error") }
        ""          | "GET"         | "/entities/890"  | "200"        | "OK"                       | ['X-Auth-Token': '789']  | true                  | "status_code=500`component=Extract Device ID`message=Unknown Error;q=0.2"                            | { return new Response(500, "Internal Server Error") }
    }
}
