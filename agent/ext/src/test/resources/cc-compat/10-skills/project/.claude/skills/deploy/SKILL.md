---
name: deploy
description: Deploys the web app to staging or production
when_to_use: When the user asks to ship, deploy or roll out
allowed-tools: Bash(npm run build), Bash(npm run deploy)
disable-model-invocation: false
user-invocable: true
context: fork
agent: general-purpose
background: false
paths: apps/web/**, packages/ui/**
model: sonnet
---
1. Run the build.
2. Run the deploy script for the target environment.
