package dev.munote.ocrtool;

import android.util.Log;
import android.content.Context;

import com.tom_roush.fontbox.ttf.CmapLookup;
import com.tom_roush.fontbox.ttf.OTFParser;
import com.tom_roush.fontbox.ttf.OpenTypeFont;
import com.tom_roush.fontbox.ttf.TTFParser;
import com.tom_roush.fontbox.ttf.TrueTypeCollection;
import com.tom_roush.fontbox.ttf.TrueTypeFont;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adapted from g-o-d-v/MyPDF (Apache-2.0).
 * 从 Android 系统字体目录中寻找可嵌入且覆盖指定 Unicode 文本的字体。
 *
 * 字体资源必须保持打开直到 PDDocument.save() 完成，因此本类与 PDDocument 同生命周期使用。
 */
public final class PdfSystemFontResolver implements Closeable {

    private static final List<String> FONT_DIRS = Arrays.asList(
            "/system/fonts",
            "/system_ext/fonts",
            "/product/fonts",
            "/system/product/fonts",
            "/vendor/fonts"
    );

    private static final List<String> PRIORITY_FONT_NAMES = Arrays.asList(
            "NotoSansCJK-Regular.ttc",
            "NotoSansCJKsc-Regular.otf",
            "NotoSansCJKkr-Regular.otf",
            "NotoSansCJKjp-Regular.otf",
            "NotoSansSC-Regular.otf",
            "NotoSansKR-Regular.otf",
            "NotoSansJP-Regular.otf",
            "DroidSansFallback.ttf",
            "DroidSansChinese.ttf",
            "HwChinese-medium.ttf"
    );

    private final PDDocument document;
    private final Context context;
    private final String logTag;
    private File fallbackFontFile;
    private static final long FALLBACK_FONT_SIZE = 17772300L;
    private static final String FALLBACK_ASSET = "fonts/MuOCR-NotoSansSC.ttf";
    private final Map<String, ResolvedFont> cache = new HashMap<>();
    private final List<Closeable> openFontResources = new ArrayList<>();
    // PDFBox-Android 2.0.27.0 does NOT subset fonts in saveIncremental().
    // Track all fonts (including older cache entries) and subset explicitly.
    private final List<PDFont> fontsForSubset = new ArrayList<>();
    // The exact OCR Unicode for each original TrueType glyph ID (PDF CID).
    // The default PDFBox CJK glyph->Unicode reverse cmap is ambiguous.
    private final Map<PDFont, Map<Integer, Integer>> exactUnicode = new IdentityHashMap<>();
    private final Map<PDFont, Map<Integer, Integer>> originalUnicodeByGlyph =
            new LinkedHashMap<>();
    private boolean subsetCompleted = false;

    public PdfSystemFontResolver(PDDocument document, String logTag, Context context) {
        this.document = document;
        this.context = context.getApplicationContext();
        this.logTag = logTag == null || logTag.isEmpty() ? "PdfSystemFont" : logTag;
    }

    public PDFont resolve(String text, boolean bold) throws IOException {
        if (isStandardAscii(text)) {
            return bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
        }

        String script = detectScript(text);
        String key = script + (bold ? "-bold" : "-regular");
        ResolvedFont cached = cache.get(key);
        if (cached != null && supports(cached.trueTypeFont, text)) {
            rememberUnicode(cached, text);
            return cached.pdfFont;
        }

        IOException lastError = null;
        final List<File> candidates = new ArrayList<>();
        if ("chinese".equals(script) || "unicode".equals(script) ||
                "japanese".equals(script) || "korean".equals(script)) {
            // Xiaomi/Android CJK OTF and TTC fonts often contain CFF outlines,
            // which PDFBox-Android's TrueType subsetter cannot handle.
            // Prefer our licensed TrueType-glyf CJK fallback instead.
            try {
                candidates.add(prepareBundledFont());
            } catch (IOException e) {
                lastError = e;
                Log.w(logTag, "内置中文字库不可用，尝试系统字体", e);
            }
        }
        candidates.addAll(findCandidateFonts(script, bold));
        for (File fontFile : candidates) {
            try {
                ResolvedFont font = loadMatchingFont(fontFile, text);
                if (font != null) {
                    cache.put(key, font);
                    rememberUnicode(font, text);
                    Log.i(logTag, "PDF 字体: " + fontFile.getAbsolutePath() + " -> " + font.pdfFont.getName());
                    return font.pdfFont;
                }
            } catch (IOException e) {
                lastError = e;
                Log.w(logTag, "无法使用系统字体: " + fontFile.getAbsolutePath(), e);
            }
        }

        String message = "设备中未找到可嵌入且支持当前文本的系统字体，已取消创建，避免文字变成问号。"
                + " 文本类型=" + script;
        if (lastError != null) throw new IOException(message, lastError);
        throw new IOException(message);
    }

