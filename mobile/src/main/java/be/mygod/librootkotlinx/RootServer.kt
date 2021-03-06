package be.mygod.librootkotlinx

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Parcelable
import android.os.RemoteException
import android.util.Log
import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.collection.valueIterator
import eu.chainfire.librootjava.AppProcess
import eu.chainfire.librootjava.Logger
import eu.chainfire.librootjava.RootJava
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.*
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

class RootServer @JvmOverloads constructor(private val warnLogger: (String) -> Unit = { Log.w(TAG, it) }) {
    private sealed class Callback(private val server: RootServer, private val index: Long,
                                  protected val classLoader: ClassLoader?) {
        var active = true

        abstract fun cancel()
        abstract fun shouldRemove(result: Byte): Boolean
        abstract operator fun invoke(input: DataInputStream, result: Byte)
        suspend fun sendClosed() = withContext(NonCancellable) { server.execute(CancelCommand(index)) }

        private fun initException(targetClass: Class<*>, message: String): Throwable {
            var targetClass = targetClass
            while (true) {
                try {
                    // try to find a message constructor
                    return targetClass.getDeclaredConstructor(String::class.java).newInstance(message) as Throwable
                } catch (_: ReflectiveOperationException) { }
                targetClass = targetClass.superclass
            }
        }
        private fun makeRemoteException(cause: Throwable, message: String? = null) =
                if (cause is CancellationException) cause else RemoteException(message).initCause(cause)
        protected fun DataInputStream.readException(result: Byte) = when (result.toInt()) {
            EX_GENERIC -> {
                val message = readUTF()
                val name = message.split(':', limit = 2)[0]
                makeRemoteException(initException(try {
                    classLoader?.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    null
                } ?: Class.forName(name), message), message)
            }
            EX_PARCELABLE -> makeRemoteException(readParcelable<Parcelable>(classLoader) as Throwable)
            EX_SERIALIZABLE -> makeRemoteException(readSerializable(classLoader) as Throwable)
            else -> throw IllegalArgumentException("Unexpected result $result")
        }

        class Ordinary(server: RootServer, index: Long, classLoader: ClassLoader?,
                       private val callback: CompletableDeferred<Parcelable?>) : Callback(server, index, classLoader) {
            override fun cancel() = callback.cancel()
            override fun shouldRemove(result: Byte) = true
            override fun invoke(input: DataInputStream, result: Byte) {
                if (result.toInt() == SUCCESS) callback.complete(input.readParcelable(classLoader))
                else callback.completeExceptionally(input.readException(result))
            }
        }

        class Channel(server: RootServer, index: Long, classLoader: ClassLoader?,
                      private val channel: SendChannel<Parcelable?>) : Callback(server, index, classLoader) {
            val finish: CompletableDeferred<Unit> = CompletableDeferred()
            override fun cancel() = finish.cancel()
            override fun shouldRemove(result: Byte) = result.toInt() != SUCCESS
            override fun invoke(input: DataInputStream, result: Byte) {
                when (result.toInt()) {
                    // the channel we are supporting should never block
                    SUCCESS -> check(try {
                        channel.offer(input.readParcelable(classLoader))
                    } catch (closed: Throwable) {
                        active = false
                        GlobalScope.launch(Dispatchers.Unconfined) { sendClosed() }
                        finish.completeExceptionally(closed)
                        return
                    })
                    CHANNEL_CONSUMED -> finish.complete(Unit)
                    else -> finish.completeExceptionally(input.readException(result))
                }
            }
        }
    }

    private lateinit var process: Process
    /**
     * Thread safety: needs to be protected by mutex.
     */
    private lateinit var output: DataOutputStream

    @Volatile
    var active = false
    private var counter = 0L
    private lateinit var callbackListenerExit: Deferred<Unit>
    private val callbackLookup = LongSparseArray<Callback>()
    private val mutex = Mutex()

    /**
     * If we encountered unexpected output from stderr during initialization, its content will be stored here.
     *
     * It is advised to read this after initializing the instance.
     */
    fun readUnexpectedStderr(): String? {
        if (!this::process.isInitialized) return null
        var available = process.errorStream.available()
        return if (available <= 0) null else String(ByteArrayOutputStream().apply {
            while (available > 0) {
                val bytes = ByteArray(available)
                val len = process.errorStream.read(bytes)
                if (len < 0) throw EOFException()   // should not happen
                write(bytes, 0, len)
                available = process.errorStream.available()
            }
        }.toByteArray())
    }

