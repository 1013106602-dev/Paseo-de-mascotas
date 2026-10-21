# Paseo con mi mascota

App Android (Kotlin + Jetpack Compose) para registrar un paseo con tu mascota.

## Cómo abrirlo
1. Descomprime el ZIP.
2. Android Studio -> File -> Open -> selecciona la carpeta `PaseoMascota` (la que contiene `settings.gradle.kts`).
3. Espera a que Gradle sincronice (necesita internet la primera vez).
4. Abre `app/src/main/AndroidManifest.xml` y reemplaza `TU_API_KEY_AQUI` por tu API key de Google Maps (Maps SDK for Android).
5. Ejecuta en un celular real o en un emulador con **Google Play** (imagen con el logo de Play Store).

## Requisitos del profe y dónde están (MainActivity.kt)
- Permisos GPS y cámara al pulsar "Iniciar Recorrido": `launcherPermisos`
- Pasos y distancia con sensor de movimiento: `TYPE_STEP_COUNTER` (con respaldo en acelerómetro)
- Modo claro/oscuro con sensor de luz: `TYPE_LIGHT`
- Ubicación en Google Maps: `GoogleMap` con `isMyLocationEnabled`
- Botón de cámara: `launcherFoto` (`TakePicture` + `FileProvider`)
- Coordenadas, fecha/hora y marcador por foto: clase `Momento` + `Marker`
- Fotos en caché (`cacheDir/fotos`) y sección "Puntos de Interés" tipo dashboard: `LazyVerticalGrid`
- (Opcional) Ruta recorrida: `Polyline`

## Probar en emulador
Extended controls (...) -> Location para simular movimiento, y Virtual sensors para luz y acelerómetro.
