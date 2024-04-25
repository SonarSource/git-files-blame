#!/bin/bash
set -euo pipefail

PYTHON_VERSION=$(python3 --version)
NODE_VERSION=$(node --version)
NPM_VERSION=$(npm --version)
PIP3_VERSION=$(pip --version)
POETRY_VERSION=$(poetry --version)
CDK_VERSION=$(cdk --version)
JAVA_VERSION=$(java --version)
SCANNER_VERSION=$(sonar-scanner --version)
AWSCLI_V2_VERSION=$(aws --version)
DOCKER_VERSION=$(docker --version)

echo
echo "Python:${PYTHON_VERSION}"
echo "Pip:${PIP3_VERSION}"
echo "Poetry:${POETRY_VERSION}"
echo
echo "Node:${NODE_VERSION}"
echo "NPM:${NPM_VERSION}"
echo "CDK:${CDK_VERSION}"
echo
echo "Java:${JAVA_VERSION}"
echo
echo "Sonar scanner:${SCANNER_VERSION}"
echo
echo "AWS CLI:${AWSCLI_V2_VERSION}"
echo
echo "Docker:"${DOCKER_VERSION}
echo
