package dev.min.code.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceIntentTest {
    @Test
    fun http_server_is_long_lived_heuristic() {
        assertTrue(LocalServiceIntent.shouldHost("python3 -m http.server 8765"))
        assertTrue(LocalServiceIntent.shouldHost("python -m http.server 8080 --bind 0.0.0.0"))
    }

    @Test
    fun ordinary_commands_are_not_hosted() {
        assertFalse(LocalServiceIntent.shouldHost("ls -la"))
        assertFalse(LocalServiceIntent.shouldHost("git status"))
        // 无 flag 的 python 脚本不进表（主路径是 run_in_background）
        assertFalse(LocalServiceIntent.shouldHost("python script.py"))
        assertFalse(LocalServiceIntent.shouldHost("python app.py"))
        assertFalse(LocalServiceIntent.shouldHost("python3 app.py --port 3000"))
    }

    @Test
    fun background_flag_is_primary_path() {
        assertTrue(LocalServiceIntent.shouldHost("sleep 999", mapOf("run_in_background" to true)))
        assertTrue(LocalServiceIntent.shouldHost("python app.py", mapOf("run_in_background" to true)))
        assertTrue(LocalServiceIntent.shouldHost("node server.js", mapOf("runInBackground" to true)))
        assertTrue(LocalServiceIntent.isBackgroundFlag(mapOf("is_background" to "true")))
        assertFalse(LocalServiceIntent.isBackgroundFlag(mapOf("run_in_background" to false)))
    }

    @Test
    fun guess_port_and_strip() {
        assertEquals(8765, LocalServiceIntent.guessPort("python3 -m http.server 8765"))
        assertEquals(
            "python3 -m http.server 8765",
            LocalServiceIntent.stripBackgroundNoise("python3 -m http.server 8765 &"),
        )
    }

    @Test
    fun one_shot_commands_mentioning_servers_are_not_hosted() {
        // 白名单词出现在参数位置的一次性命令：旧版 containsMatchIn 会误杀
        assertFalse(LocalServiceIntent.shouldHost("pip install uvicorn"))
        assertFalse(LocalServiceIntent.shouldHost("cat vite.config.ts"))
        assertFalse(LocalServiceIntent.shouldHost("npm install -D vite"))
        assertFalse(LocalServiceIntent.shouldHost("grep uvicorn logs.txt"))
        assertFalse(LocalServiceIntent.shouldHost("echo 'remember to run uvicorn later'"))
        assertFalse(LocalServiceIntent.shouldHost("python -m pip install uvicorn"))
        assertFalse(LocalServiceIntent.shouldHost("tail -f vite.log"))
    }

    @Test
    fun dev_servers_in_command_position_are_hosted() {
        assertTrue(LocalServiceIntent.shouldHost("npm run dev"))
        assertTrue(LocalServiceIntent.shouldHost("npm dev"))
        assertTrue(LocalServiceIntent.shouldHost("npx vite"))
        assertTrue(LocalServiceIntent.shouldHost("npx --yes vite"))
        assertTrue(LocalServiceIntent.shouldHost("bunx vite"))
        assertTrue(LocalServiceIntent.shouldHost("uvicorn main:app --port 8000"))
        assertTrue(LocalServiceIntent.shouldHost("python3 -m http.server"))
        assertTrue(LocalServiceIntent.shouldHost("vite"))
        assertTrue(LocalServiceIntent.shouldHost("vite --host 0.0.0.0"))
        assertTrue(LocalServiceIntent.shouldHost("pnpm dev"))
        assertTrue(LocalServiceIntent.shouldHost("yarn dev"))
        assertTrue(LocalServiceIntent.shouldHost("python -m uvicorn main:app"))
        assertTrue(LocalServiceIntent.shouldHost("python manage.py runserver"))
        assertTrue(LocalServiceIntent.shouldHost("./manage.py runserver"))
        assertTrue(LocalServiceIntent.shouldHost("npx serve"))
        assertTrue(LocalServiceIntent.shouldHost("next dev"))
        assertTrue(LocalServiceIntent.shouldHost("flask run"))
        assertTrue(LocalServiceIntent.shouldHost("php -S 0.0.0.0:8000"))
        assertTrue(LocalServiceIntent.shouldHost("docker compose up"))
    }

    @Test
    fun shell_wrappers_still_reach_the_real_command() {
        assertTrue(LocalServiceIntent.shouldHost("cd /workspace && npm run dev"))
        assertTrue(LocalServiceIntent.shouldHost("sudo uvicorn main:app --port 8000"))
        assertTrue(LocalServiceIntent.shouldHost("PORT=5173 npx vite"))
        assertTrue(LocalServiceIntent.shouldHost("env HOST=0.0.0.0 vite"))
        assertTrue(LocalServiceIntent.shouldHost("nohup pnpm dev"))
        assertTrue(LocalServiceIntent.shouldHost("yarn dev | tee dev.log"))
        assertTrue(LocalServiceIntent.shouldHost("git pull; uvicorn main:app"))
        // 拿不准的宁可漏：sudo 带参 flag、python 带参 flag 不猜
        assertFalse(LocalServiceIntent.shouldHost("sudo -u node npm run dev"))
        assertFalse(LocalServiceIntent.shouldHost("python -W ignore manage.py runserver"))
        // 管道右段的命令位置才看：uvicorn 只是 grep 的参数
        assertFalse(LocalServiceIntent.shouldHost("cat app.log | grep uvicorn"))
    }
}
