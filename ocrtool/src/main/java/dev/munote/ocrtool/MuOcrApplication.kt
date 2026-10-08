package dev.munote.ocrtool

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MuOcrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