    private fun BufferedReader.lookForToken(token: String) {
        while (true) {
            val line = readLine() ?: throw EOFException()
            if (line.endsWith(token)) {
                val extraLength = line.length - token.length
                if (extraLength > 0) warnLogger(line.substring(0, extraLength))
                break
            }
            warnLogger(line)
        }
    }
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun doInit(context: Context, niceName: String) {
        val init = GlobalScope.async {
            try {
                process = ProcessBuilder("su").start()
                val token1 = UUID.randomUUID().toString()
                val writer = DataOutputStream(process.outputStream.buffered())
                writer.writeBytes("echo $token1\n")
                writer.flush()
                val reader = process.inputStream.bufferedReader()
                reader.lookForToken(token1)
                if (isDebugEnabled) Log.d(TAG, "Root shell initialized")
                reader to writer
            } catch (e: Exception) {
                throw NoShellException(e)
            }
        }
        val token2 = UUID.randomUUID().toString()
        val appProcess = AppProcess.getAppProcess()
        val (relocated, setup) = if (Build.VERSION.SDK_INT < 29) {
            val persistence = File(context.codeCacheDir, ".librootkotlinx-uuid")
            val uuid = if (persistence.canRead()) persistence.readText() else UUID.randomUUID().toString().also {
                persistence.writeText(it)
            }
            // workaround Samsung's stupid kernel patch: https://github.com/Chainfire/librootjava/issues/19
            val path = "/dev/app_process_$uuid"
            path to "cp -n $appProcess $path && chmod 700 $path && "
        } else appProcess to ""
        val launchString = setup + RootJava.getLaunchString(
                context.packageCodePath + " exec",  // hack: plugging in exec
                RootServer::class.java.name, relocated, AppProcess.guessIfAppProcessIs64Bits(appProcess),
                arrayOf("$token2\n"), niceName).let { result ->
            if (Build.VERSION.SDK_INT < 24) result
            // undo the patch on newer APIs to let linker do the work
            else result.replaceFirst(" LD_LIBRARY_PATH=", " __SUPPRESSED_LD_LIBRARY_PATH=")
        }
        val (reader, writer) = init.await()
        writer.writeBytes(launchString)
        writer.flush()
        reader.lookForToken(token2) // wait for ready signal
        output = writer
        require(!active)
        active = true
        if (isDebugEnabled) Log.d(TAG, "Root server initialized")
    }

    private fun callbackSpin() {
        val input = DataInputStream(process.inputStream.buffered())
        while (active) {
            val index = try {
                input.readLong()
            } catch (_: EOFException) {
                break
            }
            val result = input.readByte()
            val callback = mutex.synchronized {
                callbackLookup[index]!!.also {
                    if (it.shouldRemove(result).also { shouldRemove ->
                                Timber.i("$index($result) should remove $it: $shouldRemove")
                            }) {
                        callbackLookup.remove(index)
                        it.active = false
                    }
                }
            }
            if (isDebugEnabled) Log.d(TAG, "Received callback #$index: $result")
            callback(input, result)
        }
    }

