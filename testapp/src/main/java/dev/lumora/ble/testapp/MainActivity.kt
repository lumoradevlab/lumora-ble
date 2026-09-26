package dev.lumora.ble.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lumora.ble.core.ConnectionState
import dev.lumora.ble.core.DiscoveredDevice
import timber.log.Timber

/**
 * On-device harness for the standard GATT path.
 *
 * Exists because the connection lifecycle cannot be unit-tested: `GattConnection`
 * needs a real radio, so scan → connect → subscribe → parse is only ever
 * exercised here.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())

        setContent {
            MaterialTheme {
                val vm: ScanViewModel = viewModel()
                var granted by remember { mutableStateOf(vm.permissionsGranted()) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted = vm.permissionsGranted() }

                LaunchedEffect(Unit) {
                    if (!granted) launcher.launch(vm.requiredPermissions)
                }

                Surface(Modifier.fillMaxSize()) {
                    HarnessScreen(vm, granted) { launcher.launch(vm.requiredPermissions) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HarnessScreen(
    vm: ScanViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val devices by vm.devices.collectAsState()
    val events by vm.events.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val latest by vm.latest.collectAsState()
    val connections by vm.connections.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Lumora BLE — standard profile") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissionsGranted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Bluetooth permissions are required.",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestPermissions) { Text("Grant") }
                    }
                }
            }

            Button(
                onClick = vm::toggleScan,
                enabled = permissionsGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scanning) "Stop scan" else "Scan for heart rate (0x180D)")
            }

            if (scanning && devices.isEmpty()) {
                // The single most common cause of an empty scan.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Nothing yet.", fontWeight = FontWeight.Bold)
                        Text(
                            "A Fitbit or Pixel Watch only advertises while it is " +
                                "sharing: swipe down → Connected Fitness → Connect. " +
                                "A chest strap just needs to be worn.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (latest.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Live readings", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        latest.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(value, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (devices.isNotEmpty()) {
                Text("Devices", fontWeight = FontWeight.Bold)
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        state = connections[device.id],
                        onConnect = { vm.connect(device) },
                        onDisconnect = { vm.disconnect(device.id) },
                    )
                }
            }

            Text("Log", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(events) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    state: ConnectionState?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is ConnectionState.Ready
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: "(unnamed)", fontWeight = FontWeight.Bold)
                Text(
                    "${device.id.address}   rssi ${device.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                state?.let {
                    Text(
                        it.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it is ConnectionState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Button(onClick = if (connected) onDisconnect else onConnect) {
                Text(if (connected) "Disconnect" else "Connect")
            }
        }
    }
}

private fun ConnectionState.label(): String = when (this) {
    is ConnectionState.Disconnected -> "disconnected"
    is ConnectionState.Scanning -> "scanning"
    is ConnectionState.Connecting -> "connecting…"
    is ConnectionState.Authenticating -> "authenticating…"
    is ConnectionState.Ready -> "ready"
    is ConnectionState.Failed -> "failed: $reason"
}
