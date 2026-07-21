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
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tunespark.music.AppScreen
import com.tunespark.music.WeatherInfo
import com.tunespark.music.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class GeocoderAddress(
    val name: String,
    val lat: Double,
    val lng: Double
)

@Composable
fun LocationScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    val sharedPrefs = remember { context.getSharedPreferences("tunespark_location_prefs", Context.MODE_PRIVATE) }
    
    var locationEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("location_enabled", false))
    }
    
    var autoLocation by remember {
        mutableStateOf(sharedPrefs.getBoolean("auto_location", false))
    }
    
    // We maintain both an auto_location_display and a manual_location_display in sharedPrefs
    var autoLocationDisplay by remember {
        mutableStateOf(sharedPrefs.getString("auto_location_display", "San Francisco, CA (37.7749, -122.4194)") ?: "San Francisco, CA (37.7749, -122.4194)")
    }
    var manualLocationDisplay by remember {
        mutableStateOf(sharedPrefs.getString("manual_location_display", "Mumbai, IN (19.0760, 72.8777)") ?: "Mumbai, IN (19.0760, 72.8777)")
    }
    
    // The active display coordinate depends on whether we are in auto mode or manual mode
    val locationDisplay = if (autoLocation) autoLocationDisplay else manualLocationDisplay
    
    var gpsStatusText by remember { mutableStateOf("") }
    
    // For manual input search
    var manualSearchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<GeocoderAddress>>(emptyList()) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }

    // Dynamic Geocoder background search with debounce
    LaunchedEffect(manualSearchQuery) {
        if (manualSearchQuery.length < 3) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(500) // Debounce to avoid excessive queries
        isSearchingSuggestions = true
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(manualSearchQuery, 5)
                if (!addresses.isNullOrEmpty()) {
                    suggestions = addresses.map { address ->
                        val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.featureName ?: ""
                        val country = address.countryName ?: ""
                        val displayName = if (city.isNotEmpty() && country.isNotEmpty()) "$city, $country" else city.ifEmpty { country }
                        GeocoderAddress(
                            name = displayName,
                            lat = address.latitude,
                            lng = address.longitude
                        )
                    }.filter { it.name.isNotEmpty() }
                } else {
                    suggestions = emptyList()
                }
            } catch (e: Exception) {
                suggestions = emptyList()
            } finally {
                isSearchingSuggestions = false
            }
        }
    }

    // Live weather state on Location Screen
    var weatherInfo by remember { mutableStateOf<WeatherInfo?>(null) }
    var isWeatherLoading by remember { mutableStateOf(false) }

    // Fetch weather if location is enabled
    LaunchedEffect(locationDisplay, autoLocation, locationEnabled) {
        if (locationEnabled) {
            isWeatherLoading = true
            try {
                val info = withContext(Dispatchers.IO) {
                    WeatherService.fetchWeather(locationDisplay)
                }
                weatherInfo = info
            } catch (e: Exception) {
                weatherInfo = null
            } finally {
                isWeatherLoading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchDeviceLocation(context, { resolved ->
                autoLocationDisplay = resolved
                sharedPrefs.edit()
                    .putString("auto_location_display", resolved)
                    .putString("location_display", resolved)
                    .apply()
                gpsStatusText = "Location updated"
            }, { status ->
                gpsStatusText = status
            })
        } else {
            gpsStatusText = "Permission denied."
            Toast.makeText(context, "Location permission is required for automatic GPS updates", Toast.LENGTH_SHORT).show()
        }
    }

    // Auto location trigger
    LaunchedEffect(autoLocation, locationEnabled) {
        if (locationEnabled && autoLocation) {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                fetchDeviceLocation(context, { resolved ->
                    autoLocationDisplay = resolved
                    sharedPrefs.edit()
                        .putString("auto_location_display", resolved)
                        .putString("location_display", resolved)
                        .apply()
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
        } else if (locationEnabled && !autoLocation) {
            // If switched back to manual, restore manual location display
            sharedPrefs.edit().putString("location_display", manualLocationDisplay).apply()
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(title = "Location", onBack = { onNavigate(AppScreen.SETTINGS) })

        Spacer(modifier = Modifier.height(16.dp))

        // 1. Enable Location Switch (Root element)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val newValue = !locationEnabled
                    locationEnabled = newValue
                    sharedPrefs.edit().putBoolean("location_enabled", newValue).apply()
                }
                .padding(
                    horizontal = 10.dp,
                    vertical = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Location",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Toggle weather and location services inside the app",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            SimpleToggleSwitch(
                checked = locationEnabled,
                onCheckedChange = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    locationEnabled = it
                    sharedPrefs.edit().putBoolean("location_enabled", it).apply()
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }

        // If location is disabled, nothing shows below it
        if (locationEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // 2. Automatic Location Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val newValue = !autoLocation
                        autoLocation = newValue
                        sharedPrefs.edit().putBoolean("auto_location", newValue).apply()
                    }
                    .padding(
                        horizontal = 10.dp,
                        vertical = 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic Location",
                        color = textColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Fetch device GPS automatically",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                SimpleToggleSwitch(
                    checked = autoLocation,
                    onCheckedChange = {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        autoLocation = it
                        sharedPrefs.edit().putBoolean("auto_location", it).apply()
                    },
                    backgroundColor = backgroundColor,
                    textColor = textColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!autoLocation) {
                // MANUAL STATE: Custom elegant search input with dynamic drop-down auto-suggestions from Geocoder database
                Text(
                    text = "Manual Location Entry",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = manualSearchQuery,
                    onValueChange = { manualSearchQuery = it },
                    label = { Text("Enter Location") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search icon",
                            tint = Color.Gray
                        )
                    },
                    trailingIcon = {
                        if (isSearchingSuggestions) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = primaryColor,
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = textColor.copy(alpha = 0.2f),
                        focusedLabelColor = primaryColor,
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(30.dp)
                )

                // DROPDOWN AUTO-SUGGESTIONS FROM GLOBAL GEOCODER
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(12.dp))
                            .border(1.dp, textColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(vertical = 4.dp)
                    ) {
                        suggestions.forEach { city ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val formatted = "${city.name} (${String.format(Locale.US, "%.4f", city.lat)}, ${String.format(Locale.US, "%.4f", city.lng)})"
                                        manualLocationDisplay = formatted
                                        sharedPrefs.edit()
                                            .putString("manual_location_display", formatted)
                                            .putString("location_display", formatted)
                                            .apply()
                                        manualSearchQuery = "" // Reset query after selection
                                        Toast.makeText(context, "Location Saved: ${city.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Suggestion",
                                    tint = primaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = city.name,
                                    color = textColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // LOCATION AND WEATHER CARD (Shown in BOTH States under `locationEnabled`)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, textColor.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Active Location",
                            tint = primaryColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (autoLocation) "Current Auto Location" else "Saved Manual Location",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = locationDisplay,
                                color = textColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (autoLocation) {
                            IconButton(
                                onClick = {
                                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (hasFine || hasCoarse) {
                                        fetchDeviceLocation(context, { resolved ->
                                            autoLocationDisplay = resolved
                                            sharedPrefs.edit()
                                                .putString("auto_location_display", resolved)
                                                .putString("location_display", resolved)
                                                .apply()
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
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh GPS",
                                    tint = textColor
                                )
                            }
                        }
                    }

                    if (autoLocation && gpsStatusText.isNotEmpty()) {
                        Text(
                            text = gpsStatusText,
                            color = primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = textColor.copy(alpha = 0.1f))

                    // Show Weather for locationDisplay
                    if (isWeatherLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = primaryColor, strokeWidth = 2.dp)
                        }
                    } else if (weatherInfo != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = weatherInfo?.emoji ?: "☁️",
                                fontSize = 32.sp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column {
                                Text(
                                    text = "${weatherInfo?.temperature?.toInt()}°C",
                                    color = textColor,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = weatherInfo?.description ?: "Unknown",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Unable to load live weather for coordinates",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(110.dp))
    }
}

@Composable
private fun SimpleToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    textColor: Color
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val trackWidth = 52.dp
    val trackHeight = 32.dp

    val thumbSize by animateDpAsState(
        targetValue = if (checked) 24.dp else 20.dp,
        label = "thumbSize"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 6.dp,
        label = "thumbOffset"
    )

    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(100))
            .background(if (checked) textColor else backgroundColor)
            .border(
                width = 1.5.dp,
                color = textColor,
                shape = RoundedCornerShape(100)
            )
            .clickable { 
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCheckedChange(!checked) 
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(backgroundColor)
                .then(
                    if (!checked) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = textColor,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
        )
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