    /**
     * The fallback asset is copied once into private persistent app storage.
     * Loading font outlines directly from an APK stream can cause an extra
     * large heap allocation in FontBox, especially with 17MB CJK fonts.
     */
    private File prepareBundledFont() throws IOException {
        if (fallbackFontFile != null && fallbackFontFile.isFile() &&
                fallbackFontFile.length() == FALLBACK_FONT_SIZE) return fallbackFontFile;
        File dir = new File(context.getFilesDir(), "muocr-font-v1");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建中文字库缓存目录");
        }
        File target = new File(dir, "MuOCR-NotoSansSC.ttf");
        if (!target.isFile() || target.length() != FALLBACK_FONT_SIZE) {
            File tmp = new File(dir, "MuOCR-NotoSansSC.ttf.tmp");
            try {
                try (InputStream input = context.getAssets().open(FALLBACK_ASSET);
                     FileOutputStream output = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count > 0) output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (tmp.length() != FALLBACK_FONT_SIZE) {
                    throw new IOException("中文字库副本不完整: " + tmp.length());
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法替换旧中文字库");
                }
                if (!tmp.renameTo(target)) {
                    throw new IOException("无法保存中文字库到磁盘");
                }
            } finally {
                tmp.delete();
            }
        }
        fallbackFontFile = target;
        return target;
    }

    private ResolvedFont loadMatchingFont(File file, String text) throws IOException {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttc")) {
            return loadFromCollection(file, text);
        }
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return null;

        TrueTypeFont ttf = null;
        try {
            if (lower.endsWith(".otf")) {
                OpenTypeFont otf = new OTFParser().parse(file);
                if (otf.isPostScript()) {
                    otf.close();
                    return null;
                }
                ttf = otf;
            } else {
                ttf = new TTFParser().parse(file);
            }
            if (!isEmbeddableTrueType(ttf) || !supports(ttf, text)) {
                ttf.close();
                return null;
            }
            PDFont font = PDType0Font.load(document, ttf, true);
            fontsForSubset.add(font);
            openFontResources.add(ttf);
            return new ResolvedFont(font, ttf);
        } catch (Exception e) {
            if (ttf != null) {
                try { ttf.close(); } catch (IOException ignore) {}
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("字体载入失败: " + file.getAbsolutePath(), e);
        }
    }

    private ResolvedFont loadFromCollection(File file, String text) throws IOException {
        final TrueTypeCollection collection = new TrueTypeCollection(file);
        final TrueTypeFont[] selected = new TrueTypeFont[1];
        try {
            try {
                collection.processAllFonts(ttf -> {
                    if (isEmbeddableTrueType(ttf) && supports(ttf, text)) {
                        selected[0] = ttf;
                        throw FontMatched.INSTANCE;
                    }
                });
            } catch (FontMatched matched) {
                // 用异常提前结束 TTC 遍历，避免解析集合中的所有字体。
            }

            if (selected[0] == null) {
                collection.close();
                return null;
            }

            PDFont font = PDType0Font.load(document, selected[0], true);
            fontsForSubset.add(font);
            openFontResources.add(collection);
            return new ResolvedFont(font, selected[0]);
        } catch (Exception e) {
            try { collection.close(); } catch (IOException ignore) {}
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("TTC 字体载入失败: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * CFF/OpenType and some .ttc members can be exposed as TrueTypeFont
     * despite the lack of a /glyf table. Checking the filename extension
     * or OpenTypeFont.isPostScript() alone misses this case.
     * PDFBox-Android 2.0.27's TrueType subsetter requires glyf + loca.
     */
    private static boolean isEmbeddableTrueType(TrueTypeFont font) {
        Map<String, ?> tables = font.getTableMap();
        return tables.containsKey("glyf") && tables.containsKey("loca")
                && !tables.containsKey("CFF ")
                && (!(font instanceof OpenTypeFont) || !((OpenTypeFont) font).isPostScript());
    }

    private static boolean supports(TrueTypeFont font, String text) {
        try {
            CmapLookup cmap = font.getUnicodeCmapLookup(false);
            if (cmap == null) return false;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                offset += Character.charCount(cp);
                if (shouldIgnoreForGlyphCheck(cp)) continue;
                if (cmap.getGlyphId(cp) == 0) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean shouldIgnoreForGlyphCheck(int cp) {
        return cp == '\n' || cp == '\r' || cp == '\t'
                || Character.isWhitespace(cp)
                || Character.getType(cp) == Character.FORMAT;
    }

    private static boolean isStandardAscii(String text) {
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (shouldIgnoreForGlyphCheck(cp)) continue;
            if (cp < 0x20 || cp > 0x7E) return false;
        }
        return true;
    }

    private static String detectScript(String text) {
        boolean han = false, hangul = false, kana = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) han = true;
            else if (script == Character.UnicodeScript.HANGUL) hangul = true;
            else if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) kana = true;
        }
        if (hangul) return "korean";
        if (kana) return "japanese";
        if (han) return "chinese";
        return "unicode";
    }

    private static List<File> findCandidateFonts(String script, boolean bold) {
        Set<File> unique = new LinkedHashSet<>();

        for (String dirPath : FONT_DIRS) {
            File dir = new File(dirPath);
            for (String name : PRIORITY_FONT_NAMES) {
                File file = new File(dir, name);
                if (file.isFile() && file.canRead()) unique.add(file);
            }
        }

        List<File> discovered = new ArrayList<>();
        for (String dirPath : FONT_DIRS) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles(file -> {
                if (file == null || !file.isFile() || !file.canRead()) return false;
                String name = file.getName().toLowerCase(Locale.ROOT);
                return name.endsWith(".ttf") || name.endsWith(".ttc") || name.endsWith(".otf");
            });
            if (files != null) discovered.addAll(Arrays.asList(files));
        }

        discovered.sort(Comparator
                .comparingInt((File file) -> fontScore(file.getName(), script, bold))
                .reversed()
                .thenComparing(File::getAbsolutePath));
        unique.addAll(discovered);
        return new ArrayList<>(unique);
    }

    private static int fontScore(String fileName, String script, boolean bold) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.contains("emoji") || name.contains("symbol") || name.contains("math")) return -1000;

        int score = 0;
        if (name.contains("cjk")) score += 100;
        if (name.contains("fallback")) score += 90;
        if (name.contains("sans")) score += 20;
        if (name.contains("regular")) score += 15;
        if (bold && name.contains("bold")) score += 20;
        if (!bold && name.contains("bold")) score -= 10;

        if ("chinese".equals(script)) {
            if (name.contains("sc") || name.contains("hans") || name.contains("chinese") || name.contains("zh")) score += 80;
        } else if ("korean".equals(script)) {
            if (name.contains("kr") || name.contains("korean") || name.contains("ko")) score += 80;
        } else if ("japanese".equals(script)) {
            if (name.contains("jp") || name.contains("japanese") || name.contains("ja")) score += 80;
        }
        return score;
    }

    /**
     * Identity-H text streams refer to the original glyph IDs, not subset glyph
     * indices. Save the real OCR Unicode character for every glyph ID. PDFBox's
     * default ToUnicode builder chooses the first Unicode alias for that glyph
     * and can produce Kangxi radicals instead of common Chinese characters.
     */
    private void rememberUnicode(ResolvedFont resolved, String text) throws IOException {
        CmapLookup cmap = resolved.trueTypeFont.getUnicodeCmapLookup(false);
        if (cmap == null) return;
        Map<Integer, Integer> mapping = originalUnicodeByGlyph.computeIfAbsent(
                resolved.pdfFont, font -> new LinkedHashMap<>());
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            int gid = cmap.getGlyphId(cp);
            if (gid <= 0) continue;
            Integer previous = mapping.get(gid);
            if (previous == null ||
                    (isRadicalAlias(previous) && !isRadicalAlias(cp))) {
                mapping.put(gid, cp);
            }
        }
    }

    private static boolean isRadicalAlias(int cp) {
        return cp >= 0x2E80 && cp <= 0x2FDF;
    }

    private void writeExactToUnicodeMaps() throws IOException {
        for (Map.Entry<PDFont, Map<Integer, Integer>> entry :
                originalUnicodeByGlyph.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            // PDFBox's built-in ToUnicode is not enough for shared CJK glyphs.
            // Replace it with the Unicode codepoints actually written by OCR.
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(UnicodeCMapBuilder.build(entry.getValue()));
            }
            entry.getKey().getCOSObject().setItem(COSName.TO_UNICODE, stream);
            entry.getKey().getCOSObject().setNeedToBeUpdated(true);
        }
    }

    /**
     * The PDF is searchable only if each glyph CID maps back to the original
     * OCR Unicode character. Several CJK code points share an identical glyph
     * with a Kangxi radical; PDFBox's cmapLookup.getCharCodes(gid).get(0)
     * chooses the radical instead of the expected Chinese character.
     */
    private void rememberUnicode(ResolvedFont resolved, String text) throws IOException {
        CmapLookup lookup = resolved.trueTypeFont.getUnicodeCmapLookup(false);
        if (lookup == null) throw new IOException("OCR 字体没有 Unicode cmap");
        Map<Integer, Integer> byGid = exactUnicode.get(resolved.pdfFont);
        if (byGid == null) {
            byGid = new HashMap<>();
            exactUnicode.put(resolved.pdfFont, byGid);
        }
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (shouldIgnoreForGlyphCheck(cp)) continue;
            int gid = lookup.getGlyphId(cp);
            if (gid <= 0) continue;
            Integer previous = byGid.get(gid);
            if (previous == null || isCompatibilityRadical(previous) && !isCompatibilityRadical(cp)) {
                byGid.put(gid, cp);
            } else if (previous != cp && !isCompatibilityRadical(cp)) {
                Log.w(logTag, "OCR glyph CID collision: " + gid +
                    " maps both U+" + Integer.toHexString(previous) +
                    " and U+" + Integer.toHexString(cp));
            }
        }
    }

    private static boolean isCompatibilityRadical(int codepoint) {
        // Kangxi radicals are the main CJK Unicode aliases involved in this
        // PDFBox issue; preserve exact OCR strings for all ordinary Han glyphs.
        return codepoint >= 0x2F00 && codepoint <= 0x2FDF;
    }

    /**
     * PDFBox-Android inherited a PDFBox bug: saveIncremental() doesn't invoke
     * PDType0Font.subset(). Without this, Chinese fonts may be unusable in the
     * exported PDF despite a successful write.
     *
     * Call after drawing all OCR text, and before saveIncremental().
     */
    public void subsetFontsForIncrementalSave() throws IOException {
        if (subsetCompleted) return;
        for (PDFont font : fontsForSubset) {
            if (font.willBeSubset()) {
                font.subset();
                Map<Integer, Integer> mapping = exactUnicode.get(font);
                if (mapping != null && !mapping.isEmpty()) {
                    byte[] cmapBytes = OcrUnicodeCMap.encode(mapping);
                    PDStream cmapStream = new PDStream(
                        document, new ByteArrayInputStream(cmapBytes), COSName.FLATE_DECODE);
                    font.getCOSObject().setItem(COSName.TO_UNICODE, cmapStream);
                    font.getCOSObject().setNeedToBeUpdated(true);
                }
            }
        }
        writeExactToUnicodeMaps();
        subsetCompleted = true;
    }

    @Override
    public void close() {
        for (int i = openFontResources.size() - 1; i >= 0; i--) {
            try { openFontResources.get(i).close(); }
            catch (Exception e) { Log.w(logTag, "关闭字体资源失败", e); }
        }
        openFontResources.clear();
    }

    private static final class ResolvedFont {
        final PDFont pdfFont;
        final TrueTypeFont trueTypeFont;

        ResolvedFont(PDFont pdfFont, TrueTypeFont trueTypeFont) {
            this.pdfFont = pdfFont;
            this.trueTypeFont = trueTypeFont;
        }
    }

    private static final class FontMatched extends IOException {
        static final FontMatched INSTANCE = new FontMatched();
        private FontMatched() { super("font matched"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
