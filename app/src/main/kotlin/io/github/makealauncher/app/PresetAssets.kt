package io.github.makealauncher.app

import android.content.res.AssetManager
import android.graphics.Typeface
import io.github.makealauncher.core.scene.PresetFiles
import java.io.IOException
import java.io.InputStream

/** A preset folder inside the APK's assets, e.g. `presets/demo`. */
class PresetAssets(private val assets: AssetManager, private val dir: String) : PresetFiles {

    override fun read(path: String): String? = open(path)?.bufferedReader()?.use { it.readText() }

    fun open(path: String): InputStream? = try {
        assets.open(resolve(path))
    } catch (e: IOException) {
        null
    }

    fun typeface(path: String): Typeface? = try {
        Typeface.createFromAsset(assets, resolve(path))
    } catch (e: RuntimeException) {
        null
    }

    private fun resolve(path: String): String = "$dir/" + path.trimStart('/')
}
