# OpenNMS iLert Plugin

[![build](https://github.com/iLert/ilert-opennms/actions/workflows/build.yml/badge.svg)](https://github.com/iLert/ilert-opennms/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Forwards OpenNMS alarms to [iLert](https://www.ilert.com/) as alerts, using the
[iLert Events API](https://docs.ilert.com/developer-docs/rest-api/api-reference/events).

A new alarm raises an alert, acknowledging the alarm accepts it, and clearing or deleting the alarm
resolves it. Alerts are deduplicated on the alarm's reduction key, so repeated events for the same
alarm update one alert rather than paging twice. Which alarms are forwarded is controlled per alert
source by a JEXL filter, with an optional hold-down delay so short-lived alarms never page at all.

Requires OpenNMS Meridian 2026 or newer. Also available in the community version, OpenNMS Horizon
35 or newer. It is built against OpenNMS Integration API `2.0.0` and Java 17.

## Documentation

Installation and configuration: [iLert docs — OpenNMS integration](https://docs.ilert.com/integrations/inbound-integrations/opennms).

## Building

```shell
make
```

Installs the bundles into your local Maven repository and writes the kar to
`assembly/kar/target`. Requires JDK 17 and Maven.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright © 2026 iLert GmbH.
