package com.example.paseomascota

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private const val METROS_POR_PASO = 0.75


data class Momento(
    val archivo: String,
    val lat: Double,
    val lng: Double,
    val fecha: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppPaseo() }
    }
}

@SuppressLint("MissingPermission") // el permiso de GPS se pide antes de iniciar el recorrido
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPaseo() {
    val context = LocalContext.current


    var paseoActivo by remember { mutableStateOf(false) }
    var pasos by remember { mutableIntStateOf(0) }
    var pasosBase by remember { mutableFloatStateOf(-1f) } // el sensor cuenta desde el último reinicio del teléfono
    var lux by remember { mutableFloatStateOf(-1f) }       // lectura del sensor de luz (-1 = sin lectura)
    var modoOscuro by remember { mutableStateOf(false) }
    var ubicacion by remember { mutableStateOf<LatLng?>(null) }
    var ubicacionFoto by remember { mutableStateOf<LatLng?>(null) }
    var fotoActual by remember { mutableStateOf<File?>(null) }
    var pestana by remember { mutableIntStateOf(0) }       // 0 = Recorrido, 1 = Puntos de Interés
    val ruta = remember { mutableStateListOf<LatLng>() }       // puntos de la Polyline
    val momentos = remember { mutableStateListOf<Momento>() }  // fotos del recorrido
    val camaraMapa = rememberCameraPositionState()

    val distanciaMetros = pasos * METROS_POR_PASO


    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val valor = e.values[0]
                lux = valor

                if (valor < 20f) modoOscuro = true
                else if (valor > 60f) modoOscuro = false
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose { sm.unregisterListener(listener) }
    }


    DisposableEffect(paseoActivo) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val cliente = LocationServices.getFusedLocationProviderClient(context)

        val contador = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val acelerometro = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val permisoActividad = Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED

        val usarContador = contador != null && permisoActividad

        val listenerContador = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (pasosBase < 0) pasosBase = e.values[0]
                pasos = (e.values[0] - pasosBase).toInt()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        var ultimoPasoMs = 0L
        val listenerAcelerometro = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val magnitud = sqrt(
                    e.values[0] * e.values[0] +
                            e.values[1] * e.values[1] +
                            e.values[2] * e.values[2]
                )
                val ahora = System.currentTimeMillis()
                if (magnitud > 11.5f && ahora - ultimoPasoMs > 400) {
                    ultimoPasoMs = ahora
                    pasos += 1
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        val callbackGps = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let {
                    val punto = LatLng(it.latitude, it.longitude)
                    ubicacion = punto
                    ruta.add(punto)
                }
            }
        }

        if (paseoActivo) {
            if (usarContador) {
                sm.registerListener(listenerContador, contador, SensorManager.SENSOR_DELAY_UI)
            } else if (acelerometro != null) {
                sm.registerListener(listenerAcelerometro, acelerometro, SensorManager.SENSOR_DELAY_GAME)
            }

            val peticion = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
            cliente.requestLocationUpdates(peticion, callbackGps, Looper.getMainLooper())

            cliente.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && ubicacion == null) {
                    ubicacion = LatLng(loc.latitude, loc.longitude)
                }
            }
        }

        onDispose {
            sm.unregisterListener(listenerContador)
            sm.unregisterListener(listenerAcelerometro)
            cliente.removeLocationUpdates(callbackGps)
        }
    }

    // ------------------ PERMISOS (GPS + Cámara) ------------------
    val launcherPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultado ->
        val gps = resultado[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                resultado[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val camara = resultado[Manifest.permission.CAMERA] == true
        if (gps && camara) {
            // Nuevo recorrido: se reinicia todo y se limpia la caché de fotos anteriores
            File(context.cacheDir, "fotos").deleteRecursively()
            pasos = 0
            pasosBase = -1f
            ubicacion = null
            ruta.clear()
            momentos.clear()
            paseoActivo = true
        } else {
            Toast.makeText(
                context, "Necesito los permisos de GPS y cámara para el recorrido", Toast.LENGTH_LONG
            ).show()
        }
    }


    val launcherFoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { exito ->
        val archivo = fotoActual
        val lugar = ubicacionFoto
        if (exito && archivo != null && lugar != null) {
            val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            momentos.add(Momento(archivo.absolutePath, lugar.latitude, lugar.longitude, fecha))
            Toast.makeText(context, "📍 Punto de interés guardado", Toast.LENGTH_SHORT).show()
        }
    }


    MaterialTheme(colorScheme = if (modoOscuro) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("🐶 Paseo con mi mascota") },
                    actions = {
                        Text(
                            text = if (lux < 0) "Sin lectura de luz"
                            else "%.0f lux %s".format(Locale.US, lux, if (modoOscuro) "🌙" else "☀️"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = pestana == 0,
                        onClick = { pestana = 0 },
                        icon = { Text("🐾") },
                        label = { Text("Recorrido") }
                    )
                    NavigationBarItem(
                        selected = pestana == 1,
                        onClick = { pestana = 1 },
                        icon = { Text("📷") },
                        label = { Text("Puntos de Interés") }
                    )
                }
            }
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {

                if (pestana == 0) {

                    if (!paseoActivo) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("🐕", fontSize = 72.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("¡Hora del paseo!", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Registra tus pasos y guarda fotos de los mejores momentos con tu mascota.",
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = {
                                val permisos = mutableListOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.CAMERA
                                )

                                if (Build.VERSION.SDK_INT >= 29) {
                                    permisos.add(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                                launcherPermisos.launch(permisos.toTypedArray())
                            }) { Text("Iniciar Recorrido") }


                            if (pasos > 0 || momentos.isNotEmpty()) {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    "Último recorrido: $pasos pasos · " +
                                            "%.0f m · ${momentos.size} fotos".format(Locale.US, distanciaMetros),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {

                        LaunchedEffect(ubicacion) {
                            ubicacion?.let {
                                camaraMapa.animate(CameraUpdateFactory.newLatLngZoom(it, 17f))
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TarjetaDato("Pasos", "$pasos", Modifier.weight(1f))
                                TarjetaDato(
                                    "Distancia",
                                    "%.0f m".format(Locale.US, distanciaMetros),
                                    Modifier.weight(1f)
                                )
                            }

                            GoogleMap(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                cameraPositionState = camaraMapa,
                                properties = MapProperties(isMyLocationEnabled = true)
                            ) {

                                if (ruta.size > 1) {
                                    Polyline(points = ruta.toList(), color = Color.Blue, width = 10f)
                                }

                                momentos.forEachIndexed { i, m ->
                                    key(m.archivo) {
                                        Marker(
                                            state = rememberMarkerState(position = LatLng(m.lat, m.lng)),
                                            title = "Momento ${i + 1}",
                                            snippet = m.fecha
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val lugar = ubicacion
                                        if (lugar == null) {
                                            Toast.makeText(
                                                context, "Buscando señal de GPS...", Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            val carpeta = File(context.cacheDir, "fotos").apply { mkdirs() }
                                            val archivo = File(carpeta, "foto_${System.currentTimeMillis()}.jpg")
                                            fotoActual = archivo
                                            ubicacionFoto = lugar // coordenadas del momento de la captura
                                            val uri = FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", archivo
                                            )
                                            launcherFoto.launch(uri)
                                        }
                                    }
                                ) { Text("📸 Tomar foto") }

                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { paseoActivo = false }
                                ) { Text("Terminar") }
                            }
                        }
                    }

                } else {

                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "Puntos de Interés",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TarjetaDato("Fotos", "${momentos.size}", Modifier.weight(1f))
                            TarjetaDato("Pasos", "$pasos", Modifier.weight(1f))
                            TarjetaDato(
                                "Distancia",
                                "%.0f m".format(Locale.US, distanciaMetros),
                                Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        if (momentos.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aún no hay fotos 🐾")
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(momentos) { i, m ->
                                    Card {
                                        Column {
                                            AsyncImage(
                                                model = File(m.archivo),
                                                contentDescription = "Momento ${i + 1}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxWidth().height(140.dp)
                                            )
                                            Column(Modifier.padding(8.dp)) {
                                                Text("Momento ${i + 1}", fontWeight = FontWeight.Bold)
                                                Text("🕒 ${m.fecha}", style = MaterialTheme.typography.labelMedium)
                                                Text(
                                                    "📍 %.5f, %.5f".format(Locale.US, m.lat, m.lng),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TarjetaDato(titulo: String, valor: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(titulo, style = MaterialTheme.typography.labelMedium)
            Text(
                valor,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
