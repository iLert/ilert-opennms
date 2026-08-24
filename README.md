# OpenNMS ilert Plugin

[![build](https://github.com/iLert/ilert-opennms/actions/workflows/build.yml/badge.svg)](https://github.com/iLert/ilert-opennms/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Forwards OpenNMS alarms to [ilert](https://www.ilert.com/) as alerts, using the
[ilert Events API](https://docs.ilert.com/developer-docs/rest-api/api-reference/events).

A new alarm raises an alert, acknowledging the alarm accepts it, and clearing or deleting the alarm
resolves it. Alerts are deduplicated on the alarm's reduction key, so repeated events for the same
alarm update one alert rather than paging twice. Which alarms are forwarded is controlled per alert
source by a JEXL filter, with an optional hold-down delay so short-lived alarms never page at all.

Requires OpenNMS Horizon 35 or newer — it is built against OpenNMS Integration API `2.0.0`, the
version Horizon has shipped since 35.

## Documentation

Installation and configuration: **ilert docs — link to be added.**

## Building

```shell
make
```

Installs the bundles into your local Maven repository and writes the kar to
`assembly/kar/target`. Requires JDK 17 and Maven.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright © 2026 ilert GmbH.

## Acknowledgements

Inspired by the [OpenNMS PagerDuty Plugin](https://github.com/OpenNMS/opennms-pagerduty-plugin).