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
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
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
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray

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
    val status: String = "Select a device to browse.",
    val busy: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val cursor: String? = null,
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
    private var prompt by mutableStateOf<ActionPrompt?>(null)
    private var pendingAction: PendingAction? = null
    private data class PendingAction(val action: FileAction, val source: PaneState, val destination: PaneState?,
        val items: List<CommanderItem>)
    private data class AutoCopyJob(val sourcePath: String, val deviceName: String,
        val storageId: Int, val storageName: String, val folderNames: List<String>,
        val vendorId: Int = 0, val productId: Int = 0)
    private var startupCopy: AutoCopyJob? = null
    private var startupCopyStarted = false
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
                        usbManager.deviceList.containsKey(device.deviceName)) {
                        val job = startupCopy?.takeIf { startupCopyStarted &&
                            it.deviceName == device.deviceName && device.vendorId == 0x091e }
                        if (job == null) openDevice(device)
                        else {
                            startupCopy = null
                            retryAutoCopyRaw(job, device)
                        }
                    }
                    else failDevice(device.deviceName, "USB access denied or device disconnected. Select the device to retry.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    startupCopy = readAutoCopyJob()
                    startupCopyStarted = false
                    refreshDevices()
                }
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
        startupCopy = readAutoCopyJob()
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
                        if (panes[index].source == Source.PHONE && !phoneAccess())
                            showPhoneAccessExplanation = true
                        else refreshVisiblePanes()
                    },
                    onRefresh = ::refreshVisiblePanes,
                    showPhoneAccessExplanation = showPhoneAccessExplanation,
                    onConfirmPhoneAccess = ::requestPhoneAccess,
                    onDismissPhoneAccess = { showPhoneAccessExplanation = false },
                    prompt = prompt, onDismissPrompt = { prompt = null; pendingAction = null },
                    onConfirmPrompt = ::confirmAction, onFileAction = ::prepareAction,
                    onToggle = { index, id -> update(index) { pane -> pane.copy(
                        selectedIds = if (id in pane.selectedIds) pane.selectedIds - id else pane.selectedIds + id) } },
                    onSelectAll = { val index = activePane; update(index) { pane -> pane.copy(
                        selectedIds = if (pane.selectedIds.size == pane.entries.size) emptySet()
                        else pane.entries.mapTo(mutableSetOf()) { it.id }) } },
                    onCursor = { index, id -> update(index) { it.copy(cursor = id) } }
                )
            }
        }
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        if (phoneAccess()) panes.indices.filter { panes[it].source == Source.PHONE && !panes[it].busy }
            .forEach { listPhoneFolder(it) }
        else panes.indices.filter { panes[it].source == Source.PHONE }.forEach { index ->
            update(index) { it.copy(entries = emptyList(), busy = false,
                status = "Grant all-files access to browse shared storage (read-only).", revision = it.revision + 1) }
        }
    }

    private fun update(index: Int, transform: (PaneState) -> PaneState) {
        panes = panes.toMutableList().also { it[index] = transform(it[index]) }
    }

    private fun readAutoCopyJob(): AutoCopyJob? {
        return try {
            val preferences = getSharedPreferences("retry_copy", MODE_PRIVATE)
            val sourcePath = preferences.getString("source", null) ?: return null
            val deviceName = preferences.getString("device", null) ?: return null
            val storageName = preferences.getString("storage_name", null) ?: return null
            val folders = JSONArray(preferences.getString("folders", "[]"))
            AutoCopyJob(sourcePath, deviceName, preferences.getInt("storage_id", -1), storageName,
                (0 until folders.length()).map { folders.getString(it) },
                preferences.getInt("vendor_id", 0), preferences.getInt("product_id", 0))
        } catch (_: Exception) { null }
    }

    private fun saveAutoCopyJob(job: AutoCopyJob) {
        getSharedPreferences("retry_copy", MODE_PRIVATE).edit()
            .putString("source", job.sourcePath).putString("device", job.deviceName)
            .putInt("storage_id", job.storageId).putString("storage_name", job.storageName)
            .putString("folders", JSONArray(job.folderNames).toString())
            .putInt("vendor_id", job.vendorId).putInt("product_id", job.productId).apply()
    }

    private fun clearAutoCopyJob() {
        getSharedPreferences("retry_copy", MODE_PRIVATE).edit().clear().apply()
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
            Source.PHONE -> listOf(CommanderCrumb("Devices", -1), CommanderCrumb(phoneLabel, 0)) +
                pane.phoneFolders.drop(1).mapIndexed { i, folder -> CommanderCrumb(folder.name, i + 1) }
            Source.MTP -> listOf(CommanderCrumb("Devices", -1), CommanderCrumb(name ?: "MTP device", 0)) +
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
            navigationKey = "$index:${pane.source}:${pane.deviceName}:${pane.storage?.id}:${pane.folders.map { it.handle }}:${pane.phoneFolders.map { it.path }}",
            selectedIds = pane.selectedIds, cursor = pane.cursor
        )
    }

    private fun canBack(pane: PaneState) = pane.source != Source.DEVICES

    private fun navigateTo(index: Int, depth: Int) {
        val pane = panes[index]
        if (pane.busy) return
        when (pane.source) {
            Source.DEVICES -> Unit
            Source.PHONE -> if (depth < 0) {
                update(index) { PaneState(revision = it.revision + 1) }
            } else if (depth == 0) {
                update(index) { it.copy(phoneFolders = listOf(phoneRoot)) }
                listPhoneFolder(index)
            } else if (depth < pane.phoneFolders.size) {
                update(index) { it.copy(phoneFolders = it.phoneFolders.take(depth + 1)) }
                listPhoneFolder(index)
            }
            Source.MTP -> {
                val count = storages[pane.deviceName].orEmpty().size
                when {
                    depth < 0 -> update(index) { PaneState(revision = it.revision + 1) }
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
        if (pane.busy) return
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
            panes.indices.filter { panes[it].source == Source.PHONE }.forEach { listPhoneFolder(it) }
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

    private fun listPhoneFolder(index: Int, completionStatus: String? = null) {
        val pane = panes[index]
        if (pane.source != Source.PHONE) return
        if (!phoneAccess()) {
            update(index) { it.copy(entries = emptyList(), busy = false, revision = it.revision + 1,
                status = "Grant all-files access to browse shared storage (read-only).") }
            return
        }
        val folder = pane.phoneFolders.last()
        val revision = pane.revision + 1
        update(index) { it.copy(revision = revision, busy = true, entries = emptyList(),
            selectedIds = emptySet(), cursor = null, status = "Reading phone folder…") }
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
                        status = result.fold({ entries -> completionStatus ?: if (entries.isEmpty()) "Phone folder is empty."
                            else "${entries.size} phone items" }, { "Cannot read phone folder: ${it.message}" })) }
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
        val job = startupCopy
        if (!startupCopyStarted && job != null) {
            val device = devices.firstOrNull { it.deviceName == job.deviceName }
                ?: devices.filter { it.vendorId == job.vendorId && it.productId == job.productId &&
                    job.vendorId != 0 }.singleOrNull()
                ?: devices.filter { it.vendorId == 0x091e }.singleOrNull()
                    ?.takeIf { job.vendorId == 0 && job.folderNames.any { folder ->
                        folder.equals("GARMIN", ignoreCase = true) } }
            device?.let {
                startupCopy = job.copy(deviceName = device.deviceName)
                startupCopyStarted = true
                if (device.vendorId == 0x091e && usbManager.hasPermission(device)) {
                    startupCopy = null
                    retryAutoCopyRaw(job.copy(deviceName = device.deviceName), device)
                } else selectDevice(0, device)
            }
        }
    }

    private fun refreshVisiblePanes() {
        refreshDevices()
        panes.indices.forEach { index ->
            val pane = panes[index]
            if (pane.busy) return@forEach
            when (pane.source) {
                Source.DEVICES -> Unit
                Source.PHONE -> listPhoneFolder(index)
                Source.MTP -> if (pane.storage != null) listMtpFolder(index)
                    else pane.deviceName?.let { name ->
                        devices.firstOrNull { it.deviceName == name }?.let { device ->
                            if (name in readyDevices) showStorages(index) else selectDevice(index, device)
                        }
                    }
            }
        }
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
                android.util.Log.i("AndroidMTP", "MTP session opened for ${device.vendorId}:${device.productId}")
                val found = session.storageIds?.map { id ->
                    StorageEntry(id, session.getStorageInfo(id)?.description?.takeIf { it.isNotBlank() } ?: "Storage $id")
                } ?: emptyList()
                android.util.Log.i("AndroidMTP", "MTP storages: ${found.map { it.id }}")
                val garmin = device.vendorId == 0x091e
                val model = device.productName?.trim()?.takeIf { it.isNotEmpty() }
                runOnUiThread {
                    if (epoch == epochs[name] && !isDestroyed) {
                        identities = identities + (name to DeviceIdentity(garmin, model))
                        storages = storages + (name to found)
                        readyDevices = readyDevices + name
                        panes.indices.filter { panes[it].deviceName == device.deviceName }.forEach(::showStorages)
                        startupCopy?.takeIf { startupCopyStarted && it.deviceName == name }?.let { job ->
                            startupCopy = null
                            retryAutoCopy(job)
                        }
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

    private fun retryAutoCopy(job: AutoCopyJob) {
        val epoch = epochs[job.deviceName]
        update(0) { it.copy(busy = true, revision = it.revision + 1,
            status = "Retrying last failed copy to watch…") }
        worker.execute {
            val result = runCatching {
                check(epoch == epochs[job.deviceName]) { "Watch disconnected" }
                check(phoneAccess()) { "Phone access revoked" }
                val file = File(job.sourcePath)
                check(file.isFile && isInPhoneStorage(file) &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Source file is unavailable" }
                val session = sessions[job.deviceName] ?: error("MTP device disconnected")
                val ids = session.storageIds
                if (ids == null || ids.isEmpty()) {
                    sessions.remove(job.deviceName)?.close()
                    val device = usbManager.deviceList[job.deviceName] ?: error("Watch disconnected")
                    android.util.Log.i("AndroidMTP", "Trying raw Garmin MTP upload after storage listing failed")
                    RawMtpUploader.uploadToPath(usbManager, device, job.storageId,
                        job.folderNames, file)
                    return@runCatching null
                }
                val storageId = ids.firstOrNull { it == job.storageId }
                    ?: ids.firstOrNull { session.getStorageInfo(it)?.description == job.storageName }
                    ?: error("Watch storage changed")
                val storage = StorageEntry(storageId,
                    session.getStorageInfo(storageId)?.description ?: job.storageName)
                var parent = -1
                val folders = job.folderNames.map { folderName ->
                    val expectedParent = if (parent == -1) 0 else parent
                    val folder = mtpHandles(job.deviceName, storageId, parent).asSequence().mapNotNull { handle ->
                        sessions[job.deviceName]?.getObjectInfo(handle)
                            ?.takeIf { it.parent == expectedParent && it.format == MtpConstants.FORMAT_ASSOCIATION &&
                                it.name == folderName }
                    }.firstOrNull() ?: error("Watch folder $folderName is unavailable")
                    parent = folder.objectHandle
                    Folder(parent, folderName)
                }
                val target = PaneState(source = Source.MTP, deviceName = job.deviceName,
                    storage = storage, folders = folders)
                val source = PaneState(source = Source.PHONE, phoneFolders = listOf(file.parentFile!!))
                copyEntry(source, target, CommanderItem(file.path, file.name, false,
                    file.length(), file.lastModified()))
                check(epoch == epochs[job.deviceName]) { "Watch disconnected" }
                target
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold({ target ->
                    android.util.Log.i("AndroidMTP", "Automatic copy completed: ${File(job.sourcePath).name}")
                    clearAutoCopyJob()
                    if (panes[0].deviceName == job.deviceName) {
                        if (target == null) update(0) { it.copy(busy = false,
                            status = "Automatic copy completed: ${File(job.sourcePath).name}") }
                        else {
                            update(0) { target.copy(revision = it.revision + 1) }
                            listMtpFolder(0, "Automatic copy completed: ${File(job.sourcePath).name}")
                        }
                    }
                }, { error ->
                    android.util.Log.e("AndroidMTP", "Automatic copy failed", error)
                    if (panes[0].deviceName == job.deviceName)
                        update(0) { it.copy(busy = false,
                            status = "Automatic copy failed: ${error.message}") }
                })
            }
        }
    }

    private fun retryAutoCopyRaw(job: AutoCopyJob, device: UsbDevice) {
        val epoch = bumpEpoch(device.deviceName)
        update(0) { PaneState(source = Source.MTP, deviceName = device.deviceName,
            busy = true, status = "Retrying last failed copy to watch…",
            revision = it.revision + 1) }
        worker.execute {
            val result = runCatching {
                check(phoneAccess()) { "Phone access revoked" }
                val file = File(job.sourcePath)
                check(file.isFile && isInPhoneStorage(file) &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Source file is unavailable" }
                check(usbManager.deviceList.containsKey(device.deviceName)) { "Watch disconnected" }
                android.util.Log.i("AndroidMTP", "Trying raw Garmin MTP upload for ${file.name}")
                RawMtpUploader.uploadToPath(usbManager, device, job.storageId,
                    job.folderNames, file)
                check(usbManager.deviceList.containsKey(device.deviceName)) { "Watch disconnected" }
            }
            runOnUiThread {
                if (isDestroyed || epoch != epochs[device.deviceName]) return@runOnUiThread
                result.fold({
                    android.util.Log.i("AndroidMTP", "Automatic copy completed: ${File(job.sourcePath).name}")
                    clearAutoCopyJob()
                    update(0) { it.copy(busy = false,
                        status = "Automatic copy completed: ${File(job.sourcePath).name}") }
                }, { error ->
                    android.util.Log.e("AndroidMTP", "Automatic copy failed", error)
                    update(0) { it.copy(busy = false,
                        status = "Automatic copy failed: ${error.message}") }
                })
            }
        }
    }

    private fun showStorages(index: Int) {
        val deviceStorages = storages[panes[index].deviceName].orEmpty()
        if (deviceStorages.size == 1) openStorage(index, deviceStorages.single())
        else update(index) { it.copy(storage = null, folders = emptyList(), entries = emptyList(), busy = false,
            revision = it.revision + 1,
            status = if (deviceStorages.isEmpty()) "Connected, but no MTP storages were reported."
                else "Select a storage to browse.") }
    }

    private fun openStorage(index: Int, storage: StorageEntry) {
        update(index) { it.copy(storage = storage, folders = emptyList()) }
        listMtpFolder(index)
    }

    private fun listMtpFolder(index: Int, completionStatus: String? = null) {
        val pane = panes[index]
        val storage = pane.storage ?: return
        val name = pane.deviceName ?: return
        val path = pane.folders
        val epoch = epochs[name]
        val revision = pane.revision + 1
        update(index) { it.copy(revision = revision, busy = true, status = "Listing files…") }
        worker.execute {
            val result = try {
                check(epoch == epochs[name]) { "Device is no longer connected" }
                val parent = path.lastOrNull()?.handle ?: -1
                Result.success(mtpHandles(name, storage.id, parent).toList().mapNotNull { handle ->
                    sessions[name]?.getObjectInfo(handle)?.takeIf { it.parent == if (parent == -1) 0 else parent }?.let { info ->
                        FileEntry(handle, info.name ?: "Unnamed", info.format == MtpConstants.FORMAT_ASSOCIATION,
                            info.compressedSizeLong, info.dateModified)
                    }
                }.sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name.lowercase() }))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (!isDestroyed && epoch == epochs[name] && panes[index].revision == revision &&
                    panes[index].deviceName == name) {
                    update(index) { it.copy(busy = false,
                        entries = result.getOrNull()?.map { entry -> CommanderItem(entry.handle.toString(), entry.name,
                            entry.directory, entry.size, entry.modified) } ?: it.entries,
                        selectedIds = if (result.isSuccess) emptySet() else it.selectedIds,
                        cursor = if (result.isSuccess) null else it.cursor,
                        status = result.fold({ entries -> completionStatus ?: if (entries.isEmpty()) "Folder is empty."
                            else "${entries.size} items" }, { "Could not list files: ${it.message}" })) }
                }
            }
        }
    }

    private fun prepareAction(action: FileAction) {
        val source = panes[activePane]
        val destination = if (action == FileAction.COPY) panes[1 - activePane] else null
        if (source.busy || destination?.busy == true || source.source == Source.DEVICES ||
            source.source == Source.MTP && source.storage == null ||
            destination != null && (destination.source == Source.DEVICES || destination.source == Source.MTP && destination.storage == null)) {
            update(activePane) { it.copy(status = "Open a writable folder in each required pane first.") }; return
        }
        val items = if (source.selectedIds.isNotEmpty()) source.entries.filter { it.id in source.selectedIds }
            else source.entries.filter { it.id == source.cursor }
        if (action == FileAction.COPY && source.source == Source.MTP && destination?.source == Source.PHONE &&
            !phoneAccess()) {
            update(activePane) { it.copy(status = "Grant all-files access to copy to the phone.") }; return
        }
        if (action == FileAction.COPY && destination?.source == Source.PHONE && !phoneAccess() ||
            source.source == Source.PHONE && !phoneAccess()) {
            update(activePane) { it.copy(status = "Grant all-files access before modifying phone storage.") }; return
        }
        if (action != FileAction.MKDIR && items.isEmpty()) {
            update(activePane) { it.copy(status = "Mark items or tap one item first.") }; return
        }
        if (action == FileAction.RENAME && items.size != 1) {
            update(activePane) { it.copy(status = "Rename requires exactly one item.") }; return
        }
        if (action == FileAction.RENAME && source.source == Source.MTP) {
            update(activePane) { it.copy(status = "MTP rename is not supported by Android's public MtpDevice API.") }; return
        }
        if (action == FileAction.COPY && source.source == Source.MTP && destination?.source == Source.MTP) {
            update(activePane) { it.copy(status = "Direct MTP-to-MTP copy is unavailable; copy to phone first.") }; return
        }
        pendingAction = PendingAction(action, source, destination, items)
        if (action == FileAction.COPY) {
            confirmAction("")
            return
        }
        val names = items.take(3).joinToString(", ") { it.name } + if (items.size > 3) "…" else ""
        prompt = ActionPrompt(action, action.name.lowercase().replaceFirstChar { it.uppercase() }, when (action) {
            FileAction.COPY -> error("Copy starts without confirmation")
            FileAction.DELETE -> "Permanently delete ${items.size} item(s) ($names)? Folders and all contents will be deleted. This cannot be undone."
            FileAction.RENAME -> "Rename ${items.single().name} on the phone?"
            FileAction.MKDIR -> "Create a new folder in this pane?"
        }, if (action == FileAction.RENAME) items.single().name else "")
    }

    private fun confirmAction(input: String) {
        val request = pendingAction ?: return
        prompt = null; pendingAction = null
        val sourceIndex = panes.indexOfFirst { it === request.source }
        if (sourceIndex < 0) return
        // Reject stale prompts if either pane has moved since the confirmation was prepared.
        if (request.destination != null && panes[1 - sourceIndex] != request.destination) {
            update(sourceIndex) { it.copy(status = "Folder changed; action cancelled. Try again.") }; return
        }
        if (request.action == FileAction.MKDIR || request.action == FileAction.RENAME) {
            if (!validName(input)) {
                update(sourceIndex) { it.copy(status = "Invalid name: use a single non-empty filename without / or \\.") }; return
            }
        }
        val targetIndex = if (request.destination != null) 1 - sourceIndex else sourceIndex
        val source = request.source
        val destination = request.destination
        val sourceEpoch = source.deviceName?.let { epochs[it] }
        val targetEpoch = destination?.deviceName?.let { epochs[it] }
        update(sourceIndex) { it.copy(busy = true, status = "${request.action} in progress…") }
        if (targetIndex != sourceIndex) update(targetIndex) { it.copy(busy = true, status = "Copy in progress…") }
        worker.execute {
            var done = 0
            var failure: String? = null
            var failedItem: CommanderItem? = null
            try {
                fun checkConnection() {
                    check(source.deviceName == null || sourceEpoch == epochs[source.deviceName]) { "Source disconnected" }
                    check(destination?.deviceName == null || targetEpoch == epochs[destination.deviceName]) { "Destination disconnected" }
                }
                checkConnection()
                when (request.action) {
                    FileAction.MKDIR -> {
                        if (source.source == Source.PHONE) {
                            check(phoneAccess()) { "Phone access revoked" }
                            check(phoneChild(source, input).mkdir()) { "Could not create folder" }
                        } else createMtpFolder(source, input)
                        done++
                    }
                    FileAction.RENAME -> {
                        check(phoneAccess()) { "Phone access revoked" }
                        val old = phoneItem(source, request.items.single())
                        val new = phoneChild(source, input)
                        check(old.renameTo(new)) { "Rename failed" }
                        done++
                    }
                    else -> for (item in request.items) {
                        try {
                            checkConnection()
                            if (request.action == FileAction.DELETE) deleteEntry(source, item)
                            else {
                                var lastProgress = 0L
                                copyEntry(source, destination!!, item) { copied, total ->
                                    if (source.source == Source.MTP &&
                                        (copied == total || copied - lastProgress >= 8L * 1024 * 1024)) {
                                        lastProgress = copied
                                        runOnUiThread {
                                            if (!isDestroyed && panes[sourceIndex].busy &&
                                                panes[sourceIndex].deviceName == source.deviceName)
                                                update(sourceIndex) { it.copy(
                                                    status = "Copying ${item.name}: $copied / $total bytes") }
                                        }
                                    }
                                }
                            }
                            done++
                        } catch (e: Exception) {
                            failedItem = item
                            failure = "${e.message} (${item.name})"
                            break
                        }
                    }
                }
            } catch (e: Exception) { failure = e.message ?: "Operation failed" }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (request.action == FileAction.COPY && source.source == Source.PHONE &&
                    destination?.source == Source.MTP) {
                    val failed = failedItem
                    val usbDevice = usbManager.deviceList[destination.deviceName]
                    if (failed != null && !failed.directory && failure != null) saveAutoCopyJob(AutoCopyJob(
                        failed.id, destination.deviceName!!, destination.storage!!.id,
                        destination.storage.name, destination.folders.map { it.name },
                        usbDevice?.vendorId ?: 0, usbDevice?.productId ?: 0))
                    else if (failure == null && request.items.any { item ->
                        readAutoCopyJob()?.let { it.sourcePath == item.id &&
                            it.deviceName == destination.deviceName } == true
                    }) clearAutoCopyJob()
                }
                val progress = "$done/${if (request.action == FileAction.MKDIR) 1 else request.items.size} completed"
                val status = failure?.let { "Stopped: $it · $progress" } ?: progress
                // Refresh only the same folders; never refresh a different destination after navigation.
                listOf(sourceIndex, targetIndex).distinct().forEach { index ->
                    val snapshot = if (index == sourceIndex) source else destination!!
                    if (panes[index].source == snapshot.source && panes[index].deviceName == snapshot.deviceName &&
                        panes[index].storage == snapshot.storage && panes[index].folders == snapshot.folders &&
                        panes[index].phoneFolders == snapshot.phoneFolders) {
                        update(index) { it.copy(busy = false, selectedIds = emptySet(), cursor = null, status = status) }
                        if (snapshot.source == Source.PHONE) listPhoneFolder(index, status)
                        else listMtpFolder(index, status)
                    }
                }
            }
        }
    }

    private fun validName(name: String) = name.isNotBlank() && name == name.trim() &&
        name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    private fun phoneChild(pane: PaneState, name: String): File {
        check(validName(name)) { "Invalid filename" }
        check(phoneAccess() && isInPhoneStorage(pane.phoneFolders.last()) &&
            !java.nio.file.Files.isSymbolicLink(pane.phoneFolders.last().toPath())) { "Phone access revoked or unsafe folder" }
        val child = File(pane.phoneFolders.last(), name)
        check(isInPhoneStorage(child) && !java.nio.file.Files.isSymbolicLink(child.toPath())) { "Unsafe destination" }
        check(!child.exists()) { "Destination already exists" }
        return child
    }

    private fun phoneItem(pane: PaneState, item: CommanderItem): File {
        val file = File(pane.phoneFolders.last(), item.name)
        check(file.path == item.id && isInPhoneStorage(file) && file.exists() &&
            !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Source changed or is a link" }
        return file
    }

    private fun mtpSession(pane: PaneState): MtpDevice = sessions[pane.deviceName] ?: error("MTP device disconnected")

    private fun mtpInfo(pane: PaneState, item: CommanderItem): MtpObjectInfo {
        val handle = item.id.toIntOrNull() ?: error("Invalid MTP object")
        val info = mtpSession(pane).getObjectInfo(handle) ?: error("MTP object disappeared")
        check(info.storageId == pane.storage?.id && info.parent == (pane.folders.lastOrNull()?.handle ?: 0) &&
            info.name == item.name) { "MTP object changed" }
        return info
    }

    private fun reopenMtpSession(name: String): MtpDevice {
        val device = usbManager.deviceList[name] ?: error("MTP device disconnected")
        check(usbManager.hasPermission(device)) { "USB permission was revoked" }
        sessions.remove(name)?.close()
        val connection = usbManager.openDevice(device) ?: error("Could not reopen USB device")
        val reopened = MtpDevice(device)
        if (!reopened.open(connection)) {
            connection.close()
            error("Could not reopen MTP session")
        }
        sessions[name] = reopened
        return reopened
    }

    // MTP may return null when its session goes stale after a previous transfer or a host rescan.
    // Reopen once before relying on a listing for either display or collision checks.
    private fun mtpHandles(name: String, storageId: Int, parent: Int): IntArray {
        val session = sessions[name] ?: error("MTP device disconnected")
        session.getObjectHandles(storageId, 0, parent)?.let { return it }
        return reopenMtpSession(name).getObjectHandles(storageId, 0, parent)
            ?: error("MTP device could not list this folder after reconnecting")
    }

    private fun sendMtpObjectInfo(pane: PaneState, info: MtpObjectInfo): MtpObjectInfo {
        mtpSession(pane).sendObjectInfo(info)?.let { return it }
        val name = pane.deviceName ?: error("MTP device disconnected")
        reopenMtpSession(name)
        check(!mtpExists(pane, info.name)) {
            "MTP created ${info.name} without confirming it. Refresh the folder before retrying"
        }
        // Some devices accept a new object's metadata only with a zero size;
        // sendObject supplies the actual byte count in the following request.
        val retryInfo = if (info.format == MtpConstants.FORMAT_ASSOCIATION) info
            else MtpObjectInfo.Builder(info).setCompressedSize(0).build()
        val garminImportHint = if (pane.folders.any { it.name.equals("GARMIN", ignoreCase = true) } &&
            pane.folders.lastOrNull()?.name?.equals("NewFiles", ignoreCase = true) != true &&
            (info.name.endsWith(".fit", ignoreCase = true) || info.name.endsWith(".gpx", ignoreCase = true)))
            "; try GARMIN/NewFiles" else ""
        return mtpSession(pane).sendObjectInfo(retryInfo)
            ?: error("MTP device refused ${if (info.format == MtpConstants.FORMAT_ASSOCIATION) "folder" else "file"} creation$garminImportHint")
    }

    private fun mtpExists(pane: PaneState, name: String): Boolean {
        val deviceName = pane.deviceName ?: error("MTP device disconnected")
        val parent = pane.folders.lastOrNull()?.handle ?: -1
        val handles = mtpHandles(deviceName, pane.storage!!.id, parent)
        return handles.any { sessions[deviceName]?.getObjectInfo(it)?.name == name }
    }

    private fun createMtpFolder(pane: PaneState, name: String): Int {
        check(!mtpExists(pane, name)) { "Destination already exists" }
        val info = MtpObjectInfo.Builder().setStorageId(pane.storage!!.id)
            .setParent(pane.folders.lastOrNull()?.handle ?: -1).setName(name)
            .setFormat(MtpConstants.FORMAT_ASSOCIATION).setAssociationType(1).build()
        return sendMtpObjectInfo(pane, info).objectHandle
    }

    private fun deleteEntry(pane: PaneState, item: CommanderItem) {
        if (pane.source == Source.PHONE) {
            check(phoneAccess()) { "Phone access revoked" }
            fun remove(file: File) {
                check(phoneAccess() && isInPhoneStorage(file) && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    "Unsafe path or phone access revoked"
                }
                if (file.isDirectory) (file.listFiles() ?: error("Cannot list ${file.name}")).forEach(::remove)
                check(file.delete()) { "Could not delete ${file.name}" }
            }
            remove(phoneItem(pane, item))
        } else {
            val info = mtpInfo(pane, item)
            val session = mtpSession(pane)
            fun remove(handle: Int) {
                val current = session.getObjectInfo(handle) ?: error("MTP object disappeared")
                if (current.format == MtpConstants.FORMAT_ASSOCIATION)
                    (session.getObjectHandles(current.storageId, 0, handle) ?: error("Cannot list MTP folder"))
                        .forEach(::remove)
                check(session.deleteObject(handle)) { "MTP device refused deletion of ${current.name}" }
            }
            remove(info.objectHandle)
        }
    }

    private fun copyEntry(source: PaneState, target: PaneState, item: CommanderItem,
        onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        check(source.source != target.source || source.source != Source.PHONE ||
            source.phoneFolders.last() != target.phoneFolders.last()) { "Source and destination are the same folder" }
        if (source.source == Source.PHONE) {
            check(phoneAccess()) { "Phone access revoked" }
            val file = phoneItem(source, item)
            if (target.source == Source.PHONE) {
                val output = phoneChild(target, item.name)
                check(!output.canonicalPath.startsWith("${file.canonicalPath}${File.separator}")) {
                    "Cannot copy a folder into itself"
                }
                fun copy(from: File, to: File) {
                    check(phoneAccess() && isInPhoneStorage(from) && isInPhoneStorage(to) &&
                        !java.nio.file.Files.isSymbolicLink(from.toPath()) && !to.exists()) {
                        "Unsafe path, destination exists, or phone access revoked"
                    }
                    if (from.isDirectory) {
                        check(to.mkdir()) { "Could not create ${to.name}" }
                        (from.listFiles() ?: error("Cannot list ${from.name}")).forEach { copy(it, File(to, it.name)) }
                    } else from.copyTo(to, overwrite = false)
                }
                copy(file, output)
            } else {
                check(file.isFile) { "MTP folder copy is not supported; select files individually" }
                check(!mtpExists(target, item.name)) { "Destination already exists" }
                val name = target.deviceName ?: error("MTP device disconnected")
                val device = usbManager.deviceList[name] ?: error("MTP device disconnected")
                // MtpDevice.sendObjectInfo never gets an answer from Garmin watches, so use the raw path directly.
                if (device.vendorId == 0x091e) {
                    sessions.remove(name)?.close()
                    try {
                        android.util.Log.i("AndroidMTP", "Trying raw Garmin MTP upload for ${file.name}")
                        RawMtpUploader.upload(usbManager, device, target.storage!!.id,
                            target.folders.lastOrNull()?.handle ?: -1, file)
                    } finally {
                        runCatching { reopenMtpSession(name) }
                    }
                    return
                }
                val fileSize = file.length()
                val info = MtpObjectInfo.Builder().setStorageId(target.storage!!.id)
                    .setParent(target.folders.lastOrNull()?.handle ?: -1).setName(item.name)
                    .setFormat(MtpConstants.FORMAT_UNDEFINED)
                    .setCompressedSize(fileSize.coerceAtMost(0xffffffffL)).build()
                val created = sendMtpObjectInfo(target, info)
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        check(mtpSession(target).sendObject(created.objectHandle, fileSize, descriptor)) { "MTP transfer failed" }
                    }
                } catch (e: Exception) { mtpSession(target).deleteObject(created.objectHandle); throw e }
            }
        } else {
            val info = mtpInfo(source, item)
            check(info.format != MtpConstants.FORMAT_ASSOCIATION) { "MTP folder copy is not supported; select files individually" }
            check(phoneAccess()) { "Phone access revoked" }
            val output = phoneChild(target, item.name)
            val total = info.compressedSizeLong
            try {
                if (total in (8L * 1024 * 1024) until 0xffffffffL) {
                    check(output.createNewFile()) { "Destination already exists" }
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (copied < total) {
                            check(phoneAccess()) { "Phone access revoked" }
                            val count = minOf(buffer.size.toLong(), total - copied).toInt()
                            val read = mtpSession(source).getPartialObject(
                                info.objectHandle, copied, count.toLong(), buffer)
                            check(read in 1..count.toLong()) { "MTP read stopped at byte $copied" }
                            stream.write(buffer, 0, read.toInt())
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                } else {
                    check(mtpSession(source).importFile(info.objectHandle, output.absolutePath)) {
                        "MTP transfer failed"
                    }
                }
            } catch (e: Exception) { output.delete(); throw e }
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
