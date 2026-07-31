#!/usr/bin/env bash
# Wrapper - delegates to module/build.sh
cd "$(dirname "$0")/.."
exec module/build.sh