    /**
     * Initialize a RootServer synchronously, can throw a lot of exceptions.
     *
     * @param context Any [Context] from the app.
     * @param niceName Name to call the rooted Java process.
     */
    suspend fun init(context: Context, niceName: String = "${context.packageName}:root") {
        val future = CompletableDeferred<Unit>()
        callbackListenerExit = GlobalScope.async(Dispatchers.IO) {
            try {
                doInit(context, niceName)
                future.complete(Unit)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
                return@async
            }
            val errorReader = async(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader().useLines { seq ->
                        for (line in seq) warnLogger(line)
                    }
                } catch (_: IOException) { }
            }
            try {
                callbackSpin()
                if (active) throw RemoteException("Root process exited unexpectedly")
            } catch (e: Throwable) {
                process.destroy()
                throw e
            } finally {
                if (isDebugEnabled) Log.d(TAG, "Waiting for exit")
                errorReader.await()
                process.waitFor()
                withContext(NonCancellable) { closeInternal(true) }
            }
        }
        future.await()
    }
    /**
     * Convenience function that initializes and also logs warnings to [Log].
     */
    suspend fun initAndroidLog(context: Context, niceName: String = "${context.packageName}:root") = try {
        init(context, niceName)
    } finally {
        readUnexpectedStderr()?.let { Log.e(TAG, it) }
    }

    /**
     * Caller should check for active.
     */
    private fun sendLocked(command: Parcelable) {
        output.writeParcelable(command)
        output.flush()
        if (isDebugEnabled) Log.d(TAG, "Sent #$counter: $command")
        counter++
    }

    suspend fun execute(command: RootCommandOneWay) = mutex.withLock { if (active) sendLocked(command) }
    @Throws(RemoteException::class)
    suspend inline fun <reified T : Parcelable?> execute(command: RootCommand<T>) =
        execute(command, T::class.java.classLoader)
    @Throws(RemoteException::class)
    suspend fun <T : Parcelable?> execute(command: RootCommand<T>, classLoader: ClassLoader?): T {
        val future = CompletableDeferred<T>()
        @Suppress("UNCHECKED_CAST")
        val callback = Callback.Ordinary(this, counter, classLoader, future as CompletableDeferred<Parcelable?>)
        mutex.withLock {
            if (active) {
                callbackLookup[counter] = callback
                sendLocked(command)
            } else future.cancel()
        }
        try {
            return future.await()
        } finally {
            if (callback.active) callback.sendClosed()
            callback.active = false
        }
    }

    @ExperimentalCoroutinesApi
    @Throws(RemoteException::class)
    inline fun <reified T : Parcelable?> create(command: RootCommandChannel<T>, scope: CoroutineScope) =
            create(command, scope, T::class.java.classLoader)
    @ExperimentalCoroutinesApi
    @Throws(RemoteException::class)
    fun <T : Parcelable?> create(command: RootCommandChannel<T>, scope: CoroutineScope,
                                 classLoader: ClassLoader?) = scope.produce<T>(
            capacity = command.capacity.also {
                when (it) {
                    Channel.UNLIMITED, Channel.CONFLATED -> { }
                    else -> throw IllegalArgumentException("Unsupported channel capacity $it")
                }
            }) {
        @Suppress("UNCHECKED_CAST")
        val callback = Callback.Channel(this@RootServer, counter, classLoader, this as SendChannel<Parcelable?>)
        mutex.withLock {
            if (active) {
                callbackLookup[counter] = callback
                sendLocked(command)
            } else callback.finish.cancel()
        }
        try {
            callback.finish.await()
        } finally {
            if (callback.active) callback.sendClosed()
            callback.active = false
        }
    }

    private suspend fun closeInternal(fromWorker: Boolean = false) = mutex.withLock {
        if (active) {
            active = false
            if (isDebugEnabled) Log.d(TAG, if (fromWorker) "Shutting down from worker" else "Shutting down from client")
            try {
                sendLocked(Shutdown())
                output.close()
                process.outputStream.close()
            } catch (e: IOException) {
                Log.i(TAG, "send Shutdown failed", e)
            }
            if (isDebugEnabled) Log.d(TAG, "Client closed")
        }
        if (fromWorker) {
            for (callback in callbackLookup.valueIterator()) callback.cancel()
            callbackLookup.clear()
        }
    }
    /**
     * Shutdown the instance gracefully.
     */
    suspend fun close() {
        closeInternal()
        callbackListenerExit.await()
    }

    companion object {
        /**
         * If set to true, debug information will be printed to logcat.
         */
        @JvmStatic
        var isDebugEnabled = false
            set(value) {
                field = value
                Logger.setDebugLogging(value)
            }

        private const val TAG = "RootServer"
        private const val SUCCESS = 0
        private const val EX_GENERIC = 1
        private const val EX_PARCELABLE = 2
        private const val EX_SERIALIZABLE = 4
        private const val CHANNEL_CONSUMED = 3

        private fun DataInputStream.readByteArray() = ByteArray(readInt()).also { readFully(it) }

        private inline fun <reified T : Parcelable> DataInputStream.readParcelable(
            classLoader: ClassLoader? = T::class.java.classLoader) = readByteArray().toParcelable<T>(classLoader)
        private fun DataOutputStream.writeParcelable(data: Parcelable?, parcelableFlags: Int = 0) {
            val bytes = data.toByteArray(parcelableFlags)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readSerializable(classLoader: ClassLoader?) =
                object : ObjectInputStream(ByteArrayInputStream(readByteArray())) {
                    override fun resolveClass(desc: ObjectStreamClass) = Class.forName(desc.name, false, classLoader)
                }.readObject()

        private inline fun <T> Mutex.synchronized(crossinline block: () -> T): T = runBlocking {
            withLock { block() }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "Uncaught exception from $thread", throwable)
                exitProcess(1)
            }
            rootMain(args)
            exitProcess(0)  // there might be other non-daemon threads
        }

        private fun DataOutputStream.pushThrowable(callback: Long, e: Throwable) {
            writeLong(callback)
            if (e is Parcelable) {
                writeByte(EX_PARCELABLE)
                writeParcelable(e)
            } else try {
                val bytes = ByteArrayOutputStream().apply {
                    ObjectOutputStream(this).use { it.writeObject(e) }
                }.toByteArray()
                writeByte(EX_SERIALIZABLE)
                writeInt(bytes.size)
                write(bytes)
            } catch (_: NotSerializableException) {
                writeByte(EX_GENERIC)
                writeUTF(StringWriter().also {
                    e.printStackTrace(PrintWriter(it))
                }.toString())
            }
            flush()
        }
        private fun DataOutputStream.pushResult(callback: Long, result: Parcelable?) {
            writeLong(callback)
            writeByte(SUCCESS)
            writeParcelable(result)
            flush()
        }

        private fun rootMain(args: Array<String>) {
            require(args.isNotEmpty())
            if (Build.VERSION.SDK_INT < 24) RootJava.restoreOriginalLdLibraryPath()
            val mainInitialized = CountDownLatch(1)
            val main = Thread({
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
                mainInitialized.countDown()
                Looper.loop()
            }, "main")
            main.start()
            val job = Job()
            val defaultWorker by lazy {
                mainInitialized.await()
                CoroutineScope(Dispatchers.Main.immediate + job)
            }
            val callbackWorker = newSingleThreadContext("callbackWorker")
            val cancellables = LongSparseArray<() -> Unit>()

            // thread safety: usage of output should be guarded by callbackWorker
            val output = DataOutputStream(System.out.buffered().apply {
                val writer = writer()
                writer.appendln(args[0])    // echo ready signal
                writer.flush()
            })
            // thread safety: usage of input should be in main thread
            val input = DataInputStream(System.`in`.buffered())
            var counter = 0L
            if (isDebugEnabled) Log.d(TAG, "Server entering main loop")
            loop@ while (true) {
                val command = try {
                    input.readParcelable<Parcelable>(RootServer::class.java.classLoader)
                } catch (_: EOFException) {
                    break
                }
                val callback = counter
                if (isDebugEnabled) Log.d(TAG, "Received #$callback: $command")
                when (command) {
                    is CancelCommand -> cancellables[command.index]?.invoke()
                    is RootCommandOneWay -> defaultWorker.launch {
                        try {
                            command.execute()
                        } catch (e: Throwable) {
                            Log.e(command.javaClass.simpleName, "Unexpected exception in RootCommandOneWay", e)
                        }
                    }
                    is RootCommand<*> -> {
                        val commandJob = Job()
                        cancellables[callback] = { commandJob.cancel() }
                        defaultWorker.launch(commandJob) {
                            val result = try {
                                val result = command.execute()
                                withContext(callbackWorker + NonCancellable) { output.pushResult(callback, result) }
                            } catch (e: Throwable) {
                                withContext(callbackWorker + NonCancellable) { output.pushThrowable(callback, e) }
                            } finally {
                                cancellables.remove(callback)
                            }
                        }
                    }
                    is RootCommandChannel<*> -> defaultWorker.launch {
                        try {
                            coroutineScope {
                                command.create(this).also {
                                    cancellables[callback] = { it.cancel() }
                                }.consumeEach { result ->
                                    withContext(callbackWorker) { output.pushResult(callback, result) }
                                }
                            }
                            @Suppress("BlockingMethodInNonBlockingContext")
                            withContext(callbackWorker + NonCancellable) {
                                output.writeByte(CHANNEL_CONSUMED)
                                output.writeLong(callback)
                                output.flush()
                            }
                        } catch (e: Throwable) {
                            withContext(callbackWorker + NonCancellable) { output.pushThrowable(callback, e) }
                        } finally {
                            cancellables.remove(callback)
                        }
                    }
                    is Shutdown -> break@loop
                    else -> throw IllegalArgumentException("Unrecognized input: $command")
                }
                counter++
            }
            job.cancel()
            if (isDebugEnabled) Log.d(TAG, "Clean up initiated before exit. Jobs: ${job.children.joinToString()}")
            if (runBlocking { withTimeoutOrNull(5000) { job.join() } } == null) {
                Log.w(TAG, "Clean up timeout: ${job.children.joinToString()}")
            } else if (isDebugEnabled) Log.d(TAG, "Clean up finished, exiting")
        }
    }
}
