[![Circle CI](https://circleci.com/gh/rackerlabs/cloud-monitoring-filter-bundle.svg?style=shield)](https://circleci.com/gh/rackerlabs/cloud-monitoring-filter-bundle)

# Cloud Monitoring Custom Repose Filter Bundle

This project is intended to assist Cloud Monitoring in maintaining a custom filter for Repose. 

## Clone this project
`git clone git@github.com:rackerlabs/cloud-monitoring-filter-bundle.git`

## Build the cloned project
 - `cd cloud-monitoring-filter-bundle`
 - `mvn clean install`

## Copy the artifacts to a Repose node
 - `scp ./custom-bundle/target/custom-bundle-1.0-SNAPSHOT.ear                                       root@<SERVER_HOSTING_REPOSE>:/usr/share/repose/filters/`
 - `scp ./extract-device-id/src/main/resources/META-INF/schema/examples/extract-device-id.cfg.xml   root@<SERVER_HOSTING_REPOSE>:/etc/repose/`

## Add the extract-device-id filter to the system-model.cfg.xml
 - `ssh root@<SERVER_HOSTING_REPOSE>`
 - `vi /etc/repose/system-model.cfg.xml`

# Adding a new filter to this custom filter bundle

## Create the directories to hold the new filter
 - `mkdir -p ./hello-world-new/src/main/new/org/openrepose/filters/custom/helloworldnew/`
 - `mkdir -p ./hello-world-new/src/test/new/org/openrepose/filters/custom/helloworldnew/`

## Create the files for the new filter.
 - `touch ./hello-world-new/pom.xml`
 - `touch ./hello-world-new/src/main/new/org/openrepose/filters/custom/helloworldnew/HelloWorldNewFilter.new`
 - `touch ./hello-world-new/src/test/new/org/openrepose/filters/custom/helloworldnew/HelloWorldNewFilterTest.new`

## Add the new module to the top level POM.
 - `vi ./pom.xml`

```
    ...
    <modules>
        <module>custom-bundle</module>
        <module>extract-device-id</module>
        <module>hello-world-new</module>
    </modules>
    ...
```
 
## Add the new module to the custom bundle POM dependency.
 - `vi ./custom-bundle/pom.xml`

```
    ...
    <dependencies>
    ...
        <dependency>
            <groupId>org.openrepose.filters.custom</groupId>
            <artifactId>hello-world-new</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
    ...
```

## Add the new filter info to the bundle.
 - `vi ./custom-bundle/src/main/application/WEB-INF/web-fragment.xml`

```
    ...
    <filter>
        <filter-name>hello-world-new</filter-name>
        <filter-class>org.openrepose.filters.custom.helloworldnew.HelloWorldNewFilter</filter-class>
    </filter>
    ...
```
