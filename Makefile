# Convenience wrapper around the Maven build. Everything here is a shortcut for
# a plain 'mvn' invocation - there is nothing you cannot do without make.
#
# Requires JDK 17 and Maven. Use 'build' (or plain 'make'), not 'mvn test': the
# Karaf feature verification resolves the plugin bundle out of the local Maven
# repository, so the bundle has to be installed there first.

.DEFAULT_GOAL := build
SHELL := /bin/bash -o nounset -o pipefail -o errexit

ARTIFACTS_DIR := target/artifacts
KAR_DIR       := assembly/kar/target
# Matches the <finalName> set in assembly/kar/pom.xml, not the default
# artifactId-named kar that lands in the same directory.
KAR_GLOB      := opennms-ilert-plugin-*.kar
TARBALL       := opennms-ilert-plugin.tar.gz

.PHONY: help build test clean collect-artifacts

help:
	@echo "build              Compile, test and install into the local Maven repository (default)"
	@echo "test               Run the test suite only"
	@echo "clean              Remove build output"
	@echo "collect-artifacts  Assemble the release tarball under $(ARTIFACTS_DIR)"

build:
	mvn install

test:
	mvn test

clean:
	mvn clean

# Produces the tarball published by the release workflow, plus a detached
# checksum for it so a download can be verified without unpacking first.
collect-artifacts:
	mkdir -p $(ARTIFACTS_DIR)
	cp $(KAR_DIR)/$(KAR_GLOB) LICENSE $(ARTIFACTS_DIR)
	cd $(ARTIFACTS_DIR) && shasum -a 256 -b $(KAR_GLOB) LICENSE > shasum256.txt
	cd $(ARTIFACTS_DIR) && tar czf $(TARBALL) $(KAR_GLOB) shasum256.txt LICENSE
	cd $(ARTIFACTS_DIR) && shasum -a 256 -b $(TARBALL) > opennms-ilert-plugin.sha256