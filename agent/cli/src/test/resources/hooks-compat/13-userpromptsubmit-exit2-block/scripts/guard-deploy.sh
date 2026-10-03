#!/bin/sh
# 拦截要直接上生产的提示词：exit 2，理由进 stderr
input=$(cat)
case "$input" in
  *"deploy to prod"*)
    echo "Deploying to production needs a release ticket; ask the release manager first" >&2
    exit 2
    ;;
esac
exit 0
