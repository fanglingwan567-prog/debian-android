package app.birch.debian

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

internal class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val host = RuntimeHost(application)
    private val images = ImageStore(application.filesDir)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val current = MutableLiveData<SetupState>()
    val state: LiveData<SetupState> = current

    init { checkPermission() }

    // Only one operation can run. Results and progress from its single writer reach main in order.
    // A cleared model drops late callbacks, even when blocking I/O outlives interruption.
    private fun publish(next: SetupState) {
        main.post { if (!worker.isShutdown) current.value = next }
    }

    private fun runTask(busy: SetupState, failure: (String) -> SetupState, work: () -> SetupState) {
        current.value = busy
        worker.execute {
            val result = try { work() } catch (ex: Exception) { failure(ex.reason()) }
            publish(result)
        }
    }

    fun checkPermission() {
        val state = current.value
        if (state != null && state !is SetupState.PermissionFailed) return
        runTask(SetupState.Checking, SetupState::PermissionFailed) {
            resolveStartup(host.check(), images)
        }
    }

    fun loadCatalog(channel: DebianRegistry.Channel) {
        if (current.value !is SetupState.Download) return
        val choice = SetupState.Download(channel)
        runTask(SetupState.Catalog(channel), { choice.copy(error = it) }) {
            choice.copy(versions = DebianRegistry.catalog(channel))
        }
    }

    fun selectVersion(version: DebianRegistry.Version) {
        val choice = current.value as? SetupState.Download ?: return
        if (version in choice.versions) current.value = choice.copy(selected = version, error = null)
    }

    fun download() {
        val choice = current.value as? SetupState.Download ?: return
        val selected = choice.selected ?: return
        val transfer = SetupState.Transfer(choice.copy(error = null))
        runTask(transfer, { choice.copy(error = it) }) {
            val saved = DebianRegistry.download(selected, images) { received, total ->
                publish(transfer.copy(received = received, total = total))
            }
            SetupState.Install(saved, transfer.choice)
        }
    }

    fun reselect() {
        val ready = current.value as? SetupState.Install ?: return
        current.value = ready.choice
    }

    fun install() {
        val ready = current.value as? SetupState.Install ?: return
        val reading = getApplication<Application>().getString(R.string.install_reading_image)
        runTask(SetupState.Installing(reading), { ready.copy(error = it) }) {
            host.install(images, ready.image) { publish(SetupState.Installing(it)) }
            terminalAfterCleanup(images)
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
