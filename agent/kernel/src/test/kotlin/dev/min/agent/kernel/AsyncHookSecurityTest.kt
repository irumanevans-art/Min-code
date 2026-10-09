package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.hooks.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsyncHookSecurityTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun BUG_async_plugin_values_use_spawn_environment_without_script_values(): Unit = runBlocking {
        val fs = FakeFs()
        val commands = mutableListOf<ShellCommand>()
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                commands += command
                return ExecResult.Backgrounded(ExecHandle("async", 1), "")
            }
            override suspend fun kill(handle: ExecHandle) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = testDeps(ScriptedModel(), emptyList())
        val deps = KernelDeps(base.clock, base.ids, base.models, emptyList(),
            HostPorts(fs, FakeSearch(), exec) { FakeArtifacts() }, "test", base.zone)
        val rt = SessionRuntime.create(FileSessionStore(tmp.root.toPath(), { 0L }),
            LogHeader(LOG_FORMAT_VERSION, SessionId("async-security"), 0), SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), deps, scope)
        // 轮询间隔改由运行时的 asyncHookTimings 提供（后台 hooks 第二轮）；拉长到一分钟，测试期间不发生轮询
        rt.asyncHookTimings.apply { pollMs = 60_000; livenessMs = 60_000; fallbackLivenessMs = 60_000 }
        val runner = AsyncHookRunner(rt, scope)
        try {
            val secret = "async-plugin-secret-fixture"
            val handler = HookHandler(HookEvent.SessionStart, null, "command", "program", listOf(secret), null, null,
                ConfigSource.Plugin, JsonObject(emptyMap()), async = true, pluginEnv = mapOf("CLAUDE_PLUGIN_OPTION_TOKEN" to secret))
            runner.launch(HookEvent.SessionStart, handler, JsonObject(emptyMap()), emptyMap(), GuestPath("/workspace"), null)
            assertEquals(secret, commands.single().env["MIN_PLUGIN_0_ARG_1"])
            assertEquals(secret, commands.single().env["MIN_PLUGIN_0_ENV_0"])
            assertFalse(fs.writes.any { it.second.decodeToString().contains(secret) })
        } finally {
            runner.cancelAll()
            scope.cancel()
        }
    }
}
