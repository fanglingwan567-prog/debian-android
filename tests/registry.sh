#!/bin/sh
set -eu
runner=$(dirname "$0")/art-smoke.sh
sh "$runner" RegistryHttpSmoke
exec sh "$runner" RegistrySmoke "$@"
