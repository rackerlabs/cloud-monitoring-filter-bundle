# Cloud Monitoring Custom Repose Filter - Extract Device ID

## Purpose
The Extract Device ID filter provides a way to query the Monitoring as a Service (MaaS) API to obtain a Device ID based on the Entity ID in the URL. 

## General filter information
Filter name: extract-device-id

Filter configuration file: extract-device-id.cfg.xml

Release: 1.0

## Prerequisites
Headers:

* The *X-Auth-Token* request header is required.
* IF the *X-Tenant-Id* request header is present, THEN it is passed on in the request to the MaaS API call as well.
* This filter will populates *X-Device-Id* the response header.

Preceding filters:

* This filter requires a preceding filter(s) to provide the *X-Auth-Token* header.

Proceeding filters:

* This filter has no required proceeding filters, but is typically followed by the [Valkyrie Authorization filter](https://repose.atlassian.net/wiki/display/REPOSE/Valkyrie+Authorization+filter).

## Basic configuration
* [XML schema definition](https://github.com/rackerlabs/cloud-monitoring-filter-bundle/blob/master/extract-device-id/src/main/resources/META-INF/schema/config/extract-device-id.xsd)
* [Example configuration](https://github.com/rackerlabs/cloud-monitoring-filter-bundle/blob/master/extract-device-id/src/main/resources/META-INF/schema/examples/extract-device-id.cfg.xml)

## Configurable parameters
| Elements          | Attributes           | Required/Optional | Description |
|:----------------- |:--------------------:|:-----------------:|:------------------------------------------------------------------------------------------------------- |
| extract-device-id |                      | Required          | Specifies the sub-elements and attributes to define this configuration.                                 |
|                   | maas-service-uri     | Required          | Specifies the endpoint location of the MaaS API service to connect to.                                  |
|                   | cache-timeout-millis | Optional          | The Time To Live (TTL) for caching of MaaS Entity/Device ID data. Default is **0**. (i.e. **Disabled**) |
| delegating        |                      | Optional          | If present, then delegating is enabled.                                                                 |
|                   | quality              | Optional          | Sets the quality factor used by the [Delegation Response Processor (DeRP) filter](https://repose.atlassian.net/wiki/display/REPOSE/Delegation+Response+Processor+%28DeRP%29+Filter). Default is **0.2** |

## Return codes and conditions
* 400 - **Bad Request**
  * IF the request URI does not contain an Entity ID.
* 401 - **Unauthorized**
  * IF the *X-Auth-Token* request header is not present.
* 503 - **Service Unavailable**
  * IF the MaaS API endpoint Rate Limits this filter (i.e. returns a status code of 413 - *Request Entity Too Large* OR 429 - *Too Many Requests*),
  * THEN the *Retry-After* header will be appropriately populated in the response with an [RFC 1123 International format](https://www.ietf.org/rfc/rfc1123.txt)/[ISO 8601](http://www.w3.org/TR/NOTE-datetime).
* 500 - **Internal Server Error**
  * Failed (non-200 - *OK*)/Invalid response from the MaaS API endpoint.
