# MONDE TV — Android nativo (base de desarrollo)

Este proyecto es la primera base Android nativa con Kotlin y Media3/ExoPlayer.

## Incluye ahora
- Proyecto Android Gradle.
- Interfaz inicial oscura con marca MONDE TV.
- Importación de M3U por URL y selector de archivo.
- Lista básica de elementos importados.
- Verificación inicial de credenciales Xtream Codes.
- Reproducción con Media3/ExoPlayer.
- Selector Automática / 480p / 720p / 1080p basado en las pistas que el canal realmente ofrece.
- Flujo de GitHub Actions para compilar un APK debug en la nube.

## Limitaciones conocidas de esta base
- No es todavía una versión terminada ni probada en dispositivos reales.
- Xtream Codes solo verifica credenciales; cargar catálogos live/VOD/series queda para la siguiente iteración.
- El clasificador M3U es básico y necesita mejor soporte de atributos y grupos.
- Perfiles familiares, favoritos, búsqueda, catálogo tipo Netflix y navegación completa con control remoto aún deben implementarse.
- La calidad depende de las variantes disponibles en la fuente. No se puede convertir un stream de baja resolución a Full HD.
- La versión debug no está firmada para publicación en Google Play.
- Utiliza solo listas y servicios para los que tengas autorización.

## Compilar desde el teléfono
1. Crea una cuenta en GitHub si no tienes una.
2. Crea un repositorio nuevo llamado `monde-tv`.
3. Sube todos los archivos de esta carpeta manteniendo sus rutas.
4. Abre la pestaña Actions y ejecuta `Build MONDE TV APK` (o espera a que termine al subir a `main`).
5. Abre la ejecución completada y descarga el artefacto `MONDE-TV-debug`.
6. Descomprime el artefacto y abre `app-debug.apk` en Android para instalarlo.

El proyecto usa Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17 y compileSdk 35.
