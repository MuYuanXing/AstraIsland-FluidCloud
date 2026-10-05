package com.astraflow.fluidcloud.hook

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/**
 * 插件在系统界面里的出错保护(和星河岛的崩溃自动暂停同一个规矩):10 分钟内因为插件让系统界面崩溃 3 次,插件自动暂停 10 分钟——
 * 不装挂钩、不读系统流体云,系统界面恢复原样;过了 10 分钟,下一次系统界面启动时照常启动;用户点「重新开启」随时恢复
 * (重启系统界面后生效,见 [resumeIfRequested])。
 *
 * 算在插件头上的崩溃:未捕获异常的堆栈(连同起因)里有插件的类;或者上一次系统界面在插件用本机库(DexKit)找代码的途中退出——
 * 本机库崩溃抓不到异常,开始前记一笔、做完擦掉([risky]),下次启动还在就算一次。上一次是别处的未捕获异常让系统界面退出的,
 * 途中的那一笔不算(异常已经按自己的堆栈算过)。同一份文件连着两次在找代码的途中退出(中间没有一次找完),以后跳过这份文件
 * ([isSkipped]),不让它反复把系统界面带崩;只中途退出一次的(例如正好赶上重启系统界面)下一次照常找,找完就不再记着。
 *
 * 记录放在系统界面自己的设备加密目录(开机解锁前也读得到),崩溃时同步写好再交给系统原来的处理器。
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_CRASHES = 3
    private const val MAX_FILES = 16
    private const val PACKAGE_PREFIX = "com.astraflow.fluidcloud"
    private const val STATE_FILE = "guard.properties"
    /** 找代码时记的那一笔:scan-<进程号>-<线程号>,内容是文件身份 */
    private const val SCAN_PREFIX = "scan-"
    /** 系统界面因为未捕获异常退出时记下:java-crash-<进程号> */
    private const val JAVA_CRASH_PREFIX = "java-crash-"
    private const val KEY_CRASHES = "crashes"
    private const val KEY_DISABLED_UNTIL = "disabledUntil"
    private const val KEY_TRIPPED_AT = "trippedAt"
    private const val KEY_REASON = "reason"
    /** 找代码途中退出过一次的文件 */
    private const val KEY_SUSPECTS = "suspects"
    /** 连着两次在找代码途中退出的文件,以后不再找 */
    private const val KEY_SKIPPED = "skipped"

    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var recorderInstalled = false
    /** 检查里换成别的时钟 */
    @Volatile var clock: () -> Long = System::currentTimeMillis
    /** 这个进程的进程号(检查里换成别的) */
    @Volatile var pid: () -> Int = { runCatching { android.os.Process.myPid() }.getOrDefault(0) }

    /** 在系统界面一加载时调用:记下存放目录,把上一次没做完的本机库查找算作一次崩溃 */
    fun init(directory: File) {
        synchronized(lock) {
            dir = directory.also { it.mkdirs() }
            val files = directory.listFiles().orEmpty()
            val crashedInJava = files.filter { it.name.startsWith(JAVA_CRASH_PREFIX) }
                .mapTo(mutableSetOf()) { it.name.removePrefix(JAVA_CRASH_PREFIX) }
            files.filter { it.name.startsWith(JAVA_CRASH_PREFIX) }.forEach { it.delete() }
            val unfinished = files.filter { it.name.startsWith(SCAN_PREFIX) }
            if (unfinished.isEmpty()) return
            // 那个进程是因为未捕获异常退出的:异常已经按自己的堆栈算过,途中的这一笔不另算
            val interrupted = unfinished.filterNot { it.name.removePrefix(SCAN_PREFIX).substringBefore('-') in crashedInJava }
                .mapNotNull { file -> runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } }
                .distinct()
            unfinished.forEach { it.delete() }
            if (interrupted.isEmpty()) {
                Log.i(TAG, "a code search was cut short by an exception elsewhere; not counted")
                return
            }
            val state = load()
            val suspects = state.list(KEY_SUSPECTS)
            val again = interrupted.filter { it in suspects }
            state.setList(KEY_SUSPECTS, (suspects - again.toSet() + interrupted.filterNot { it in suspects }).distinct().takeLast(MAX_FILES))
            state.setList(KEY_SKIPPED, (state.list(KEY_SKIPPED) + again).distinct().takeLast(MAX_FILES))
            save(state)
            Log.e(TAG, "the last code search ended the process: $interrupted, skipped from now on: $again")
            recordLocked("代码搜索中途退出")
        }
    }

    /** 系统界面的未捕获异常:记下这个进程是因为异常退出的;里面有插件的类时记一次崩溃。记完照常交给系统原来的处理器 */
    fun installRecorder() {
        if (recorderInstalled) return
        recorderInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir?.let { File(it, JAVA_CRASH_PREFIX + pid()).writeText(clock().toString()) }
                if (attributable(error)) {
                    val tripped = recordCrash("${error.javaClass.simpleName}: ${error.message?.take(80).orEmpty()}")
                    Log.e(TAG, "SystemUI crash caused by the plugin (thread=${thread.name}), paused=$tripped", error)
                }
            }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
        }
    }

    /** 这次异常是不是插件引起的:它和起因的堆栈里有插件的类 */
    fun attributable(error: Throwable): Boolean = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .take(16).any { t -> t.stackTrace.any { it.className.startsWith(PACKAGE_PREFIX) } }

    /** 记一次崩溃;返回这一次是否让插件暂停 */
    fun recordCrash(reason: String): Boolean = synchronized(lock) { recordLocked(reason) }

    private fun recordLocked(reason: String): Boolean {
        if (dir == null) return false
        val now = clock()
        val state = load()
        val times = state.getProperty(KEY_CRASHES).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0 until WINDOW_MS } + now
        state.setProperty(KEY_CRASHES, times.joinToString(","))
        val tripped = times.size >= MAX_CRASHES
        if (tripped) {
            state.setProperty(KEY_DISABLED_UNTIL, (now + WINDOW_MS).toString())
            state.setProperty(KEY_TRIPPED_AT, now.toString())
            state.setProperty(KEY_REASON, reason.take(120))
        }
        save(state)
        return tripped
    }

    /** 能不能启动:不在暂停中(暂停到点后,下一次系统界面启动时照常启动) */
    fun shouldStart(): Boolean = synchronized(lock) {
        val until = load().getProperty(KEY_DISABLED_UNTIL)?.toLongOrNull() ?: return true
        val now = clock()
        !(now < until && until - now <= WINDOW_MS)
    }

    /** 这一次暂停的开始时间(墙上时间);没在暂停中为空 */
    fun pausedAt(): Long? = synchronized(lock) {
        if (shouldStart()) null else load().getProperty(KEY_TRIPPED_AT)?.toLongOrNull()
    }

    /** 用户点了「重新开启」([requestedAt] 晚于这次暂停):清掉崩溃记录,这一次照常启动;返回是否清了 */
    fun resumeIfRequested(requestedAt: Long): Boolean = synchronized(lock) {
        val state = load()
        val trippedAt = state.getProperty(KEY_TRIPPED_AT)?.toLongOrNull() ?: return false
        if (requestedAt <= trippedAt) return false
        val cleared = Properties()
        // 文件的记录照旧:跳过的那份曾经连着两次把系统界面带崩
        for (key in listOf(KEY_SUSPECTS, KEY_SKIPPED)) state.getProperty(key)?.let { cleared.setProperty(key, it) }
        save(cleared)
        true
    }

    /** 这份文件(路径、大小、修改时间)以前连着两次在找代码的途中把系统界面带崩,不再去找 */
    fun isSkipped(identity: String): Boolean = synchronized(lock) { identity in load().list(KEY_SKIPPED) }

    /**
     * 用本机库找代码这类抓不到崩溃的事:开始前记一笔、做完擦掉;每个线程一笔,互不影响。
     * 做完(包括抛出异常)说明这份文件没把系统界面带崩,不再记着它可疑。
     */
    fun <T> risky(identity: String, block: () -> T): T {
        val marker = dir?.let { File(it, "$SCAN_PREFIX${pid()}-${Thread.currentThread().id}") }
        runCatching { marker?.writeText(identity) }
        try {
            return block()
        } finally {
            runCatching { marker?.delete() }
            runCatching { clearSuspect(identity) }
        }
    }

    private fun clearSuspect(identity: String) = synchronized(lock) {
        val state = load()
        val suspects = state.list(KEY_SUSPECTS)
        if (identity !in suspects) return@synchronized
        state.setList(KEY_SUSPECTS, suspects - identity)
        save(state)
    }

    private fun Properties.list(key: String): List<String> = getProperty(key).orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun Properties.setList(key: String, values: List<String>) {
        if (values.isEmpty()) remove(key) else setProperty(key, values.joinToString("\n"))
    }

    private fun load(): Properties = Properties().apply {
        val file = dir?.let { File(it, STATE_FILE) } ?: return@apply
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    private fun save(state: Properties) {
        val directory = dir ?: return
        runCatching {
            val temp = File(directory, "$STATE_FILE.tmp")
            temp.outputStream().use { state.store(it, null) }
            if (!temp.renameTo(File(directory, STATE_FILE))) {
                File(directory, STATE_FILE).delete()
                temp.renameTo(File(directory, STATE_FILE))
            }
        }.onFailure { Log.w(TAG, "guard state not saved: ${it.message}") }
    }

    /** 检查用:换一个目录、清掉状态 */
    fun resetForTest(directory: File?) {
        synchronized(lock) {
            dir = directory
            clock = System::currentTimeMillis
            pid = { runCatching { android.os.Process.myPid() }.getOrDefault(0) }
            recorderInstalled = false
        }
    }
}
