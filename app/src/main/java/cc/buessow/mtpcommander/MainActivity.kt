package cc.buessow.mtpcommander

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
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
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.buessow.mtpcommander.ui.theme.AndroidMTPTheme
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val GARMIN_VENDOR_ID = 0x091e
private const val ACTION_USB_PERMISSION = "cc.buessow.mtpcommander.USB_PERMISSION"
private data class StorageEntry(val id: Int, val name: String)
private data class DeviceIdentity(val isGarmin: Boolean, val model: String?)
private data class FileEntry(val handle: Int, val name: String, val directory: Boolean, val size: Long, val modified: Long)
private data class Folder(val handle: Int, val name: String)
private enum class Source { DEVICES, PHONE, MTP, DRIVE }
private val DRIVE_ROOT = DriveFolder(DriveClient.ROOT, "My Drive")
private data class PaneState(
    val source: Source = Source.DEVICES,
    val deviceName: String? = null,
    val storage: StorageEntry? = null,
    val folders: List<Folder> = emptyList(),
    val phoneFolders: List<File> = emptyList(),
    val driveFolders: List<DriveFolder> = emptyList(),
    val entries: List<CommanderItem> = emptyList(),
    val status: String = "Select a device to browse.",
    val busy: Boolean = false,
    val cursor: String? = null,
    val progress: Float? = null,
    val revision: Int = 0
)

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    // Every MtpDevice operation, including close, happens on this one worker.
    private val worker = Executors.newSingleThreadExecutor()
    // Sessions are owned exclusively by the worker; epochs invalidate queued IO per device.
    private val sessions = mutableMapOf<String, MtpDevice>()
    // The USB connection behind each MtpDevice session, which raw Garmin uploads borrow.
    private val connections = mutableMapOf<String, UsbDeviceConnection>()
    // Raw upload sessions share a device's MtpDevice connection for the duration of one copy batch.
    private val rawSessions = mutableMapOf<String, RawMtpUploader.Session>()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val pendingDeviceNames = mutableSetOf<String>()
    // Drive requests run here so that network waits never hold up USB transfers on the worker.
    private val network = Executors.newSingleThreadExecutor()
    private val drive by lazy { DriveClient(applicationContext) }
    // Metadata of every Drive item shown, so copies can refuse Google Docs, which have no file content.
    private val driveFiles = ConcurrentHashMap<String, DriveFile>()
    private var driveConsentPending = false
    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        driveConsentPending = false
        // A failed consent screen carries Google's reason, e.g. an OAuth client that does not match this app.
        val failure = if (result.resultCode == RESULT_OK) null else runCatching {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
        }.exceptionOrNull()
        val granted = result.resultCode == RESULT_OK
        panes.indices.filter { panes[it].source == Source.DRIVE }.forEach { index ->
            if (granted) listDriveFolder(index)
            else update(index) { it.copy(busy = false, status = failure?.let { e -> "${driveError(e)} Refresh to retry." }
                ?: "Google Drive access was not granted. Refresh to retry.") }
        }
    }

    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var identities by mutableStateOf<Map<String, DeviceIdentity>>(emptyMap())
    private var storages by mutableStateOf<Map<String, List<StorageEntry>>>(emptyMap())
    private var readyDevices by mutableStateOf<Set<String>>(emptySet())
    private var panes by mutableStateOf(listOf(PaneState(), PaneState()))
    private var activePane by mutableStateOf(0)
    private var showPhoneAccessExplanation by mutableStateOf(false)
    private var mode by mutableStateOf(CommanderMode.NONE)
    // In delete mode this pane shows the trash; items are dragged into it from the other pane.
    private var trashPane by mutableStateOf(1)
    private var trash by mutableStateOf<List<TrashEntry>>(emptyList())
    // Search runs recursively below the folder that was open in searchPane when search mode started.
    private var searchOn by mutableStateOf(false)
    private var searchPane = 0
    private var searchRoot: PaneState? = null
    private var searchRootLabel by mutableStateOf("")
    private var searchQuery by mutableStateOf("")
    private var searchHits by mutableStateOf<List<SearchResult>>(emptyList())
    private var searchBusy by mutableStateOf(false)
    private var searchStatus by mutableStateOf("")
    private var searchGeneration = 0
    private data class SearchResult(val hit: SearchHit, val phoneFolders: List<File>, val folders: List<Folder>,
        val driveFolders: List<DriveFolder> = emptyList())
    private data class TrashEntry(val pane: PaneState, val item: CommanderItem, val epoch: Int?) {
        val key = "${pane.deviceName ?: "phone"}:${item.id}"
    }
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
                    activePane = activePane,
                    onActivate = { if (mode != CommanderMode.DELETE || it != trashPane) activePane = it },
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
                    mode = mode, onMode = ::toggleMode, trashPane = trashPane,
                    trash = trash.map { it.item.copy(id = it.key) },
                    onDrop = { from, to, items ->
                        if (mode == CommanderMode.COPY && to == 1 - from) startCopy(from, items)
                        else if (mode == CommanderMode.DELETE && to == trashPane && from != trashPane)
                            addToTrash(from, items)
                    },
                    onRemoveFromTrash = { key -> trash = trash.filter { it.key != key } },
                    onDelete = ::deleteTrash,
                    search = SearchState(searchOn, 1 - searchPane, searchRootLabel, searchQuery,
                        searchHits.map { it.hit }, searchBusy, searchStatus),
                    onSearchToggle = ::toggleSearch, onSearchQuery = { searchQuery = it }, onSearch = ::startSearch,
                    onDropHit = ::dropSearchHit,
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

    private fun paneData(index: Int, pane: PaneState): CommanderPane {
        val name = pane.deviceName?.let { identities[it]?.let(::deviceModelLabel)
            ?: devices.firstOrNull { device -> device.deviceName == it }?.productName ?: "MTP device" }
        val deviceStorages = storages[pane.deviceName].orEmpty()
        val entries = when (pane.source) {
            Source.DEVICES -> listOf(CommanderItem("local:phone", phoneLabel, true, -1, 0),
                CommanderItem("local:drive", "Google Drive", true, -1, 0)) + devices.map { device ->
                CommanderItem(device.deviceName, identities[device.deviceName]?.let(::deviceModelLabel)
                    ?: device.productName ?: "MTP device", true, -1, 0)
            }
            Source.PHONE, Source.DRIVE -> pane.entries
            Source.MTP -> if (pane.storage == null && !pane.busy) deviceStorages.map {
                CommanderItem(it.id.toString(), it.name, true, -1, 0)
            } else pane.entries
        }
        val path = when (pane.source) {
            Source.DEVICES -> listOf(CommanderCrumb("Devices", 0))
            Source.PHONE -> listOf(CommanderCrumb("Devices", -1), CommanderCrumb(phoneLabel, 0)) +
                pane.phoneFolders.drop(1).mapIndexed { i, folder -> CommanderCrumb(folder.name, i + 1) }
            Source.DRIVE -> listOf(CommanderCrumb("Devices", -1), CommanderCrumb("Google Drive", 0)) +
                pane.driveFolders.drop(1).mapIndexed { i, folder -> CommanderCrumb(folder.name, i + 1) }
            Source.MTP -> listOf(CommanderCrumb("Devices", -1), CommanderCrumb(name ?: "MTP device", 0)) +
                (if (deviceStorages.size > 1 && pane.storage?.name != "Internal storage")
                    listOfNotNull(pane.storage?.let { CommanderCrumb(it.name, 1) }) else emptyList()) +
                pane.folders.mapIndexed { i, folder ->
                    CommanderCrumb(folder.name, i + 1 + if (deviceStorages.size > 1) 1 else 0)
                }
        }
        return CommanderPane(
            title = when (pane.source) {
                Source.DEVICES -> "Devices"
                Source.PHONE -> phoneLabel
                Source.DRIVE -> "Google Drive"
                Source.MTP -> name ?: "MTP device"
            },
            path = path, entries = entries, status = if (pane.source == Source.DEVICES && devices.isEmpty())
                "No MTP devices found. Check USB cable and file transfer mode, then refresh. The phone is available above."
                else pane.status,
            busy = pane.busy, canBack = canBack(pane),
            draggable = pane.source == Source.PHONE || pane.source == Source.DRIVE ||
                pane.source == Source.MTP && pane.storage != null,
            actionLabel = if (pane.source == Source.PHONE && !phoneAccess()) "Grant all-files access" else "Refresh",
            navigationKey = "$index:${pane.source}:${pane.deviceName}:${pane.storage?.id}:${pane.folders.map { it.handle }}:${pane.phoneFolders.map { it.path }}:${pane.driveFolders.map { it.id }}",
            cursor = pane.cursor, progress = pane.progress
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
            Source.DRIVE -> if (depth < 0) {
                update(index) { PaneState(revision = it.revision + 1) }
            } else if (depth < pane.driveFolders.size) {
                update(index) { it.copy(driveFolders = it.driveFolders.take(depth + 1)) }
                listDriveFolder(index)
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
            } else if (item.id == "local:drive") {
                update(index) { PaneState(source = Source.DRIVE, driveFolders = listOf(DRIVE_ROOT), revision = it.revision + 1) }
                listDriveFolder(index)
            } else devices.firstOrNull { it.deviceName == item.id }?.let { selectDevice(index, it) }
            Source.PHONE -> {
                val folder = pane.phoneFolders.lastOrNull() ?: return
                val child = File(folder, item.name)
                if (child.path == item.id && isInPhoneStorage(child) && child.isDirectory) {
                    update(index) { it.copy(phoneFolders = it.phoneFolders + child) }
                    listPhoneFolder(index)
                }
            }
            Source.DRIVE -> {
                update(index) { it.copy(driveFolders = it.driveFolders + DriveFolder(item.id, item.name)) }
                listDriveFolder(index)
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
            Source.DRIVE -> if (pane.driveFolders.size <= 1) update(index) { PaneState(revision = it.revision + 1) }
                else {
                    update(index) { it.copy(driveFolders = it.driveFolders.dropLast(1)) }
                    listDriveFolder(index)
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
            cursor = null, status = "Reading phone folder…") }
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

    private fun listDriveFolder(index: Int, completionStatus: String? = null) {
        val pane = panes[index]
        if (pane.source != Source.DRIVE) return
        val folder = pane.driveFolders.last()
        val revision = pane.revision + 1
        update(index) { it.copy(revision = revision, busy = true, entries = emptyList(),
            cursor = null, status = "Reading Google Drive…") }
        network.execute {
            val result = runCatching {
                drive.list(folder.id).onEach { driveFiles[it.id] = it }
                    .map { CommanderItem(it.id, it.name, it.folder, it.size, it.modified) }
                    .sortedWith(compareBy<CommanderItem> { !it.directory }.thenBy { it.name.lowercase() })
            }
            runOnUiThread {
                if (!isDestroyed && panes[index].revision == revision && panes[index].source == Source.DRIVE) {
                    update(index) { it.copy(entries = result.getOrNull() ?: emptyList(), busy = false,
                        status = result.fold({ entries -> completionStatus ?: if (entries.isEmpty()) "Folder is empty."
                            else "${entries.size} items" }, { driveError(it) })) }
                }
            }
        }
    }

    /** Main thread: turns a Drive failure into a status line, asking for access when Google requires it. */
    private fun driveError(e: Throwable): String {
        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
        return when {
            cause is DriveAuthorizationRequired -> {
                if (!driveConsentPending) {
                    driveConsentPending = true
                    driveConsent.launch(IntentSenderRequest.Builder(cause.intent).build())
                }
                "Waiting for Google Drive access…"
            }
            cause is ApiException && cause.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
                "Google sign-in is not set up for this app: add an Android OAuth client for $packageName in Google Cloud."
            cause is ApiException && cause.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED ->
                "Add a Google account to this phone to use Google Drive."
            cause is ApiException -> "Google sign-in failed: ${cause.message ?: CommonStatusCodes.getStatusCodeString(cause.statusCode)}"
            else -> "Google Drive: ${cause.message ?: cause.javaClass.simpleName}"
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
        identities = identities.filterKeys { it in connected } + devices.filter { it.deviceName !in identities }
            .associate { it.deviceName to DeviceIdentity(it.vendorId == GARMIN_VENDOR_ID,
                it.productName?.trim()?.takeIf(String::isNotEmpty)) }
        val missing = (panes.mapNotNull { it.deviceName } + readyDevices + pendingDeviceNames)
            .filter { it !in connected }.toSet()
        missing.forEach(::disconnect)
    }

    private fun refreshVisiblePanes() {
        refreshDevices()
        panes.indices.forEach { index ->
            val pane = panes[index]
            if (pane.busy) return@forEach
            when (pane.source) {
                Source.DEVICES -> Unit
                Source.PHONE -> listPhoneFolder(index)
                Source.DRIVE -> listDriveFolder(index)
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
                closeSession(name)
                val connection = usbManager.openDevice(device) ?: error("Could not open USB device")
                val session = MtpDevice(device)
                if (!session.open(connection)) {
                    connection.close()
                    error("Device did not accept an MTP connection")
                }
                if (epoch != epochs[name]) { session.close(); return@execute }
                sessions[name] = session
                connections[name] = connection
                android.util.Log.i("AndroidMTP", "MTP session opened for ${device.vendorId}:${device.productId}")
                val found = session.storageIds?.map { id ->
                    StorageEntry(id, session.getStorageInfo(id)?.description?.takeIf { it.isNotBlank() } ?: "Storage $id")
                } ?: emptyList()
                android.util.Log.i("AndroidMTP", "MTP storages: ${found.map { it.id }}")
                val garmin = device.vendorId == GARMIN_VENDOR_ID
                val model = device.productName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: session.deviceInfo?.model?.trim()?.takeIf { it.isNotEmpty() }
                runOnUiThread {
                    if (epoch == epochs[name] && !isDestroyed) {
                        identities = identities + (name to DeviceIdentity(garmin, model ?: identities[name]?.model))
                        storages = storages + (name to found)
                        readyDevices = readyDevices + name
                        panes.indices.filter { panes[it].deviceName == device.deviceName }.forEach(::showStorages)
                    }
                }
            } catch (e: Exception) {
                closeSession(name)
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
            status = if (deviceStorages.isEmpty())
                    "The device answered but reported no storage; it is probably stuck. Unplug and reconnect it."
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
                        cursor = if (result.isSuccess) null else it.cursor,
                        status = result.fold({ entries -> completionStatus ?: if (entries.isEmpty()) "Folder is empty."
                            else "${entries.size} items" }, { "Could not list files: ${it.message}" })) }
                }
            }
        }
    }

    private fun toggleMode(toggled: CommanderMode) {
        val next = if (mode == toggled) CommanderMode.NONE else toggled
        if (mode == CommanderMode.DELETE) trash = emptyList()
        // While searching, the results come from searchPane and the other pane is the copy or trash target.
        if (next == CommanderMode.DELETE) trashPane = if (searchOn) 1 - searchPane else 1 - activePane
        mode = next
    }

    private fun toggleSearch() {
        if (searchOn) {
            searchOn = false
            searchGeneration++
            searchBusy = false
            searchHits = emptyList()
            searchRoot = null
            return
        }
        val root = panes[activePane]
        if (!writablePane(root) || root.source == Source.PHONE && !phoneAccess()) {
            update(activePane) { it.copy(status = "Open a folder to search in first.") }; return
        }
        if (mode == CommanderMode.DELETE && trashPane == activePane) trash = emptyList()
        searchPane = activePane
        trashPane = 1 - searchPane
        searchRoot = root
        searchRootLabel = paneData(activePane, root).path.joinToString(" / ") { it.label }
        searchHits = emptyList()
        searchStatus = "Enter a name or a pattern like *.fit"
        searchOn = true
    }

    private fun dropSearchHit(id: String) {
        val result = searchHits.firstOrNull { it.hit.item.id == id } ?: return
        val root = searchRoot ?: return
        val source = when (root.source) {
            Source.PHONE -> root.copy(phoneFolders = result.phoneFolders)
            Source.DRIVE -> root.copy(driveFolders = result.driveFolders)
            else -> root.copy(folders = result.folders)
        }
        when (mode) {
            CommanderMode.COPY -> startCopy(searchPane, listOf(result.hit.item), source)
            CommanderMode.DELETE -> addToTrash(searchPane, listOf(result.hit.item), source)
            CommanderMode.NONE -> Unit
        }
    }

    private fun startSearch() {
        val root = searchRoot ?: return
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        val generation = ++searchGeneration
        // Without wildcards a query matches any part of the name; with * or ? it must match the whole name.
        val matcher = if (query.any { it == '*' || it == '?' }) Regex(query.split('*').joinToString(".*") { part ->
            part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
            .let { regex -> { name: String -> regex.matches(name) } }
        else { name: String -> name.contains(query, ignoreCase = true) }
        val epoch = root.deviceName?.let { epochs[it] }
        searchHits = emptyList()
        searchBusy = true
        searchStatus = "Searching…"
        (if (root.source == Source.DRIVE) network else worker).execute {
            val found = mutableListOf<SearchResult>()
            var scanned = 0
            fun cancelled() = generation != searchGeneration
            fun publish(done: Boolean, failure: Throwable? = null) {
                val snapshot = found.toList()
                val count = scanned
                runOnUiThread {
                    if (isDestroyed || generation != searchGeneration) return@runOnUiThread
                    searchHits = snapshot
                    searchBusy = !done
                    searchStatus = failure?.let { "Search stopped: ${it.message ?: "error"} · ${snapshot.size} found" }
                        ?: if (done) "${snapshot.size} found in $count items"
                        else "Searching… ${snapshot.size} found in $count items"
                    failure ?: return@runOnUiThread
                    if (root.source == Source.DRIVE) searchStatus = "Search stopped: ${driveError(failure)} · ${snapshot.size} found"
                }
            }
            try {
                if (root.source == Source.PHONE) {
                    fun walk(folders: List<File>) {
                        val children = folders.last().listFiles() ?: return
                        for (file in children.sortedBy { it.name.lowercase() }) {
                            if (cancelled()) return
                            if (!isInPhoneStorage(file) || java.nio.file.Files.isSymbolicLink(file.toPath())) continue
                            scanned++
                            if (matcher(file.name)) found += SearchResult(SearchHit(
                                CommanderItem(file.path, file.name, file.isDirectory,
                                    if (file.isDirectory) -1 else file.length(), file.lastModified()),
                                folders.drop(root.phoneFolders.size).joinToString("/") { it.name }), folders, emptyList())
                            if (file.isDirectory) walk(folders + file)
                            if (scanned % 500 == 0) publish(false)
                        }
                    }
                    check(phoneAccess()) { "Phone access revoked" }
                    walk(root.phoneFolders)
                } else if (root.source == Source.DRIVE) {
                    fun walk(folders: List<DriveFolder>) {
                        for (file in drive.list(folders.last().id).sortedBy { it.name.lowercase() }) {
                            if (cancelled()) return
                            scanned++
                            driveFiles[file.id] = file
                            if (matcher(file.name)) found += SearchResult(SearchHit(
                                CommanderItem(file.id, file.name, file.folder, file.size, file.modified),
                                folders.drop(root.driveFolders.size).joinToString("/") { it.name }),
                                emptyList(), emptyList(), folders)
                            if (file.folder) walk(folders + DriveFolder(file.id, file.name))
                            if (scanned % 100 == 0) publish(false)
                        }
                    }
                    walk(root.driveFolders)
                } else {
                    val name = root.deviceName!!
                    val storageId = root.storage!!.id
                    fun walk(folders: List<Folder>) {
                        val parent = folders.lastOrNull()?.handle ?: -1
                        val infos = mtpHandles(name, storageId, parent).toList().mapNotNull { sessions[name]?.getObjectInfo(it) }
                            .filter { it.parent == if (parent == -1) 0 else parent }
                        for (info in infos.sortedBy { it.name.orEmpty().lowercase() }) {
                            if (cancelled()) return
                            check(epoch == epochs[name]) { "Device disconnected" }
                            scanned++
                            val directory = info.format == MtpConstants.FORMAT_ASSOCIATION
                            val fileName = info.name ?: "Unnamed"
                            if (matcher(fileName)) found += SearchResult(SearchHit(
                                CommanderItem(info.objectHandle.toString(), fileName, directory,
                                    info.compressedSizeLong, info.dateModified),
                                folders.drop(root.folders.size).joinToString("/") { it.name }), emptyList(), folders)
                            if (directory) walk(folders + Folder(info.objectHandle, fileName))
                            if (scanned % 100 == 0) publish(false)
                        }
                    }
                    walk(root.folders)
                }
                if (!cancelled()) publish(true)
            } catch (e: Exception) { if (!cancelled()) publish(true, e) }
        }
    }

    private fun writablePane(pane: PaneState) = !pane.busy &&
        (pane.source == Source.PHONE || pane.source == Source.DRIVE || pane.source == Source.MTP && pane.storage != null)

    private fun addToTrash(sourceIndex: Int, items: List<CommanderItem>, source: PaneState = panes[sourceIndex]) {
        if (!writablePane(source)) {
            update(sourceIndex) { it.copy(status = "Open a folder before dragging items to the trash.") }; return
        }
        if (source.source == Source.PHONE && !phoneAccess()) {
            update(sourceIndex) { it.copy(status = "Grant all-files access before modifying phone storage.") }; return
        }
        val epoch = source.deviceName?.let { epochs[it] }
        val added = items.map { TrashEntry(source, it, epoch) }.filter { entry -> trash.none { it.key == entry.key } }
        trash = trash + added
    }

    private fun deleteTrash() {
        val entries = trash
        val sourceIndex = 1 - trashPane
        if (entries.isEmpty() || panes[sourceIndex].busy) return
        trash = emptyList()
        update(sourceIndex) { it.copy(busy = true, status = "Deleting ${entries.size} item(s)…") }
        worker.execute {
            var done = 0
            var failure: String? = null
            var driveFailure: Exception? = null
            for (entry in entries) {
                try {
                    check(entry.pane.deviceName == null || entry.epoch == epochs[entry.pane.deviceName]) {
                        "Device disconnected"
                    }
                    deleteEntry(entry.pane, entry.item)
                    done++
                } catch (e: Exception) {
                    failure = "${e.message} (${entry.item.name})"
                    driveFailure = e.takeIf { entry.pane.source == Source.DRIVE }
                    break
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // Keep what was not deleted so it can be retried.
                if (mode == CommanderMode.DELETE) trash = entries.drop(done) + trash
                // Drop results that were deleted, including everything inside deleted folders.
                val deleted = entries.take(done).map { it.item.id }.toSet()
                searchHits = searchHits.filterNot { result -> result.hit.item.id in deleted ||
                    deleted.any { result.hit.item.id.startsWith("$it/") } ||
                    result.folders.any { it.handle.toString() in deleted } ||
                    result.driveFolders.any { it.id in deleted } }
                val progress = "$done/${entries.size} deleted"
                driveFailure?.let { failure = "${driveError(it)} (${entries[done].item.name})" }
                finishPaneAction(sourceIndex, null, failure?.let { "Stopped: $it · $progress" } ?: progress)
            }
        }
    }

    private fun startCopy(sourceIndex: Int, items: List<CommanderItem>, source: PaneState = panes[sourceIndex]) {
        val targetIndex = 1 - sourceIndex
        val destination = panes[targetIndex]
        if (!writablePane(source) || !writablePane(destination)) {
            update(sourceIndex) { it.copy(status = "Open a folder in each pane before copying.") }; return
        }
        if ((source.source == Source.PHONE || destination.source == Source.PHONE) && !phoneAccess()) {
            update(sourceIndex) { it.copy(status = "Grant all-files access to copy to or from the phone.") }; return
        }
        if (source.source == Source.MTP && destination.source == Source.MTP) {
            update(sourceIndex) { it.copy(status = "Direct MTP-to-MTP copy is unavailable; copy to phone first.") }; return
        }
        if (items.isEmpty()) return
        val sourceEpoch = source.deviceName?.let { epochs[it] }
        val targetEpoch = destination.deviceName?.let { epochs[it] }
        update(sourceIndex) { it.copy(busy = true, status = "Copy in progress…") }
        update(targetIndex) { it.copy(busy = true, status = "Copy in progress…") }
        worker.execute {
            var done = 0
            var failure: String? = null
            var driveFailure: Exception? = null
            for (item in items) {
                try {
                    check(source.deviceName == null || sourceEpoch == epochs[source.deviceName]) { "Source disconnected" }
                    check(destination.deviceName == null || targetEpoch == epochs[destination.deviceName]) {
                        "Destination disconnected"
                    }
                    var lastReport = 0L
                    val position = if (items.size > 1) " (${done + 1}/${items.size})" else ""
                    copyEntry(source, destination, item) { copied, total ->
                        val now = System.currentTimeMillis()
                        if (copied == total || now - lastReport >= 200) {
                            lastReport = now
                            val fraction = if (total > 0) copied.toFloat() / total else null
                            val status = "Copying ${item.name}$position: ${Formatter.formatShortFileSize(this, copied)}" +
                                " / ${Formatter.formatShortFileSize(this, total)}" +
                                (fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
                            runOnUiThread {
                                if (!isDestroyed) listOf(sourceIndex, targetIndex).forEach { index ->
                                    if (panes[index].busy) update(index) { it.copy(status = status, progress = fraction) }
                                }
                            }
                        }
                    }
                    done++
                } catch (e: Exception) {
                    failure = "${e.message} (${item.name})"
                    if (source.source == Source.DRIVE || destination.source == Source.DRIVE) driveFailure = e
                    break
                }
            }
            destination.deviceName?.let(::endRawSession)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val progress = "$done/${items.size} copied"
                driveFailure?.let { failure = "${driveError(it)} (${items[done].name})" }
                finishPaneAction(sourceIndex, targetIndex, failure?.let { "Stopped: $it · $progress" } ?: progress)
            }
        }
    }

    /** On the worker: ends a batch's raw upload session; the MtpDevice session it borrowed stays open. */
    private fun endRawSession(name: String) {
        rawSessions.remove(name)?.let { runCatching { it.close() } }
    }

    private fun finishPaneAction(sourceIndex: Int, targetIndex: Int?, status: String) {
        listOfNotNull(sourceIndex, targetIndex).forEach { index ->
            update(index) { it.copy(busy = false, progress = null, status = status) }
            when (panes[index].source) {
                Source.PHONE -> listPhoneFolder(index, status)
                Source.MTP -> if (panes[index].storage != null) listMtpFolder(index, status)
                Source.DRIVE -> listDriveFolder(index, status)
                Source.DEVICES -> {}
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

    /** On the worker: closes a device's MtpDevice session together with the raw session borrowing it. */
    private fun closeSession(name: String) {
        rawSessions.remove(name)?.let { runCatching { it.close() } }
        connections.remove(name)
        sessions.remove(name)?.close()
    }

    private fun reopenMtpSession(name: String): MtpDevice {
        val device = usbManager.deviceList[name] ?: error("MTP device disconnected")
        check(usbManager.hasPermission(device)) { "USB permission was revoked" }
        closeSession(name)
        val connection = usbManager.openDevice(device) ?: error("Could not reopen USB device")
        val reopened = MtpDevice(device)
        if (!reopened.open(connection)) {
            connection.close()
            error("Could not reopen MTP session")
        }
        sessions[name] = reopened
        connections[name] = connection
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
        } else if (pane.source == Source.DRIVE) {
            drive.trash(item.id)
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
        check(source.source != target.source || source.source != Source.DRIVE ||
            source.driveFolders.last() != target.driveFolders.last()) { "Source and destination are the same folder" }
        when {
            source.source == Source.PHONE && target.source == Source.PHONE -> {
                check(phoneAccess()) { "Phone access revoked" }
                copyPhoneToPhone(phoneItem(source, item), phoneChild(target, item.name), onProgress)
            }
            target.source == Source.PHONE -> {
                check(phoneAccess()) { "Phone access revoked" }
                val output = phoneChild(target, item.name)
                if (source.source == Source.DRIVE) downloadDrive(driveFile(item), output, onProgress)
                else downloadMtp(source, item, output, onProgress)
            }
            source.source == Source.PHONE -> {
                check(phoneAccess()) { "Phone access revoked" }
                val file = phoneItem(source, item)
                if (target.source == Source.DRIVE) uploadDrive(target, file, onProgress)
                else {
                    check(file.isFile) { "MTP folder copy is not supported; select files individually" }
                    uploadMtp(target, file, onProgress)
                }
            }
            source.source == Source.DRIVE && target.source == Source.DRIVE -> {
                val file = driveFile(item)
                check(!file.folder) { "Google Drive cannot copy folders; copy through the phone" }
                val folderId = target.driveFolders.last().id
                check(!drive.exists(folderId, file.name)) { "Destination already exists" }
                drive.copy(file.id, folderId, file.name)
            }
            else -> {
                // Between the watch and Drive the file passes through a temporary copy under the item's own name,
                // which the raw Garmin upload uses as the destination name.
                check(validName(item.name)) { "Invalid filename" }
                val staging = File(cacheDir, "transfer").apply { deleteRecursively(); mkdirs() }
                val temp = File(staging, item.name)
                try {
                    if (source.source == Source.DRIVE) {
                        val file = driveFile(item)
                        check(!file.folder) { "MTP folder copy is not supported; select files individually" }
                        downloadDrive(file, temp, onProgress)
                        uploadMtp(target, temp, onProgress)
                    } else {
                        check(!drive.exists(target.driveFolders.last().id, item.name)) { "Destination already exists" }
                        downloadMtp(source, item, temp, onProgress)
                        uploadDrive(target, temp, onProgress)
                    }
                } finally { staging.deleteRecursively() }
            }
        }
    }

    private fun copyPhoneToPhone(from: File, to: File, onProgress: (Long, Long) -> Unit) {
        check(!to.canonicalPath.startsWith("${from.canonicalPath}${File.separator}")) { "Cannot copy a folder into itself" }
        fun copy(from: File, to: File) {
            check(phoneAccess() && isInPhoneStorage(from) && isInPhoneStorage(to) &&
                !java.nio.file.Files.isSymbolicLink(from.toPath()) && !to.exists()) {
                "Unsafe path, destination exists, or phone access revoked"
            }
            if (from.isDirectory) {
                check(to.mkdir()) { "Could not create ${to.name}" }
                (from.listFiles() ?: error("Cannot list ${from.name}")).forEach { copy(it, File(to, it.name)) }
            } else {
                check(to.createNewFile()) { "Could not create ${to.name}" }
                from.inputStream().use { input -> FileOutputStream(to).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    val total = from.length()
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, total)
                    }
                } }
            }
        }
        copy(from, to)
    }

    /** Uploads [file] under its own name into the target pane's MTP folder. */
    private fun uploadMtp(target: PaneState, file: File, onProgress: (Long, Long) -> Unit) {
        val name = target.deviceName ?: error("MTP device disconnected")
        val device = usbManager.deviceList[name] ?: error("MTP device disconnected")
        // MtpDevice.sendObjectInfo never gets an answer from Garmin watches, so use the raw path directly.
        // The raw session checks for existing names itself and stays open for the rest of the batch.
        if (device.vendorId == GARMIN_VENDOR_ID) {
            val upload = rawSessions.getOrPut(name) {
                check(sessions[name] != null) { "MTP device disconnected" }
                RawMtpUploader.Session.shared(connections[name] ?: error("MTP device disconnected"), device)
            }
            upload.upload(target.storage!!.id, target.folders.lastOrNull()?.handle ?: -1, file, onProgress)
            return
        }
        check(!mtpExists(target, file.name)) { "Destination already exists" }
        val fileSize = file.length()
        val info = MtpObjectInfo.Builder().setStorageId(target.storage!!.id)
            .setParent(target.folders.lastOrNull()?.handle ?: -1).setName(file.name)
            .setFormat(MtpConstants.FORMAT_UNDEFINED)
            .setCompressedSize(fileSize.coerceAtMost(0xffffffffL)).build()
        val created = sendMtpObjectInfo(target, info)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                check(mtpSession(target).sendObject(created.objectHandle, fileSize, descriptor)) { "MTP transfer failed" }
            }
        } catch (e: Exception) { mtpSession(target).deleteObject(created.objectHandle); throw e }
    }

    private fun downloadMtp(source: PaneState, item: CommanderItem, output: File, onProgress: (Long, Long) -> Unit) {
        val info = mtpInfo(source, item)
        check(info.format != MtpConstants.FORMAT_ASSOCIATION) { "MTP folder copy is not supported; select files individually" }
        val total = info.compressedSizeLong
        try {
            if (total in (8L * 1024 * 1024) until 0xffffffffL) {
                check(output.createNewFile()) { "Destination already exists" }
                FileOutputStream(output).use { stream ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (copied < total) {
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

    private fun driveFile(item: CommanderItem): DriveFile =
        driveFiles[item.id] ?: error("Google Drive item is unknown; refresh the folder")

    /** Downloads a Drive file, or a folder with everything in it, to a new local [to]. */
    private fun downloadDrive(file: DriveFile, to: File, onProgress: (Long, Long) -> Unit) {
        check(validName(file.name)) { "Invalid filename ${file.name}" }
        if (file.folder) {
            check(to.mkdir()) { "Could not create ${to.name}" }
            drive.list(file.id).forEach { child ->
                driveFiles[child.id] = child
                check(validName(child.name)) { "Invalid filename ${child.name}" }
                downloadDrive(child, File(to, child.name), onProgress)
            }
            return
        }
        check(file.copyable) { "${file.name} is a Google document without file content" }
        check(to.createNewFile()) { "Destination already exists" }
        try { drive.download(file.id, to, onProgress) } catch (e: Exception) { to.delete(); throw e }
    }

    /** Uploads a local file or folder under its own name into the target pane's Drive folder. */
    private fun uploadDrive(target: PaneState, file: File, onProgress: (Long, Long) -> Unit) {
        val folderId = target.driveFolders.last().id
        check(!drive.exists(folderId, file.name)) { "Destination already exists" }
        fun upload(parentId: String, from: File) {
            check(!java.nio.file.Files.isSymbolicLink(from.toPath())) { "Cannot copy link ${from.name}" }
            if (from.isDirectory) {
                val id = drive.createFolder(parentId, from.name)
                (from.listFiles() ?: error("Cannot list ${from.name}")).forEach { upload(id, it) }
            } else drive.upload(parentId, from, from.name, onProgress)
        }
        upload(folderId, file)
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
        worker.execute { closeSession(name) }
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        (readyDevices + panes.mapNotNull { it.deviceName }).forEach(::bumpEpoch)
        worker.execute {
            sessions.keys.toList().forEach(::closeSession)
        }
        worker.shutdown()
        network.shutdownNow()
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
