#!/usr/bin/env bash

set -a # automatically export all variables
source .app.local.env
set +a

sbt "project app" run
