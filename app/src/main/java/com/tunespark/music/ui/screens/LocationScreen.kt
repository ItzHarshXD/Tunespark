package com.tunespark.music.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tunespark.music.AppScreen
import java.util.Locale

@Composable
fun LocationScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("tunespark_location_prefs", Context.MODE_PRIVATE) }
    var autoLocation by remember {
        mutableStateOf(sharedPrefs.getBoolean("auto_location", true))
    }
    var locationDisplay by remember {
        mutableStateOf(sharedPrefs.getString("location_display", "San Francisco, CA (37.7749, -122.4194)") ?: "San Francisco, CA (37.7749, -122.4194)")
    }
    var gpsStatusText by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchDeviceLocation(context, { resolved ->
                locationDisplay = resolved
                sharedPrefs.edit().putString("location_display", resolved).apply()
                gpsStatusText = "Location updated"
            }, { status ->
                gpsStatusText = status
            })
        } else {
            gpsStatusText = "Permission denied."
            Toast.makeText(context, "Location permission is required for automatic GPS updates", Toast.LENGTH_SHORT).show()
        }
    }

    // If autoLocation is enabled and we have permissions, fetch location. Or request if just toggled on.
    LaunchedEffect(autoLocation) {
        if (autoLocation) {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                fetchDeviceLocation(context, { resolved ->
                    locationDisplay = resolved
                    sharedPrefs.edit().putString("location_display", resolved).apply()
                    gpsStatusText = "Location updated"
                }, { status ->
                    gpsStatusText = status
                })
            } else {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }
    }

    if (showManualDialog) {
        var manualCity by remember { mutableStateOf("") }
        var manualLat by remember { mutableStateOf("") }
        var manualLng by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Enter Location Manually", color = Color.White) },
            containerColor = Color(0xFF1E1E1E),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualCity,
                        onValueChange = { manualCity = it },
                        label = { Text("City, State", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF0000),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualLat,
                        onValueChange = { manualLat = it },
                        label = { Text("Latitude (e.g. 40.7128)", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF0000),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualLng,
                        onValueChange = { manualLng = it },
                        label = { Text("Longitude (e.g. -74.0060)", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF0000),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val city = manualCity.trim().ifEmpty { "Manual Location" }
                        val lat = manualLat.trim().toDoubleOrNull() ?: 0.0
                        val lng = manualLng.trim().toDoubleOrNull() ?: 0.0
                        val formattedLat = String.format(Locale.US, "%.4f", lat)
                        val formattedLng = String.format(Locale.US, "%.4f", lng)
                        val manualDisplay = "$city ($formattedLat, $formattedLng)"
                        
                        locationDisplay = manualDisplay
                        autoLocation = false
                        sharedPrefs.edit()
                            .putBoolean("auto_location", false)
                            .putString("location_display", manualDisplay)
                            .apply()
                        
                        showManualDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(title = "Location", onBack = { onNavigate(AppScreen.SETTINGS) })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Automatic Location",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Use device GPS for weather updates",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
            Switch(
                checked = autoLocation,
                onCheckedChange = { checked ->
                    autoLocation = checked
                    sharedPrefs.edit().putBoolean("auto_location", checked).apply()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFFF0000),
                    checkedTrackColor = Color(0xFFFF0000).copy(alpha = 0.5f),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text(
                text = "📍",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Current: $locationDisplay",
                    color = Color.White,
                    fontSize = 16.sp
                )
                if (gpsStatusText.isNotEmpty()) {
                    Text(
                        text = gpsStatusText,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            
            // Dedicated Refresh button
            IconButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        fetchDeviceLocation(context, { resolved ->
                            locationDisplay = resolved
                            sharedPrefs.edit().putString("location_display", resolved).apply()
                            gpsStatusText = "Location updated"
                        }, { status ->
                            gpsStatusText = status
                        })
                    } else {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = "🔄",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Dedicated GPS crosshair action button
            IconButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        fetchDeviceLocation(context, { resolved ->
                            locationDisplay = resolved
                            sharedPrefs.edit().putString("location_display", resolved).apply()
                            gpsStatusText = "Location updated"
                        }, { status ->
                            gpsStatusText = status
                        })
                    } else {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = "🎯",
                    fontSize = 24.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { showManualDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2C2C2C),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🧭",
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Enter Location Manually",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

fun fetchDeviceLocation(
    context: Context,
    onLocationFetched: (String) -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    onStatusUpdate("Requesting location...")
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onStatusUpdate("Permission not granted.")
        return
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    if (!isGpsEnabled && !isNetworkEnabled) {
        onStatusUpdate("GPS/Network disabled.")
        return
    }

    val provider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER

    try {
        val lastKnownLocation = locationManager.getLastKnownLocation(provider)
        if (lastKnownLocation != null) {
            resolveCoordinatesToDisplay(context, lastKnownLocation.latitude, lastKnownLocation.longitude, onLocationFetched)
        } else {
            onStatusUpdate("Retrieving live location...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    provider,
                    null,
                    context.mainExecutor
                ) { location ->
                    if (location != null) {
                        resolveCoordinatesToDisplay(context, location.latitude, location.longitude, onLocationFetched)
                    } else {
                        onStatusUpdate("Failed to get location.")
                    }
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        resolveCoordinatesToDisplay(context, location.latitude, location.longitude, onLocationFetched)
                        locationManager.removeUpdates(this)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
            }
        }
    } catch (e: SecurityException) {
        onStatusUpdate("Security Exception: ${e.message}")
    } catch (e: Exception) {
        onStatusUpdate("Error: ${e.message}")
    }
}

fun resolveCoordinatesToDisplay(
    context: Context,
    latitude: Double,
    longitude: Double,
    onResult: (String) -> Unit
) {
    var cityName = "Unknown Location"
    try {
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                if (addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: ""
                    val state = address.adminArea ?: ""
                    cityName = if (city.isNotEmpty() && state.isNotEmpty()) "$city, $state" else city.ifEmpty { state.ifEmpty { "Unknown" } }
                }
                val formatted = String.format(Locale.US, "%.4f", latitude)
                val formattedLong = String.format(Locale.US, "%.4f", longitude)
                onResult("$cityName ($formatted, $formattedLong)")
            }
            return
        } else {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: ""
                val state = address.adminArea ?: ""
                cityName = if (city.isNotEmpty() && state.isNotEmpty()) "$city, $state" else city.ifEmpty { state.ifEmpty { "Unknown" } }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    val formatted = String.format(Locale.US, "%.4f", latitude)
    val formattedLong = String.format(Locale.US, "%.4f", longitude)
    onResult("$cityName ($formatted, $formattedLong)")
}
