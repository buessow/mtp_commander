package cc.buessow.androidmtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.buessow.androidmtp.ui.theme.AndroidMTPTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val ACTION_USB_PERMISSION = "cc.buessow.androidmtp.USB_PERMISSION"
private data class StorageEntry(val id: Int, val name: String)
private data class DeviceIdentity(val isGarmin: Boolean, val model: String?)
private data class FileEntry(val handle: Int, val name: String, val directory: Boolean, val size: Long, val modified: Long)
private data class Folder(val handle: Int, val name: String)
private enum class Source { DEVICES, PHONE, MTP }
private data class PaneState(
    val source: Source = Source.DEVICES,
    val deviceName: String? = null,
    val storage: StorageEntry? = null,
    val folders: List<Folder> = emptyList(),
    val phoneFolders: List<File> = emptyList(),
    val entries: List<CommanderItem> = emptyList(),
    val status: String = "Select a device to browse (read-only).",
    val busy: Boolean = false,
    val revision: Int = 0
)

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    // Every MtpDevice operation, including close, happens on this one worker.
    private val worker = Executors.newSingleThreadExecutor()
    // Sessions are owned exclusively by the worker; epochs invalidate queued IO per device.
    private val sessions = mutableMapOf<String, MtpDevice>()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val pendingDeviceNames = mutableSetOf<String>()

    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var identities by mutableStateOf<Map<String, DeviceIdentity>>(emptyMap())
    private var storages by mutableStateOf<Map<String, List<StorageEntry>>>(emptyMap())
    private var readyDevices by mutableStateOf<Set<String>>(emptySet())
    private var panes by mutableStateOf(listOf(PaneState(), PaneState()))
    private var activePane by mutableStateOf(0)
    private var showPhoneAccessExplanation by mutableStateOf(false)
    private val phoneRoot by lazy { Environment.getExternalStorageDirectory() }
    private val phoneLabel = listOf(Build.MANUFACTURER, Build.MODEL).map { it.trim() }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(" ").ifEmpty { "Phone" }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (!pendingDeviceNames.remove(device.deviceName)) return
                    if (panes.none { it.deviceName == device.deviceName }) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        usbManager.deviceList.containsKey(device.deviceName)) openDevice(device)
                    else failDevice(device.deviceName, "USB access denied or device disconnected. Select the device to retry.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    intent.usbDevice()?.let { device ->
                        identities = identities - device.deviceName
                        if (device.deviceName in readyDevices || device.deviceName in pendingDeviceNames ||
                            panes.any { it.deviceName == device.deviceName }) disconnect(device.deviceName)
                    }
                    refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, RECEIVER_NOT_EXPORTED)
        setContent {
            BackHandler(enabled = canBack(panes[activePane])) { goBack(activePane) }
            AndroidMTPTheme(darkTheme = false, dynamicColor = false) {
                CommanderScreen(
                    panes = panes.mapIndexed { index, pane -> paneData(index, pane) },
                    activePane = activePane, onActivate = { activePane = it },
                    onOpen = { index, item -> activePane = index; openItem(index, item) },
                    onNavigate = ::navigateTo, onAction = { index ->
                        if (panes[index].source == Source.PHONE) {
                            if (!phoneAccess()) showPhoneAccessExplanation = true else listPhoneFolder(index)
                        } else refreshDevices()
                    },
                    onRefresh = ::refreshDevices,
                    showPhoneAccessExplanation = showPhoneAccessExplanation,
                    onConfirmPhoneAccess = ::requestPhoneAccess,
                    onDismissPhoneAccess = { showPhoneAccessExplanation = false }
                )
            }
        }
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        if (phoneAccess()) panes.indices.filter { panes[it].source == Source.PHONE }.forEach(::listPhoneFolder)
        else panes.indices.filter { panes[it].source == Source.PHONE }.forEach { index ->
            update(index) { it.copy(entries = emptyList(), busy = false,
                status = "Grant all-files access to browse shared storage (read-only).", revision = it.revision + 1) }
        }
    }

    private fun update(index: Int, transform: (PaneState) -> PaneState) {
        panes = panes.toMutableList().also { it[index] = transform(it[index]) }
    }

    private fun paneData(index: Int, pane: PaneState): CommanderPane {
        val name = pane.deviceName?.let { identities[it]?.let(::deviceModelLabel)
            ?: devices.firstOrNull { device -> device.deviceName == it }?.productName ?: "MTP device" }
        val deviceStorages = storages[pane.deviceName].orEmpty()
        val entries = when (pane.source) {
            Source.DEVICES -> listOf(CommanderItem("local:phone", phoneLabel, true, -1, 0)) + devices.map { device ->
                CommanderItem(device.deviceName, identities[device.deviceName]?.let(::deviceModelLabel)
                    ?: device.productName ?: "MTP device", true, -1, 0)
            }
            Source.PHONE -> pane.entries
            Source.MTP -> if (pane.storage == null && !pane.busy) deviceStorages.map {
                CommanderItem(it.id.toString(), it.name, true, -1, 0)
            } else pane.entries
        }
        val path = when (pane.source) {
            Source.DEVICES -> listOf(CommanderCrumb("Devices", 0))
            Source.PHONE -> listOf(CommanderCrumb(phoneLabel, 0)) +
                pane.phoneFolders.drop(1).mapIndexed { i, folder -> CommanderCrumb(folder.name, i + 1) }
            Source.MTP -> listOf(CommanderCrumb(name ?: "MTP device", 0)) +
                (if (deviceStorages.size > 1 && pane.storage?.name != "Internal storage")
                    listOfNotNull(pane.storage?.let { CommanderCrumb(it.name, 1) }) else emptyList()) +
                pane.folders.mapIndexed { i, folder ->
                    CommanderCrumb(folder.name, i + 1 + if (deviceStorages.size > 1) 1 else 0)
                }
        }
        return CommanderPane(
            title = if (pane.source == Source.DEVICES) "Devices" else if (pane.source == Source.PHONE) phoneLabel else name ?: "MTP device",
            path = path, entries = entries, status = if (pane.source == Source.DEVICES && devices.isEmpty())
                "No MTP devices found. Check USB cable and file transfer mode, then refresh. The phone is available above."
                else pane.status,
            busy = pane.busy, canBack = canBack(pane),
            actionLabel = if (pane.source == Source.PHONE && !phoneAccess()) "Grant all-files access" else "Refresh",
            navigationKey = "$index:${pane.source}:${pane.deviceName}:${pane.storage?.id}:${pane.folders.map { it.handle }}:${pane.phoneFolders.map { it.path }}"
        )
    }

    private fun canBack(pane: PaneState) = pane.source != Source.DEVICES

    private fun navigateTo(index: Int, depth: Int) {
        val pane = panes[index]
        when (pane.source) {
            Source.DEVICES -> Unit
            Source.PHONE -> if (depth == 0) {
                update(index) { it.copy(phoneFolders = listOf(phoneRoot)) }
                listPhoneFolder(index)
            } else if (depth < pane.phoneFolders.size) {
                update(index) { it.copy(phoneFolders = it.phoneFolders.take(depth + 1)) }
                listPhoneFolder(index)
            }
            Source.MTP -> {
                val count = storages[pane.deviceName].orEmpty().size
                when {
                    depth == 0 -> showStorages(index)
                    count > 1 && depth == 1 -> if (pane.storage != null) {
                        update(index) { it.copy(folders = emptyList()) }
                        listMtpFolder(index)
                    }
                    pane.storage != null -> {
                        val folderCount = depth - 1 - if (count > 1) 1 else 0
                        if (folderCount in 0 until pane.folders.size) {
                            update(index) { it.copy(folders = it.folders.take(folderCount)) }
                            listMtpFolder(index)
                        }
                    }
                }
            }
        }
    }

    private fun bumpEpoch(name: String): Int = epochs.merge(name, 1, Int::plus)!!
    private fun phoneAccess() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun openItem(index: Int, item: CommanderItem) {
        val pane = panes[index]
        if (pane.busy || !item.directory) return
        when (pane.source) {
            Source.DEVICES -> if (item.id == "local:phone") {
                update(index) { PaneState(source = Source.PHONE, phoneFolders = listOf(phoneRoot), revision = it.revision + 1) }
                if (phoneAccess()) listPhoneFolder(index) else {
                    update(index) { it.copy(status = "Grant all-files access to browse shared storage (read-only).") }
                    showPhoneAccessExplanation = true
                }
            } else devices.firstOrNull { it.deviceName == item.id }?.let { selectDevice(index, it) }
            Source.PHONE -> {
                val folder = pane.phoneFolders.lastOrNull() ?: return
                val child = File(folder, item.name)
                if (child.path == item.id && isInPhoneStorage(child) && child.isDirectory) {
                    update(index) { it.copy(phoneFolders = it.phoneFolders + child) }
                    listPhoneFolder(index)
                }
            }
            Source.MTP -> if (pane.storage == null) {
                storages[pane.deviceName].orEmpty().firstOrNull { it.id.toString() == item.id }?.let { openStorage(index, it) }
            } else pane.entries.firstOrNull { it.id == item.id }?.let {
                val folder = Folder(it.id.toInt(), it.name)
                update(index) { state -> state.copy(folders = state.folders + folder) }
                listMtpFolder(index)
            }
        }
    }

    private fun goBack(index: Int) {
        val pane = panes[index]
        when (pane.source) {
            Source.DEVICES -> Unit
            Source.PHONE -> if (pane.phoneFolders.size <= 1) update(index) { PaneState(revision = it.revision + 1) }
                else {
                    update(index) { it.copy(phoneFolders = it.phoneFolders.dropLast(1)) }
                    listPhoneFolder(index)
                }
            Source.MTP -> when {
                pane.folders.isNotEmpty() -> {
                    update(index) { it.copy(folders = it.folders.dropLast(1)) }
                    listMtpFolder(index)
                }
                pane.storage != null && storages[pane.deviceName].orEmpty().size > 1 -> update(index) {
                    it.copy(storage = null, entries = emptyList(), busy = false,
                        status = "Select a storage.", revision = it.revision + 1)
                }
                else -> update(index) { PaneState(revision = it.revision + 1) }
            }
        }
    }

    private fun requestPhoneAccess() {
        showPhoneAccessExplanation = false
        if (phoneAccess()) {
            panes.indices.filter { panes[it].source == Source.PHONE }.forEach(::listPhoneFolder)
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            panes.indices.filter { panes[it].source == Source.PHONE }.forEach { index ->
                update(index) { it.copy(status = "All-files access requires Android 11 or later.") }
            }
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e: Exception) {
                panes.indices.filter { panes[it].source == Source.PHONE }.forEach { index ->
                    update(index) { it.copy(status = "Cannot open access Settings: ${e.message}") }
                }
            }
        }
    }

    private fun isInPhoneStorage(file: File): Boolean = try {
        val path = file.canonicalPath
        val root = phoneRoot.canonicalPath
        path == root || path.startsWith("$root${File.separator}")
    } catch (_: Exception) { false }

    private fun listPhoneFolder(index: Int) {
        val pane = panes[index]
        if (pane.source != Source.PHONE) return
        if (!phoneAccess()) {
            update(index) { it.copy(entries = emptyList(), busy = false, revision = it.revision + 1,
                status = "Grant all-files access to browse shared storage (read-only).") }
            return
        }
        val folder = pane.phoneFolders.last()
        val revision = pane.revision + 1
        update(index) { it.copy(revision = revision, busy = true, entries = emptyList(), status = "Reading phone folder…") }
        worker.execute {
            val result = try {
                check(phoneAccess()) { "All-files access was revoked" }
                check(isInPhoneStorage(folder)) { "Folder is outside shared storage" }
                val children = folder.listFiles() ?: error("Folder is inaccessible")
                Result.success(children.filter(::isInPhoneStorage).map { file ->
                    CommanderItem(file.path, file.name, file.isDirectory,
                        if (file.isDirectory) -1 else file.length(), file.lastModified())
                }.sortedWith(compareBy<CommanderItem> { !it.directory }.thenBy { it.name.lowercase() }))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (!isDestroyed && panes[index].revision == revision && panes[index].source == Source.PHONE) {
                    if (!phoneAccess()) update(index) { it.copy(entries = emptyList(), busy = false,
                        status = "All-files access revoked. Grant access to browse shared storage.") }
                    else update(index) { it.copy(entries = result.getOrNull() ?: emptyList(), busy = false,
                        status = result.fold({ entries -> if (entries.isEmpty()) "Phone folder is empty."
                            else "${entries.size} phone items · read-only" }, { "Cannot read phone folder: ${it.message}" })) }
                }
            }
        }
    }

    private fun refreshDevices() {
        devices = usbManager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any {
                val usbInterface = device.getInterface(it)
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                    usbInterface.name?.contains("MTP", ignoreCase = true) == true
            }
        }.sortedBy { it.deviceName }
        val connected = devices.mapTo(mutableSetOf()) { it.deviceName }
        identities = identities.filterKeys { it in connected }
        val missing = (panes.mapNotNull { it.deviceName } + readyDevices + pendingDeviceNames)
            .filter { it !in connected }.toSet()
        missing.forEach(::disconnect)
    }

    private fun selectDevice(index: Int, device: UsbDevice) {
        if (!usbManager.deviceList.containsKey(device.deviceName)) { refreshDevices(); return }
        update(index) { PaneState(source = Source.MTP, deviceName = device.deviceName,
            busy = true, status = "Connecting…", revision = it.revision + 1) }
        if (device.deviceName in readyDevices) {
            showStorages(index)
            return
        }
        if (device.deviceName in pendingDeviceNames) return
        if (usbManager.hasPermission(device)) openDevice(device)
        else {
            pendingDeviceNames += device.deviceName
            val permissionIntent = PendingIntent.getBroadcast(this, device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                    .setData(Uri.parse("mtp-permission:${device.deviceName}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            try { usbManager.requestPermission(device, permissionIntent) }
            catch (e: Exception) {
                pendingDeviceNames.remove(device.deviceName)
                failDevice(device.deviceName, "Permission request failed: ${e.message}. Select the device to retry.")
            }
        }
    }

    private fun openDevice(device: UsbDevice) {
        val name = device.deviceName
        val epoch = bumpEpoch(name)
        worker.execute {
            if (epoch != epochs[name]) return@execute
            try {
                sessions.remove(name)?.close()
                val connection = usbManager.openDevice(device) ?: error("Could not open USB device")
                val session = MtpDevice(device)
                if (!session.open(connection)) {
                    connection.close()
                    error("Device did not accept an MTP connection")
                }
                if (epoch != epochs[name]) { session.close(); return@execute }
                sessions[name] = session
                val found = session.storageIds?.map { id ->
                    StorageEntry(id, session.getStorageInfo(id)?.description?.takeIf { it.isNotBlank() } ?: "Storage $id")
                } ?: emptyList()
                val model = session.deviceInfo?.model?.trim()?.takeIf { it.isNotEmpty() }
                val garmin = try {
                    found.any { storage ->
                        session.getObjectHandles(storage.id, 0, -1)?.any { handle ->
                            session.getObjectInfo(handle)?.let { info ->
                                info.parent == 0 && info.format == MtpConstants.FORMAT_ASSOCIATION &&
                                    info.name.equals("GARMIN", ignoreCase = true)
                            } == true
                        } == true
                    }
                } catch (_: Exception) { false }
                runOnUiThread {
                    if (epoch == epochs[name] && !isDestroyed) {
                        identities = identities + (name to DeviceIdentity(garmin, model))
                        storages = storages + (name to found)
                        readyDevices = readyDevices + name
                        panes.indices.filter { panes[it].deviceName == device.deviceName }.forEach(::showStorages)
                    }
                }
            } catch (e: Exception) {
                sessions.remove(name)?.close()
                runOnUiThread {
                    if (epoch == epochs[name] && !isDestroyed) {
                        readyDevices = readyDevices - name
                        storages = storages - name
                        failDevice(name, "MTP connection failed: ${e.message}. Select the device to retry.")
                    }
                }
            }
        }
    }

    private fun failDevice(name: String, message: String) {
        panes.indices.filter { panes[it].deviceName == name }.forEach { index ->
            update(index) { PaneState(status = message, revision = it.revision + 1) }
        }
    }

    private fun showStorages(index: Int) {
        val deviceStorages = storages[panes[index].deviceName].orEmpty()
        if (deviceStorages.size == 1) openStorage(index, deviceStorages.single())
        else update(index) { it.copy(storage = null, folders = emptyList(), entries = emptyList(), busy = false,
            revision = it.revision + 1,
            status = if (deviceStorages.isEmpty()) "Connected, but no MTP storages were reported."
                else "Select a storage to browse (read-only).") }
    }

    private fun openStorage(index: Int, storage: StorageEntry) {
        update(index) { it.copy(storage = storage, folders = emptyList()) }
        listMtpFolder(index)
    }

    private fun listMtpFolder(index: Int) {
        val pane = panes[index]
        val storage = pane.storage ?: return
        val name = pane.deviceName ?: return
        val path = pane.folders
        val epoch = epochs[name]
        val revision = pane.revision + 1
        update(index) { it.copy(revision = revision, entries = emptyList(), busy = true, status = "Listing files…") }
        worker.execute {
            val result = try {
                check(epoch == epochs[name]) { "Device is no longer connected" }
                val session = sessions[name] ?: error("MTP session is unavailable")
                // -1 is the storage root. 0 would recursively request every object.
                val parent = path.lastOrNull()?.handle ?: -1
                Result.success(session.getObjectHandles(storage.id, 0, parent)?.toList()?.mapNotNull { handle ->
                    session.getObjectInfo(handle)?.takeIf { it.parent == if (parent == -1) 0 else parent }?.let { info ->
                        FileEntry(handle, info.name ?: "Unnamed", info.format == MtpConstants.FORMAT_ASSOCIATION,
                            info.compressedSizeLong, info.dateModified)
                    }
                }?.sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name.lowercase() })
                    ?: error("Could not list this folder"))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (!isDestroyed && epoch == epochs[name] && panes[index].revision == revision &&
                    panes[index].deviceName == name) {
                    update(index) { it.copy(busy = false,
                        entries = result.getOrNull()?.map { entry -> CommanderItem(entry.handle.toString(), entry.name,
                            entry.directory, entry.size, entry.modified) } ?: emptyList(),
                        status = result.fold({ entries -> if (entries.isEmpty()) "Folder is empty."
                            else "${entries.size} items · read-only" }, { "Could not list files: ${it.message}" })) }
                }
            }
        }
    }

    private fun disconnect(name: String) {
        pendingDeviceNames.remove(name)
        panes.indices.filter { panes[it].deviceName == name }.forEach { index ->
            update(index) { PaneState(status = "Device disconnected. Reconnect and select it again.",
                revision = it.revision + 1) }
        }
        bumpEpoch(name)
        readyDevices = readyDevices - name
        storages = storages - name
        worker.execute { sessions.remove(name)?.close() }
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        (readyDevices + panes.mapNotNull { it.deviceName }).forEach(::bumpEpoch)
        worker.execute { sessions.values.forEach { it.close() }; sessions.clear() }
        worker.shutdown()
        super.onDestroy()
    }
}

private fun deviceModelLabel(identity: DeviceIdentity): String =
    identity.model?.let { model ->
        if (identity.isGarmin && !model.startsWith("Garmin", ignoreCase = true)) "Garmin $model" else model
    } ?: if (identity.isGarmin) "Garmin device" else "MTP device"

private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
